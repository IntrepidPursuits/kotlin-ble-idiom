package io.intrepid.bleidiom

import arrow.data.Try
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.with
import com.polidea.rxandroidble.RxBleConnection
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.exceptions.BleDisconnectedException
import com.polidea.rxandroidble.exceptions.BleException
import io.intrepid.bleidiom.log.LogLevel
import io.intrepid.bleidiom.log.Logger
import io.intrepid.bleidiom.module.LibKodein
import io.intrepid.bleidiom.module.TAG_EXECUTOR_SCHEDULER
import io.intrepid.bleidiom.util.toRx2
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import java.util.*
import kotlin.collections.set

typealias BleConnection = Try<RxBleConnection>

/**
 * For each known instance of [RxBleDevice], there is one instance of this class.
 * Multiple instances of [BleService] can share one [BleIdiomDevice].
 */
class BleIdiomDevice internal constructor(internal val device: RxBleDevice) {
    companion object {
        private val logger: Logger = LibKodein.with(BleIdiomDevice::class).instance()
        private val sharedConnectionScheduler: Scheduler
            get() = LibKodein.with(this).instance(TAG_EXECUTOR_SCHEDULER)
    }

    val macAddress = device.macAddress?.toLowerCase(Locale.US) ?: ""
    val name = device.name ?: ""

    private val printName = "$macAddress($name)"

    @Volatile
    internal var rssi: Int = 0

    // TODO Negotiate actual size.
    internal val mtuSize = MIN_MTU_SIZE

    @Volatile
    internal var autoConnect = false

    internal var scanRecord: ByteArray? = null
        get() = synchronized(this) { field }
        set(value) = synchronized(this) {
            if ((value == null) || !Arrays.equals(field, value)) {
                parsedScanRecord = null
            }
            field = value
        }

    internal var parsedScanRecord: ScanRecordInfo? = null
        get() = synchronized(this) {
            scanRecord?.let {
                val result = if (field == null) ScanRecordInfo.parseFromBytes(it) else field
                field = result?.parseError?.let { throw it } ?: result
                field
            }
        }

    internal val sharedConnection by lazy {
        Observable.defer {
            createConnection().doOnNext { retryCount = 0 }
        }.subscribeOn(sharedConnectionScheduler)
    }

    @Volatile
    internal var retryStrategy: (String, Boolean, Int) -> Single<Boolean> = { _, _, _ -> Single.just(false) }

    @Volatile
    private var retryCount = 0

    // No it can't be private since the custom 'get' or 'set' won't be called if it is... weird but true...
    @Suppress("MemberVisibilityCanBePrivate")
    internal var killedConnectionPub: ObservableEmitter<Unit>? = null
        get() = synchronized(killedConnectionObs) { field }
        set(value) = synchronized(killedConnectionObs) { field = value }

    private val killedConnectionObs = Observable.defer {
        Observable.create<Unit> {
            killedConnectionPub = it.apply { setCancellable { killedConnectionPub = null } }
        }.startWith(Unit)
    }

    private val connectionAdapter = ConnectionSharingAdapter<RxBleConnection>()

    /**
     * Use this property to attach any piece of data to this particular BleIdiomDevice.
     */
    private val userState by lazy { mutableMapOf<String, Any?>() }

    internal fun killCurrentConnection() {
        synchronized(killedConnectionObs) {
            killedConnectionPub
                    ?.onError(BleForcedDisconnectedException("Disconnected from $macAddress by force"))
        }
    }

    /**
     * Monitors the connection-state changes that may occur over the lifetime of this service-device.
     */
    internal fun observeConnectionState() =
            device.observeConnectionStateChanges()
                    .toRx2()
                    .map { state -> ConnectionState[state] }!!

    internal fun <T : Any> modifyUserState(name: String, block: T?.() -> T?) = synchronized(this) {
        @Suppress("UNCHECKED_CAST")
        val newValue = (userState[name] as T?).block()
        userState[name] = newValue
        newValue
    }

    override fun toString() = printName

    private fun createConnection(): Observable<BleConnection> =
            establishConnection()
                    .flatMap {
                        when (it) {
                            is Try.Success -> Observable.just(it)
                            is Try.Failure -> handleError(it)
                        }
                    }

    private fun establishConnection() =
            establishKillableConnection()
                    .compose(connectionAdapter)
                    .map { Try.pure(it) }
                    .onErrorResumeNext { error: Throwable ->
                        Observable.never<BleConnection>().startWith(Try.raise(error))
                    }

    private fun establishKillableConnection() =
            Observable.combineLatest(
                    device.establishConnection(autoConnect).toRx2(),
                    killedConnectionObs,
                    BiFunction<RxBleConnection, Unit, RxBleConnection> { conn, _ -> conn }
            )

    private fun handleError(brokenConnection: Try.Failure<RxBleConnection>) =
            when (brokenConnection.exception) {
                is BleDisconnectedException -> {
                    logger.log(LogLevel.WARN, "Disconnection detected for $this")
                    val retryObs = retryStrategy(macAddress, autoConnect, ++retryCount)
                            .observeOn(sharedConnectionScheduler)

                    retryObs.flatMapObservable { retry ->
                        if (retry) {
                            logger.log(LogLevel.DEBUG, "Trying to reconnect($retryCount) to $this")
                            createConnection()
                        } else {
                            logger.log(LogLevel.DEBUG, "Not trying to reconnect($retryCount) to $this")
                            Observable.just(brokenConnection)
                        }
                    }
                }
                else -> Observable.just(brokenConnection)
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

class BleForcedDisconnectedException(message: String) : BleException(message)

private class ConnectionSharingAdapter<T> : ObservableTransformer<T, T> {
    private val sync = Any()

    internal var connectionObservable: Observable<T>? = null
        get() = synchronized(sync) { field }
        set(value) = synchronized(sync) { field = value }

    override fun apply(source: Observable<T>): ObservableSource<T> {
        synchronized(sync) {
            connectionObservable?.let {
                return it
            }

            val connectionObs = source
                    .doOnDispose { connectionObservable = null }
                    .replay(1)
                    .refCount()
            connectionObservable = connectionObs
            return connectionObs
        }
    }
}
