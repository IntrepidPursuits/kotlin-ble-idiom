package io.intrepid.bleidiom.module

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.conf.ConfigurableKodein
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.with
import com.polidea.rxandroidble2.exceptions.BleException
import io.intrepid.bleidiom.BuildConfig
import io.intrepid.bleidiom.log.LogLevel
import io.intrepid.bleidiom.log.Logger
import io.reactivex.exceptions.UndeliverableException

const val TAG_DEBUG = "DEBUG"
const val TAG_BUILD_TYPE = "BUILD_TYPE"
const val TAG_EXECUTOR_SCHEDULER = "EXECUTOR"

/**
 * Initializes the Kodein provider for this library.
 * After this method has been called, the [LibKodein] can be used for dependency injection.
 *
 * @param appKodein The kodein from the app that is to be (re-)configured.
 */
fun initBleIdiomModules(appKodein: ConfigurableKodein) {
    val buildInfoModule = Kodein.Module {
        constant(TAG_DEBUG) with BuildConfig.DEBUG
        constant(TAG_BUILD_TYPE) with BuildConfig.BUILD_TYPE
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

/**
 * Handles any [UndeliverableException] related to this BLE Idiom library.
 *
 * This handler should be called by the app's error-handler provided
 * to [io.reactivex.plugins.RxJavaPlugins.setErrorHandler].
 */
object UndeliverableBleExceptionHandler : (Throwable) -> Boolean {
    private val logger by lazy {
        LibKodein.with("UndeliverableBleExceptionHandler").instance<Logger>()
    }

    override operator fun invoke(t: Throwable) =
            if (t is UndeliverableException && isBleException(t.cause)) {
                logger.log(LogLevel.ERROR, t.cause!!, "Caught undeliverable BLE error")
                true
            } else {
                false
            }

    private fun isBleException(t: Throwable?): Boolean = when {
        t === null || t === t.cause -> false
        t is BleException -> true
        else -> isBleException(t.cause)
    }
}