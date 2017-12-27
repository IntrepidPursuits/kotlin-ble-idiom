package io.intrepid.bleidiom.test

import android.app.Application
import android.content.Context
import com.github.salomonbrys.kodein.*
import com.github.salomonbrys.kodein.bindings.Scope
import com.github.salomonbrys.kodein.bindings.ScopeRegistry
import com.github.salomonbrys.kodein.conf.ConfigurableKodein
import com.nhaarman.mockito_kotlin.mock
import com.polidea.rxandroidble.RxBleClient
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.mockrxandroidble.RxBleClientMock
import com.polidea.rxandroidble.mockrxandroidble.RxBleDeviceMock
import io.intrepid.bleidiom.log.Logger
import io.intrepid.bleidiom.module.initBleIdiomModules
import io.reactivex.Scheduler
import io.reactivex.schedulers.TestScheduler

@Suppress("PropertyName")
val LibTestKodein: Kodein
    get() = BleTestModules.testKodein

class BleTestModules {
    companion object {

        var testDevices: Iterable<RxBleDeviceMock> = listOf()

        internal val testKodein: ConfigurableKodein = ConfigurableKodein(true)

        fun setup(factory: Companion.() -> Unit) {
            testKodein.addImport(TestAppModule)
            initBleIdiomModules(testKodein) {
                addImport(BleModuleOverrides, allowOverride = true)
            }
            this.factory()
        }

        fun tearDown() {
            testKodein.clear()
        }

        private val TestAppModule = Kodein.Module {
            bind<Application>() with singleton { mock<Application>() }
            bind<Context>() with singleton { instance<Application>() }

            bind<TestScheduler>() with scopedSingleton(TestScope) { TestScheduler() }
            bind<Scheduler>() with scopedSingleton(TestScope) { with(it).instance<TestScheduler>() }

            bind<RxBleDeviceMock>() with factory { macAddress: String ->
                with(macAddress).instance<RxBleDevice>() as RxBleDeviceMock
            }
        }

        private val BleModuleOverrides = Kodein.Module(allowSilentOverride = true) {
            // Provides the RxAndroidBle overrides. Return a RxBleClientMock instead.
            bind<RxBleClient>() with provider {
                RxBleClientMock.Builder()
                        .setDeviceDiscoveryObservable(rx.Observable.from(testDevices))
                        .build()
            }

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