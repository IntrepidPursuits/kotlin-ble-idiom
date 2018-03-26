package com.example.bledsldemo

import io.intrepid.bleidiom.BleCharValue
import io.intrepid.bleidiom.BleService
import io.intrepid.bleidiom.bleCharHandler

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */

internal class TestService : BleService<TestService>() {
    companion object {
        fun addConfiguration() {
            BleService<TestService> {
                configure {
                    uuid = "1234"

                    readAndWrite {
                        data between "0001" and ::value1
                        data between "0002" and ::value2
                    }
                }
            }
        }
    }

    var value1: BleCharValue<Int> by bleCharHandler()
    var value2: BleCharValue<Int> by bleCharHandler()
}

val MAC_ADDRESS = "12:34:56:78:9A:BC"

//@Suppress("FunctionName")
//@RunWith(PowerMockRunner::class)
//@PrepareForTest(
//        value = [(RxBleClientMock.CharacteristicsBuilder::class), (RxBleClientMock.DeviceBuilder::class)],
//        fullyQualifiedNames = ["com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock\$16"] // This thing is mocking android.bluetooth.BluetoothGattDescriptor
//)
//class ExampleUnitTest: BleMockClientBaseTest() {
//    private lateinit var serverDevice: ServerDevice
//
//    @Before
//    override fun setup() {
//        super.setup()
//
//        TestService.addConfiguration()
//
//        BleTestModules.load {
//            testDevices = listOf(
//                    buildDeviceService(TestService::class, MAC_ADDRESS) { 10 }.build() as RxBleDeviceMock
//            )
//        }
//
//        serverDevice = BleTestModules.kodein.with(MAC_ADDRESS).instance()
//    }
//
//    @After
//    override fun tearDown() {
//        super.tearDown()
//
//        BleTestModules.unload()
//    }
//
//    @Test
//    fun just_some_sample_test()  {
//        val initialValue = 5
//        val addedValue = 10
//        val expectedValue = initialValue + addedValue
//
//        // Dummy Server setup.
//        val testServerObserver = TestObserver<Int>()
//        serverDevice[TestService::value1]?.observeServerReads()
//                ?.subscribeBy { char ->
//                    char.value = initialValue
//                }
//
//        // Observe write-characteristic calls coming in from the client.
//        serverDevice[TestService::value2]?.observeServerWrites()
//                ?.map { char -> char.value }
//                ?.toObservable()
//                ?.subscribe(testServerObserver)
//
//        // Start client test.
//        val device = ServiceDeviceFactory.obtainClientDevice<TestService>(serverDevice.uuid, serverDevice)
//
//        val writeObserver = TestObserver<Int>()
//        device.writeObserverFactory = {
//            val writePublisher = PublishSubject.create<Int>()
//            writePublisher.subscribe(writeObserver)
//            writePublisher
//        }
//
//        // Assign a value based on the 'number' characteristic to the 'string1' characteristic.
//        device[TestService::value2] = device[TestService::value1] + addedValue
//
//        testScheduler.triggerActions()
//
//        // Check for the observed writes on the client.
//        writeObserver.assertComplete()
//        writeObserver.assertValues(expectedValue)
//
//        // And check if the (dummy) server got the expected value.
//        testServerObserver.assertValues(expectedValue)
//
//        assertEquals(ConnectionState.Disconnected, device.observeConnectionState().firstOrError().blockingGet())
//    }
//}