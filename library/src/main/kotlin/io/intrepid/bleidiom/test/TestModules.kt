package io.intrepid.bleidiom.test

import android.app.Application
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
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.spy
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.mockrxandroidble.RxBleClientMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import io.intrepid.bleidiom.BleIdiomDevice
import io.intrepid.bleidiom.log.Logger
import io.intrepid.bleidiom.module.LibKodein
import io.intrepid.bleidiom.module.PublicBleModule
import io.intrepid.bleidiom.module.initBleIdiomModules
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.schedulers.TestScheduler

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
                spy(with(macAddress).instance<RxBleDevice>() as RxBleDeviceMock)
            }
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

