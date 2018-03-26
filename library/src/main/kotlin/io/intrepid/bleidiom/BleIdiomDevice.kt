package io.intrepid.bleidiom

import arrow.data.Try
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.with
import com.jakewharton.rx.ReplayingShare
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import io.intrepid.bleidiom.log.LogLevel
import io.intrepid.bleidiom.log.Logger
import io.intrepid.bleidiom.module.LibKodein
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.PublishSubject
import java.util.Arrays
import java.util.Locale
import kotlin.collections.set

typealias BleConnection = Try<RxBleConnection>

/**
 * For each known instance of [RxBleDevice], there is one instance of this class.
 * Multiple instances of [BleService] can share one [BleIdiomDevice].
 */
class BleIdiomDevice internal constructor(internal val device: RxBleDevice) {
    companion object {
        private val logger: Logger = LibKodein.with(BleIdiomDevice::class).instance()
    }

    val macAddress = device.macAddress?.toUpperCase(Locale.US) ?: ""
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
            if (!Arrays.equals(field, value)) {
                isParsedScanRecordDirty = true
            }
            field = value
        }

    internal fun <S : Any> getParsedScanRecord(parser: (ScanRecordInfo) -> S?) = synchronized(this) {
        val parse = isParsedScanRecordDirty
        isParsedScanRecordDirty = false

        if (parse) {
            _parsedScanRecord = scanRecord?.let { parser(ScanRecordInfo.parseFromBytes(it)) }
        }

        @Suppress("UNCHECKED_CAST")
        _parsedScanRecord as S?
    }

    @Volatile
    private var isParsedScanRecordDirty = true

    private var _parsedScanRecord: Any? = null

    internal val sharedConnection by lazy { createConnection().doOnNext { retryCount = 0 } }

    @Volatile
    internal var retryStrategy: (String, Boolean, Int) -> Single<Boolean> = { _, _, _ -> Single.just(false) }

    @Volatile
    private var retryCount = 0

    private val killedConnectionPub = PublishSubject.create<Unit>()

    internal val killedConnectionObs: Observable<Unit> = killedConnectionPub

    private var killableConnection: Observable<RxBleConnection>? = null

    /**
     * Use this property to attach any piece of data to this particular BleIdiomDevice.
     */
    private val userState by lazy { hashMapOf<String, Any?>() }

    internal fun killCurrentConnection() {
        killedConnectionPub.onNext(Unit)
    }

    /**
     * Monitors the connection-state changes that may occur over the lifetime of this service-device.
     */
    internal fun observeConnectionState() =
            device.observeConnectionStateChanges()
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

    private fun establishConnection(): Observable<Try<RxBleConnection>> {
        return establishKillableConnection()!!
                .doOnTerminate { synchronized(this@BleIdiomDevice) { killableConnection = null } }
                .map { Try.pure(it) }
                .onErrorResumeNext { error: Throwable ->
                    Observable.never<BleConnection>().startWith(Try.raise(error))
                }
    }

    private fun establishKillableConnection() = synchronized(this) {
        if (killableConnection == null) {
            killableConnection = device.establishConnection(autoConnect)
                    .takeUntil(killedConnectionObs)
                    .compose(ReplayingShare.instance())
        }
        killableConnection
    }

    private fun handleError(brokenConnection: Try.Failure<RxBleConnection>) =
            when (brokenConnection.exception) {
                is BleDisconnectedException -> {
                    logger.log(LogLevel.WARN, "Disconnection detected for $this")
                    val retryObs = retryStrategy(macAddress, autoConnect, ++retryCount)

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
