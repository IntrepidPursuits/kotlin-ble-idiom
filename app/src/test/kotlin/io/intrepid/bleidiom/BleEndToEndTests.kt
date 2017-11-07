package io.intrepid.bleidiom

import com.polidea.rxandroidble.mockrxandroidble.RxBleClientMock
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Function3
import io.reactivex.observers.TestObserver
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.subjects.PublishSubject
import org.junit.*
import org.junit.runner.RunWith
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.modules.junit4.PowerMockRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal class TestService : BleService<TestService>() {
    companion object {
        fun define() {
            BleService<TestService> {
                configure {
                    uuid = "181C"

                    read {
                        data from "0010" into ::number
                    }

                    write {
                        data from ::string1 into "0011"
                    }

                    readAndWrite {
                        data between "0012" and ::string2
                        data between ::string3 and "0013"
                        data between ::bytes and "0014"
                    }
                }
            }
        }
    }

    var string1: BleCharValue<String> by bleCharHandler()
    var string2: BleCharValue<String> by bleCharHandler()
    var string3: BleCharValue<String> by bleCharHandler()
    val number: BleCharValue<Int> by bleCharHandler {
        fromByteArray = { toByteArrayNumber(it) }
        toByteArray = { toNumberByteArray(it) }
    }
    var bytes: BleCharValue<ByteArray> by bleCharHandler()
}

internal class TestService2 : BleService<TestService2>()

@Suppress("FunctionName")
@RunWith(PowerMockRunner::class)
@PrepareForTest(
        value = [(RxBleClientMock.CharacteristicsBuilder::class), (RxBleClientMock.DeviceBuilder::class)],
        fullyQualifiedNames = ["com.polidea.rxandroidble.mockrxandroidble.RxBleConnectionMock\$21"]
)
class BleEndToEndTests : BleMockClientBaseTest() {
    companion object {
        const val INITIAL_NUMBER_VAl = 1
        val INITIAL_BYTES_VAL = ByteArray(5, { index -> index.toByte() })

        @BeforeClass
        @JvmStatic
        fun load() {
        }

        @AfterClass
        @JvmStatic
        fun unload() {
        }
    }

    private lateinit var serverDevice: ServerDevice

    @Before
    override fun setup() {
        super.setup()

        TestService.define()

        serverDevice = buildDeviceService(TestService::class) {
            when {
                this == TestService::number -> INITIAL_NUMBER_VAl
                this == TestService::bytes -> INITIAL_BYTES_VAL
                else -> name
            }
        }.build() as ServerDevice
    }

    @After
    override fun tearDown() {
        super.tearDown()
    }

    @Test
    fun test_service_creation_success() {
        // Just this. When test fails, a class-cast exception will be thrown.
        @Suppress("UNUSED_VARIABLE")
        val device = ServiceDeviceFactory.createClientDevice<TestService>(serverDevice.uuid, serverDevice)
        assertTrue(true)
    }

    @Test
    fun test_service_creation_failure() {
        // Just this. Test succeeds if a class-cast exception is thrown.
        try {
            @Suppress("UNUSED_VARIABLE")
            val device = ServiceDeviceFactory.createClientDevice<TestService2>(serverDevice.uuid, serverDevice)
            fail("A class cast exception should have been thrown")
        } catch (e: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun test_service_connection() {
        val connectionObserver = TestObserver<ConnectionState>()
        val testObserver = TestObserver<Any>()

        val device = ServiceDeviceFactory.createClientDevice<TestService>(serverDevice.uuid, serverDevice)
        device.observeConnectionState().subscribe(connectionObserver)
        device.sharedConnection.subscribe(testObserver)

        testScheduler.triggerActions()

        connectionObserver.assertValues(ConnectionState.Disconnected, ConnectionState.Connecting, ConnectionState.Connected)

        testObserver.dispose()

        testScheduler.triggerActions()

        connectionObserver.assertValues(ConnectionState.Disconnected, ConnectionState.Connecting, ConnectionState.Connected, ConnectionState.Disconnected)

        testObserver.assertNoErrors()
        testObserver.assertValueCount(1)
    }

    @Test
    fun test_read_TestService_number() {
        val index = 3
        val mult = 5
        val testObserver = TestObserver<Any>()

        val device = ServiceDeviceFactory.createClientDevice<TestService>(serverDevice.uuid, serverDevice)
        (device[TestService::bytes][index] * 5).subscribe(testObserver)

        testObserver.dispose()

        testScheduler.triggerActions()

        assertEquals(ConnectionState.Disconnected, device.observeConnectionState().firstOrError().blockingGet())
        testObserver.assertComplete()
        testObserver.assertValues(INITIAL_BYTES_VAL[index] * mult)
    }

    @Test
    fun test_read_TestService_number_multiple_times() {
        val testObserver = TestObserver<Any>()

        val device = ServiceDeviceFactory.createClientDevice<TestService>(serverDevice.uuid, serverDevice)
        Observable.zip<Int,Int,Int,Int>(
                device[TestService::number],
                device[TestService::number],
                device[TestService::number],
                Function3 { v1, v2, v3 -> v1 + v2 + v3 })
                .subscribe(testObserver)

        testObserver.dispose()

        testScheduler.triggerActions()

        assertEquals(ConnectionState.Disconnected, device.observeConnectionState().firstOrError().blockingGet())
        testObserver.assertComplete()
        testObserver.assertValues(3 * INITIAL_NUMBER_VAl)
    }

    @Test
    fun test_read_increasing_TestService_number_multiple_times() {
        // Dummy Server setup, which will increase the "number" characteristic each time it is read.
        serverDevice[TestService::number]?.observeServerReads()?.subscribeBy { char ->
            char.value++
        }

        // Start client test.
        val expectedValue = 2 + 3 + 4
        val testObserver = TestObserver<Any>()

        val device = ServiceDeviceFactory.createClientDevice<TestService>(serverDevice.uuid, serverDevice)
        Observable.zip<Int,Int,Int,Int>(
                device[TestService::number],
                device[TestService::number],
                device[TestService::number],
                Function3 { v1, v2, v3 -> v1 + v2 + v3 })
                .subscribe(testObserver)

        testObserver.dispose()

        testScheduler.triggerActions()

        testObserver.assertComplete()
        testObserver.assertValues(expectedValue)
    }

    @Test
    fun test_read_increasing_TestService_assign_one_char_to_other_char() {
        val expectedNumber = 5
        val expectedPrefix = "Number "
        val expectedValue = "$expectedPrefix$expectedNumber"

        // Dummy Server setup.
        val testServerObserver = TestObserver<String>()
        serverDevice[TestService::number]?.observeServerReads()
                ?.subscribeBy { char ->
                    char.value = expectedNumber
                }

        // Observe write-characteristic calls coming in from the client.
        serverDevice[TestService::string1]?.observeServerWrites()
                ?.map { char -> char.value }
                ?.toObservable()
                ?.subscribe(testServerObserver)

        // Start client test.
        val device = ServiceDeviceFactory.createClientDevice<TestService>(serverDevice.uuid, serverDevice)

        val writeObserver = TestObserver<String>()
        device.writeObserverFactory = {
            val writePublisher = PublishSubject.create<String>()
            writePublisher.subscribe(writeObserver)
            writePublisher
        }

        // Assign a value based on the 'number' characteristic to the 'string1' characteristic.
        device[TestService::string1] = device[TestService::number].map { number -> "$expectedPrefix$number" }

        testScheduler.triggerActions()

        // Check for the observed writes on the client.
        writeObserver.assertComplete()
        writeObserver.assertValues(expectedValue)

        // And check if the (dummy) server got the expected value.
        testServerObserver.assertValues(expectedValue)

        assertEquals(ConnectionState.Disconnected, device.observeConnectionState().firstOrError().blockingGet())
    }

    @Test
    fun test_retaining_a_connection() {
        val connectionObserver: TestObserver<ConnectionState> = TestObserver()

        // Start client test.
        val device = ServiceDeviceFactory.createClientDevice<TestService>(serverDevice.uuid, serverDevice)
        device.observeConnectionState().subscribe(connectionObserver)

        // Retain first connection.
        device.retainConnection()

        testScheduler.triggerActions()

        connectionObserver.assertValueAt(connectionObserver.valueCount() - 1) {
            it == ConnectionState.Connected
        }

        // Retain second connection.
        device.retainConnection()

        // Release the second one.
        device.releaseRetainedConnection()

        testScheduler.triggerActions()

        connectionObserver.assertValueAt(connectionObserver.valueCount() - 1) {
            it == ConnectionState.Connected
        }

        // Do some operations.
        device[TestService::string1] = "Hello"
        device[TestService::number].subscribe()
        device[TestService::string2] = device[TestService::string3]

        // And release the first one.
        device.releaseRetainedConnection()

        testScheduler.triggerActions()

        connectionObserver.assertValueAt(connectionObserver.valueCount() - 1) {
            it == ConnectionState.Disconnected
        }
    }

    @Test
    fun test_for_race_conditions() {
        // Dummy Server setup, where "number" always returns 0
        val testServerObserver = TestObserver<Pair<String,String>>()
        serverDevice[TestService::number]?.observeServerReads()
                ?.subscribeBy { char ->
                    char.value = 0
                }

        // Observe write-characteristic calls coming in from the client.
        Flowable.zip(
                serverDevice[TestService::string1]?.observeServerWrites()
                        ?.map { char -> char.value },
                serverDevice[TestService::string2]?.observeServerWrites()
                        ?.map { char -> char.value },
                BiFunction<String,String,Pair<String,String>> { a, b  -> a to b })
                .toObservable().subscribe(testServerObserver)

        // Observe connection state.
        val connectionObserver = TestObserver<ConnectionState>()

        // Start client test.
        val device = ServiceDeviceFactory.createClientDevice<TestService>(serverDevice.uuid, serverDevice)
        device.observeConnectionState().subscribe(connectionObserver)

        // Generate 100 pairs, each pair's first value is odd, its seconds value is even.
        // Generate them using two threads using different timing.
        val thread1 = Thread {
            // Generate pairs whose first and second values lie between 0 and 99
            for (i in 0..49) {
                device[TestService::string1] = (device[TestService::number] + 1 + 2*i).asString()
                Thread.sleep(10)
                device[TestService::string2] = (device[TestService::number] + 2*i).asString()
            }
        }
        thread1.start()

        testScheduler.triggerActions()

        val thread2 = Thread {
            // Generate pairs whose first and second values lie between 100 and 199
            for (i in 0..49) {
                device[TestService::string2] = (device[TestService::number] + 100 + 2*i).asString()
                Thread.sleep(9)
                device[TestService::string1] = (device[TestService::number] + 101 + 2*i).asString()
            }
        }
        thread2.start()

        testScheduler.triggerActions()

        thread1.join()

        testScheduler.triggerActions()

        thread2.join()

        testScheduler.triggerActions()

        // And check if the (dummy) server got the expected value.
        testServerObserver.assertValueCount(100)

        val sortedString1Values = testServerObserver.values().asSequence()
                .toSet()
                .map { pair -> pair.first.toInt() }
                .sorted().toList()

        val sortedString2Values = testServerObserver.values().asSequence()
                .toSet()
                .map { pair -> pair.second.toInt() }
                .sorted().toList()

        assertEquals(1, sortedString1Values[0])
        assertEquals(199, sortedString1Values.last())
        assertTrue {  sortedString1Values.all { it -> it % 2 == 1 } }

        assertEquals(0, sortedString2Values[0])
        assertEquals(198, sortedString2Values.last())
        assertTrue {  sortedString2Values.all { it -> it % 2 == 0 } }

        assertEquals(ConnectionState.Disconnected, connectionObserver.values().last())
    }

    @Test
    fun test_observing_characteristic() {
        val firstNameChar = serverDevice[TestService::string2]

        // Observe connection state.
        val testObs = TestObserver<String>()
        val device = ServiceDeviceFactory.createClientDevice<TestService>(serverDevice.uuid, serverDevice)

        val connectionObserver = TestObserver<ConnectionState>()
        device.observeConnectionState()
                .map { state -> state }
                .subscribe(connectionObserver)

        // Client observes future server-notifications.
        device.observe(TestService::string2).subscribe(testObs)

        // Server sends out three notifications to any client that is listening/observing.
        firstNameChar?.sendNotification("string2-1".toByteArray())
        firstNameChar?.sendNotification("string2-2".toByteArray())
        firstNameChar?.sendNotification("string2-3".toByteArray())

        testScheduler.triggerActions()

        testObs.assertValues("string2-1", "string2-2", "string2-3")

        assertEquals(ConnectionState.Connected, connectionObserver.values().last())

        testObs.dispose()

        testScheduler.triggerActions()

        assertEquals(ConnectionState.Disconnected, connectionObserver.values().last())
    }
}
