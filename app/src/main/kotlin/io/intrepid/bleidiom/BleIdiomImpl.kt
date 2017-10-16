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
            BleServiceDSLImpl<Svc>().apply {
                dsl()
            }
        }
    }
}

internal class BleServiceDSLImpl<Svc> : BleServiceDSL<Svc> {
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

    private val readDSL = object : BleServiceReadDSL<Svc> {
        override val data: ReadableCharDSL<Svc>
            get() = object : ReadableCharDSL<Svc> {
                override fun from(uuid: String) = this@BleServiceDSLImpl.from(uuid)
                override fun into(property: KProperty1<Svc, BleCharValue<*>>) = this@BleServiceDSLImpl.into(property)
            }
    }

    private val writeDSL = object : BleServiceWriteDSL<Svc> {
        override val data: WritableCharDSL<Svc>
            get() = object : WritableCharDSL<Svc> {
                override fun from(property: KMutableProperty1<Svc, out BleCharValue<*>>) = this@BleServiceDSLImpl.from(property)
                override fun into(uuid: String) = this@BleServiceDSLImpl.into(uuid)
            }
    }

    internal val readCharacteristicsMap: MutableMap<String, String> = mutableMapOf()
    internal val writeCharacteristicsMap: MutableMap<String, String> = mutableMapOf()

    override fun read(dsl: BleServiceReadDSL<Svc>.() -> Unit) = readDSL.dsl()

    override fun write(dsl: BleServiceWriteDSL<Svc>.() -> Unit) = writeDSL.dsl()

    private fun from(uuid: String) = ReadableCharBuilder<Svc>(this).apply {
        charUUID = fixCharUUID(uuid)
        buildIfReady(readCharacteristicsMap)
    }

    private fun into(property: KCallable<*>) = ReadableCharBuilder<Svc>(this).apply {
        prop = property
        buildIfReady(readCharacteristicsMap)
    }

    private fun from(property: KCallable<*>) = WritableCharBuilder<Svc>(this).apply {
        prop = property
        buildIfReady(writeCharacteristicsMap)
    }

    private fun into(uuid: String) = WritableCharBuilder<Svc>(this).apply {
        charUUID = fixCharUUID(uuid)
        buildIfReady(writeCharacteristicsMap)
    }
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

/**
 * Builds a BLE-characteristic, ties a BleCharValue property to a characteristic-UUID.
 */
private abstract class CharBuilder(protected val service: BleServiceDSLImpl<*>) {
    internal var prop: KCallable<*>? = null
    internal var charUUID: String? = null

    internal fun buildIfReady(map: MutableMap<String, String>) {
        val property = prop
        val uuid = charUUID
        if (property == null || uuid == null) {
            return // Not all data is known; not yet ready to build it.
        }
        // Build it by registering it.
        map[property.name] = uuid
    }
}

/**
 * Builds a readable BLE-characteristic, ties a BleCharValue property to a 'read' characteristic-UUID.
 */
private class ReadableCharBuilder<Svc>(service: BleServiceDSLImpl<*>) : CharBuilder(service), ReadableCharDSL<Svc> {
    infix
    override fun from(uuid: String) = apply {
        charUUID = fixCharUUID(uuid)
        buildIfReady(service.readCharacteristicsMap)
    }

    infix
    override fun into(property: KProperty1<Svc, BleCharValue<*>>) = apply {
        prop = property
        buildIfReady(service.readCharacteristicsMap)
    }
}

/**
 * Builds a writable BLE-characteristic, ties a mutable BleCharValue property to a 'write' characteristic-UUID.
 */
private class WritableCharBuilder<Svc>(service: BleServiceDSLImpl<*>) : CharBuilder(service), WritableCharDSL<Svc> {
    infix
    override fun from(property: KMutableProperty1<Svc, out BleCharValue<*>>) = apply {
        prop = property
        buildIfReady(service.writeCharacteristicsMap)
    }

    infix
    override fun into(uuid: String) = apply {
        charUUID = fixCharUUID(uuid)
        buildIfReady(service.writeCharacteristicsMap)
    }
}
