package io.intrepid.bleidiom.test

import io.intrepid.bleidiom.log.LogLevel
import io.intrepid.bleidiom.log.LoggerImpl
import java.io.ByteArrayOutputStream
import java.io.PrintStream

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
            printer(level).printf("$tag: $format\n${getStackTraceString(throwable)}", args)
        } else {
            printer(level).printf("$tag: ${getStackTraceString(throwable)}")
        }
    }

    private fun printer(level: LogLevel) = if (level >= LogLevel.ERROR) System.err else System.out

    private fun getStackTraceString(t: Throwable): String {
        val stringBytes = ByteArrayOutputStream()
        val writer = PrintStream(stringBytes)
        t.printStackTrace(writer)
        writer.flush()
        return String(stringBytes.toByteArray())
    }
}