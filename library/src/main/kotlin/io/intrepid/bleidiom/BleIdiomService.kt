/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 */
package io.intrepid.bleidiom

import android.bluetooth.BluetoothGattService
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.with
import io.intrepid.bleidiom.log.LogLevel
import io.intrepid.bleidiom.log.Logger
import io.intrepid.bleidiom.module.LibKodein
import io.intrepid.bleidiom.util.toRx2
import io.reactivex.*
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.primaryConstructor

/**
 * The subclass of every BLE Service that can be configured and registered by the [BleIdiomDSL].
 *
 * @sample io.intrepid.bleidiom.services.registerGenericAccess
 * @sample io.intrepid.bleidiom.services.GenericAccess
 *
 * [Svc] is the subclass itself.
 */
open class BleService<Svc : BleService<Svc>> : BleConfigureDSL<Svc> {
    companion object {
        /**
         * The 'invoke' operator allows the start of the DSL that configures this
         * BleService. See also [BleConfigureDSL.configure]
         */
        inline operator
        fun <reified T : BleService<T>> invoke(dsl: T.() -> Unit) = createPrototype<T>().dsl()

        /**
         * Clears all configured BLE Service definitions.
         */
        fun clearConfigurations() {
            Registration.clearAll()
        }

        inline
        fun <reified T : BleService<T>> createPrototype() = T::class.primaryConstructor!!.call()
    }

    final override fun configure(dsl: BleServiceDSL<Svc>.() -> Unit) = Registration.registerDSL(this::class) {
        BleServiceDSLImpl(asSvc()).apply {
            dsl()
        }
    }

    internal val dsl: BleServiceDSLImpl<*> by lazy {
        Registration.getServiceDSL(this)!!
    }

    /**
     * MAC-address of the device of this service (lowercase)
     */
    val macAddress get() = device.macAddress

    /**
     * Name of the device of this service or an empty string if it has no name.
     */
    val name get() = device.name

    /**
     * RSSI obtained during scanning of this service. It is 0 if the value is not known.
     */
    var rssi
        get() = device.rssi
        internal set(value) {
            device.rssi = value
        }

    /**
     * Max size of raw data that can be sent over BLE to the device of this service.
     * Usually this value is 20, but could've been negotiated to a higher value.
     */
    val mtuSize get() = device.mtuSize

    /**
     * The connection retry strategy.
     * When the device disconnects, a re-connection is attempted a few times.
     * Whether a re-connection is attemted, how soon and how often is determined by the value
     * set to this property.
     *
     * The property's value is a lambda that take three parameters, String, Boolean and Int.
     * * The String is the MAC-address of the service's device
     * * The Boolean is the value of the device's [BleIdiomDevice.autoConnect]
     * * The Int is the number of retries attemted since the last disconnect.
     *
     * The lambda must return a `Single<Boolean>` that emits a boolean value.
     * * When the emitted boolean value that is received is true, a re-connect is attempted.
     * * When the emitted boolean value that is received is false, the disconnect-error is
     *   propagated through the listening observers.
     * * Note that delaying the emission of a `true` value through this `Single<Boolean>` allows
     *   for a delay in a re-connect attempt.
     */
    var connectionRetryStrategy: (String, Boolean, Int) -> Single<Boolean>
        get() = throw IllegalAccessError("connectionRetryStrategy can only be set")
        set(value) {
            device.retryStrategy = value
        }

    var writeObserverFactory: (KMutableProperty1<out BleService<*>, out Any>) -> Observer<out Any> =
            { _ -> EmptyObserver }

    internal var scanRecord: ByteArray
        get() = throw IllegalAccessError("scanRecord can only be set")
        set(value) {
            device.scanRecord = value
        }

    internal lateinit var device: BleIdiomDevice

    internal val sharedConnection get() = device.sharedConnection

    internal val killedConnectionObs get() = device.killedConnectionObs

    private val subscriptionsContainer = CompositeDisposable()

    /**
     * Returns an object containing data formed from the device's Scan-Record.
     * @return The parsed/transformed scan-record information or null if it was not set.
     */
    fun <S : Any> getParsedScanRecord(): S? = mapScanRecord(device.parsedScanRecord)

    /**
     * Implement this function if parsing of scan-record information (bytes) is important
     * and return it as a instance of type [S]
     * @param scanRecord The scan-record information or null if not found.
     * @return The parsed scan-record information.
     */
    protected open fun <S : Any> mapScanRecord(scanRecord: ScanRecordInfo?): S? = null

    /**
     * Modifies a piece of user data that will be attached to the Ble Device that backs this Service.
     * @param name Name of the device-user-data.
     * @param block A block whose receiver is the current device-user-data and returns the new/updated
     *              device-user-data.
     */
    fun <T : Any> modifyDeviceUserState(name: String, block: T?.() -> T?) =
            device.modifyUserState(name, block)

    /**
     * Retains a connection to the device until [Closeable.close] is called on the return value.
     * Read and write operations are one-shot operations, ie a connection is created, the read or
     * write operation is executed and the connection is closed. If it is desired to keep the
     * connection open, then call this method. Be sure to call [Closeable.close] when
     * done.
     *
     * Retaining a connection can be a performance improvement because subsequent read and write
     * operations are faster to obtain a usable connection.
     *
     * @param onError A callback that will be called when an error happens
     * @return The [Closeable] representing the retained connection.
     */
    fun retainConnection(onError: (Throwable) -> Unit = {}) =
            object : Closeable {
                private val disposable = sharedConnection.subscribe { it.fold(onError, {}) }

                override fun close() {
                    disposable.run { if (!isDisposed) dispose() }
                }
            }

    /**
     * Forces a disconnect not only from this service but from the remote BLE device itself.
     * Note that this will cause any other Observers that are using connections to the remote
     * BLE device to terminate (the observers' 'onComplete()' will be called).
     */
    fun killCurrentConnection() = device.killCurrentConnection()

    /**
     * Monitors the connection-state changes that may occur over the lifetime of this service-device.
     */
    fun observeConnectionState() = device.observeConnectionState()

    fun discoverPrimaryServices(timeout: Long = 20_000) =
            sharedConnection.take(1).flatMapTry { it.discoverServices(timeout, TimeUnit.MILLISECONDS).toRx2() }
                    .flatMapIterable { deviceServices -> deviceServices.bluetoothGattServices }
                    .filter { gatt ->
                        gatt.type == BluetoothGattService.SERVICE_TYPE_PRIMARY &&
                                Registration.hasService(gatt.uuid)
                    }
                    .map { gatt ->
                        ServiceDeviceFactory.obtainClientDevice<BleService<*>>(gatt.uuid, device)
                    }
                    ?: Observable.empty()

    /**
     * Requests to read a BLE characteristic's value from the remote device. Upon success, the returned
     * Observable emits the read value.
     *
     * @param property A readable property of this [BleService]
     * @return [Single] that will emit the read value.
     */
    operator
    fun <Val : Any> get(property: KProperty1<Svc, BleCharValue<Val>>) = property.get(asSvc())()

    /**
     * Writes/sends a value of type [Val] to a writable BLE characteristic.
     *
     * @param property A writable property of this [BleService].
     * @param value The value to be written to the BLE characteristic.
     */
    operator
    fun <Val : Any> set(property: KMutableProperty1<Svc, BleCharValue<Val>>, value: Val) {
        with(property) {
            set(property, Observable.just(value))
        }
    }

    /**
     * Writes/sends a value of type [Val] to a writable BLE characteristic each time
     * the given value-stream emits a new value.
     *
     * @param property A writable property of this [BleService].
     * @param valueStream The stream of one value to be written to the BLE characteristic.
     */
    operator
    fun <Val : Any> set(property: KMutableProperty1<Svc, BleCharValue<Val>>, valueStream: Single<Val>) {
        with(property) {
            set(property, valueStream.toObservable())
        }
    }

    /**
     * Writes/sends a value of type [Val] to a writable BLE characteristic each time
     * the given value-stream emits a new value.
     *
     * @param property A writable property of this [BleService].
     * @param valueStream The stream of values to be written to the BLE characteristic.
     */
    operator
    fun <Val : Any> set(property: KMutableProperty1<Svc, BleCharValue<Val>>, valueStream: Flowable<Val>) {
        with(property) {
            set(property, valueStream.toObservable())
        }
    }

    /**
     * Writes/sends a value of type [Val] to a writable BLE characteristic each time
     * the given value-stream emits a new value.
     *
     * @param property A writable property of this [BleService].
     * @param valueStream The stream of values to be written to the BLE characteristic.
     */
    operator
    fun <Val : Any> set(property: KMutableProperty1<Svc, BleCharValue<Val>>, valueStream: Observable<Val>) {
        with(property) {
            @Suppress("UNCHECKED_CAST")
            get(asSvc())(valueStream)
                    .compose(AutoUnsubscribeTransformer())
                    .subscribe(writeObserverFactory(this) as Observer<Val>)
        }
    }

    /**
     * Returns a [Observable] for the given property. it will emit a value when the corresponding
     * remote BLE Characteristic is notified.
     */
    fun <Val : Any> observeNotifications(property: KProperty1<Svc, BleCharValue<Val>>) = property.get(asSvc()).observe(false)

    /**
     * Returns a [Observable] for the given property. it will emit a value when the corresponding
     * remote BLE Characteristic is indicated.
     */
    fun <Val : Any> observeIndications(property: KProperty1<Svc, BleCharValue<Val>>) = property.get(asSvc()).observe(true)

    override fun toString(): String {
        return "${this::class.simpleName}:${dsl.uuid}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun asSvc() = this as Svc
}

/**
 * Each [BleService]'s property that represents a BLE Service's characteristic and needs to be configured by
 * the [BleIdiomDSL] must be of this type.
 */
class BleCharValue<Val : Any> {
    val value: Val?
        get() = synchronized(this) {
            return inFlightValue ?: currentValue
        }

    internal var inFlightValue: Val? = null
        get() = synchronized(this) {
            return field
        }
        set(value) = synchronized(this) {
            field = value
        }

    internal var currentValue: Val? = null
        get() = synchronized(this) {
            return field
        }
        set(value) = synchronized(this) {
            inFlightValue = null
            field = value
        }

    internal lateinit var readAction: () -> Single<Val>
    internal lateinit var writeAction: (Val) -> Single<Val>
    internal lateinit var observeAction: (Boolean) -> Observable<Val>

    /**
     * Reads a value from the remote BLE characteristic and returns an Observable.
     * @return An [Single] that will emit the read value upon success.
     */
    operator fun invoke() = synchronized(this) { readAction() }

    /**
     * Write this value to the remote BLE characteristic and returns an Observable.
     * @param value The value to write to the BLE characteristic.
     * @return An [Single] that will complete upon success.
     */
    operator fun invoke(value: Val) = synchronized(this) { writeAction(value) }

    /**
     * Emits a value to the remote BLE characteristic and returns an Observable.
     * @param value The value to write to the BLE characteristic.
     * @return An [Single] that will complete upon success.
     */
    operator fun invoke(value: Single<Val>) = value.flatMap { invoke(it) }!!

    /**
     * Emits values to the remote BLE characteristic and returns an Observable.
     * @param valueStream The value to write to the BLE characteristic.
     * @return An [Observable] that will complete upon successful writing of the values.
     */
    operator fun invoke(valueStream: Flowable<Val>) = invoke(valueStream.toObservable())

    /**
     * Emits values to the remote BLE characteristic and returns an Observable.
     * @param valueStream The value to write to the BLE characteristic.
     * @return An [Observable] that will complete upon successful writing of the values.
     */
    operator fun invoke(valueStream: Observable<Val>) = valueStream.concatMap { invoke(it).toObservable() }!!

    /**
     * Returns an observable that can be watched when the remote BLE characteristic is notified
     * @param isIndication True if observing an indication, false if observing a notification.
     * @return [Observable]
     */
    fun observe(isIndication: Boolean = false) = synchronized(this) { observeAction(isIndication) }
}

/**
 * @return a [BleCharHandlerDSL] delegate that will handle the communication with a remote BLE characteristic
 * for values of type [Val].
 */
inline
fun <reified Val : Any> bleCharHandler() = bleCharHandler<Val> { forClass = Val::class }

/**
 * @param body Handler for the [BleCharHandlerDSL.toBatchInfo]
 *
 * @return a [BleCharHandlerDSL] delegate that will handle the communication with a remote BLE characteristic
 * for value of type [Val] and allows the caller to handle the [BleCharHandlerDSL.toBatchInfo].
 */
inline
fun <reified Val : Any> bleChunkedCharHandler(crossinline body: (Val, ByteArray) -> Pair<Int, ByteArray>) =
        bleCharHandler<Val> {
            forClass = Val::class
            toBatchInfo = { value, bytes -> body(value, bytes) }
        }

/**
 * @param body The code-block configuring the [ByteArray] transformations to and from value of type [Val].
 * @return a [BleCharHandlerDSL] delegate that will handle the communication with a remote BLE characteristic
 * for value of type [Val], where the [ByteArray] transformations are configured by the given code-block.
 */
fun <Val : Any> bleCharHandler(body: BleCharHandlerDSL<Val>.() -> Unit): BleCharHandlerDSL<Val> =
        BleCharValueDelegate(body)

private class AutoUnsubscribeTransformer<T> : ObservableTransformer<T, T> {
    lateinit var disposable: Disposable

    override fun apply(upstream: Observable<T>) = upstream
            .doOnSubscribe { disposable = it }
            .doFinally { if (!disposable.isDisposed) disposable.dispose() }!!
}

private object EmptyObserver : Observer<Any> {
    private val logger = LibKodein.with(EmptyObserver::class).instance<Logger>()

    override fun onSubscribe(d: Disposable) {
    }

    override fun onComplete() {
    }

    override fun onNext(t: Any) {
    }

    override fun onError(e: Throwable) {
        logger.log(LogLevel.WARN, e, "Error received.")
    }
}
