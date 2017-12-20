package io.intrepid.bleidiom

import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.withKClassOf
import com.polidea.rxandroidble.RxBleConnection
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.exceptions.BleDisconnectedException
import com.polidea.rxandroidble.utils.ConnectionSharingAdapter
import io.intrepid.bleidiom.log.LogLevel
import io.intrepid.bleidiom.log.Logger
import io.intrepid.bleidiom.module.LibKodein
import io.intrepid.bleidiom.util.toRx2
import io.reactivex.Observable
import java.util.concurrent.TimeUnit

/**
 * For each known instance of [RxBleDevice], there is one instance of this class.
 * Multiple instances of [BleService] can share one [BleIdiomDevice].
 */
class BleIdiomDevice internal constructor(val device: RxBleDevice) {
    private val logger: Logger = LibKodein.withKClassOf(this).instance()

    internal val macAddress = device.macAddress

    @Volatile
    internal var autoConnect = false

    internal val sharedConnection by lazy {
        this.device.establishConnection(autoConnect)
                .compose(ConnectionSharingAdapter())
                .toRx2()
                .retryWhen { errorObs ->
                    errorObs.flatMap { error ->
                        when (error) {
                            is BleDisconnectedException -> {
                                logger.log(LogLevel.WARN, "Disconnection detected. Trying to reconnect")
                                Observable.never<Any>().startWith(0).delay(500, TimeUnit.MILLISECONDS)
                            }
                            else -> Observable.error(error)
                        }
                    }
                }
    }

    /**
     * Use this property to attach any piece of data to this particular BleIdiomDevice.
     */
    private var userState: Any? = null

    /**
     * Monitors the connection-state changes that may occur over the lifetime of this service-device.
     */
    internal fun observeConnectionState() =
            device.observeConnectionStateChanges()
                    .toRx2()
                    .map { state -> ConnectionState[state] }!!

    internal fun <T : Any> modifyUserState(block: T?.() -> T?) {
        synchronized(this) {
            @Suppress("UNCHECKED_CAST")
            userState = (userState as T?).block()
        }
    }
}

sealed class ConnectionState {
    companion object {
        internal operator fun get(bleState: RxBleConnection.RxBleConnectionState) =
                when (bleState) {
                    RxBleConnection.RxBleConnectionState.CONNECTING -> ConnectionState.Connecting
                    RxBleConnection.RxBleConnectionState.CONNECTED -> ConnectionState.Connected
                    RxBleConnection.RxBleConnectionState.DISCONNECTED -> ConnectionState.Disconnected
                    RxBleConnection.RxBleConnectionState.DISCONNECTING -> ConnectionState.Disconnecting
                }
    }

    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Disconnecting : ConnectionState()
    object Disconnected : ConnectionState()

    override fun toString() = this::class.simpleName!!
}