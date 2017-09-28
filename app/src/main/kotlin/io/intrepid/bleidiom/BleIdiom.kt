package io.intrepid.bleidiom

import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty

interface BleIdiomDSL {
    // The forClass 'keyword' defines the target BleService sub-class to be configured with the 'with' DSL.
    infix
    fun forClass(bleServiceClass: KClass<out BleService<*>>): With

    interface With {
        // The with 'keyword' defines the DSL that configures the targeted BleService sub-class.
        infix
        fun with(dsl: BleServiceDSL.() -> Unit)
    }
}

interface BleServiceDSL {
    // The UUID of the BLE-service
    var uuid: String

    // The read 'keyword' of the BLE-DSL that starts defining one or more readable BLE-characteristics
    fun read(dsl: BleServiceReadDSL.() -> Unit)

    // The write 'keyword' of the BLE-DSL that starts defining one or more writable BLE-characteristics
    fun write(dsl: BleServiceWriteDSL.() -> Unit)
}

interface BleServiceReadDSL {
    // The data 'keyword' of the BLE-DSL that starts defining a readable BLE-characteristic
    val data: ReadableCharDSL
}

interface BleServiceWriteDSL {
    // The data 'keyword' of the BLE-DSL that starts defining a writable BLE-characteristic
    val data: WritableCharDSL
}

interface ReadableCharDSL {
    // The from 'keyword' defines the UUID of the BLE-characteristic that is the source of the BLE-data.
    infix
    fun from(uuid: String): ReadableCharDSL

    // The into 'keyword' defines the BleCharValue property that is the target of the BLE-data,
    // from which code then can *read* the incoming value.
    infix
    fun into(property: KProperty<*>): ReadableCharDSL
}

interface WritableCharDSL {
    // The from 'keyword' defines the BleCharValue property that is the source of the BLE-data.
    // Code can *write* values to this property that is then source of the outgoing BLE-data,
    infix
    fun from(property: KMutableProperty<*>): WritableCharDSL

    // The into 'keyword' defines the UUID of the BLE-characteristic that is the target of the BLE-data.
    infix
    fun into(uuid: String): WritableCharDSL
}

// Defines the DSL that allows the configuration of a bleCharHandler
interface BleCharHandlerDSL<Val : Any> {
    var forClass: KClass<out Val>
    var fromByteArray: ((ByteArray) -> Val)?
    var toByteArray: ((Val) -> ByteArray)?

    operator
    fun getValue(service: BleService<*>, prop: KProperty<*>): BleCharValue<Val>

    operator
    fun setValue(service: BleService<*>, prop: KProperty<*>, value: BleCharValue<Val>)
}
