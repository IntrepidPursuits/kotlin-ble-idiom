/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 */
package io.intrepid.bleidiom

import kotlin.reflect.*

/**
 * Marks the start of a new BLE service definition.
 */
interface BleIdiomDSL {
    /**
     * Specifies the [BleService] subclass **for** which this [BleIdiomDSL] is created.
     * @param bleServiceClass The target [BleService] subclass
     */
    infix
    fun <Svc : BleService<Svc>> forClass(bleServiceClass: KClass<Svc>): ForClassWith<Svc>

    interface ForClassWith<Svc> {
        /**
         * Registers the target [BleService] subclass,
         * then creates a new [BleServiceDSL]
         * that is configured by the given [dsl] code-block.
         * @param dsl The dsl configuration *with* which the registered [BleService] is configured.
         */
        infix
        fun with(dsl: BleServiceDSL<Svc>.() -> Unit)
    }

    /**
     * Clears all configure BLE service definitions.
     */
    fun clearConfigurations()
}

/**
 * Marks the configuration start of a new BLE service definition.
 */
interface BleConfigureDSL<Svc : BleService<Svc>> {
    fun configure(dsl: BleServiceDSL<Svc>.() -> Unit)
}

/**
 * Defines the DSL of the targeted [BleService].
 */
interface BleServiceDSL<Svc> {
    /**
     * The UUID of the BLE **Service**
     */
    var uuid: String

    /**
     * Starts the definition of the readable BLE characteristics.
     * @param dsl The block of code that configures the readable BLE characteristics for the targeted [BleService].
     */
    fun read(dsl: BleServiceReadDSL<Svc>.() -> Unit)

    /**
     * Starts the definition of the writable BLE characteristics.
     * @param dsl The block of code that configures the writable BLE characteristics for the targeted [BleService].
     */
    fun write(dsl: BleServiceWriteDSL<Svc>.() -> Unit)

    /**
     * Starts the definition of the readable and writable BLE characteristics.
     * @param dsl The block of code that configures the BLE characteristics for the targeted [BleService].
     */
    fun readAndWrite(dsl: BleServiceReadWriteDSL<Svc>.() -> Unit)
}

/**
 * Defines readable BLE characteristics.
 */
interface BleServiceReadDSL<Svc> {
    /**
     * 'data' keyword to make the DSL more legible.
     */
    val data: ReadableCharDSL<Svc>
}

/**
 * Defines writable BLE characteristics.
 */
interface BleServiceWriteDSL<Svc> {
    /**
     * 'data' keyword to make the DSL more legible.
     */
    val data: WritableCharDSL<Svc>
}

/**
 * Defines readable and writable BLE characteristics.
 */
interface BleServiceReadWriteDSL<Svc> {
    /**
     * 'data' keyword to make the DSL more legible.
     */
    val data: ReadAndWriteCharDSL<Svc>
}

/**
 * Basis of both the readable and writable character DSLs.
 */
interface CharDSL<out Svc> {
    val prototype: Svc
}

/**
 * Ties a readable BLE characteristic (its UUID) to a [BleService]'s property.
 */
interface ReadableCharDSL<Svc> : CharDSL<Svc> {
    /**
     * Defines a remote BLE characteristic from which a value can be read.
     * @param uuid The UUID representing the remote BLE characteristic.
     */
    infix
    fun from(uuid: String): ReadableCharDSL<Svc>

    /**
     * Defines which [BleService]'s property represents this readable BLE characteristic
     * that can be read by Kotlin code.
     * @param property The readable [Svc] property to be tied to this readable BLE characteristic.
     */
    infix
    fun into(property: KProperty1<Svc, BleCharValue<*>>): ReadableCharDSL<Svc>

    /**
     * Defines which [BleService]'s property represents this readable BLE characteristic
     * that can be read by Kotlin code.
     * @param property The readable [Svc] property to be tied to this readable BLE characteristic.
     */
    infix
    fun into(property: KProperty0<BleCharValue<*>>): ReadableCharDSL<Svc>

    /**
     * Defines which [BleService]'s property represents this readable BLE characteristic
     * that can be read by Kotlin code.
     * @param propertyGet Lambda that must return a [Svc] property to be tied to this readable BLE characteristic.
     */
    infix
    fun into(propertyGet: Svc.() -> KProperty0<BleCharValue<*>>) = into(prototype.propertyGet())
}

/**
 * Ties a writable BLE characteristic (its UUID) to a [BleService]'s property.
 */
interface WritableCharDSL<Svc> : CharDSL<Svc> {
    /**
     * Defines which [BleService]'s property represents this writable BLE characteristic,
     * that can be assigned/changed by Kotlin code.
     * @param property The writable [Svc] property to be tied to this writable BLE characteristic.
     */
    infix
    fun from(property: KMutableProperty1<Svc, out BleCharValue<*>>): WritableCharDSL<Svc>

    /**
     * Defines which [BleService]'s property represents this writable BLE characteristic,
     * that can be assigned/changed by Kotlin code.
     * @param property The writable [Svc] property to be tied to this writable BLE characteristic.
     */
    infix
    fun from(property: KMutableProperty0<out BleCharValue<*>>): WritableCharDSL<Svc>

    /**
     * Defines which [BleService]'s property represents this writable BLE characteristic,
     * that can be assigned/changed by Kotlin code.
     * @param propertyGet Lambdat that must return the writable [Svc] property to be tied to this writable BLE characteristic.
     */
    infix
    fun from(propertyGet: Svc.() -> KMutableProperty0<out BleCharValue<*>>) = from(prototype.propertyGet())

    /**
     * Defines a remote BLE characteristic that can be written to.
     * @param uuid The UUID representing the remote BLE characteristic.
     */
    infix
    fun into(uuid: String): WritableCharDSL<Svc>
}

/**
 * Ties a read/write BLE characteristic (its UUID) to a [BleService]'s property.
 */
interface ReadAndWriteCharDSL<Svc> : CharDSL<Svc> {
    infix fun between(uuid: String): AndPropDSL<Svc>

    infix fun between(property: KMutableProperty1<Svc, out BleCharValue<*>>): AndStringDSL

    infix fun between(property: KMutableProperty0<out BleCharValue<*>>): AndStringDSL

    infix fun between(propertyGet: Svc.() -> KMutableProperty0<out BleCharValue<*>>) =
            between(prototype.propertyGet())

    interface AndStringDSL {
        infix fun and(uuid: String)
    }

    interface AndPropDSL<Svc> : CharDSL<Svc> {
        infix fun and(property: KMutableProperty1<Svc, out BleCharValue<*>>)

        infix fun and(property: KMutableProperty0<out BleCharValue<*>>)

        infix fun and(propertyGet: Svc.() -> KMutableProperty0<out BleCharValue<*>>) =
                and(prototype.propertyGet())
    }
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
