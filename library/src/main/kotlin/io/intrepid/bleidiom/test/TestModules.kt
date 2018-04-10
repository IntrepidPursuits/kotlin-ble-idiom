package io.intrepid.bleidiom.test

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
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
import org.kodein.di.Kodein
import org.kodein.di.bindings.Scope
import org.kodein.di.bindings.ScopeRegistry
import org.kodein.di.conf.ConfigurableKodein
import org.kodein.di.generic.bind
import org.kodein.di.generic.instance
import org.kodein.di.generic.kcontext
import org.kodein.di.generic.multiton
import org.kodein.di.generic.provider
import org.kodein.di.generic.scoped
import org.kodein.di.generic.singleton

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

            bind<TestScheduler>() with scoped(TestScope).singleton { TestScheduler() }
            bind<Scheduler>() with scoped(TestScope).singleton { with(testClass).instance() }

            bind<RxBleDeviceMock>() with multiton { macAddress: String ->
                spy(with(macAddress).instance() as RxBleDeviceMock)
            }
        }

        private val BleModuleOverrides = Kodein.Module(allowSilentOverride = true) {
            // Provides the RxAndroidBle overrides. Return a RxBleClientMock instead.
            bind<RxBleClient>() with provider {
                RxBleClientMock.Builder()
                    .setDeviceDiscoveryObservable(Observable.fromIterable(testDevices))
                    .build()
            }

            bind<BleIdiomDevice>() with scoped(TestScope).singleton { device: RxBleDevice ->
                val deviceMock = with(device.macAddress).instance()
                BleIdiomDevice(deviceMock)
            }

            // Provides for logging
            bind<Logger>() with singleton { SystemOutLogger() }
        }
    }
}

internal object TestScope : Scope<Any, Any> {
    override fun getBindingContext(envContext: Any): Any {
       return kcontext(envContext)
    }

    private val registry = hashMapOf<Int, ScopeRegistry>()

    override fun getRegistry(receiver: Any?, context: Any) = synchronized(registry) {
        registry.getOrPut(context.hashCode()) { ScopeRegistry() }
    }
}

