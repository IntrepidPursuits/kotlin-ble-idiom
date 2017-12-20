package io.intrepid.bleidiom.log

import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.with
import com.polidea.rxandroidble.internal.RxBleLog
import io.intrepid.bleidiom.module.LibKodein

/**
 * Implements and provides the logger for the RxAndroidBle library.
 */
internal class RxAndroidBleLogger: RxBleLog.Logger {
    companion object {
        internal fun init(minLogLevel: Int) {
            RxBleLog.setLogLevel(minLogLevel)
            RxBleLog.setLogger(RxAndroidBleLogger())
        }
    }

    override fun log(level: Int, tag: String?, msg: String?) {
        if (msg != null) {
            val logger: Logger = LibKodein.with(tag ?: "RxBleLog").instance()
            logger.log(level.logLevel(), msg)
        }
    }
}