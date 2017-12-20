package io.intrepid.bleidiom.log

import java.util.regex.Pattern

interface Logger {
    fun log(level: LogLevel, format: String?, vararg args: Any)

    fun log(level: LogLevel, throwable: Throwable, format: String?, vararg args: Any)

    fun log(level: LogLevel, throwable: Throwable)

    fun tag(tag: String): Logger
}

enum class LogLevel constructor(val level: Int) {
    VERBOSE(2),
    DEBUG(3),
    INFO(4),
    WARN(5),
    ERROR(6),
    ASSERT(7)
}

fun Number.logLevel(): LogLevel {
    val intValue = toInt()
    return LogLevel.values().firstOrNull { it.level == intValue }!!
}

open class LoggerImpl(private val minLevel: LogLevel) : Logger {
    companion object {
        private const val MAX_TAG_LENGTH = 23
        private const val CALL_STACK_INDEX = 3
        private val ANONYMOUS_CLASS = Pattern.compile("(\\$\\d+)+$")

        private fun getAutoTag(stackLevel: Int): String {
            val stackTrace = Throwable().stackTrace
            if (stackTrace.size <= CALL_STACK_INDEX + stackLevel) {
                throw IllegalStateException("Stacktrace didn't have enough elements. Are you using proguard?")
            }
            return createStackElementTag(stackTrace[CALL_STACK_INDEX + stackLevel])
        }

        private fun createStackElementTag(element: StackTraceElement): String {
            var tag = element.className
            val m = ANONYMOUS_CLASS.matcher(tag)
            if (m.find()) {
                tag = m.replaceAll("")
            }
            tag = tag.substring(tag.lastIndexOf('.') + 1)
            return if (tag.length > MAX_TAG_LENGTH) tag.substring(0, MAX_TAG_LENGTH - 1) + "\u2026" else tag
        }
    }

    init {
        RxAndroidBleLogger.init(minLevel.level)
    }

    override final fun log(level: LogLevel, format: String?, vararg args: Any) =
            tag(1).log(level, format, *args)

    override final fun log(level: LogLevel, throwable: Throwable, format: String?, vararg args: Any) =
            tag(1).log(level, throwable, format, *args)

    override final fun log(level: LogLevel, throwable: Throwable) =
            tag(1).log(level, throwable)

    override final fun tag(tag: String): Logger = Tag(tag)

    private fun tag(stackLevel: Int) = Tag(stackLevel)

    protected open fun setExplicitTag(tag: String?) {
        // Do nothing
    }

    protected open fun writeLog(level: LogLevel, format: String?, vararg args: Any) {
        // Do nothing
    }

    protected open fun writeException(level: LogLevel, throwable: Throwable, format: String?, vararg args: Any) {
        // Do nothing
    }

    inner private class Tag(private val tag: String) : Logger {
        internal constructor(stackLevel: Int) : this(getAutoTag(stackLevel))

        override fun log(level: LogLevel, format: String?, vararg args: Any) {
            if (level >= minLevel) {
                synchronized(this@LoggerImpl) {
                    setExplicitTag(tag)
                    writeLog(level, format, *args)
                    setExplicitTag(null)
                }
            }
        }

        override fun log(level: LogLevel, throwable: Throwable, format: String?, vararg args: Any) {
            if (level >= minLevel) {
                synchronized(this@LoggerImpl) {
                    setExplicitTag(tag)
                    writeException(level, throwable, format, *args)
                    setExplicitTag(null)
                }
            }
        }

        override fun log(level: LogLevel, throwable: Throwable) {
            if (level >= minLevel) {
                synchronized(this@LoggerImpl) {
                    setExplicitTag(tag)
                    writeException(level, throwable, null)
                    setExplicitTag(null)
                }
            }
        }

        override fun tag(tag: String) = Tag(tag)
    }
}