package io.intrepid.bleidiom.module

import com.github.salomonbrys.kodein.*
import io.intrepid.bleidiom.log.LogLevel
import io.intrepid.bleidiom.log.Logger
import io.intrepid.bleidiom.log.LoggerImpl
import kotlin.reflect.KClass

/**
 * Provides a default [Logger] implementation... this module's implementation does not log anything.
 * Usage:
 * ```
 * class MyClass {
 *     val logger: Logger = LibKodein.instance()
 * }
 * ```
 */
@Suppress("PropertyName")
internal val BleLoggerImplModule = Kodein.Module {
    bind<Logger>() with singleton { LoggerImpl(LogLevel.ASSERT) }
}

/**
 * Provides [Logger]s for KClass/Class/String -> log-tag support. Usage:
 * ```
 * class MyClass {
 *     val loggerK: Logger = LibKodein.withKClassOf(this).instance()
 *     val loggerJ: Logger = LibKodein.withClassOf(this).instance()
 *     val loggerS: Logger = LibKodein.with("tag").instance()
 * }
 * ```
 */
@Suppress("PropertyName")
internal val LoggerModuleExtended = Kodein.Module {
    // Use multitons for classes, since there will be only a limited number.
    bind<Logger>() with multiton { key: Class<*> -> instance<Logger>().tag(key.simpleName) }
    bind<Logger>() with multiton { key: KClass<*> -> instance<Logger>().tag(key.simpleName!!) }
    // Use factory for String, since there can be a possible unlimited amount of String values.
    bind<Logger>() with factory { key: String -> instance<Logger>().tag(key) }
}
