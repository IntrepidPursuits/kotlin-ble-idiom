/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 */
package io.intrepid.bleidiom

import io.intrepid.bleidiom.util.toRx2
import io.reactivex.Observable
import java.util.*
import kotlin.reflect.*
import kotlin.reflect.full.primaryConstructor

internal object Registration {
    private val registeredDSLs = mutableMapOf<Int, BleServiceDSLImpl<*>>()
    private val registeredServices = mutableMapOf<UUID, KClass<out BleService<*>>>()

    internal fun registerDSL(bleServiceClass: KClass<out BleService<*>>, createServiceDSL: () -> BleServiceDSLImpl<*>) {
        val key = bleServiceClass.hashCode()
        if (key !in registeredDSLs) {
            val serviceDSL = createServiceDSL()
            registeredDSLs[key] = serviceDSL
            registeredServices[serviceDSL.uuid.toUUID()!!] = bleServiceClass
        }
    }

    internal fun clearAll() {
        registeredDSLs.clear()
        registeredServices.clear()
    }

    internal fun getServiceDSL(bleService: BleService<*>) = registeredDSLs[bleService::class.hashCode()]

    internal fun hasService(serviceUUID: UUID) = serviceUUID in registeredServices
    internal fun createService(serviceUUID: UUID) = registeredServices[serviceUUID]?.primaryConstructor?.call()

    internal fun getUUID(bleServiceClass: KClass<out BleService<*>>) = registeredDSLs[bleServiceClass.hashCode()]!!.uuid
}

/**
 * This 'keyword' will start any [BleService] configuration.
 *
 * See also [BleIdiomDSL] and its related interfaces.
 */
@Suppress("ClassName")
object configureBleService : BleIdiomDSL {

    infix
    override fun <Svc : BleService<Svc>> forClass(bleServiceClass: KClass<Svc>) = WithConfiguration<Svc>(bleServiceClass)

    override fun clearConfigurations() {
        Registration.clearAll()
    }

    class WithConfiguration<Svc>(private val bleServiceClass: KClass<out BleService<*>>) : BleIdiomDSL.ForClassWith<Svc> {

        infix
        override fun with(dsl: BleServiceDSL<Svc>.() -> Unit) = Registration.registerDSL(bleServiceClass) {
            BleServiceDSLImpl(createPrototype()).apply {
                dsl()
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun createPrototype() = bleServiceClass.primaryConstructor?.call() as Svc
    }
}

internal class BleServiceDSLImpl<Svc>(internal val svcPrototype: Svc) : BleServiceDSL<Svc> {
    lateinit
    override var uuid: String

    override fun custom(uuid: String) = fixCustomUUID(uuid)

    override fun read(dsl: BleServiceReadDSL<Svc>.() -> Unit) = readDSL.dsl()

    override fun write(dsl: BleServiceWriteDSL<Svc>.() -> Unit) = writeDSL.dsl()

    override fun readAndWrite(dsl: BleServiceReadWriteDSL<Svc>.() -> Unit) = readWriteDSL.dsl()

    internal val readCharacteristicsMap = mutableMapOf<String, String>()
    internal val writeCharacteristicsMap = mutableMapOf<String, String>()

    private val readDSL = object : BleServiceReadDSL<Svc> {
        override val data: ReadableCharDSL<Svc>
            get() = object : ReadableCharDSL<Svc> {
                override val prototype = svcPrototype

                override fun from(uuid: String) =
                        ReadableCharBuilder(`this`).apply {
                            from(uuid)
                        }

                override fun into(property: KProperty1<Svc, BleCharValue<*>>) =
                        ReadableCharBuilder(`this`).apply {
                            into(property)
                        }

                override fun into(property: KProperty0<BleCharValue<*>>) =
                        ReadableCharBuilder(`this`).apply {
                            into(property)
                        }
            }
    }

    private val writeDSL = object : BleServiceWriteDSL<Svc> {
        override val data: WritableCharDSL<Svc>
            get() = object : WritableCharDSL<Svc> {
                override val prototype = svcPrototype

                override fun from(property: KMutableProperty1<Svc, out BleCharValue<*>>) =
                        WritableCharBuilder(`this`).apply {
                            from(property)
                        }

                override fun from(property: KMutableProperty0<out BleCharValue<*>>) =
                        WritableCharBuilder(`this`).apply {
                            from(property)
                        }

                override fun into(uuid: String) =
                        WritableCharBuilder(`this`).apply {
                            into(uuid)
                        }
            }
    }

    private val readWriteDSL = object : BleServiceReadWriteDSL<Svc> {
        override val data: ReadAndWriteCharDSL<Svc>
            get() = object : ReadAndWriteCharDSL<Svc> {
                override val prototype = svcPrototype

                override fun between(uuid: String) =
                        ReadWriteCharBuilder(`this`).apply {
                            and(uuid)
                        }

                override fun between(property: KMutableProperty1<Svc, out BleCharValue<*>>) =
                        ReadWriteCharBuilder(`this`).apply {
                            and(property)
                        }

                override fun between(property: KMutableProperty0<out BleCharValue<*>>) =
                        ReadWriteCharBuilder(`this`).apply {
                            and(property)
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
    private var uuid: UUID? = null
    private var service: BleService<*>? = null

    override var forClass: KClass<out Val>
        set(value) {
            fromByteArray = fromByteArrayTransformer(value)
            toByteArray = toByteArrayTransformer(value)
        }
        get() = throw IllegalAccessError("forClass getter must never be called")

    override var fromByteArray: ((ByteArray) -> Val)? = null
    override var toByteArray: ((Val) -> ByteArray)? = null
    override var toBatchInfo: ((Val, ByteArray) -> BatchInfo) = { _, bytes ->
        service?.let { it.mtuSize to bytes } ?: Pair(MIN_MTU_SIZE, bytes)
    }

    init {
        backingField.readAction = {
            letMany(uuid, service?.sharedConnection, fromByteArray) { charUUID, connection, transform ->
                connection.take(1).flatMapTry {
                    it.readCharacteristic(charUUID)
                            .toRx2()
                            .map { byteArray -> transform(byteArray) }
                            .doOnNext { backingField.currentValue = it }
                }
            } ?: Observable.never()
        }

        backingField.observeAction = { isIndication ->
            letMany(uuid, service?.sharedConnection, fromByteArray) { charUUID, connection, transform ->
                connection.flatMapTry {
                    val setupObserver = {
                        if (isIndication) it.setupIndication(charUUID)
                        else it.setupNotification(charUUID)
                    }
                    setupObserver()
                            .flatMap { notificationSetup -> notificationSetup }
                            .toRx2()
                            .map { byteArray -> transform(byteArray) }
                            .doOnNext { backingField.currentValue = it }
                }
            } ?: Observable.never()
        }

        backingField.writeAction = { value ->
            val mtu = service?.mtuSize ?: MIN_MTU_SIZE
            letMany(uuid, service?.sharedConnection, toByteArray) { charUUID, connection, transform ->
                val inflightValue = value
                connection.take(1).flatMapTry {
                    val bytes = transform(value)
                    val writeObs = if (bytes.size > mtu) {
                        val batchInfo = toBatchInfo(value, bytes)
                        it.createNewLongWriteBuilder()
                                .setCharacteristicUuid(charUUID)
                                .setMaxBatchSize(batchInfo.first)
                                .setBytes(batchInfo.second)
                                .build()
                    } else {
                        it.writeCharacteristic(charUUID, bytes)
                    }
                    writeObs.toRx2()
                            .doOnSubscribe { backingField.inFlightValue = inflightValue }
                            .map { _ -> value }
                            .doOnNext { backingField.currentValue = it }
                }
            } ?: Observable.never()
        }
    }

    internal constructor(dsl: BleCharHandlerDSL<Val>.() -> Unit) : this() {
        dsl()
    }

    operator
    override fun getValue(service: BleService<*>, prop: KProperty<*>): BleCharValue<Val> {
        synchronized(backingField) {
            if (uuid == null) {
                uuid = charUUID(service, prop).toUUID()
                this.service = service
            }
        }
        return backingField
    }

    operator
    override fun setValue(service: BleService<*>, prop: KProperty<*>, value: BleCharValue<Val>) {
        synchronized(backingField) {
            if (uuid == null) {
                uuid = charUUID(service, prop).toUUID()
                this.service = service
            }
        }
    }

    internal fun charUUID(service: BleService<*>, prop: KProperty<*>): String? {
        val dsl = service.dsl
        return if (dsl.readCharacteristicsMap[prop.name] != null) {
            dsl.readCharacteristicsMap[prop.name]
        } else {
            dsl.writeCharacteristicsMap[prop.name]
        }
    }
}

/**
 * Builds a BLE-characteristic, ties a BleCharValue property to a characteristic-UUID.
 */
private abstract class CharBuilder<Svc>(protected val serviceDSL: BleServiceDSLImpl<Svc>) : CharDSL<Svc> {
    override final val prototype = serviceDSL.svcPrototype

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
        charUUID = uuid.toUUID().toString()
        buildIfReady(serviceDSL.readCharacteristicsMap)
    }

    infix
    override fun into(property: KProperty1<Svc, BleCharValue<*>>) = apply {
        propName = property.name
        buildIfReady(serviceDSL.readCharacteristicsMap)
    }

    infix
    override fun into(property: KProperty0<BleCharValue<*>>) = apply {
        propName = property.name
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
    override fun from(property: KMutableProperty0<out BleCharValue<*>>) = apply {
        propName = property.name
        buildIfReady(serviceDSL.writeCharacteristicsMap)
    }

    infix
    override fun into(uuid: String) = apply {
        charUUID = uuid.toUUID().toString()
        buildIfReady(serviceDSL.writeCharacteristicsMap)
    }
}

/**
 * Builds a read/write BLE-characteristic, ties a mutable BleCharValue property to a characteristic-UUID.
 */
private class ReadWriteCharBuilder<Svc>(service: BleServiceDSLImpl<Svc>)
    : CharBuilder<Svc>(service), ReadAndWriteCharDSL.AndStringDSL, ReadAndWriteCharDSL.AndPropDSL<Svc> {
    override fun and(uuid: String) {
        charUUID = uuid.toUUID().toString()
        buildIfReady()
    }

    override fun and(property: KMutableProperty1<Svc, out BleCharValue<*>>) {
        propName = property.name
        buildIfReady()
    }

    override fun and(property: KMutableProperty0<out BleCharValue<*>>) {
        propName = property.name
        buildIfReady()
    }

    private fun buildIfReady() {
        buildIfReady(serviceDSL.readCharacteristicsMap)
        buildIfReady(serviceDSL.writeCharacteristicsMap)
    }
}
