package io.intrepid.bleidiom

import rx.Observable
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.primaryConstructor

// Use this singleton's name to start the configuration (DSL) of a BleService.
@Suppress("ClassName")
object configureBleService: BleIdiomDSL {

    infix
    override fun forClass(bleServiceClass: KClass<out BleService<*>>) = WithConfiguration(bleServiceClass)

    class WithConfiguration(private val bleServiceClass: KClass<out BleService<*>>): BleIdiomDSL.With {

        infix
        override fun with(dsl: BleServiceDSL.() -> Unit) {
            BleServiceDSLImpl.Registration.registerDSL(bleServiceClass) {
                BleServiceDSLImpl().apply {
                    dsl()
                }
            }
        }
    }
}

internal class BleServiceDSLImpl: BleServiceDSL {
    internal object Registration {
        private val registeredDSLs: MutableMap<Int, BleServiceDSLImpl> = mutableMapOf()
        private val registeredServices: MutableMap<String, KClass<out BleService<*>>> = mutableMapOf()

        internal fun registerDSL(bleServiceClass: KClass<out BleService<*>>, createServiceDSL: () -> BleServiceDSLImpl) {
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

    private val readDSL: BleServiceReadDSL = object: BleServiceReadDSL {
        override val data: ReadableCharDSL
            get() = object : ReadableCharDSL {
                override fun from(uuid: String): ReadableCharBuilder = this@BleServiceDSLImpl.from(uuid)
                override fun into(property: KProperty<*>): ReadableCharBuilder = this@BleServiceDSLImpl.into(property)
            }
    }

    private val writeDSL: BleServiceWriteDSL = object: BleServiceWriteDSL {
        override val data: WritableCharDSL
            get() = object : WritableCharDSL {
                override fun from(property: KMutableProperty<*>): WritableCharBuilder = this@BleServiceDSLImpl.from(property)
                override fun into(uuid: String): WritableCharBuilder = this@BleServiceDSLImpl.into(uuid)
            }
    }

    internal val readCharacteristicsMap: MutableMap<String, String> = mutableMapOf()
    internal val writeCharacteristicsMap: MutableMap<String, String> = mutableMapOf()

    override fun read(dsl: BleServiceReadDSL.() -> Unit) = readDSL.dsl()

    override fun write(dsl: BleServiceWriteDSL.() -> Unit) = writeDSL.dsl()

    private fun from(uuid: String) = ReadableCharBuilder(this).apply {
        charUUID = fixCharUUID(uuid)
        buildIfReady(readCharacteristicsMap)
    }

    private fun into(property: KProperty<*>) = ReadableCharBuilder(this).apply {
        prop = property
        buildIfReady(readCharacteristicsMap)
    }

    private fun from(property: KMutableProperty<*>) = WritableCharBuilder(this).apply {
        prop = property
        buildIfReady(writeCharacteristicsMap)
    }

    private fun into(uuid: String) = WritableCharBuilder(this).apply {
        charUUID = fixCharUUID(uuid)
        buildIfReady(writeCharacteristicsMap)
    }
}

// This is a property-delegate that handles the BLE input/output from and to property's BLE-characteristic
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
        val charUUID = service.serviceDSL.readCharacteristicsMap[prop.name]
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
        val charUUID = service.serviceDSL.writeCharacteristicsMap[prop.name]
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

// Builds a BLE-characteristic, ties a BleCharValue property to a characteristic-UUID.
private abstract class CharBuilder<T : KProperty<*>>(protected val service: BleServiceDSLImpl) {
    internal var prop: T? = null
    internal var charUUID: String? = null

    internal fun buildIfReady(map: MutableMap<String,String>) {
        val property = prop
        val uuid = charUUID
        if (property == null  || uuid == null) {
            return // Not all data is known; not yet ready to build it.
        }
        // Build it by registering it.
        map[property.name] = uuid
    }
}

// Builds a readable BLE-characteristic, ties a BleCharValue property to a 'read' characteristic-UUID.
private class ReadableCharBuilder(service: BleServiceDSLImpl) : CharBuilder<KProperty<*>>(service), ReadableCharDSL {
    infix
    override fun from(uuid: String) = apply {
        charUUID = fixCharUUID(uuid)
        buildIfReady(service.readCharacteristicsMap)
    }

    infix
    override fun into(property: KProperty<*>) = apply {
        prop = property
        buildIfReady(service.readCharacteristicsMap)
    }
}

// Builds a writable BLE-characteristic, ties a mutable BleCharValue property to a 'write' characteristic-UUID.
private class WritableCharBuilder(service: BleServiceDSLImpl) : CharBuilder<KMutableProperty<*>>(service), WritableCharDSL {
    infix
    override fun from(property: KMutableProperty<*>) = apply {
        prop = property
        buildIfReady(service.writeCharacteristicsMap)
    }

    infix
    override fun into(uuid: String) = apply {
        charUUID = fixCharUUID(uuid)
        buildIfReady(service.writeCharacteristicsMap)
    }
}
