/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 */
package io.intrepid.bleidiom

import com.polidea.rxandroidble.RxBleConnection
import com.polidea.rxandroidble.RxBleDevice
import rx.Observable
import rx.Subscription
import rx.lang.kotlin.subscribeBy
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

/**
 * The subclass of every BLE Service that can be configured and registered by the [BleIdiomDSL].
 *
 * [Svc] is the subclass itself.
 * @sample io.intrepid.bleidiom.app.BatterijService
 */
open class BleService<Svc : BleService<Svc>> {
    internal val dsl: BleServiceDSLImpl<*> by lazy {
        BleServiceDSLImpl.Registration.getServiceDSL(this)!!
    }

    internal var device: RxBleDevice? = null
    internal var connection: RxBleConnection? = null

    private val writeSubscriptions: MutableMap<String, Subscription> = mutableMapOf()

    /**
     * Connects to the device that contains this service.
     *
     * @return [Observable] that emits this object when the connection is successful.
     */
    fun connect() = device?.establishConnection(false)
            ?.doOnUnsubscribe { handleDisconnect() }
            ?.map { conn -> connection = conn; asSvc() }
            ?: Observable.empty()

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
            val svc = asSvc()
            set(svc, BleCharValue(value))
            // For now, errors are ignored (to do)
            writeSubscriptions[name] = get(svc).write().subscribeBy(onError = {})
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
            writeSubscriptions[name] = valueStream
                    .doOnNext { set(svc, BleCharValue(it)) }
                    .flatMap { get(svc).write() }
                    .subscribeBy(onError = {})
        }
    }

    /**
     * Cancels any observed value-stream that is writing to a BLE characteristic.
     *
     * See also the [BleService.set] that take an Observable as the last parameter.
     */
    operator
    fun <Val : Any> minusAssign(property: KMutableProperty1<Svc, BleCharValue<Val>>) {
        unsubscribeWrite(property.name)
    }

    private fun unsubscribeWrite(propertyName: String) {
        with(writeSubscriptions[propertyName]) {
            if (this?.isUnsubscribed == false) {
                unsubscribe()
            }
        }
        writeSubscriptions -= propertyName
    }

    private fun handleDisconnect() {
        with(writeSubscriptions) {
            values.forEach { sub ->
                if (!sub.isUnsubscribed) {
                    sub.unsubscribe()
                }
            }
            clear()
        }
        connection = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun asSvc() = this as Svc
}

/**
 * Each [BleService]'s property that represents a BLE Service's characteristic and needs to be configured by
 * the [BleIdiomDSL] must be of this type.
 */
class BleCharValue<Val : Any>() {
    internal var value: Val? = null
    internal var readAction: () -> Observable<Val> = { Observable.empty() }
    internal var writeAction: () -> Observable<Val> = { Observable.empty() }

    /**
     * Creates a new BLE characteristic property-value.
     * @param value The new value for this property.
     */
    constructor(value: Val) : this() {
        this.value = value
    }

    /**
     * Reads a value from the remote BLE characteristic and returns an Observable.
     * @return An [Observable] that will emit the read value upon success.
     */
    fun read() = readAction()

    /**
     * Write this value to the remote BLE characteristic and returns an Observable.
     * @return An [Observable] that will complete upon success.
     * Depending upon the remote BLE characteristic, it may emit a value as well.
     */
    fun write() = writeAction()
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

/**
 * Helper function that creates a [BleCharValue] from the receiver.
 * @receiver The value from which the [BleCharValue] will be created.
 * @return The [BleCharValue] representing this receiver.
 */
inline infix
fun <reified Val : Any> Val.asRawBleValue(transform: Val.() -> ByteArray) = BleCharValue(transform())
