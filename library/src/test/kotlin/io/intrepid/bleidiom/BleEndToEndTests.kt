package io.intrepid.bleidiom

import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.with
import com.polidea.rxandroidble2.mockrxandroidble.RxBleClientMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import io.intrepid.bleidiom.services.ArrayWithShortLength
import io.intrepid.bleidiom.services.FLOAT
import io.intrepid.bleidiom.services.INT8
import io.intrepid.bleidiom.services.StructData
import io.intrepid.bleidiom.services.UINT16
import io.intrepid.bleidiom.services.UINT8
import io.intrepid.bleidiom.services.withLength
import io.intrepid.bleidiom.test.BleMockClientBaseTestHelper
import io.intrepid.bleidiom.test.BleTestModules
import io.intrepid.bleidiom.test.LibTestKodein
import io.intrepid.bleidiom.test.ServerDevice
import io.intrepid.bleidiom.test.get
import io.intrepid.bleidiom.test.uuid
import io.intrepid.bleidiom.util.RxLoop
import io.intrepid.bleidiom.util.get
import io.intrepid.bleidiom.util.times
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Function3
import io.reactivex.observers.TestObserver
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.modules.junit4.PowerMockRunner
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

internal class TestService : BleService<TestService>() {
    companion object {
        fun addConfiguration() {
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
                        data between ::chunkedData and "0015"
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
    var bytes: BleCharValue<ByteArray> by bleChunkedCharHandler { _, bytes ->
        val chunkSize = mtuSize - 4 // ChunkedData creates an overhead of 4 bytes

        val chunks = createChunks(bytes, chunkSize)
        mtuSize to chunks.foldIndexed(ByteArray(chunks.size * mtuSize)) { i, array, chunk ->
            chunk.deconstruct(array, BLE_DEFAULBLE_BYTE_ORDER, i * mtuSize)
            array
        }
    }
    var chunkedData: BleCharValue<ChunkedData> by bleCharHandler()
}

internal class TestService2 : BleService<TestService2>()

internal data class ChunkedData(val data: ArrayWithShortLength = ArrayWithShortLength(), val totalSize: Int = 0) : StructData() {
    companion object : StructData.DataFactory {
        override val packingInfo = arrayOf<Number>(0, UINT16)
    }
}

private fun createChunks(stringBytes: ByteArray, maxChunkSize: Int): MutableList<ChunkedData> {
    val chunks = mutableListOf<ChunkedData>()
    var offset = 0
    while (offset < stringBytes.size) {
        val chunkSize = min(maxChunkSize, stringBytes.size - offset)
        if (chunkSize > 0) {
            chunks += ChunkedData(stringBytes.sliceArray(offset until (offset + chunkSize)).withLength(2), stringBytes.size)
            offset += chunkSize
        }
    }
    return chunks
}

@Suppress("FunctionName", "LocalVariableName")
@RunWith(PowerMockRunner::class)
@PrepareForTest(
    value = [(RxBleClientMock.CharacteristicsBuilder::class), (RxBleClientMock.DeviceBuilder::class)],
    fullyQualifiedNames = ["com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock\$16"] // This thing is mocking android.bluetooth.BluetoothGattDescriptor
)
class BleEndToEndTests {
    companion object {
        const val MAC_ADDRESS1 = "00:11:22:33:44:55"
        const val INITIAL_NUMBER_VAl = 1
        val INITIAL_BYTES_VAL = ByteArray(5, { index -> index.toByte() })
    }

    private val testHelper = BleMockClientBaseTestHelper()
    private val testScheduler: TestScheduler get() = LibTestKodein.with(this).instance()
    private lateinit var serverDevice: ServerDevice

    @Before
    fun setup() {
        TestService.addConfiguration()

        testHelper.setup(this) {
            testDevices = listOf(BleMockClientBaseTestHelper.buildDeviceService(TestService::class, MAC_ADDRESS1) {
                when {
                    this == TestService::number -> BleEndToEndTests.INITIAL_NUMBER_VAl
                    this == TestService::bytes -> BleEndToEndTests.INITIAL_BYTES_VAL
                    this == TestService::chunkedData -> null
                    else -> name
                }
            }.build() as RxBleDeviceMock)
        }

        serverDevice = LibTestKodein.with(MAC_ADDRESS1).instance()
    }

    @After
    fun tearDown() {
        testHelper.tearDown()

        BleTestModules.tearDown()
    }

    @Test
    fun test_service_creation_success() {
        // Just this. When test fails, a class-cast exception will be thrown.
        @Suppress("UNUSED_VARIABLE")
        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)
        assertTrue(true)

        System.gc()
    }

    @Test
    fun test_service_creation_failure() {
        // Just this. Test succeeds if a class-cast exception is thrown.
        try {
            @Suppress("UNUSED_VARIABLE")
            val device = ServiceDeviceFactory.obtainClientDevice<TestService2>(serverDevice.uuid, serverDevice)
            fail("A class cast exception should have been thrown")
        } catch (e: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun test_service_connection() {
        val connectionObserver = TestObserver<ConnectionState>()
        val testObserver = TestObserver<Any>()

        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)
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

        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)
        (device[TestService::bytes][index] * 5.toByte()).subscribe(testObserver)

        testScheduler.triggerActions()

        testObserver.dispose()

        testScheduler.triggerActions()

        assertEquals(ConnectionState.Disconnected, device.observeConnectionState().firstOrError().blockingGet())
        testObserver.assertComplete()
        testObserver.assertValues(INITIAL_BYTES_VAL[index] * mult)
    }

    @Test
    fun test_read_TestService_number_multiple_times() {
        val testObserver = TestObserver<Any>()

        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)
        Single.zip<Int, Int, Int, Int>(
            device[TestService::number],
            device[TestService::number],
            device[TestService::number],
            Function3 { v1, v2, v3 -> v1 + v2 + v3 })
            .subscribe(testObserver)

        testScheduler.triggerActions()

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

        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)
        Single.zip<Int, Int, Int, Int>(
            device[TestService::number],
            device[TestService::number],
            device[TestService::number],
            Function3 { v1, v2, v3 -> v1 + v2 + v3 })
            .subscribe(testObserver)

        testScheduler.triggerActions()

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
        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)

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
    fun test_externally_chunked_writes() {
        val RANDOM = Random(System.currentTimeMillis())
        val OK_RESPONSE = 1
        val WRONG_RESPONSE = 0

        serverDevice[TestService::chunkedData]?.observeServerWrites()?.subscribeBy {
            // Server returns an incorrect response 33% of the time.
            val response = if (RANDOM.nextInt(3) == 0) WRONG_RESPONSE else OK_RESPONSE
            serverDevice[TestService::number]?.sendNotification(toNumberByteArray(response))
        }

        val longString = """
            |012Lorem ipsum dolor sit amet, consectetur adipiscing elit. Aenean volutpat dui in gravida varius.
            |Nullam lacinia metus augue, id dictum libero efficitur quis. Suspendisse iaculis ligula lectus.
            |Phasellus rutrum lacinia nulla at suscipit. Morbi vehicula gravida dolor, nec tempor purus suscipit id.
            |Nullam vitae gravida turpis. Aliquam consectetur pellentesque metus, tempor vulputate magna venenatis ut.
            |Etiam fermentum felis congue augue placerat porttitor. Nulla facilisi. Morbi pharetra augue quam,
            |a varius felis bibendum quis. Etiam eget justo laoreet, blandit quam non, tincidunt nisl.
            |Donec in augue in felis venenatis rhoncus vitae ut elit. Sed non efficitur elit, sed blandit nunc.012
            """.trimMargin("|")

        val stringBytes = longString.toByteArray()
        val maxChunkSize = 15

        val chunks = createChunks(stringBytes, maxChunkSize)
        val chunksIter = chunks.iterator()
        val expectedChunks = ceil(longString.length.toDouble() / maxChunkSize.toDouble()).toInt()

        // Start client test.
        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)

        val requestObs = { chunk: ChunkedData -> device.chunkedData(chunk) }
        val responseObs = device.number.observe()

        val resultObs = RxLoop(chunksIter) {
            // Keep going as long as there is a next item.
            invariant = { state.hasNext() }

            // Get the next item.
            next = { Single.just(state.next()) }

            // Write the item to the device (and retry when getting a wrong response).
            body<ChunkedData> {
                it.concatMap { chunk ->
                    RxLoop(WRONG_RESPONSE) {
                        // Keep going (retrying) as long as we got a wrong response.
                        invariant = { state == WRONG_RESPONSE }

                        // Write the next request and wait for the response each time and update the response-state
                        next = {
                            responseObs.delay(100, TimeUnit.MILLISECONDS)
                                .zipWith<ChunkedData, Pair<ChunkedData, Int>>(
                                    requestObs(chunk).delay(20, TimeUnit.MILLISECONDS).toObservable(),
                                    BiFunction { response, request -> Pair(request, response) }
                                )
                                .doOnNext { result -> state = result.second }
                                .singleOrError()
                        }

                        // No body... per default it just returns the iterator's items.
                    }.start<Pair<ChunkedData, Int>>()
                        .toList()
                        .toObservable()
                }
            }
        }.start<List<Pair<ChunkedData, Int>>>()

        val testObserver = TestObserver<List<Pair<ChunkedData, Int>>>()
        resultObs.subscribe(testObserver)

        while (testObserver.completions() == 0.toLong()) {
            if (!testObserver.errors().isEmpty()) {
                throw testObserver.errors()[0]
            }
            testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
            testScheduler.triggerActions()
        }

        testObserver.assertValueCount(expectedChunks)
        testObserver.values().forEachIndexed { index, list ->
            val expectedChunk = chunks[index]

            // Check that the last one has the good data
            val (goodData, goodAnswer) = list.last()
            assertEquals(OK_RESPONSE, goodAnswer)
            assertEquals(expectedChunk, goodData)

            // And check that the ones before the last one have incorrect data
            val wrongChunks = list.slice(0 until list.lastIndex)
            assertTrue(wrongChunks.none { (_, answer) -> OK_RESPONSE == answer })
            assertTrue(wrongChunks.none { (data, _) -> expectedChunk != data })
        }

        val resultString = testObserver.values().fold("") { acc, list ->
            acc + String(list.last().first.data.payload)
        }
        assertEquals(longString, resultString)

        testObserver.dispose()
    }

    @Test
    fun test_internally_chunked_writes() {
        val longString = """
            |012Lorem ipsum dolor sit amet, consectetur adipiscing elit. Aenean volutpat dui in gravida varius.
            |Nullam lacinia metus augue, id dictum libero efficitur quis. Suspendisse iaculis ligula lectus.
            |Phasellus rutrum lacinia nulla at suscipit. Morbi vehicula gravida dolor, nec tempor purus suscipit id.
            |Nullam vitae gravida turpis. Aliquam consectetur pellentesque metus, tempor vulputate magna venenatis ut.
            |Etiam fermentum felis congue augue placerat porttitor. Nulla facilisi. Morbi pharetra augue quam,
            |a varius felis bibendum quis. Etiam eget justo laoreet, blandit quam non, tincidunt nisl.
            |Donec in augue in felis venenatis rhoncus vitae ut elit. Sed non efficitur elit, sed blandit nunc.012
            """.trimMargin("|")

        // Monitor what the server (remote BLE Device) receives.
        val serverTestObserver = TestObserver<ByteArray>()
        val serverTestPub = PublishSubject.create<ByteArray>()
        serverTestPub.subscribe(serverTestObserver)

        serverDevice[TestService::bytes]?.observeServerWrites()?.subscribeBy {
            val chunkedData = StructData.construct(ChunkedData::class, it.value)
            serverTestPub.onNext(chunkedData.data.payload)
        }

        // Start client test.
        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)
        device[TestService::bytes] = longString.toByteArray()

        testScheduler.triggerActions()
        // Check if the server got the correct requests from the client.
        val resultString = serverTestObserver.values().fold("") { acc, bytes -> acc + String(bytes) }
        assertEquals(longString, resultString)
    }

    @Test
    fun test_retaining_a_connection() {
        val connectionObserver: TestObserver<ConnectionState> = TestObserver()

        // Start client test.
        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)
        device.observeConnectionState().subscribe(connectionObserver)

        // Retain first connection.
        val retainer1 = device.retainConnection()

        testScheduler.triggerActions()

        connectionObserver.assertValueAt(connectionObserver.valueCount() - 1) {
            it == ConnectionState.Connected
        }

        // Retain second connection.
        val retainer2 = device.retainConnection()

        // Release the second one.
        retainer2.close()

        testScheduler.triggerActions()

        connectionObserver.assertValueAt(connectionObserver.valueCount() - 1) {
            it == ConnectionState.Connected
        }
        val numberOfConnectionStateChanges = connectionObserver.valueCount()

        // Do some operations.
        device[TestService::string1] = "Hello"
        device[TestService::number].subscribe()
        device[TestService::string2] = device[TestService::string3]

        testScheduler.triggerActions()

        // Check that there were no connection-state changes, because we retained a connection.
        assertEquals(numberOfConnectionStateChanges, connectionObserver.valueCount())

        // And release the first one.
        retainer1.close()

        testScheduler.triggerActions()

        connectionObserver.assertValueAt(connectionObserver.valueCount() - 1) {
            it == ConnectionState.Disconnected
        }
    }

    @Test
    fun test_observing_characteristic() {
        val firstNameChar = serverDevice[TestService::string2]

        // Observe connection state.
        val testObs = TestObserver<String>()
        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)

        val connectionObserver = TestObserver<ConnectionState>()
        device.observeConnectionState()
            .map { state -> state }
            .subscribe(connectionObserver)

        // Client observes future server-notifications.
        device.observeNotifications(TestService::string2).subscribe(testObs)

        testScheduler.triggerActions()

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

    @Test
    fun test_killing_connections_on_long_living_notification_observers() {
        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)

        val bytesObservers = mutableListOf<TestObserver<ByteArray>>()
        val string2Observers = mutableListOf<TestObserver<String>>()

        // Create 5 pairs notif-observers and kill the connection after starting the observations 5 times.
        for (i in 0 until 5) {
            val byteObs = TestObserver<ByteArray>()
            val string2Obs = TestObserver<String>()

            device.observeNotifications(TestService::bytes).subscribe(byteObs)
            device.observeNotifications(TestService::string2).subscribe(string2Obs)
            testScheduler.triggerActions()

            device.killCurrentConnection()
            testScheduler.triggerActions()

            bytesObservers += byteObs
            string2Observers += string2Obs
        }

        // Create 5 more pairs notif-observers and don't kill the connection.
        for (i in 5 until 10) {
            val byteObs = TestObserver<ByteArray>()
            val string2Obs = TestObserver<String>()

            device.observeNotifications(TestService::bytes).subscribe(byteObs)
            device.observeNotifications(TestService::string2).subscribe(string2Obs)
            testScheduler.triggerActions()

            bytesObservers += byteObs
            string2Observers += string2Obs
        }

        // This means the first 5 pairs have completed (connection killed)
        for (i in 0 until 5) {
            bytesObservers[i].assertComplete()
            string2Observers[i].assertComplete()
        }

        // And the next 5 pairs are fine.
        for (i in 5 until 10) {
            bytesObservers[i].assertNotComplete()
            string2Observers[i].assertNotComplete()
        }

        // Until we kill the connection now, once affecting all notif-observers....
        device.killCurrentConnection()
        testScheduler.triggerActions()

        // This means that the last 5 pairs completed (connection killed) as well.
        for (i in 5 until 10) {
            bytesObservers[i].assertComplete()
            string2Observers[i].assertComplete()
        }
    }

    @Test
    fun test_killing_connection_when_retaining_one() {
        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)

        var error: Throwable? = null
        val retainer = device.retainConnection { error = it }

        val testObserverWrite = TestObserver<String>()
        writePeriodically(device, "String1").subscribe(testObserverWrite)

        val testObserverRead1 = TestObserver<String>()
        readPeriodically(device).subscribe(testObserverRead1)

        val testObserverNotif = TestObserver<ByteArray>()
        device.observeNotifications(TestService::bytes).subscribe(testObserverNotif)

        testScheduler.advanceTimeBy(500, TimeUnit.MILLISECONDS)

        device.killCurrentConnection()
        testScheduler.triggerActions()

        testScheduler.advanceTimeBy(500, TimeUnit.MILLISECONDS)

        // Reads and writes are using one-off-connections.
        // This means that, unless the disconnect happens when the read/write is waiting for a
        // response, a read/write will just establish a fresh new connection and do its work.
        testObserverWrite.assertValueCount(6)
        testObserverWrite.assertNoErrors()
        testObserverRead1.assertValueCount(6)
        testObserverRead1.assertNoErrors()

        // Notifications-observers complete when a connection has been killed.
        assertNull(error)
        testObserverNotif.assertComplete()

        retainer.close()
    }

    private fun writePeriodically(device: TestService, value: String) =
        Observable.interval(0, 200, TimeUnit.MILLISECONDS, testScheduler)
            .flatMapSingle { device.string2(value) }

    private fun readPeriodically(device: TestService) =
        Observable.interval(0, 200, TimeUnit.MILLISECONDS, testScheduler)
            .flatMapSingle { device.string3() }

    @Test
    fun test_data_object_assembly() {
        val testData = TestData(201, -101, SubData(-1, 1001.1001f, "0123456789", byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)), "0123")
        val array = testData.deconstruct()
        val testData2 = StructData.construct(TestData::class, array)
        val array2 = testData2.deconstruct()

        assertEquals(testData, testData2)
        assertEquals(testData.someValue, testData2.someValue)
        assertEquals(testData.someOthervalue, testData2.someOthervalue)
        assertEquals(testData.otherValue, testData2.otherValue)
        assertEquals(testData.subData!!.byteVal, testData2.subData!!.byteVal)
        assertEquals(testData.subData.number, testData2.subData.number)
        assertEquals(testData.subData.id, testData2.subData.id)
        assertArrayEquals(testData.subData.data, testData2.subData.data)
        assertArrayEquals(array, array2)
    }

    @Test
    fun test_data_object_assemblySignedVsUnsignedBytes() {
        val testData = TestData(-5, 101, SubData(1, 1001.1001f, "0123456789", byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)), "0123")
        val array = testData.deconstruct()
        val testData2 = StructData.construct(TestData::class, array)
        val array2 = testData2.deconstruct()

        assertEquals(256 + testData.someValue, testData2.someValue) // someValue is stored as an UNsigned byte.
        assertEquals(testData.someOthervalue, testData2.someOthervalue) // someOtherValue is stored as a signed byte.
        assertArrayEquals(array, array2)
    }

    @Test
    fun test_data_object_assembly_null_sub_data() {
        val testData = TestData(201, -101, null, "0123")
        val array = testData.deconstruct()
        val testData2 = StructData.construct(TestData::class, array)
        val array2 = testData2.deconstruct()

        assertEquals(testData.otherValue, testData2.otherValue)
        assertEquals(0, testData2.subData!!.byteVal)
        assertEquals(0f, testData2.subData.number)
        assertEquals("", testData2.subData.id)
        assertArrayEquals(ByteArray(10, { 0 }), testData2.subData.data)
        assertArrayEquals(array, array2)
    }

    @Test
    fun test_data_object_assembly_arrays_too_long() {
        val testData = TestData(0, 0, SubData(0, 0f, "0123456789abcdef", byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)), "0123")
        val array = testData.deconstruct()
        val testData2 = StructData.construct(TestData::class, array)
        val array2 = testData2.deconstruct()

        assertEquals(testData.otherValue, testData2.otherValue)
        assertEquals("0123456789", testData2.subData!!.id) // String is limited to 10 chars (11 - 1)
        assertArrayEquals(ByteArray(10, { it.toByte() }), testData2.subData.data) // Array is set to 10 elements
        assertArrayEquals(array, array2)
    }

    @Test
    fun test_data_object_assembly_arrays_shorter_than_size() {
        val testData = TestData(0, 0, SubData(0, 0f, "1", byteArrayOf(1)), "0123")
        val array = testData.deconstruct()
        val testData2 = StructData.construct(TestData::class, array)
        val array2 = testData2.deconstruct()

        assertEquals(testData.otherValue, testData2.otherValue)
        assertEquals("1", testData2.subData!!.id)
        assertArrayEquals(ByteArray(10, { 0 }).apply { this[0] = 1 }, testData2.subData.data)
        assertArrayEquals(array, array2)
    }

    @Test
    fun test_data_object_assembly_size_calculations() {
        val testData = TestData(201, -101, SubData(-1, 1001.1001f, "0123456789", byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)), "0123")
        val array = testData.deconstruct()
        assertEquals(array.size, testData.structSize)
    }
}

sealed class Hello(val helloValue: Int) : StructData() {
    companion object : StructData.Factory {
        override fun fromByteArrayWithSize(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int): Pair<StructData, Int> {
            return when (bytes[offset]) {
                0.toByte() -> Hello1
                else -> throw Exception("Invalid property value ${bytes[offset]}")
            } to size
        }

        override fun toByteArray(value: StructData, bytes: ByteArray, order: ByteOrder, offset: Int): Int {
            bytes[offset] = (value as Hello).helloValue.toByte()
            return size
        }

        override val packingInfo = arrayOf<Number>(UINT8)
    }
}

object Hello1 : Hello(0)

data class SubData(val byteVal: Byte, val number: Float, val id: String, val data: ByteArray, val hello: Hello = Hello1) : StructData() {
    companion object : StructData.DataFactory {
        override val packingInfo = arrayOf<Number>(UINT8, FLOAT, 11, 10, 0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        other as SubData

        if (byteVal != other.byteVal) return false
        if (number != other.number) return false
        if (id != other.id) return false
        if (!Arrays.equals(data, other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = byteVal.toInt()
        result = 31 * result + number.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + Arrays.hashCode(data)
        return result
    }
}

data class TestData(val someValue: Int, val someOthervalue: Int, val subData: SubData?, val otherValue: String) : StructData() {
    companion object : StructData.DataFactory {
        override val packingInfo = arrayOf<Number>(UINT8, INT8, 0, 0)
    }
}
