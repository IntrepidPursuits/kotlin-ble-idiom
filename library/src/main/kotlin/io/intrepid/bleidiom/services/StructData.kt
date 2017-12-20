package io.intrepid.bleidiom.services

/**
 */
import io.intrepid.bleidiom.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure

private class DataClassAssembler<T : StructData>(val data: ByteArray? = null, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, val offset: Int = 0) {
    companion object {
        const val COMPONENT = "component"

        private fun readFromBuffer(buffer: ByteBuffer, size: Int, isFloat: Boolean): Number = when (abs(size)) {
            1 -> buffer.get()
            2 -> buffer.short
            4 -> if (isFloat) buffer.float else buffer.int
            8 -> if (isFloat) buffer.double else buffer.long
            else -> throw Exception("Size must be 1, 2, 4 or 8 but is $size instead.")
        }

        private fun writeToBuffer(value: Number, buffer: ByteBuffer, size: Int, isFloat: Boolean) = when (size) {
            1 -> buffer.put(value.toByte())
            2 -> buffer.putShort(value.toShort())
            4 -> if (isFloat) buffer.putFloat(value.toFloat()) else buffer.putInt(value.toInt())
            8 -> if (isFloat) buffer.putDouble(value.toDouble()) else buffer.putLong(value.toLong())
            else -> throw Exception("Size must be 1, 2, 4 or 8 but is $size instead.")
        }
    }

    val buffer = if (data != null) ByteBuffer.wrap(data, 0, data.size).order(order)!! else null
    var start = -1

    private lateinit var packingInfo: Array<Int>
    private lateinit var dataClassComponents: Array<KFunction<*>?>

    private val sync = data ?: Any()

    private fun initialize(dataClass: KClass<out T>) {
        if (!dataClass.isData) {
            throw Exception("This is not a data class: $dataClass")
        }

        packingInfo = dataClass.factory.packingInfo

        dataClassComponents = arrayOfNulls(packingInfo.size)

        dataClass.memberFunctions.forEach {
            val idx = it.name.indexOf(COMPONENT)
            if (idx == 0) {
                val componentNum = it.name.substring(idx + COMPONENT.length).toInt()
                dataClassComponents[componentNum - 1] = it
            }
        }

        start = offset
    }

    internal fun construct(dataClass: KClass<out T>): Pair<T, Int> = synchronized(sync) {
        letMany(data, buffer) { data, buffer ->
            initialize(dataClass)

            val componentValues = arrayOfNulls<Any?>(dataClassComponents.size)

            dataClassComponents.forEachIndexed { index, function ->
                buffer.position(start)
                var size = packingInfo[index]

                val componentClass = function!!.returnType.jvmErasure
                componentValues[index] = when {
                    componentClass == Byte::class -> readFromBuffer(buffer, size, false).toByte().fixSign(size)
                    componentClass == Short::class -> readFromBuffer(buffer, size, false).toShort().fixSign(size)
                    componentClass == Int::class -> readFromBuffer(buffer, size, false).toInt().fixSign(size)
                    componentClass == Long::class -> readFromBuffer(buffer, size, false).toLong().fixSign(size)
                    componentClass == Float::class -> readFromBuffer(buffer, size, true).toFloat()
                    componentClass == Double::class -> readFromBuffer(buffer, size, true).toDouble()
                    componentClass == ByteArray::class -> ByteArray(size, { idx -> data[start + idx] })
                    componentClass == String::class -> {
                        val stringLength = (start until data.size)
                                .firstOrNull { data[it] == 0.toByte() }
                                ?.let { it - start }
                                ?: 0

                        if (size == 0) {
                            size = stringLength + 1 // one more for the '/u0000' char.
                        }
                        String(data, start, stringLength)
                    }
                    componentClass.isSubclassOf(StructData::class) -> {
                        @Suppress("UNCHECKED_CAST")
                        val structClass = componentClass as KClass<StructData>
                        val factory = structClass.factory
                        val (struct, structSize) = factory.fromByteArrayWithSize(
                                structClass,
                                data, buffer.order(), start
                        )

                        size = structSize
                        struct
                    }
                    else -> throw Exception("StructData does not support property class $componentClass")
                }

                start += abs(size)
            }
            dataClass.primaryConstructor!!.call(*componentValues) to (start - offset)
        }!!
    }

    internal fun deconstruct(dataObject: T) = deconstruct(dataObject::class, dataObject)

    internal fun deconstruct(dataObjectClass: KClass<out T>, dataObject: T? = null): Int = synchronized(sync) {
        initialize(dataObjectClass)

        dataClassComponents.forEachIndexed { index, function ->
            val value = if (dataObject != null) function!!.call(dataObject) else null

            buffer?.position(start)
            var size = abs(packingInfo[index])

            when (value) {
                is Number -> buffer?.let { writeToBuffer(value, it, size, (value is Float) || (value is Double)) }
                is ByteArray -> buffer?.put(value, 0, min(size, value.size))
                is String -> {
                    if (size == 0) {
                        size = value.length + 1
                    }
                    buffer?.put(value.toByteArray(), 0, min(size - 1, value.length))?.put(0)
                }
                is StructData -> {
                    val factory = value.factory
                    size = if (data != null) {
                        val order = buffer?.order() ?: BLE_DEFAULBLE_BYTE_ORDER
                        factory.toByteArray(value, data, order, start)
                    }
                    else {
                        factory.sizeOf(value)
                    }
                }
                else -> {
                    if (value == null) {
                        val valueClass = function!!.returnType.jvmErasure
                        when {
                            valueClass == String::class -> {
                                if (size == 0) size = 1
                            }
                            valueClass.isSubclassOf(StructData::class) -> {
                                @Suppress("UNCHECKED_CAST")
                                val structClass = valueClass as KClass<T>
                                val factory = structClass.factory
                                size = factory.sizeOf(valueClass = structClass)
                            }
                        }
                    } else {
                        throw Exception("StructData does not support property class ${value::class}")
                    }
                }
            }
            start += size
        }

        return start - offset
    }
}

abstract class StructData {
    interface Factory {
        fun sizeOf(value: StructData? = null, valueClass: KClass<out StructData>? = null): Int

        fun fromByteArray(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, offset: Int = 0): StructData

        fun fromByteArrayWithSize(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, offset: Int = 0): Pair<StructData,Int>

        fun toByteArray(value: StructData, bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, offset: Int = 0): Int

        val packingInfo: Array<Int>
    }

    abstract class DataFactory: Factory {
        override fun sizeOf(value: StructData?, valueClass: KClass<out StructData>?) = if (value != null) {
            DataClassAssembler<StructData>().deconstruct(value)
        } else {
            DataClassAssembler<StructData>().deconstruct(valueClass!!)
        }

        @Suppress("UNCHECKED_CAST")
        final override fun fromByteArray(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int) =
                fromByteArrayWithSize(dataClass, bytes, order, offset).first

        @Suppress("UNCHECKED_CAST")
        override fun fromByteArrayWithSize(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int): Pair<StructData,Int> =
                DataClassAssembler<StructData>(bytes, order, offset).construct(dataClass)

        override fun toByteArray(value: StructData, bytes: ByteArray, order: ByteOrder, offset: Int) =
                DataClassAssembler<StructData>(bytes, order, offset).deconstruct(value)
    }

    abstract class SealedFactory: Factory {
        @Suppress("UNCHECKED_CAST")
        final override fun fromByteArray(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int) =
                fromByteArrayWithSize(dataClass, bytes, order, offset).first
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : StructData> construct(dataClass: KClass<out T>, bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, offset: Int = 0): T =
                dataClass.factory.fromByteArray(dataClass, bytes, order, offset) as T
    }

    val structSize by lazy { factory.sizeOf(this) }

    fun deconstruct(bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, offset: Int = 0) =
            factory.toByteArray(this, bytes, order, offset)

    fun deconstruct(order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER): ByteArray {
        val compObj = factory
        val bytes = ByteArray(compObj.sizeOf(this))
        compObj.toByteArray(this, bytes, order)
        return bytes
    }
}

val StructData.factory get() = this::class.factory

@Suppress("UNCHECKED_CAST")
val KClass<out StructData>.factory get() : StructData.Factory {
    var struct: KClass<out StructData>? = this
    var compObj = struct?.factoryOrNull
    while (compObj == null && struct != null) {
        struct = struct.superclasses.asSequence()
                .firstOrNull { it.isSubclassOf(StructData::class) } as KClass<out StructData>?
        compObj = struct?.factoryOrNull
    }

    return compObj ?: throw NotImplementedError("Factory Companion Object of ${this} not found")
}

@Suppress("UNCHECKED_CAST")
private val KClass<out StructData>.factoryOrNull get() = this.companionObjectInstance  as? StructData.Factory

abstract class ArrayWithLength(
) : StructData() {
    abstract class Factory(val sizeSize: Int): DataFactory() {
        override fun sizeOf(value: StructData?, valueClass: KClass<out StructData>?) = when (value) {
            is ArrayWithLength -> with(value) { sizeSize + payload.size }
            else -> sizeSize
        }

        override fun toByteArray(value: StructData, bytes: ByteArray, order: ByteOrder, offset: Int) = with(value as ArrayWithLength) {
            toNumberByteArray(size.toShort(), bytes, order, offset, sizeSize)
            with(payload) {
                for (i in 0 until size) {
                    bytes[offset + sizeSize + i] = this[i]
                }
                sizeSize + size
            }
        }

        override fun fromByteArrayWithSize(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int): Pair<StructData, Int> {
            val size = toByteArrayNumber<Number>(sizeClass, bytes, order, offset, sizeSize).toInt().fixSign(sizeSize)
            val payload = bytes.sliceArray((offset + sizeSize) until (offset + sizeSize + size))
            return create(size, payload) to (sizeSize + size)
        }

        override val packingInfo = arrayOf(sizeSize, 0)

        val sizeClass: KClass<out Number> = when(sizeSize) {
            1 -> Byte::class
            2 -> Short::class
            4 -> Int::class
            8 -> Long::class
            else -> throw IllegalStateException("Invalid value for property sizeSize: $sizeSize")
        }

        protected abstract fun create(size: Int, payload: ByteArray): ArrayWithLength
    }

    abstract val size: Int
    abstract val payload: ByteArray

    override fun equals(other: Any?): Boolean {

        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArrayWithLength

        if (size != other.size) return false
        if (!Arrays.equals(payload, other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + Arrays.hashCode(payload)
        return result
    }
}

data class ArrayWithByteLength(
        override val size: Int = 0,
        override val payload: ByteArray = ByteArray(size)
) : ArrayWithLength() {
    companion object : ArrayWithLength.Factory(1) {
        override fun create(size: Int, payload: ByteArray) = ArrayWithByteLength(size, payload)
    }
}

data class ArrayWithShortLength(
        override val size: Int = 0,
        override val payload: ByteArray = ByteArray(size)
) : ArrayWithLength() {
    companion object : ArrayWithLength.Factory(2) {
        override fun create(size: Int, payload: ByteArray) = ArrayWithShortLength(size, payload)
    }
}

data class ArrayWithIntLength(
        override val size: Int = 0,
        override val payload: ByteArray = ByteArray(size)
) : ArrayWithLength() {
    companion object : ArrayWithLength.Factory(4) {
        override fun create(size: Int, payload: ByteArray) = ArrayWithIntLength(size, payload)
    }
}

data class ArrayWithLongLength(
        override val size: Int = 0,
        override val payload: ByteArray = ByteArray(size)
) : ArrayWithLength() {
    companion object : ArrayWithLength.Factory(8) {
        override fun create(size: Int, payload: ByteArray) = ArrayWithLongLength(size, payload)
    }
}

@Suppress("UNCHECKED_CAST")
fun <T : ArrayWithLength> ByteArray.withLength(sizeSize: Int): T = when (sizeSize) {
    1 -> ArrayWithByteLength(size, this)
    2 -> ArrayWithShortLength(size, this)
    4 -> ArrayWithIntLength(size, this)
    8 -> ArrayWithLongLength(size, this)
    else -> throw IllegalArgumentException("Invalid value for parameter sizeSize: $sizeSize")
} as T
