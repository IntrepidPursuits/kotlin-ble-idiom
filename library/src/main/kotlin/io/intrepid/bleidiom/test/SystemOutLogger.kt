package io.intrepid.bleidiom.test

import android.util.Log
import io.intrepid.bleidiom.log.LogLevel
import io.intrepid.bleidiom.log.LoggerImpl

private const val DEFAULT_TAG = "LOGGER"

/**
 * Logger for running tests: Prints logs to System.out or System.err
 */
class SystemOutLogger : LoggerImpl(LogLevel.VERBOSE) {
    var tag: String = DEFAULT_TAG

    override fun setExplicitTag(tag: String?) {
        this.tag = tag ?: DEFAULT_TAG
    }

    override fun writeLog(level: LogLevel, format: String?, vararg args: Any) {
        if (format != null) {
            printer(level).printf("$tag: $format\n", args)
        }
    }

    override fun writeException(level: LogLevel, throwable: Throwable, format: String?, vararg args: Any) {
        if (format != null) {
            printer(level).printf("$tag: $format\n${Log.getStackTraceString(throwable)}", args)
        } else {
            printer(level).printf("$tag: ${Log.getStackTraceString(throwable)}")
        }
    }

    private fun printer(level: LogLevel) = if (level >= LogLevel.ERROR) System.err else System.out
}