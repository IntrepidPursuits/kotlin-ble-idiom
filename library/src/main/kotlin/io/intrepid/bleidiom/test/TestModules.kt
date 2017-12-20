package io.intrepid.bleidiom.test

import android.app.Application
import android.content.Context
import com.github.salomonbrys.kodein.*
import com.github.salomonbrys.kodein.conf.ConfigurableKodein
import com.nhaarman.mockito_kotlin.mock
import com.polidea.rxandroidble.RxBleClient
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.mockrxandroidble.RxBleClientMock
import com.polidea.rxandroidble.mockrxandroidble.RxBleDeviceMock
import io.intrepid.bleidiom.log.Logger
import io.intrepid.bleidiom.module.initBleIdiomModules

/**
 * Modules for BLE related D.I.
 */
class BleTestModules {
    companion object {
        val kodein get() = testKodein

        var testDevices: Iterable<RxBleDeviceMock> = listOf()

        private val testKodein: ConfigurableKodein = ConfigurableKodein(true)

        fun load(factory: Companion.() -> Unit) {
            testKodein.addImport(TestAppModule)
            initBleIdiomModules(testKodein) {
                addImport(BleModuleOverrides, allowOverride = true)
            }
            this.factory()
        }

        fun unload() {
            testKodein.clear()
        }

        private val TestAppModule = Kodein.Module {
            bind<Application>() with singleton { mock<Application>() }
            bind<Context>() with singleton { instance<Application>() }

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
