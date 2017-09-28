package io.intrepid.bleidiom

import com.polidea.rxandroidble.RxBleConnection
import com.polidea.rxandroidble.RxBleDevice
import rx.Observable
import rx.Subscription
import rx.lang.kotlin.subscribeBy
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

// Base class of a class that represents a BLE-service
open class BleService<Svc : BleService<Svc>> {
    internal val serviceDSL: BleServiceDSLImpl by lazy {
        BleServiceDSLImpl.Registration.getServiceDSL(this)!!
    }

    internal var device: RxBleDevice? = null
    internal var connection: RxBleConnection? = null

    private val writeSubscriptions: MutableMap<String, Subscription> = mutableMapOf()

    // Connects to the BLE-device and returns an Observable of this instance.
    fun connect() = device?.establishConnection(false)
            ?.doOnUnsubscribe { handleDisconnect() }
            ?.map { conn -> connection = conn; asSvc() }
            ?: Observable.empty()

    // Requests to get a value of type <Val> from a readable BLE-characteristic and observes the incoming result
    operator
    fun <Val : Any> get(key: KProperty1<Svc, BleCharValue<Val>>) = key.get(asSvc()).read()

    // Sends/posts a value of type <Val> to a writable BLE-characteristic.
    operator
    fun <Val : Any> set(key: KMutableProperty1<Svc, BleCharValue<Val>>, value: Val) {
        with(key) {
            val svc = asSvc()
            set(svc, BleCharValue(value))
            writeSubscriptions[name] = get(svc).write().subscribeBy(onError = {})
        }
    }

    // Sends/posts values of type <Val> to a writable BLE-characteristic emitted by the provided observable
    operator
    fun <Val : Any> set(key: KMutableProperty1<Svc, BleCharValue<Val>>, value: Observable<Val>) {
        with(key) {
            val svc = asSvc()
            writeSubscriptions[name] = value
                    .doOnNext { set(svc, BleCharValue(it)) }
                    .flatMap { get(svc).write() }
                    .subscribeBy(onError = {})
        }
    }

    // Cancels any observed values to be emitted and written to the writable BLE-characteristic
    operator
    fun <Val : Any> minusAssign(key: KMutableProperty1<Svc, BleCharValue<Val>>) {
        unsubscribeWrite(key.name)
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

// Represents a BLE-characteristic that can handle (read/write) a value of type <Val>
// Instance of this class should be the properties of a BleService sub-class
class BleCharValue<Val : Any>() {
    internal var value: Val? = null
    internal var readAction: () -> Observable<Val> = { Observable.empty() }
    internal var writeAction: () -> Observable<Val> = { Observable.empty() }

    constructor(value: Val) : this() {
        this.value = value
    }

    fun read() = readAction()

    fun write() = writeAction()
}

// Functions to create BleCharHandlerDSL instances that handle the ble BLE input/output.
inline
fun <reified Val : Any> bleCharHandler(): BleCharHandlerDSL<Val> = bleCharHandler { forClass = Val::class }

fun <Val : Any> bleCharHandler(body: BleCharHandlerDSL<Val>.() -> Unit): BleCharHandlerDSL<Val> = BleCharValueDelegate(body)


inline infix
fun <reified T : Any> T.asRawBleValue(transform: T.() -> ByteArray) = BleCharValue<ByteArray>(this.transform())
