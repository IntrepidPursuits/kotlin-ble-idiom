/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 */
package io.intrepid.bleidiom

import com.polidea.rxandroidble.RxBleConnection.RxBleConnectionState
import com.polidea.rxandroidble.RxBleConnection.RxBleConnectionState.*
import com.polidea.rxandroidble.RxBleDevice
import com.polidea.rxandroidble.utils.ConnectionSharingAdapter
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.primaryConstructor

/**
 * The subclass of every BLE Service that can be configured and registered by the [BleIdiomDSL].
 *
 * [Svc] is the subclass itself.
 * @sample io.intrepid.bleidiom.app.BatterijService
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

    override final fun configure(dsl: BleServiceDSL<Svc>.() -> Unit) = Registration.registerDSL(this::class) {
        BleServiceDSLImpl(asSvc()).apply {
            dsl()
        }
    }

    internal val dsl: BleServiceDSLImpl<*> by lazy {
        Registration.getServiceDSL(this)!!
    }

    var autoConnect = false

    var writeObserverFactory: (KMutableProperty1<out BleService<*>, out Any>) -> Observer<*> =
            { _ -> PublishSubject.create<Any>() }

    internal lateinit var device: RxBleDevice

    internal val sharedConnection by lazy {
        device.establishConnection(autoConnect)
                .compose(ConnectionSharingAdapter())
                .toRx2Observable()
    }

    private val connectionCounter = AtomicInteger(0)
    private val subscriptionsContainer = CompositeDisposable()

    /**
     * Retains a connection to the device until [releaseRetainedConnection] is called.
     * Read and write operations are one-shot operations, ie a connection is created, the read or
     * write operation is executed and the connection is closed. If it is desired to keep the
     * connection open, then call this method. Be sure to call [releaseRetainedConnection] when
     * done.
     *
     * Retaining a connection can be a performance improvement because subsequent read and write
     * operations are faster to obtain a usable connection.
     */
    fun retainConnection() {
        if (connectionCounter.getAndIncrement() == 0) {
            subscriptionsContainer.add(sharedConnection.subscribe())
        }
    }

    /**
     * Releases a retained connection to the device. See also [retainConnection]
     */
    fun releaseRetainedConnection() {
        if (connectionCounter.decrementAndGet() == 0) {
            subscriptionsContainer.clear()
        }
    }

    /**
     * Monitors the connection-state changes that may occur over the lifetime of this service-device.
     */
    fun observeConnectionState() =
            device.observeConnectionStateChanges()
                    .toRx2Observable()
                    .map { state -> ConnectionState[state] }!!

    /**
     * Requests to read a BLE characteristic's value from the remote device. Upon success, the returned
     * Observable emits the read value.
     *
     * @param property A readable property of this [BleService]
     * @return [Observable] that will emit the read value.
     */
    operator
    fun <Val : Any> get(property: KProperty1<Svc, BleCharValue<Val>>) = property.get(asSvc()).read()

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
            val svc = asSvc()
            @Suppress("UNCHECKED_CAST")
            valueStream
                    .flatMap { value -> get(svc).write(value) }
                    .compose({ upstream ->
                        var disposable: Disposable? = null
                        upstream
                                .doOnSubscribe {
                                    disposable = if (subscriptionsContainer.add(it)) it else null
                                }
                                .doOnTerminate {
                                    disposable?.let { subscriptionsContainer.delete(it) }
                                    disposable = null
                                }
                    })
                    .subscribe(writeObserverFactory(property) as Observer<in Val>)
        }
    }

    /**
     * Returns a [Observable] for the given property. it will emit a value when the corresponding
     * remote BLE Characteristic is notified.
     */
    fun <Val : Any> observe(property: KProperty1<Svc, BleCharValue<Val>>) = property.get(asSvc()).observe()

    @Suppress("UNCHECKED_CAST")
    private fun asSvc() = this as Svc
}

/**
 * Each [BleService]'s property that represents a BLE Service's characteristic and needs to be configured by
 * the [BleIdiomDSL] must be of this type.
 */
class BleCharValue<Val : Any>() {
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

    internal lateinit var readAction: () -> Observable<Val>
    internal lateinit var writeAction: (Val) -> Observable<Val>
    internal lateinit var observeAction: () -> Observable<Val>

    /**
     * Creates a new BLE characteristic property-value.
     * @param value The new value for this property.
     */
    constructor(value: Val) : this() {
        this.currentValue = value
    }

    /**
     * Reads a value from the remote BLE characteristic and returns an Observable.
     * @return An [Observable] that will emit the read value upon success.
     */
    fun read() = synchronized(this) { readAction() }

    /**
     * Write this value to the remote BLE characteristic and returns an Observable.
     * @param value The value to write to the BLE characteristic.
     * @return An [Observable] that will complete upon success.
     * Depending upon the remote BLE characteristic, it may emit a value as well.
     */
    fun write(value: Val) = synchronized(this) { writeAction(value) }

    /**
     * Returns an observable that can be watched when the remote BLE characteristic is notified
     * @return [Observable]
     */
    fun observe() = synchronized(this) { observeAction() }
}

/**
 * @return a [BleCharHandlerDSL] delegate that will handle the communication with a remote BLE characteristic
 * for values of type [Val].
 */
inline
fun <reified Val : Any> bleCharHandler(): BleCharHandlerDSL<Val> = bleCharHandler { forClass = Val::class }

/**
 * @param body The code-block configuring the [ByteArray] transformations to and from value of type [Val].
 * @return a [BleCharHandlerDSL] delegate that will handle the communication with a remote BLE characteristic
 * for value of type [Val], where the [ByteArray] transformations are configured by the given code-block.
 */
fun <Val : Any> bleCharHandler(body: BleCharHandlerDSL<Val>.() -> Unit): BleCharHandlerDSL<Val> = BleCharValueDelegate(body)

sealed class ConnectionState {
    companion object {
        internal operator fun get(bleState: RxBleConnectionState) =
                when (bleState) {
                    CONNECTING -> ConnectionState.Connecting
                    CONNECTED -> ConnectionState.Connected
                    DISCONNECTED -> ConnectionState.Disconnected
                    DISCONNECTING -> ConnectionState.Disconnecting
                }
    }

    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Disconnecting : ConnectionState()
    object Disconnected : ConnectionState()

    override fun toString() = this::class.simpleName!!
}
