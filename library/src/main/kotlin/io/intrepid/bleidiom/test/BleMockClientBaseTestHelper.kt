package io.intrepid.bleidiom.test

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doAnswer
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.spy
import com.polidea.rxandroidble.internal.connection.NoRetryStrategy
import com.polidea.rxandroidble2.NotificationSetupMode
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.RxBleDeviceServices
import com.polidea.rxandroidble2.exceptions.BleAlreadyConnectedException
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import com.polidea.rxandroidble2.internal.connection.ImmediateSerializedBatchAckStrategy
import com.polidea.rxandroidble2.mockrxandroidble.RxBleClientMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import io.intrepid.bleidiom.BleCharValue
import io.intrepid.bleidiom.BleCharValueDelegate
import io.intrepid.bleidiom.BleIdiomDevice
import io.intrepid.bleidiom.BleService
import io.intrepid.bleidiom.letMany
import io.intrepid.bleidiom.toUUID
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import io.reactivex.processors.PublishProcessor
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import org.powermock.api.mockito.PowerMockito
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
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
        fullyQualifiedNames = ["com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock\$16"]
    )
*/

/**
 * Base Test-Helper class of all tests that need to test BLE functionality and setup a mock RxBleDeviceMock client
 * that acts as a BLE Server proxy and doesn't require RoboElectric.
 */
open class BleMockClientBaseTestHelper : BleBaseTestHelper() {
    companion object {
        const val deviceName = "MOCK_DEVICE"

        internal var macAddress: String? = null

        fun <T : BleService<T>> buildDeviceService(
                serviceClass: KClass<T>,
                macAddress: String,
                getInitialValue: KProperty1<T, BleCharValue<Any>>.() -> Any? = { null }
        ): RxBleClientMock.DeviceBuilder {
            val service: T = serviceClass.primaryConstructor!!.call()
            BleMockClientBaseTestHelper.macAddress = macAddress

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
                            val uuid = charUUID.toUUID()!!

                            charListBuilder = addCharacteristic(macAddress, uuid, property, charListBuilder)

                            val characteristic = TestableBluetoothGattCharacteristic[macAddress][uuid]
                            if (characteristic != null) {
                                characteristic.setInitialCharValue(property.getInitialValue(), castDelegate)

                                deviceBuilder = deviceBuilder
                                        .notificationSource(uuid, characteristic.serverNotificationObservable)
                            }
                        }
                    }
                }
            }

            return deviceBuilder
                    .deviceMacAddress(BleMockClientBaseTestHelper.macAddress!!)
                    .deviceName(deviceName)
                    .addService(service.dsl.uuid.toUUID()!!, charListBuilder.build())
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

    override fun setup(testClass: Any, factory: BleTestModules.Companion.() -> Unit) {
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

        PowerMockito
                .whenNew(RxBleDeviceMock::class.java)
                .withAnyArguments()
                .thenAnswer {
                    @Suppress("UNCHECKED_CAST")
                    TestRxBleDeviceMock(
                            it.arguments[0] as String,
                            it.arguments[1] as String,
                            it.arguments[2] as ByteArray,
                            it.arguments[3] as Int?,
                            it.arguments[4] as RxBleDeviceServices,
                            it.arguments[5] as Map<UUID, Observable<ByteArray>>
                    )
                }

        super.setup(testClass, factory)
    }

    override fun tearDown() {
        super.tearDown()

        TestableBluetoothGattCharacteristic.tearDown()
        BleService.clearConfigurations()
        macAddress = null
    }
}

typealias ServerDevice = RxBleDeviceMock

/**
 *  Returns the first advertised service UUID.
 */
val ServerDevice.uuid get() = advertisedUUIDs[0]!!

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
         * is represented by the current value of [BleMockClientBaseTestHelper.macAddress]).
         * @param characteristic The [TestableBluetoothGattCharacteristic] representing the BLE characteristic.
         */
        internal fun registerCharacteristic(characteristic: TestableBluetoothGattCharacteristic<*>) {
            val macAddress = BleMockClientBaseTestHelper.macAddress!!
            TestableBluetoothGattCharacteristic[macAddress].registerCharacteristic(characteristic)
        }
    }

    private val registeredChars: MutableList<TestableBluetoothGattCharacteristic<Any>> = mutableListOf()
    private val registeredProps: MutableMap<String, TestableBluetoothGattCharacteristic<Any>> = hashMapOf()

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
        private val registeredServerDevices = hashMapOf<String, ServerCharacteristics>()

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

    var valueOnServer: ByteArray
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

/**
 * Modifies the [RxBleDeviceMock]'s [establishConnection] method by marking returned [RxBleConnection] as
 * connected or not and by returning a brand new [RxBleConnection] object for each new connection.
 */
open class TestRxBleDeviceMock(
        name: String,
        macAddress: String,
        scanRecord: ByteArray,
        rssi: Int?,
        rxBleDeviceServices: RxBleDeviceServices,
        characteristicNotificationSources: Map<UUID, Observable<ByteArray>>
) : RxBleDeviceMock(name, macAddress, scanRecord, rssi, rxBleDeviceServices, characteristicNotificationSources) {
    private val isConnected = AtomicBoolean(false)

    private val rxBleConnectionFactory: () -> TestRxBleConnectionMock = {
        spy(TestRxBleConnectionMock(macAddress, rxBleDeviceServices, rssi!!, characteristicNotificationSources))
    }

    private val connectionStateBehaviorSubject = BehaviorSubject.createDefault<RxBleConnection.RxBleConnectionState>(
            RxBleConnection.RxBleConnectionState.DISCONNECTED
    )

    override fun establishConnection(autoConnect: Boolean): Observable<RxBleConnection> {
        return Observable.defer {
            if (isConnected.compareAndSet(false, true)) {
                val connection = rxBleConnectionFactory()
                Observable.never<RxBleConnection>().startWith(connection)
                        .doOnSubscribe {
                            connectionStateBehaviorSubject.onNext(RxBleConnection.RxBleConnectionState.CONNECTING)
                        }
                        .doOnNext {
                            connectionStateBehaviorSubject.onNext(RxBleConnection.RxBleConnectionState.CONNECTED)
                        }
                        .doFinally {
                            connection.isConnectedToDevice = false
                            connectionStateBehaviorSubject.onNext(RxBleConnection.RxBleConnectionState.DISCONNECTED)
                            isConnected.set(false)
                        }
            } else {
                Observable.error(BleAlreadyConnectedException(macAddress))
            }
        }
    }

    override fun observeConnectionStateChanges(): Observable<RxBleConnection.RxBleConnectionState> {
        return connectionStateBehaviorSubject.distinctUntilChanged()
    }
}

/**
 * An implementation of [RxBleConnectionMock] that will throw exceptions when methods on it are called
 * while it is marked as disconnected.
 */
open class TestRxBleConnectionMock(
        private val macAddress: String,
        rxBleDeviceServices: RxBleDeviceServices,
        rssi: Int,
        characteristicNotificationSources: Map<UUID, Observable<ByteArray>>
) : RxBleConnectionMock(rxBleDeviceServices, rssi, characteristicNotificationSources) {
    @Volatile var isConnectedToDevice: Boolean = true

    override fun writeCharacteristic(bluetoothGattCharacteristic: BluetoothGattCharacteristic, data: ByteArray): Single<ByteArray> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.writeCharacteristic(bluetoothGattCharacteristic, data)
    }

    override fun writeCharacteristic(characteristicUuid: UUID, data: ByteArray): Single<ByteArray> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.writeCharacteristic(characteristicUuid, data)
    }

    override fun requestMtu(mtu: Int): Single<Int> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.requestMtu(mtu)
    }

    override fun requestConnectionPriority(connectionPriority: Int, delay: Long, timeUnit: TimeUnit): Completable {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.requestConnectionPriority(connectionPriority, delay, timeUnit)
    }

    override fun setupNotification(characteristicUuid: UUID): Observable<Observable<ByteArray>> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.setupNotification(characteristicUuid)
    }

    override fun setupNotification(characteristic: BluetoothGattCharacteristic): Observable<Observable<ByteArray>> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.setupNotification(characteristic)
    }

    override fun setupNotification(characteristicUuid: UUID, setupMode: NotificationSetupMode): Observable<Observable<ByteArray>> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.setupNotification(characteristicUuid, setupMode)
    }

    override fun setupNotification(characteristic: BluetoothGattCharacteristic, setupMode: NotificationSetupMode): Observable<Observable<ByteArray>> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.setupNotification(characteristic, setupMode)
    }

    override fun setupIndication(characteristicUuid: UUID): Observable<Observable<ByteArray>> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.setupIndication(characteristicUuid)
    }

    override fun setupIndication(characteristic: BluetoothGattCharacteristic): Observable<Observable<ByteArray>> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.setupIndication(characteristic)
    }

    override fun setupIndication(characteristicUuid: UUID, setupMode: NotificationSetupMode): Observable<Observable<ByteArray>> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.setupIndication(characteristicUuid, setupMode)
    }

    override fun setupIndication(characteristic: BluetoothGattCharacteristic, setupMode: NotificationSetupMode): Observable<Observable<ByteArray>> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.setupIndication(characteristic, setupMode)
    }

    override fun writeDescriptor(serviceUuid: UUID, characteristicUuid: UUID, descriptorUuid: UUID, data: ByteArray): Completable {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.writeDescriptor(serviceUuid, characteristicUuid, descriptorUuid, data)
    }

    override fun writeDescriptor(descriptor: BluetoothGattDescriptor, data: ByteArray): Completable {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.writeDescriptor(descriptor, data)
    }

    override fun readDescriptor(serviceUuid: UUID, characteristicUuid: UUID, descriptorUuid: UUID): Single<ByteArray> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.readDescriptor(serviceUuid, characteristicUuid, descriptorUuid)
    }

    override fun readDescriptor(descriptor: BluetoothGattDescriptor): Single<ByteArray> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.readDescriptor(descriptor)
    }

    @Suppress("DEPRECATION", "OverridingDeprecatedMember")
    override fun getCharacteristic(characteristicUuid: UUID): Single<BluetoothGattCharacteristic> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.getCharacteristic(characteristicUuid)
    }

    override fun readRssi(): Single<Int> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.readRssi()
    }

    override fun discoverServices(): Single<RxBleDeviceServices> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.discoverServices()
    }

    override fun discoverServices(timeout: Long, timeUnit: TimeUnit): Single<RxBleDeviceServices> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.discoverServices(timeout, timeUnit)
    }

    override fun readCharacteristic(characteristicUuid: UUID): Single<ByteArray> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.readCharacteristic(characteristicUuid)
    }

    override fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Single<ByteArray> {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return super.readCharacteristic(characteristic)
    }

    override fun createNewLongWriteBuilder(): RxBleConnection.LongWriteOperationBuilder {
        if (!isConnectedToDevice) {
            throw BleDisconnectedException(macAddress)
        }
        return TestLongWriteBuilder(this)
    }
}

private class TestLongWriteBuilder(private val connection: RxBleConnection) : RxBleConnection.LongWriteOperationBuilder {

    private var bluetoothGattCharacteristicObservable: Observable<BluetoothGattCharacteristic>? = null

    private var maxBatchSize = 20 // default

    private var bytes: ByteArray? = null

    private var writeOperationAckStrategy: RxBleConnection.WriteOperationAckStrategy = ImmediateSerializedBatchAckStrategy()// default

    private var writeOperationRetryStrategy: RxBleConnection.WriteOperationRetryStrategy = NoRetryStrategy()// default

    override fun setWriteOperationRetryStrategy(writeOperationRetryStrategy: RxBleConnection.WriteOperationRetryStrategy): RxBleConnection.LongWriteOperationBuilder {
        this.writeOperationRetryStrategy = writeOperationRetryStrategy
        return this
    }

    override fun setBytes(bytes: ByteArray): RxBleConnection.LongWriteOperationBuilder {
        this.bytes = bytes
        return this
    }

    override fun setCharacteristicUuid(uuid: UUID): RxBleConnection.LongWriteOperationBuilder {
        bluetoothGattCharacteristicObservable = connection.discoverServices()
                .flatMap({ rxBleDeviceServices -> rxBleDeviceServices.getCharacteristic(uuid) })
                .toObservable()
        return this
    }

    override fun setCharacteristic(
            bluetoothGattCharacteristic: BluetoothGattCharacteristic): RxBleConnection.LongWriteOperationBuilder {
        bluetoothGattCharacteristicObservable = Observable.just(bluetoothGattCharacteristic)
        return this
    }

    override fun setMaxBatchSize(maxBatchSize: Int): RxBleConnection.LongWriteOperationBuilder {
        this.maxBatchSize = maxBatchSize
        return this
    }

    override fun setWriteOperationAckStrategy(writeOperationAckStrategy: RxBleConnection.WriteOperationAckStrategy): RxBleConnection.LongWriteOperationBuilder {
        this.writeOperationAckStrategy = writeOperationAckStrategy
        return this
    }

    override fun build(): Observable<ByteArray> {
        if (bluetoothGattCharacteristicObservable == null) {
            throw IllegalArgumentException("setCharacteristicUuid() or setCharacteristic() needs to be called before build()")
        }

        if (bytes == null) {
            throw IllegalArgumentException("setBytes() needs to be called before build()")
        }

        return letMany(bluetoothGattCharacteristicObservable, bytes) { bleGattCharObs, bytes ->
            val excess = bytes.size % maxBatchSize > 0
            val numberOfBatches = bytes.size / maxBatchSize + if (excess) 1 else 0
            val numberOfBatchesLeft = AtomicInteger(numberOfBatches)

            val batchObs = Observable.fromCallable { numberOfBatchesLeft.get() }
            Observable.zip(batchObs, bleGattCharObs,
                    BiFunction { chunkIdx: Int, char: BluetoothGattCharacteristic -> (chunkIdx to char) })
                    .concatMap { (idx, char) ->
                        val start = maxBatchSize * (numberOfBatches - idx)
                        val end = min(start + maxBatchSize, bytes.size)
                        connection
                                .writeCharacteristic(char, bytes.sliceArray(start until end))
                                .toObservable()
                                .concatMap { Observable.just(idx) }
                    }
                    .map { idx -> idx > 0 }
                    .compose(writeOperationAckStrategy)
                    .repeatWhen { it.takeWhile { numberOfBatchesLeft.decrementAndGet() > 0 } }
                    .toList()
                    .flatMapObservable { Observable.just(bytes) }
        } ?: Observable.empty()
    }
}

/* Some Test Helper functions/properties */

/**
 * Creates a new [BleIdiomDevice] with a mocked [RxBleDevice]
 */
val bleDeviceWithRxMock get() = BleIdiomDevice(mock())

/**
 * Returns a (mocked) [RxBleDevice] as a handle ([Any]) from a [BleIdiomDevice]
 */
val BleIdiomDevice.rxMock get(): Any = this.device

/**
 * Returns a (mocked) [BleIdiomDevice] from a [BleService]
 */
val BleService<*>.device get() = this.device

var BleService<*>.scanningRecord
    get() = this.scanRecord
    set(value) {
        this.scanRecord = value
    }
