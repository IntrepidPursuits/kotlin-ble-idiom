package io.intrepid.bleidiom

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.factory
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.scopedSingleton
import com.github.salomonbrys.kodein.with
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doAnswer
import com.nhaarman.mockito_kotlin.spy
import com.nhaarman.mockito_kotlin.whenever
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import com.polidea.rxandroidble2.mockrxandroidble.RxBleClientMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import io.intrepid.bleidiom.test.BleMockClientBaseTestHelper
import io.intrepid.bleidiom.test.BleTestModules
import io.intrepid.bleidiom.test.BleTestModules.Companion.testKodeinOverrides
import io.intrepid.bleidiom.test.LibTestKodein
import io.intrepid.bleidiom.test.ServerDevice
import io.intrepid.bleidiom.test.TestScope
import io.intrepid.bleidiom.test.get
import io.intrepid.bleidiom.test.uuid
import io.intrepid.bleidiom.util.asString
import io.intrepid.bleidiom.util.plus
import io.reactivex.Emitter
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.functions.BiFunction
import io.reactivex.observers.TestObserver
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.modules.junit4.PowerMockRunner
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@Suppress("DEPRECATION")
private val bleDisconnectedException
    get() = BleDisconnectedException()

@Suppress("FunctionName", "LocalVariableName")
@RunWith(PowerMockRunner::class)
@PrepareForTest(
        value = [(RxBleClientMock.CharacteristicsBuilder::class), (RxBleClientMock.DeviceBuilder::class)],
        fullyQualifiedNames = ["com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock\$16"] // This thing is mocking android.bluetooth.BluetoothGattDescriptor
)
class BleIdiomDeviceTest {
    private val testHelper = BleMockClientBaseTestHelper()
    private lateinit var serverDevice: ServerDevice
    private lateinit var serviceDevice: TestService
    private lateinit var device: BleIdiomDevice

    private val testModule = Kodein.Module(allowSilentOverride = true) {
        bind<RxBleDeviceMock>() with factory { macAddress: String ->
            spy(with(macAddress).instance<RxBleDevice>()) as RxBleDeviceMock
        }

        // For this test-suite, return an 'actual' RxBleDeviceMock, not a mocked instance of it.
        bind<BleIdiomDevice>() with scopedSingleton(TestScope) { device: RxBleDevice -> BleIdiomDevice(device) }
    }

    @Before
    fun setup() {
        TestService.addConfiguration()

        testKodeinOverrides = {
            import(testModule, allowOverride = true)
        }

        testHelper.setup(this) {
            testDevices = listOf(BleMockClientBaseTestHelper.buildDeviceService(TestService::class, BleEndToEndTests.MAC_ADDRESS1) {
                when {
                    this == TestService::number -> BleEndToEndTests.INITIAL_NUMBER_VAl
                    this == TestService::bytes -> BleEndToEndTests.INITIAL_BYTES_VAL
                    this == TestService::chunkedData -> null
                    else -> name
                }
            }.build() as RxBleDeviceMock)
        }

        RxJavaPlugins.reset()
        RxAndroidPlugins.reset()

        serverDevice = LibTestKodein.with(BleEndToEndTests.MAC_ADDRESS1).instance()
        serviceDevice = ServiceDeviceFactory.obtainClientDevice(serverDevice.uuid, serverDevice)
        device = serviceDevice.device
    }

    @After
    fun tearDown() {
        testHelper.tearDown()

        BleTestModules.tearDown()
    }

    @Test
    fun test_for_many_reconnections() {
        // Dummy Server setup, where "number" always returns 0
        val testServerObserver = TestObserver<Pair<String, String>>()
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
                BiFunction<String, String, Pair<String, String>> { a, b -> a to b })
                .toObservable().subscribe(testServerObserver)

        // Observe connection state.
        val connectionObserver = TestObserver<ConnectionState>()

        // Start client test.
        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)
        device.observeConnectionState().subscribe(connectionObserver)

        // Generate 100 pairs, each pair's first value is odd, its seconds value is even.
        // Generate them using two threads using different timing.
        val thread1 = Thread {
            // Generate pairs whose first and second values lie between 0 and 99
            for (i in 0..99) {
                device[TestService::string1] = (device[TestService::number] + 1 + 2 * i).asString()
                Thread.sleep(10)
                device[TestService::string2] = (device[TestService::number] + 2 * i).asString()
            }
        }
        thread1.start()

        thread1.join()

        Thread.sleep(50)

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
        assertTrue { sortedString1Values.all { it -> it % 2 == 1 } }

        assertEquals(0, sortedString2Values[0])
        assertEquals(198, sortedString2Values.last())
        assertTrue { sortedString2Values.all { it -> it % 2 == 0 } }

        assertEquals(ConnectionState.Disconnected, connectionObserver.values().last())
    }

    @Test
    fun test_for_many_reconnections_in_race_conditions() {
        // As of now, this test occasionally fails.
        // Instead of 100 values in the testServerObserver, there are 99 on occasion.

        // Dummy Server setup, where "number" always returns 0
        val testServerObserver = TestObserver<Pair<String, String>>()
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
                BiFunction<String, String, Pair<String, String>> { a, b -> a to b })
                .toObservable().subscribe(testServerObserver)

        // Observe connection state.
        val connectionObserver = TestObserver<ConnectionState>()

        // Start client test.
        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)
        device.observeConnectionState().subscribe(connectionObserver)

        // Generate 100 pairs, each pair's first value is odd, its seconds value is even.
        // Generate them using two threads using different timing.
        val thread1 = Thread {
            // Generate pairs whose first and second values lie between 0 and 99
            for (i in 0..49) {
                Thread.sleep(5)
                device[TestService::string1] = (device[TestService::number] + 1 + 2 * i).asString()
                Thread.sleep(5)
                device[TestService::string2] = (device[TestService::number] + 2 * i).asString()
            }
        }
        thread1.start()

        val thread2 = Thread {
            // Generate pairs whose first and second values lie between 100 and 199
            for (i in 0..49) {
                Thread.sleep(9)
                device[TestService::string2] = (device[TestService::number] + 100 + 2 * i).asString()
                device[TestService::string1] = (device[TestService::number] + 101 + 2 * i).asString()
            }
        }
        thread2.start()

        thread1.join()
        thread2.join()

        Thread.sleep(50)

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
        assertTrue { sortedString1Values.all { it -> it % 2 == 1 } }

        assertEquals(0, sortedString2Values[0])
        assertEquals(198, sortedString2Values.last())
        assertTrue { sortedString2Values.all { it -> it % 2 == 0 } }

        assertEquals(ConnectionState.Disconnected, connectionObserver.values().last())
    }

    @Test
    fun test_no_successful_connection() {
        val expectedCount = 4
        val delay = 100L

        var count = 0

        doAnswer { inv ->
            count++
            val ret = inv.callRealMethod() as Observable<*>
            ret.flatMap { Observable.error<RxBleConnection>(bleDisconnectedException) }
        }.whenever(serverDevice).establishConnection(any())

        var numberOfRetries = 0
        serviceDevice.connectionRetryStrategy = { _, _, retryCount ->
            if (retryCount < expectedCount) {
                numberOfRetries++
                val ret = Single.just(true).delay(delay, TimeUnit.MILLISECONDS, Schedulers.newThread())
                ret
            } else {
                Single.just(false)
            }
        }

        val testObs = TestObserver<RxBleConnection>()

        serviceDevice.sharedConnection.mapTry().subscribe(testObs)
        Thread.sleep((expectedCount + 1) * delay)

        testObs.assertNotComplete()
        testObs.assertNoValues()
        testObs.assertError { it is BleDisconnectedException }
        assertEquals(expectedCount, count)
        assertEquals(count - 1, numberOfRetries)

        testObs.dispose()
    }

    @Test
    fun test_successful_connection_after_2_retries() {
        val expectedCount = 4
        val expectedRetries = 2
        val delay = 100L

        var count = 0
        doAnswer { inv ->
            count++
            val ret = inv.callRealMethod() as Observable<*>
            ret.flatMap {
                if (count == (expectedRetries + 1)) Observable.just(it)
                else Observable.error<RxBleConnection>(bleDisconnectedException)
            }
        }.whenever(serverDevice).establishConnection(any())

        var numberOfRetries = 0
        serviceDevice.connectionRetryStrategy = { _, _, retryCount ->
            if (retryCount < expectedCount) {
                numberOfRetries++
                Single.just(true).delay(delay, TimeUnit.MILLISECONDS, Schedulers.newThread())
            } else {
                fail("Should not reach this code")
            }
        }

        val testObs = TestObserver<RxBleConnection>()

        serviceDevice.sharedConnection.mapTry().subscribe(testObs)
        testObs.awaitCount(1) {}

        testObs.assertNotComplete()
        testObs.assertValueCount(1)
        testObs.assertNoErrors()
        assertEquals(expectedRetries, numberOfRetries)
        assertEquals(count - 1, numberOfRetries)

        testObs.dispose()
    }

    @Test
    fun test_restored_connection_after_retry_a_few_times_in_a_row() {
        val delay = 100L
        val maxRetries = 3

        val emittersObs = TestObserver<Emitter<Unit>>()
        val emitters = BehaviorSubject.create<Emitter<Unit>>()
        val safeEmitters = emitters.toSerialized()
        emitters.subscribe(emittersObs)

        doAnswer { inv ->
            val ret = inv.callRealMethod() as Observable<*>
            val controller = Observable.create<Unit>({ emitter ->
                safeEmitters.onNext(emitter)
            })
            Observable.zip(ret, controller, BiFunction { conn: Any, _: Unit -> conn })
        }.whenever(serverDevice).establishConnection(any())

        serviceDevice.connectionRetryStrategy = { _, _, retryCount ->
            if (retryCount <= maxRetries) Single.just(true).delay(delay, TimeUnit.MILLISECONDS, Schedulers.newThread())
            else Single.just(false)
        }

        val testObs = TestObserver<RxBleConnection>()

        serviceDevice.sharedConnection.mapTry().subscribe(testObs)

        var emitterCount = 1
        var connectionCount = 1
        emittersObs.awaitCount(emitterCount++) {}
        emitters.value.onNext(Unit) // Trigger initial connection.

        // Wait and check if we actually did get one connection.
        testObs.awaitCount(connectionCount) {}
        testObs.assertValueCount(connectionCount)

        // Do this five times: Disconnect and then reconnect
        (1..5).forEach {
            // Force 'maxRetries' disconnects before reconnecting successfully again.
            (1..maxRetries).forEach {
                // Trigger disconnection after another 10ms
                Thread.sleep(10)
                emitters.value.onError(bleDisconnectedException)

                // Wait until retry connection attempt is handled...
                emittersObs.awaitCount(emitterCount++) {}

                // ...and check if we actually still have 'connectionCount' connections.
                testObs.assertValueCount(connectionCount)
            }

            // Trigger a successful connection (without delay)
            emitters.value.onNext(Unit)
            connectionCount++

            // Wait and check if we actually did get the new connection.
            testObs.awaitCount(connectionCount) {}
            testObs.assertValueCount(connectionCount)
        }

        testObs.dispose()
    }

    @Test
    fun test_fail_to_restore_connection_after_retry() {
        val delay = 100L
        val maxRetries = 3

        val emittersObs = TestObserver<Emitter<Unit>>()
        val emitters = BehaviorSubject.create<Emitter<Unit>>()
        val safeEmitters = emitters.toSerialized()
        emitters.subscribe(emittersObs)

        doAnswer { inv ->
            val ret = inv.callRealMethod() as Observable<*>
            val controller = Observable.create<Unit>({ emitter ->
                safeEmitters.onNext(emitter)
            })
            Observable.zip(ret, controller, BiFunction { conn: Any, _: Unit -> conn })
        }.whenever(serverDevice).establishConnection(any())

        serviceDevice.connectionRetryStrategy = { _, _, retryCount ->
            if (retryCount <= maxRetries) Single.just(true).delay(delay, TimeUnit.MILLISECONDS, Schedulers.newThread())
            else Single.just(false)
        }

        val testObs = TestObserver<RxBleConnection>()

        serviceDevice.sharedConnection.mapTry().subscribe(testObs)

        var emitterCount = 1
        val connectionCount = 1
        emittersObs.awaitCount(emitterCount++) {}
        emitters.value.onNext(Unit) // Trigger initial connection.

        // Wait and check if we actually did get one connection.
        testObs.awaitCount(connectionCount) {}
        testObs.assertValueCount(connectionCount)

        // Do this five times: Disconnect and then reconnect
        (1..1).forEach {
            // Force more than 'maxRetries' disconnects which will prevent a successful reconnect.
            (1..(maxRetries + 1)).forEach {
                // Trigger disconnection after another 10ms
                Thread.sleep(10)
                emitters.value.onError(bleDisconnectedException)

                // Wait...
                if (it <= maxRetries) {
                    // ...until retry connection attempt is handled...
                    emittersObs.awaitCount(emitterCount++) {}
                } else {
                    // ...until refusing the retry is handled...
                    Thread.sleep(10)
                }

                // ...and check if we actually still have 'connectionCount' connections.
                testObs.assertValueCount(connectionCount)
            }

            // Trigger a successful connection (without delay)
            emitters.value.onNext(Unit)

            // The number of failures (BleDisconnectedException) were bigger than 'maxRetries'
            // Even triggering a successful connection won't fix this.
            testObs.assertValueCount(connectionCount)
            testObs.assertError { it is BleDisconnectedException }
        }

        testObs.dispose()
    }
}
