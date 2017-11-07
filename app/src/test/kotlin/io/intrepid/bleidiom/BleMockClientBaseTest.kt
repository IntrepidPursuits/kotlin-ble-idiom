package io.intrepid.bleidiom

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doAnswer
import com.nhaarman.mockito_kotlin.mock
import com.polidea.rxandroidble.mockrxandroidble.RxBleClientMock
import com.polidea.rxandroidble.mockrxandroidble.RxBleDeviceMock
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.processors.PublishProcessor
import io.reactivex.subjects.PublishSubject
import org.powermock.api.mockito.PowerMockito
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

@Suppress("PrivatePropertyName")

/* Move this to the top of your actual concrete test-class:

    @RunWith(PowerMockRunner::class)
    @PrepareForTest(
        value = [(RxBleClientMock.CharacteristicsBuilder::class), (RxBleClientMock.DeviceBuilder::class)],
        fullyQualifiedNames = ["com.polidea.rxandroidble.mockrxandroidble.RxBleConnectionMock\$21"]
    )
*/

/**
 * Base class of all tests that need to test BLE functionality and setup a mock RxBleDeviceMock client
 * that acts as a BLE Server proxy and doesn't require RoboElectric.
 */
open class BleMockClientBaseTest : BleBaseTest() {
    companion object {
        var macAddress: String? = null

        val newMacAddress
            get() = IntArray(6, { RANDOM.nextInt(256) })
                    .fold("") { mac, byte ->
                        val byteStr = byte.toString(16).padStart(2, '0')
                        if (mac.isEmpty()) byteStr else "$mac:$byteStr"
                    }

        private val RANDOM = Random(System.currentTimeMillis())
    }

    override fun setup() {
        super.setup()

        PowerMockito
                .whenNew(BluetoothGattService::class.java)
                .withAnyArguments()
                .thenAnswer {
                    TestableBluetoothGattService.mock(it.arguments[0] as UUID)
                }

        PowerMockito
                .whenNew(BluetoothGattCharacteristic::class.java)
                .withAnyArguments()
                .thenAnswer {
                    TestableBluetoothGattCharacteristic.mock(it.arguments[0] as UUID)
                }

        PowerMockito
                .whenNew(BluetoothGattDescriptor::class.java)
                .withAnyArguments()
                .thenAnswer {
                    TestableBluetoothGattDescriptor.mock(it.arguments[0] as UUID)
                }
    }

    override fun tearDown() {
        super.tearDown()

        TestableBluetoothGattCharacteristic.tearDown()
        BleService.clearConfigurations()
        macAddress = null
    }

    internal fun <T : BleService<T>> buildDeviceService(
            serviceClass: KClass<T>,
            getInitialValue: KProperty1<T, BleCharValue<Any>>.() -> Any? = { null }
    ): RxBleClientMock.DeviceBuilder {
        val service: T = serviceClass.primaryConstructor!!.call()
        macAddress = newMacAddress

        var charListBuilder = RxBleClientMock.CharacteristicsBuilder()
        var deviceBuilder = RxBleClientMock.DeviceBuilder()

        serviceClass.memberProperties.forEach { prop ->
            if (prop.returnType.classifier == BleCharValue::class) {
                @Suppress("UNCHECKED_CAST")
                val property = (prop as KProperty1<T, BleCharValue<Any>>)

                property.isAccessible = true

                val delegate = property.getDelegate(service)
                if (delegate is BleCharValueDelegate<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val castDelegate: BleCharValueDelegate<Any> = delegate as BleCharValueDelegate<Any>

                    val charUUID = castDelegate.charUUID(service, property)
                    if (charUUID != null) {
                        val uuid = UUID.fromString(fixCharUUID(charUUID))

                        charListBuilder = addCharacteristic(macAddress!!, uuid, property, charListBuilder)

                        val characteristic = TestableBluetoothGattCharacteristic[macAddress!!][uuid]
                        if (characteristic != null) {
                            characteristic.setInitialCharValue(property.getInitialValue(), castDelegate)

                            deviceBuilder = deviceBuilder
                                    .notificationSource(uuid, characteristic.serverNotificationObservable.toRx1Observable())
                        }
                    }
                }
            }
        }

        return deviceBuilder
                .deviceMacAddress(macAddress!!)
                .addService(UUID.fromString(fixSvcUUID(service.dsl.uuid)), charListBuilder.build())
                .rssi(0)
                .scanRecord(ByteArray(0))
    }

    private fun <T : BleService<T>> addCharacteristic(
            macAddress: String,
            uuid: UUID, property: KProperty1<T, BleCharValue<Any>>,
            charListBuilder: RxBleClientMock.CharacteristicsBuilder): RxBleClientMock.CharacteristicsBuilder {
        var builder = charListBuilder

        builder = builder.addCharacteristic(uuid, ByteArray(0), listOf())
        TestableBluetoothGattCharacteristic[macAddress].tie(uuid, property)

        return builder
    }
}

internal typealias ServerDevice = RxBleDeviceMock

/**
 *  Returns the first advertised service UUID.
 */
val ServerDevice.uuid get() = advertisedUUIDs[0]

/**
 * Returns the [TestableBluetoothGattCharacteristic] with the given UUID
 * @param charUUID The UUID of the characteristic.
 * @return A [TestableBluetoothGattCharacteristic]
 */
operator fun ServerDevice.get(charUUID: UUID) =
        TestableBluetoothGattCharacteristic[macAddress][charUUID]

/**
 * Returns the [TestableBluetoothGattCharacteristic] for the given [BleCharValue] property.
 * @param property The property that represents the characteristic.
 * @return A [TestableBluetoothGattCharacteristic]
 */
operator fun <V : Any> ServerDevice.get(property: KProperty1<out BleService<*>, BleCharValue<V>>) =
        TestableBluetoothGattCharacteristic[macAddress][property]

/**
 * Instances of this type accompany mocked [BluetoothGattService] instances and handle their mocked
 * implementation.
 */
class TestableBluetoothGattService(val uuid: UUID) {
    companion object {
        internal fun mock(svcUUID: UUID) =
                mock<BluetoothGattService> {
                    val testObj = TestableBluetoothGattService(svcUUID)
                    on { uuid } doAnswer {
                        testObj.uuid
                    }

                    on { addCharacteristic(any()) } doAnswer {
                        testObj.listOfChars += it.arguments[0] as BluetoothGattCharacteristic; true
                    }

                    on { getCharacteristic(any()) } doAnswer { svc ->
                        val charUUID = svc.arguments[0] as UUID
                        testObj.listOfChars.firstOrNull { it.uuid == charUUID }
                    }
                }
    }

    private val listOfChars: MutableList<BluetoothGattCharacteristic> = mutableListOf()
}

/**
 * Represents the handling and management of [BluetoothGattCharacteristic]s on a dummy/mocked server.
 */
internal class ServerCharacteristics {
    companion object {
        /**
         * Ties a [TestableBluetoothGattCharacteristic] to the actively configured device (this device
         * is represented by the current value of [BleMockClientBaseTest.macAddress]).
         * @param characteristic The [TestableBluetoothGattCharacteristic] representing the BLE characteristic.
         */
        internal fun registerCharacteristic(characteristic: TestableBluetoothGattCharacteristic<*>) {
            val macAddress = BleMockClientBaseTest.macAddress!!
            TestableBluetoothGattCharacteristic[macAddress].registerCharacteristic(characteristic)
        }
    }

    private val registeredChars: MutableList<TestableBluetoothGattCharacteristic<Any>> = mutableListOf()
    private val registeredProps: MutableMap<String, TestableBluetoothGattCharacteristic<Any>> = mutableMapOf()

    internal operator fun get(charUUID: UUID) = registeredChars.firstOrNull { it.uuid == charUUID }

    @Suppress("UNCHECKED_CAST")
    internal operator fun <V : Any> get(property: KProperty1<out BleService<*>, BleCharValue<V>>) =
            registeredProps[propertyId(property)] as TestableBluetoothGattCharacteristic<V>?

    internal fun tearDown() {
        registeredChars.clear()
        registeredProps.clear()
    }

    /**
     * Ties a [BleCharValue] property to a BLE characteristic on the dummy/mock server.
     * @param charUUID The UUID representing the BLE characteristic.
     */
    internal fun tie(charUUID: UUID, property: KProperty1<out BleService<*>, BleCharValue<Any>>) {
        val characteristic = registeredChars.firstOrNull { it.uuid == charUUID }
        if (characteristic != null) {
            registeredProps[propertyId(property)] = characteristic
        }
    }

    private fun registerCharacteristic(characteristic: TestableBluetoothGattCharacteristic<*>) {
        if (registeredChars.any { it.uuid == characteristic.uuid }) {
            throw IllegalStateException("UUID ${characteristic.uuid} already exists.")
        }

        @Suppress("UNCHECKED_CAST")
        registeredChars += characteristic as TestableBluetoothGattCharacteristic<Any>
    }

    private fun propertyId(property: KProperty1<out BleService<*>, BleCharValue<*>>) =
            property.instanceParameter?.type.toString() + "::" + property.name
}

/**
 * Instances of this type accompany mocked [BluetoothGattCharacteristic] instances and handle their mocked
 * implementation.
 */
class TestableBluetoothGattCharacteristic<V : Any>(val uuid: UUID) {
    companion object {
        private val registeredServerDevices = mutableMapOf<String, ServerCharacteristics>()

        internal operator fun get(macAddress: String): ServerCharacteristics {
            var serverChar = registeredServerDevices[macAddress]
            if (serverChar == null) {
                serverChar = ServerCharacteristics()
                registeredServerDevices[macAddress] = serverChar
            }
            return serverChar
        }

        internal fun tearDown() {
            registeredServerDevices.values.forEach {
                it.tearDown()
            }
            registeredServerDevices.clear()
        }

        internal fun mock(charUUID: UUID) =
                mock<BluetoothGattCharacteristic> {
                    val testObj = TestableBluetoothGattCharacteristic<Any>(charUUID)
                    on { uuid } doAnswer {
                        testObj.uuid
                    }

                    on { value } doAnswer {
                        testObj.valueOnServer
                    }

                    on { setValue(any<ByteArray>()) } doAnswer {
                        testObj.valueOnServer = it.arguments[0] as ByteArray; true
                    }

                    on { addDescriptor(any()) } doAnswer {
                        testObj.descriptors += it.arguments[0] as BluetoothGattDescriptor; true
                    }

                    on { getDescriptor(any()) } doAnswer {
                        val uuid = it.arguments[0] as UUID
                        testObj.descriptors.firstOrNull { it.uuid == uuid }
                    }
                }
    }

    var value: V
        get() {
            synchronized(this) {
                return fromByteArray(valueAsByteArray)
            }
        }
        set(value) {
            synchronized(this) {
                valueAsByteArray = toByteArray(value)
            }
        }

    private var valueAsByteArray = ByteArray(0)

    private lateinit var fromByteArray: ((ByteArray) -> V)
    private lateinit var toByteArray: ((V) -> ByteArray)

    private var valueOnServer: ByteArray
        get() {
            synchronized(this) {
                serverReadProcessor.onNext(this)
                return valueAsByteArray
            }
        }
        set(value) {
            synchronized(this) {
                valueAsByteArray = value
                serverWriteProcessor.onNext(this)
            }
        }

    internal val serverNotificationObservable: Observable<ByteArray>
        get() = serverNotificationPublisher

    private val serverNotificationPublisher = PublishSubject.create<ByteArray>()

    private val serverReadProcessor = PublishProcessor.create<TestableBluetoothGattCharacteristic<V>>()
    private val serverWriteProcessor = PublishProcessor.create<TestableBluetoothGattCharacteristic<V>>()

    private val descriptors = mutableListOf<BluetoothGattDescriptor>()

    init {
        @Suppress("UNCHECKED_CAST")
        ServerCharacteristics.registerCharacteristic(this)
    }

    fun observeServerReads(): Flowable<TestableBluetoothGattCharacteristic<V>> = serverReadProcessor

    fun observeServerWrites(): Flowable<TestableBluetoothGattCharacteristic<V>> = serverWriteProcessor

    fun sendNotification(newValueOnServer: ByteArray) {
        synchronized(this) {
            // Assign directly to valueAsByteArray to bypass the serverWriteProcessor's onNext call.
            valueAsByteArray = newValueOnServer
            serverNotificationPublisher.onNext(valueAsByteArray)
        }
    }

    internal fun setInitialCharValue(initVal: V?, delegate: BleCharValueDelegate<V>) {
        fromByteArray = delegate.fromByteArray!!
        toByteArray = delegate.toByteArray!!

        if (initVal != null) {
            value = initVal
        }
    }
}

/**
 * Instances of this type accompany mocked [BluetoothGattDescriptor] instances and handle their mocked
 * implementation.
 */
class TestableBluetoothGattDescriptor(val uuid: UUID) {
    companion object {
        internal fun mock(descUUID: UUID) =
                mock<BluetoothGattDescriptor> {
                    val testObj = TestableBluetoothGattDescriptor(descUUID)
                    on { uuid } doAnswer {
                        testObj.uuid
                    }

                    on { value } doAnswer {
                        testObj.value
                    }

                    on { setValue(any()) } doAnswer {
                        testObj.value = it.arguments[0] as ByteArray; true
                    }
                }

    }

    private var value: ByteArray? = null
}
