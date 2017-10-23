/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 */
package io.intrepid.bleidiom

import rx.Observable
import java.util.*
import kotlin.reflect.*
import kotlin.reflect.full.primaryConstructor

/**
 * This 'keyword' will start any [BleService] configuration.
 *
 * See also [BleIdiomDSL] and its related interfaces.
 * @sample [io.intrepid.bleidiom.app.defineBleServices]
 */
@Suppress("ClassName")
object configureBleService : BleIdiomDSL {
    private val registration = BleServiceDSLImpl.Registration

    infix
    override fun <Svc : BleService<Svc>> forClass(bleServiceClass: KClass<Svc>) = WithConfiguration<Svc>(bleServiceClass)

    class WithConfiguration<Svc>(private val bleServiceClass: KClass<out BleService<*>>) : BleIdiomDSL.ForClassWith<Svc> {

        infix
        override fun with(dsl: BleServiceDSL<Svc>.() -> Unit) = registration.registerDSL(bleServiceClass) {
            BleServiceDSLImpl(createPrototype()).apply {
                dsl()
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun createPrototype() = bleServiceClass.primaryConstructor?.call() as Svc
    }
}

internal class BleServiceDSLImpl<Svc>(internal val svcPrototype: Svc) : BleServiceDSL<Svc> {
    internal object Registration {
        private val registeredDSLs: MutableMap<Int, BleServiceDSLImpl<*>> = mutableMapOf()
        private val registeredServices: MutableMap<String, KClass<out BleService<*>>> = mutableMapOf()

        internal fun registerDSL(bleServiceClass: KClass<out BleService<*>>, createServiceDSL: () -> BleServiceDSLImpl<*>) {
            val key = bleServiceClass.hashCode()
            if (key !in registeredDSLs) {
                val serviceDSL = createServiceDSL()
                registeredDSLs[key] = serviceDSL
                registeredServices[serviceDSL.uuid] = bleServiceClass
            }
        }

        internal fun getServiceDSL(bleService: BleService<*>) = registeredDSLs[bleService::class.hashCode()]

        internal fun hasService(serviceUUID: String) = serviceUUID in registeredServices
        internal fun createService(serviceUUID: String) = registeredServices[serviceUUID]?.primaryConstructor?.call()
    }

    lateinit
    override var uuid: String

    override fun read(dsl: BleServiceReadDSL<Svc>.() -> Unit) = readDSL.dsl()

    override fun write(dsl: BleServiceWriteDSL<Svc>.() -> Unit) = writeDSL.dsl()

    internal val readCharacteristicsMap: MutableMap<String, String> = mutableMapOf()
    internal val writeCharacteristicsMap: MutableMap<String, String> = mutableMapOf()

    private val readDSL = object : BleServiceReadDSL<Svc> {
        override val data: ReadableCharDSL<Svc>
            get() = object : ReadableCharDSL<Svc> {
                override fun from(uuid: String) =
                        ReadableCharBuilder(`this`).apply {
                            from(uuid)
                        }

                override fun into(property: KProperty1<Svc, BleCharValue<*>>) =
                        ReadableCharBuilder(`this`).apply {
                            into(property)
                        }

                override fun into(propertyGet: Svc.() -> KProperty0<BleCharValue<*>>) =
                        ReadableCharBuilder(`this`).apply {
                            into(propertyGet)
                        }
            }
    }

    private val writeDSL = object : BleServiceWriteDSL<Svc> {
        override val data: WritableCharDSL<Svc>
            get() = object : WritableCharDSL<Svc> {
                override fun from(property: KMutableProperty1<Svc, out BleCharValue<*>>) =
                        WritableCharBuilder(`this`).apply {
                            from(property)
                        }

                override fun from(propertyGet: Svc.() -> KMutableProperty0<out BleCharValue<*>>) =
                        WritableCharBuilder(`this`).apply {
                            from(propertyGet)
                        }

                override fun into(uuid: String) =
                        WritableCharBuilder(`this`).apply {
                            into(uuid)
                        }
            }
    }

    private val `this` = this
}

/**
 * This is a property-delegate that handles the BLE input/output from and to property's BLE-characteristic
 */
internal class BleCharValueDelegate<Val : Any>() : BleCharHandlerDSL<Val> {
    private val backingField = BleCharValue<Val>()

    override var forClass: KClass<out Val>
        set(value) {
            fromByteArray = fromByteArrayTransformer(value)
            toByteArray = toByteArrayTransformer(value)
        }
        get() = throw IllegalAccessError("forClass-getter must not be called")

    override var fromByteArray: ((ByteArray) -> Val)? = null
    override var toByteArray: ((Val) -> ByteArray)? = null

    internal constructor(dsl: BleCharHandlerDSL<Val>.() -> Unit) : this() {
        dsl()
    }

    operator
    override fun getValue(service: BleService<*>, prop: KProperty<*>): BleCharValue<Val> {
        val charUUID = service.dsl.readCharacteristicsMap[prop.name]
        val transformFrom = fromByteArray

        backingField.readAction = {
            if (charUUID != null && transformFrom != null)
                service.connection
                        ?.readCharacteristic(UUID.fromString(charUUID))
                        ?.map { byteArray -> transformFrom(byteArray) }
                        ?.take(1) ?: Observable.empty()
            else Observable.empty()
        }

        return backingField
    }

    operator
    override fun setValue(service: BleService<*>, prop: KProperty<*>, value: BleCharValue<Val>) {
        val charUUID = service.dsl.writeCharacteristicsMap[prop.name]
        val transformTo = toByteArray
        val transformFrom = fromByteArray
        val propValue = value.value

        backingField.writeAction = {
            if (charUUID != null && transformTo != null && transformFrom != null && propValue != null)
                service.connection
                        ?.writeCharacteristic(UUID.fromString(charUUID), transformTo(propValue))
                        ?.map { byteArray -> transformFrom(byteArray) }
                        ?.take(1) ?: Observable.empty()
            else Observable.empty()
        }
    }
}

/**
 * Builds a BLE-characteristic, ties a BleCharValue property to a characteristic-UUID.
 */
private abstract class CharBuilder<Svc>(protected val serviceDSL: BleServiceDSLImpl<Svc>) {
    protected val svcPrototype: Svc = serviceDSL.svcPrototype
    protected var propName: String? = null
    protected var charUUID: String? = null

    internal fun buildIfReady(map: MutableMap<String, String>) {
        val propertyName = propName
        val uuid = charUUID
        if (propertyName == null || uuid == null) {
            return // Not all data is known; not yet ready to build it.
        }
        // Build it by registering it.
        map[propertyName] = uuid
    }
}

/**
 * Builds a readable BLE-characteristic, ties a BleCharValue property to a 'read' characteristic-UUID.
 */
private class ReadableCharBuilder<Svc>(service: BleServiceDSLImpl<Svc>) : CharBuilder<Svc>(service), ReadableCharDSL<Svc> {
    infix
    override fun from(uuid: String) = apply {
        charUUID = fixCharUUID(uuid)
        buildIfReady(serviceDSL.readCharacteristicsMap)
    }

    infix
    override fun into(property: KProperty1<Svc, BleCharValue<*>>) = apply {
        propName = property.name
        buildIfReady(serviceDSL.readCharacteristicsMap)
    }

    infix
    override fun into(propertyGet: Svc.() -> KProperty0<BleCharValue<*>>) = apply {
        propName = svcPrototype.propertyGet().name
        buildIfReady(serviceDSL.readCharacteristicsMap)
    }
}

/**
 * Builds a writable BLE-characteristic, ties a mutable BleCharValue property to a 'write' characteristic-UUID.
 */
private class WritableCharBuilder<Svc>(service: BleServiceDSLImpl<Svc>) : CharBuilder<Svc>(service), WritableCharDSL<Svc> {
    infix
    override fun from(property: KMutableProperty1<Svc, out BleCharValue<*>>) = apply {
        propName = property.name
        buildIfReady(serviceDSL.writeCharacteristicsMap)
    }

    infix
    override fun from(propertyGet: Svc.() -> KMutableProperty0<out BleCharValue<*>>) = apply {
        propName = svcPrototype.propertyGet().name
        buildIfReady(serviceDSL.writeCharacteristicsMap)
    }

    infix
    override fun into(uuid: String) = apply {
        charUUID = fixCharUUID(uuid)
        buildIfReady(serviceDSL.writeCharacteristicsMap)
    }
}
