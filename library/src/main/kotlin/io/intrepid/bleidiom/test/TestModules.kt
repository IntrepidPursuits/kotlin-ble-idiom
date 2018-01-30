package io.intrepid.bleidiom.test

import android.app.Application
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.github.salomonbrys.kodein.*
import com.github.salomonbrys.kodein.bindings.Scope
import com.github.salomonbrys.kodein.bindings.ScopeRegistry
import com.github.salomonbrys.kodein.conf.ConfigurableKodein
import com.nhaarman.mockito_kotlin.*
import com.polidea.rxandroidble.RxBleClient
import com.polidea.rxandroidble.RxBleConnection
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.internal.connection.ImmediateSerializedBatchAckStrategy
import com.polidea.rxandroidble.mockrxandroidble.RxBleClientMock
import com.polidea.rxandroidble.mockrxandroidble.RxBleDeviceMock
import io.intrepid.bleidiom.BleIdiomDevice
import io.intrepid.bleidiom.letMany
import io.intrepid.bleidiom.log.Logger
import io.intrepid.bleidiom.module.LibKodein
import io.intrepid.bleidiom.module.PublicBleModule
import io.intrepid.bleidiom.module.initBleIdiomModules
import io.reactivex.Scheduler
import io.reactivex.schedulers.TestScheduler
import rx.Observable
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

@Suppress("PropertyName")
val LibTestKodein: Kodein
    get() = LibKodein

class BleTestModules {
    companion object {

        var testDevices: Iterable<RxBleDeviceMock> = listOf()
        val testKodein: ConfigurableKodein = ConfigurableKodein(true)
        var testKodeinOverrides: Kodein.Builder.() -> Unit = {}
        var initKodein: (ConfigurableKodein) -> Unit = { initBleIdiomModules(it) }

        private var testClass: Any? = null

        fun setup(testClass: Any, factory: Companion.() -> Unit) {
            this.testClass = testClass
            testKodein.addImport(PublicBleModule)
            testKodein.addImport(TestAppModule, allowOverride = true)
            testKodein.addImport(BleModuleOverrides, allowOverride = true)
            testKodein.addConfig(testKodeinOverrides)

            initKodein(testKodein)
            this.factory()
        }

        fun tearDown() {
            testClass = null
            testKodeinOverrides = {}
            initKodein = { initBleIdiomModules(it) }
            testKodein.clear()
        }

        private val TestAppModule = Kodein.Module(allowSilentOverride = true) {
            bind<Application>() with singleton { mock<Application>() }
            bind<Context>() with singleton { instance<Application>() }

            bind<TestScheduler>() with scopedSingleton(TestScope) { TestScheduler() }
            bind<Scheduler>() with scopedSingleton(TestScope) { with(testClass).instance<TestScheduler>() }

            bind<RxBleDeviceMock>() with factory { macAddress: String ->
                val spiedDevice = spy(with(macAddress).instance<RxBleDevice>() as RxBleDeviceMock)
                createMockedLongWriteBuilder(spiedDevice)
            }
        }

        private fun createMockedLongWriteBuilder(spiedDevice: RxBleDeviceMock): RxBleDeviceMock {
            doAnswer { inv ->
                @Suppress("UNCHECKED_CAST")
                val spiedConnection = spy(inv.callRealMethod()) as Observable<RxBleConnection>
                spiedConnection.map {
                    val spiedConn = spy(it)
                    doAnswer { TestLongWriteBuilder(spiedConn) }.whenever(spiedConn).createNewLongWriteBuilder()
                    spiedConn
                }
            }.whenever(spiedDevice).establishConnection(any())
            return spiedDevice
        }

        private val BleModuleOverrides = Kodein.Module(allowSilentOverride = true) {
            // Provides the RxAndroidBle overrides. Return a RxBleClientMock instead.
            bind<RxBleClient>() with provider {
                RxBleClientMock.Builder()
                        .setDeviceDiscoveryObservable(rx.Observable.from(testDevices))
                        .build()
            }

            bind<BleIdiomDevice>() with scopedSingleton(TestScope) { device: RxBleDevice -> BleIdiomDevice(device) }

            // Provides for logging
            bind<Logger>() with singleton { SystemOutLogger() }
        }
    }
}

private object TestScope : Scope<Any> {
    private val registry = mutableMapOf<Int, ScopeRegistry>()

    override fun getRegistry(context: Any) = synchronized(registry) {
        registry.getOrPut(context.hashCode()) { ScopeRegistry() }
    }
}

private class TestLongWriteBuilder(private val connection: RxBleConnection) : RxBleConnection.LongWriteOperationBuilder {
    private var bluetoothGattCharacteristicObservable: Observable<BluetoothGattCharacteristic>? = null

    private var maxBatchSize = 20 // default

    private var bytes: ByteArray? = null

    private var writeOperationAckStrategy: RxBleConnection.WriteOperationAckStrategy = ImmediateSerializedBatchAckStrategy()// default

    override fun setBytes(bytes: ByteArray): RxBleConnection.LongWriteOperationBuilder {
        this.bytes = bytes
        return this
    }

    override fun setCharacteristicUuid(uuid: UUID): RxBleConnection.LongWriteOperationBuilder {
        bluetoothGattCharacteristicObservable = connection.discoverServices().flatMap(
                { rxBleDeviceServices -> rxBleDeviceServices.getCharacteristic(uuid) }
        )
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
            Observable.zip(batchObs, bleGattCharObs) { chunkIdx, char -> Pair(chunkIdx, char) }
                    .concatMap { (idx, char) ->
                        val start = maxBatchSize * (numberOfBatches - idx)
                        val end = min(start + maxBatchSize, bytes.size)
                        connection
                                .writeCharacteristic(char, bytes.sliceArray(start until end))
                                .concatMap { Observable.just(idx) }
                    }
                    .map { idx -> idx > 0 }
                    .compose(writeOperationAckStrategy)
                    .repeatWhen { it.takeWhile { numberOfBatchesLeft.decrementAndGet() > 0 } }
                    .toCompletable()
                    .andThen(Observable.just<ByteArray>(bytes))
        } ?: Observable.empty()
    }
}