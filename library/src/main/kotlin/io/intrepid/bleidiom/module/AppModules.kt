package io.intrepid.bleidiom.module

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.conf.ConfigurableKodein
import com.github.salomonbrys.kodein.with
import io.intrepid.bleidiom.BuildConfig

/**
 * Initializes the Kodein provider for this library.
 * After this method has been called, the [LibKodein] can be used for dependency injection.
 *
 * @param appKodein The kodein from the app that is to be (re-)configured.
 * @param overridesConfig A block of code overriding the [appKodein] bindings and imports that are
 *                        provided to this BleIdiom library
 */
fun initBleIdiomModules(appKodein: ConfigurableKodein, overridesConfig: ConfigurableKodein.() -> Unit = {}) {
    val buildInfoModule = Kodein.Module {
        constant("DEBUG") with BuildConfig.DEBUG
        constant("BUILD_TYPE") with BuildConfig.BUILD_TYPE
    }

    // Configure the necessary dependency providers to be used internally for this library.
    val libraryModule = Kodein.Module {
        // Provides information about the App's build-information.
        import(buildInfoModule)
        // Provides for Loggers.
        import(BleLoggerImplModule)
        // Provides the internal extended logger implementation.
        import(LoggerModuleExtended)
    }

    // Add dependency providers that provide public instances to the app's kodein (and to
    // the kodein of this BleIdiom library itself).
    appKodein.addImport(BleModule)
    // Import provider on which public providers from BleModule rely.
    appKodein.addImport(RxAndroidBleModule)
    // Configure/add the appKodein's providers that override the default providers.
    appKodein.overridesConfig()

    // And merge it all together.
    LibKodein = Kodein {
        // Import the necessary dependencies that this library knows and needs.
        import(libraryModule)
        // Extend it with the dependencies of the app, allowing it to override existing providers,
        // and preventing  BleIdiom providers from leaking into the appKodein.
        extend(appKodein, allowOverride = true)
    }
}

@Suppress("PropertyName")
internal lateinit var LibKodein: Kodein
