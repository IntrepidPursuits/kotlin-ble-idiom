package io.intrepid.bleidiom.test

import android.app.Application
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.bindings.Scope
import com.github.salomonbrys.kodein.bindings.ScopeRegistry
import com.github.salomonbrys.kodein.conf.ConfigurableKodein
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.multiton
import com.github.salomonbrys.kodein.provider
import com.github.salomonbrys.kodein.scopedSingleton
import com.github.salomonbrys.kodein.singleton
import com.github.salomonbrys.kodein.with
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doAnswer
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.spy
import com.nhaarman.mockito_kotlin.whenever
import com.polidea.rxandroidble.internal.connection.NoRetryStrategy
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.internal.connection.ImmediateSerializedBatchAckStrategy
import com.polidea.rxandroidble2.mockrxandroidble.RxBleClientMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import io.intrepid.bleidiom.BleIdiomDevice
import io.intrepid.bleidiom.letMany
import io.intrepid.bleidiom.log.Logger
import io.intrepid.bleidiom.module.LibKodein
import io.intrepid.bleidiom.module.PublicBleModule
import io.intrepid.bleidiom.module.initBleIdiomModules
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.functions.BiFunction
import io.reactivex.schedulers.TestScheduler
import java.util.UUID
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

            bind<RxBleDeviceMock>() with multiton { macAddress: String ->
                val spiedDevice = spy(with(macAddress).instance<RxBleDevice>() as RxBleDeviceMock)
                createMockedLongWriteBuilder(spiedDevice)
            }
        }

        private fun createMockedLongWriteBuilder(spiedDevice: RxBleDeviceMock): RxBleDeviceMock {
            doAnswer { inv ->
                @Suppress("UNCHECKED_CAST")
                val spiedConnection = inv.callRealMethod() as Observable<RxBleConnection>
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
                    .setDeviceDiscoveryObservable(Observable.fromIterable(testDevices))
                    .build()
            }

            bind<BleIdiomDevice>() with scopedSingleton(TestScope) { device: RxBleDevice ->
                val deviceMock = with(device.macAddress).instance<RxBleDeviceMock>()
                BleIdiomDevice(deviceMock)
            }

            // Provides for logging
            bind<Logger>() with singleton { SystemOutLogger() }
        }
    }
}

internal object TestScope : Scope<Any> {
    private val registry = hashMapOf<Int, ScopeRegistry>()

    override fun getRegistry(context: Any) = synchronized(registry) {
        registry.getOrPut(context.hashCode()) { ScopeRegistry() }
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