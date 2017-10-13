package io.intrepid.bleidiom

import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

/**
 * Marks the start of a new BLE service definition.
 */
interface BleIdiomDSL {
    /**
     * Specifies the [BleService] subclass **for** which this [BleIdiomDSL] is created.
     * @param bleServiceClass The target [BleService] subclass
     */
    infix
    fun forClass(bleServiceClass: KClass<out BleService<*>>): ForClassWith

    interface ForClassWith {
        /**
         * Registers the target [BleService] subclass,
         * then creates a new [BleServiceDSL]
         * that is configured by the given [dsl] code-block.
         * @param dsl The dsl configuration *with* which the registered [BleService] is configured.
         */
        infix
        fun with(dsl: BleServiceDSL.() -> Unit)
    }
}

/**
 * Defines the DSL of the targeted [BleService].
 */
interface BleServiceDSL {
    /**
     * The UUID of the BLE **Service**
     */
    var uuid: String

    /**
     * Starts the definition of the readable BLE characteristics.
     * @param dsl The block of code that configures the readable BLE characteristics for the targeted [BleService].
     */
    fun read(dsl: BleServiceReadDSL.() -> Unit)

    /**
     * Starts the definition of the writable BLE characteristics.
     * @param dsl The block of code that configures the writable BLE characteristics for the targeted [BleService].
     */
    fun write(dsl: BleServiceWriteDSL.() -> Unit)
}

/**
 * Defines readable BLE characteristics.
 */
interface BleServiceReadDSL {
    /**
     * 'data' keyword to make the DSL more legible.
     */
    val data: ReadableCharDSL
}

/**
 * Defines writable BLE characteristics.
 */
interface BleServiceWriteDSL {
    /**
     * 'data' keyword to make the DSL more legible.
     */
    val data: WritableCharDSL
}

/**
 * Ties a readable BLE characteristic (its UUID) to a [BleService]'s property.
 */
interface ReadableCharDSL {
    /**
     * Defines a remote BLE characteristic from which a value can be read.
     * @param uuid The UUID representing the remote BLE characteristic.
     */
    infix
    fun from(uuid: String): ReadableCharDSL

    /**
     * Defines which [BleService]'s property represents this readable BLE characteristic
     * that can be read by Kotlin code.
     * @param property The readable [Svc] property to be tied to this readable BLE characteristic.
     */
    infix
    fun <Svc : BleService<Svc>> into(property: KProperty1<Svc, BleCharValue<*>>): ReadableCharDSL
}

/**
 * Ties a writable BLE characteristic (its UUID) to a [BleService]'s property.
 */
interface WritableCharDSL {
    /**
     * Defines which [BleService]'s property represents this writable BLE characteristic,
     * that can be assigned/changed by Kotlin code.
     * @param property The writable [Svc] property to be tied to this writable BLE characteristic.
     */
    infix
    fun <Svc : BleService<Svc>> from(property: KMutableProperty1<Svc, out BleCharValue<*>>): WritableCharDSL

    /**
     * Defines a remote BLE characteristic that can be written to.
     * @param uuid The UUID representing the remote BLE characteristic.
     */
    infix
    fun into(uuid: String): WritableCharDSL
}

/**
 * When creating a new [BleService], the properties that represent its BLE characteristics must
 * be instances of [BleCharValue] that **delegate** to implementations of this [BleCharHandlerDSL] interface.
 *
 * This interface allows each property ([BleCharValue]) to define appropriate transformer-functions that will
 * transform [ByteArray]s into instances of the [BleCharValue]'s own [Val]-type and vice-versa.
 *
 * When configuring your own transformer-functions,
 * either assign a lambda to [forClass]
 * or assign lambdas to both [fromByteArray] and [toByteArray].
 */
interface BleCharHandlerDSL<Val : Any> {
    /**
     * Assigns the appropriate values to [fromByteArray] and [toByteArray] when it's set
     * to a known [KClass].
     *
     * This property can be set only. It can't be read.
     */
    var forClass: KClass<out Val>

    /**
     * The lambda that transforms a [ByteArray] into a value of the property's type [Val].
     */
    var fromByteArray: ((ByteArray) -> Val)?

    /**
     * The lambda that transforms a value of the property's type [Val] into a [ByteArray].
     */
    var toByteArray: ((Val) -> ByteArray)?

    operator
    fun getValue(service: BleService<*>, prop: KProperty<*>): BleCharValue<Val>

    operator
    fun setValue(service: BleService<*>, prop: KProperty<*>, value: BleCharValue<Val>)
}
