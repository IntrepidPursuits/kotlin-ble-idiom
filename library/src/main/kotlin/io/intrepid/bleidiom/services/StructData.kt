package io.intrepid.bleidiom.services

import arrow.core.Option
import arrow.core.getOrElse
import arrow.syntax.option.none
import arrow.syntax.option.some
import arrow.syntax.option.toOption
import io.intrepid.bleidiom.*
import io.intrepid.bleidiom.services.StructData.Companion.construct
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure

/**
 * Transforms a [ByteArray] into an array of numbers ([T] is either [Byte], [Short], [Int], [Long], [Float] or [Double])
 *
 * @param order The endianness of the byte-array.
 * @return The array of numbers
 */
inline fun <reified T : Number> ByteArray.bytesToNumbers(order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER): Array<T> {
    val buffer = ByteBuffer.wrap(this).order(order)
    return constructFromBytes(size, numberByteArraySize(T::class)) {
        when(T::class) {
            Byte::class -> buffer.get(it) as T
            Short::class -> buffer.getShort(it) as T
            Int::class -> buffer.getInt(it) as T
            Long::class -> buffer.getLong(it) as T
            Float::class -> buffer.getFloat(it) as T
            Double::class -> buffer.getDouble(it) as T
            else -> buffer.getInt(it) as T
        }
    }
}

/**
 * Transforms a [ByteArray] into an array of [StructData] instances. The [T] parameter must be a specific
 * non-abstract subclass of StructData. The instances in the returned array will all be of type [T].
 *
 * @param T The type of [StructData] instances to put into the array.
 * @param order The endianness of the byte-array.
 * @return The array of instances of type [T].
 */
inline fun <reified T : StructData> ByteArray.bytesToStructs(order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER) =
        constructFromBytes(size, T::class.structDataSize) { offset ->
            StructData.construct(T::class, this, order, offset)
        }

/**
 * Transforms an array of numbers - all of type [T] - into a [ByteArray]
 *
 * @param order The endianness of the byte-array
 * @return The byte-array containing the values of this array of number.
 */
inline fun <reified T : Number> Array<T>.numbersToBytes(order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER): ByteArray {
    var byteBuffer: ByteBuffer? = null

    return destructToBytes(numberByteArraySize(T::class)) { array, offset, item ->
        if (byteBuffer == null) {
            byteBuffer = ByteBuffer.wrap(array).order(order)
        }
        val buffer = byteBuffer!!

        when(T::class) {
            Byte::class -> buffer.put(offset, item as Byte)
            Short::class -> buffer.putShort(offset, item as Short)
            Int::class -> buffer.putInt(offset, item as Int)
            Long::class -> buffer.putLong(offset, item as Long)
            Float::class -> buffer.putFloat(offset, item as Float)
            Double::class -> buffer.putDouble(offset, item as Double)
            else -> buffer.putInt(offset, item as Int)
        }
    }
}

/**
 * Transforms an array of [StructData] - all of type [T] - into a [ByteArray]
 *
 * @param order The endianness of the byte-array
 * @return The byte-array containing the values of this array of StructDatas.
 */
inline fun <reified T : StructData> Array<T>.structsToBytes(order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER): ByteArray {
    return destructToBytes(T::class.structDataSize) { array, offset, item ->
        item.deconstruct(array, order, offset)
    }
}

/**
 * Should not be called directly from an outside client
 */
inline fun <reified T> constructFromBytes(arraySize: Int, itemSize: Int, constructor: (Int) -> T): Array<T> {
    return Array(arraySize / itemSize) { constructor(it * itemSize) }
}

/**
 * Should not be called directly from an outside client
 */
inline fun <reified T> Array<T>.destructToBytes(itemSize: Int, destructor: (ByteArray, Int, T) -> Unit): ByteArray {
    val array = ByteArray(size * itemSize)
    forEachIndexed { i, item -> destructor(array, i * itemSize, item) }
    return array
}

/**
 * Should not be called directly from an outside client
 */
val KClass<out StructData>.structDataSize: Int
    get() {
        val infoSize = factory.size
        return if (infoSize != 0) infoSize
        else {
            val calcSize = DataClassAssembler<StructData>().deconstruct(dataObjectClass = this)
            if (calcSize != 0) calcSize
            else throw IllegalStateException("${this.simpleName} elements appear in a " +
                    "StructArray. Their size cannot be dynamic.")
        }
    }

@Suppress("NOTHING_TO_INLINE")
/**
 * This is the base-class of data-types that need to be able to be constructed from a flat [ByteArray]
 * and deconstructed into flat a [ByteArray].
 *
 * It is called StructData, since it mimics the C/C++ packing of fields in structs.
 *
 * Properties in StructData instances have packing-information, instructing the [construct] and
 * [deconstruct] methods how to unpack or pack each StructData instance.
 *
 * E.g. say this is a C struct
 *
 *      typedef struct
 *      {
 *          uint32_t number;
 *          int16_t minValue;
 *          uint8_t flag:1;
 *          uint8_t flag:2;
 *          uint8_t depth:6;
 *          char title;
 *      } SomeDataType;
 *
 * could be represented by a StructData as follows
 *
 *     data class SomeDataType(
 *          val number: Int,
 *          val minValue: Int,
 *          val flag1: Boolean, val flag2: Boolean,
 *          val depth: Int,
 *          val title: String
 *     ): StructData() {
 *          companion object: StructData.DataFactory {
 *              override val packingInfo = array(
 *                  4,      Unsigned int (size in bytes is positive)
 *                  -2,     Signed short (size in bytes is negative)
 *                  1(1),   1 bit flag
 *                  1(1),   1 bit flag
 *                  1(6),   6 bit unsigned value
 *                  0       String determines its own length (looks for terminating 0-byte).
 *          }
 *     }
 *
 *     val someData = StructData.construct(SomeDataType::class, byteArray)
 *     ...
 *     val byteData = someData.deconstruct()
 *
 * These are the types that are supported as properties of [StructData] (data) classes.
 *
 * * [Byte] packing-size can be 1 or -1 with bit-size from 1 through 8
 * * [Short] packing-size can be 1, 2, -1 or -2 with bit-size from 1 through 16
 * * [Int] packing-size can be 1, 2, 4 or -1, -2, -4 with bit-size from 1 through 32
 * * [Long] packing-size can be 1, 2, 4 or -1, -2, -4, -8 with bit-size from 1 through 64
 * * [Double] packing-size must be -4, bit-size is not used.
 * * [Float] packing-size must be -8, bit-size is not used.
 * * [Boolean] packing-size and bit-size can be anyone that are valid for Byte, Short, Int or Long.
 *   The value read or written will be '0' for false, and '1' for true.
 * * [Enum] packing-size and bit-size can be anyone that are valid for Byte, Short, Int or Long.
 *   The value read or written will either by the [Enum.ordinal] or its public '`value: Int`' property
 *   if it has one.
 * * [ByteArray] If packing-size is set to a positive number, this will be the fixed size of the byte-array.
 *   The byte-array's length will be limited by the available data in the provided input or output-array.
 *   If the packing-size is set to 0, the byte-array will consume all data up to the end of the provided
 *   input- or output-array. The bit-size is ignored.
 * * [String] UTF-8. If the packing-size is set to a positive number, the string will be truncated to that length.
 *   If the packing-size is set to 0, the string's length will be determined by the terminating `\u00`
 *   character
 * * [StructData] If the packing-size is 0, the actual size in bytes will be calculated.
 *   If the packing-size is positive, the actual size will not be calculated. This can speed up
 *   the construction and destruction of StructData. Be sure to supply the correct size, otherwise
 *   the behavior may become undefined.
 */
abstract class StructData {
    /**
     * Base for any factory handling StructData construction and destruction.
     * It provides info for the size of a structure.
     *
     * The StructData's construction and destruction will look at a sub-class'
     * '`companion object`' and examines the interface that the companion-object implements/extends.
     * Based on that, the StructData knows what to do.
     */
    interface PackingInfo {
        /**
         * Holds the size in bytes that can represent all values of the class implementing this interface.
         * If it is 0, the size is unknown.
         */
        val size: Int
    }

    /**
     * Base for all structures that have one or more sub-fields.
     * Sub-fields are ordered and the packing-information (byte-size and bit-size) must be provided
     * for each sub-field in the same order (see [packingInfo])
     */
    interface Factory : PackingInfo {
        /**
         * Returns the calculated size of the given StructData class or instance.
         *
         *
         * If the [value] is not null, the size in bytes of the [value] is returned.
         *
         * If it is null, the size in bytes of a typical [valueClass] will be returned. Note that this could be 0 (unknown).
         *
         * @param value StructData instance.
         * @param valueClass StructData subclass
         * @return Size, in bytes, of the given value or class.
         */
        fun sizeOf(value: StructData? = null, valueClass: KClass<out StructData>? = null): Int = size

        /**
         * Returns a instance of class [dataClass] that is constructed from the given byte-array.
         *
         * @param dataClass The class of the instance to construct.
         * @param bytes the byte-array from which the instance will be constructed.
         * @param order The endianness of the byte-array (defaults to [ByteOrder.LITTLE_ENDIAN])
         * @param offset The start-position of the byte-array from which data will be read (defaults to 0)
         * @return An instance of class [dataClass]
         */
        fun fromByteArray(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, offset: Int = 0) =
                fromByteArrayWithSize(dataClass, bytes, order, offset).first

        /**
         * Returns a instance of class [dataClass], and its size in bytes,
         * that is constructed from the given byte-array.
         *
         * @param dataClass The class of the instance to construct.
         * @param bytes the byte-array from which the instance will be constructed.
         * @param order The endianness of the byte-array (defaults to [ByteOrder.LITTLE_ENDIAN])
         * @param offset The start-position of the byte-array from which data will be read (defaults to 0)
         * @return An instance of class [dataClass] with its size
         */
        fun fromByteArrayWithSize(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, offset: Int = 0): Pair<StructData, Int>

        /**
         * Fills a byte-array with the data of [value] and returns the number of bytes written.
         *
         * @param value The StructData instance to deconstruct into the given byte-array.
         * @param bytes the byte-array into which the instance will be written.
         * @param order The endianness of the byte-array (defaults to [ByteOrder.LITTLE_ENDIAN])
         * @param offset The start-position of the byte-array to which data will be written (defaults to 0)
         * @return The number of bytes written.
         */
        fun toByteArray(value: StructData, bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, offset: Int = 0): Int

        override val size: Int
            // Calculate the sum of all the packing-info's byte-sizes. But if one of them is 0,
            // the entire size will be 0 (unknown).
            get() = packingInfo
                    .fold(0().some()) { packingSum, size ->
                        if (size.toInt() == 0) none() else packingSum.map { it + size.abs() }
                    }
                    .getOrElse { 0() }
                    .toInt()

        /**
         * The packing-information for each property of subclasses of this StructData.
         * The values in this number-array can be either any Number (but will be interpreted as an Int)
         * or it can be a [StructPacking] instance allowing for both a byte-size and a bit-size value.
         */
        val packingInfo: Array<Number>
    }

    /**
     * Factory for a StructData sub-class that has no properties at all.
     */
    abstract class NoDataFactory : Factory {
        final override fun sizeOf(value: StructData?, valueClass: KClass<out StructData>?) = 0

        @Suppress("UNCHECKED_CAST")
        final override fun fromByteArrayWithSize(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int): Pair<StructData, Int> =
                dataClass.primaryConstructor!!.call() to 0

        final override fun toByteArray(value: StructData, bytes: ByteArray, order: ByteOrder, offset: Int) = 0

        final override val size = 0
        final override val packingInfo = arrayOf<Number>()
    }

    /**
     * Factory for a StructData sub-class that is a so-called Kotlin '`data class`'.
     */
    interface DataFactory : Factory {
        override fun sizeOf(value: StructData?, valueClass: KClass<out StructData>?) = if (value != null) {
            DataClassAssembler<StructData>().deconstruct(value)
        } else {
            DataClassAssembler<StructData>().deconstruct(valueClass!!)
        }

        @Suppress("UNCHECKED_CAST")
        override fun fromByteArrayWithSize(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int): Pair<StructData, Int> =
                DataClassAssembler<StructData>(bytes, order, offset).construct(dataClass)

        override fun toByteArray(value: StructData, bytes: ByteArray, order: ByteOrder, offset: Int) =
                DataClassAssembler<StructData>(bytes, order, offset).deconstruct(value)
    }

    companion object {
        /**
         * Returns a instance of class [dataClass] that is constructed from the given byte-array.
         *
         * @param dataClass The class of the instance to construct.
         * @param bytes the byte-array from which the instance will be constructed.
         * @param order The endianness of the byte-array (defaults to [ByteOrder.LITTLE_ENDIAN])
         * @param offset The start-position of the byte-array from which data will be read (defaults to 0)
         * @return An instance of class [dataClass]
         */
        @Suppress("UNCHECKED_CAST")
        fun <T : StructData> construct(dataClass: KClass<out T>, bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, offset: Int = 0): T =
                dataClass.factory.fromByteArray(dataClass, bytes, order, offset) as T
    }

    val structSize by lazy { factory.sizeOf(this) }

    /**
     * Fills a byte-array with the data of this StructData and returns the number of bytes written.
     *
     * @param bytes the byte-array into which the instance will be written.
     * @param order The endianness of the byte-array (defaults to [ByteOrder.LITTLE_ENDIAN])
     * @param offset The start-position of the byte-array to which data will be written (defaults to 0)
     * @return The number of bytes written.
     */
    inline fun deconstruct(bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, offset: Int = 0) =
            factory.toByteArray(this, bytes, order, offset)

    /**
     * Returns a byte-array with the data of this StructData.
     *
     * @param order The endianness of the byte-array (defaults to [ByteOrder.LITTLE_ENDIAN])
     * @return The byte-array containing the data of this StructData.
     */
    fun deconstruct(order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER): ByteArray {
        val compObj = factory
        val bytes = ByteArray(compObj.sizeOf(this))
        compObj.toByteArray(this, bytes, order)
        return bytes
    }
}

/**
 * Should not be called directly from an outside client
 */
inline val StructData.factory get() = this::class.factory

/**
 * Should not be called directly from an outside client
 */
@Suppress("UNCHECKED_CAST")
val KClass<out StructData>.factory
    get() =
        factoryInfoFromClass(this)
                ?: throw NotImplementedError("Factory Companion Object of ${this} not found")

/**
 * Represents a [StructData] that contains the length of a byte-array and then
 * the byte array itself. In C/C++
 *
 *     typedef struct {
 *         int size;
 *         char payload[size];
 *     }
 *
 * where size of '`size`' is defined by the [ArrayWithLength.Factory.sizeSize] (1, 2 or 4 bytes)
 *
 * @param size The size in bytes of the [payload]
 */
abstract class ArrayWithLength(private val size: Number) : StructData() {
    abstract class Factory(private val sizeSize: Int) : DataFactory {
        override fun sizeOf(value: StructData?, valueClass: KClass<out StructData>?) = when (value) {
            is ArrayWithLength -> with(value) { sizeSize + payload.size }
            else -> sizeSize
        }

        override fun toByteArray(value: StructData, bytes: ByteArray, order: ByteOrder, offset: Int) = with(value as ArrayWithLength) {
            toNumberByteArray(size, bytes, order, offset, sizeSize)
            with(payload) {
                System.arraycopy(this, 0, bytes, offset + sizeSize, size)
                sizeSize + size
            }
        }

        override fun fromByteArrayWithSize(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int): Pair<StructData, Int> {
            val size = toByteArrayNumber<Number>(sizeClass, bytes, order, offset, sizeSize).toInt().fixSign(sizeSize)
            val payload = bytes.sliceArray((offset + sizeSize) until (offset + sizeSize + size))
            return create(size, payload) to (sizeSize + size)
        }

        override val packingInfo = arrayOf<Number>(sizeSize, 0)

        private val sizeClass: KClass<out Number> = when (sizeSize) {
            UINT8 -> Byte::class
            UINT16 -> Short::class
            UINT32 -> Int::class
            else -> throw IllegalStateException("Invalid value for property sizeSize: $sizeSize")
        }

        protected abstract fun create(size: Int, payload: ByteArray): ArrayWithLength
    }

    abstract val payload: ByteArray

    final override fun equals(other: Any?): Boolean {
        if (this === other) return true

        other as ArrayWithLength

        if (size != other.size) return false
        if (!Arrays.equals(payload, other.payload)) return false

        return true
    }

    final override fun hashCode(): Int {
        var result = size.toInt()
        result = 31 * result + Arrays.hashCode(payload)
        return result
    }
}

/**
 * Represents a [StructData] whose equivalent in C/C++ is
 *
 *     typedef struct {
 *         uint8_t size;
 *         char payload[size];
 *     } Array;
 *
 * @param payload The byte-array.
 */
@Suppress("ArrayInDataClass") // equals/hashCode are implemented in base class
data class ArrayWithByteLength(
        override val payload: ByteArray = ByteArray(0)
) : ArrayWithLength(payload.size.toByte()) {
    companion object : ArrayWithLength.Factory(UINT8) {
        override fun create(size: Int, payload: ByteArray) = ArrayWithByteLength(payload)
    }
}

/**
 * Represents a [StructData] whose equivalent in C/C++ is
 *
 *     typedef struct {
 *         uint16_t size;
 *         char payload[size];
 *     } Array;
 *
 * @param payload The byte-array.
 */
@Suppress("ArrayInDataClass") // equals/hashCode are implemented in base class
data class ArrayWithShortLength(
        override val payload: ByteArray = ByteArray(0)
) : ArrayWithLength(payload.size.toShort()) {
    companion object : ArrayWithLength.Factory(UINT16) {
        override fun create(size: Int, payload: ByteArray) = ArrayWithShortLength(payload)
    }
}

/**
 * Represents a [StructData] whose equivalent in C/C++ is
 *
 *     typedef struct {
 *         uint32_t size;
 *         char payload[size];
 *     } Array;
 *
 * @param payload The byte-array.
 */
@Suppress("ArrayInDataClass") // equals/hashCode are implemented in base class
data class ArrayWithIntLength(
        override val payload: ByteArray = ByteArray(0)
) : ArrayWithLength(payload.size) {
    companion object : ArrayWithLength.Factory(UINT32) {
        override fun create(size: Int, payload: ByteArray) = ArrayWithIntLength(payload)
    }
}

/**
 * Returns the appropriate subclass of [ArrayWithLength] based on the size of the '`size`' property.
 */
@Suppress("UNCHECKED_CAST")
fun <T : ArrayWithLength> ByteArray.withLength(sizeSize: Int): T = when (sizeSize) {
    UINT8 -> ArrayWithByteLength(this)
    UINT16 -> ArrayWithShortLength(this)
    UINT32 -> ArrayWithIntLength(this)
    else -> throw IllegalArgumentException("Invalid value for parameter sizeSize: $sizeSize")
} as T

@Suppress("NOTHING_TO_INLINE")
private inline fun Number.abs(): StructPacking = when {
    this is StructPacking -> this.abs()
    else -> StructPacking(abs(toInt()))
}

private const val COMPONENT = "component"

@Suppress("NOTHING_TO_INLINE")
private inline fun readFromBuffer(buffer: ReadOnlyStructBuffer, start: StructPacking, size: StructPacking, isFloat: Boolean): Number =
        with(buffer[start, size]) {
            when (abs(size.size)) {
                UINT8 -> byte
                UINT16 -> short
                4 -> if (isFloat) float else int
                8 -> if (isFloat) double else long
                else -> throw Exception("Size must be 1, 2, 4 or 8 but is $size instead.")
            }
        }


@Suppress("NOTHING_TO_INLINE")
private inline fun writeToBuffer(value: Number, buffer: WritableStructBuffer, start: StructPacking, size: StructPacking, isFloat: Boolean) =
        with(buffer[start, size]) {
            when (size.size) {
                UINT8 -> byte = value.toByte()
                UINT16 -> short = value.toShort()
                4 -> if (isFloat) float = value.toFloat() else int = value.toInt()
                8 -> if (isFloat) double = value.toDouble() else long = value.toLong()
                else -> throw Exception("Size must be 1, 2, 4 or 8 but is $size instead.")
            }
        }

@Suppress("NOTHING_TO_INLINE")
private class DataClassAssembler<T : StructData>(val data: ByteArray? = null, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER, val offset: Int = 0) {
    val buffer = (if (data != null) StructBuffer.wrap(data, order) else null)
    var start = -1()

    private lateinit var packingInfo: Array<StructPacking>
    private lateinit var dataClassComponents: Array<KFunction<*>>

    private val sync = data ?: Any()

    private fun initialize(dataClass: KClass<out T>) {
        if (!dataClass.isData) {
            throw Exception("This is not a data class: $dataClass")
        }

        val (packing, components) = dataClassInfoFromClass(dataClass)

        packingInfo = packing!!
        dataClassComponents = components!!

        start = offset()

        if (packingInfo.size != dataClassComponents.size) {
            throw IllegalStateException("Sizes of packingInfo(${packingInfo.size}) and " +
                    "data-class components(${dataClassComponents.size}) are different " +
                    "for class $dataClass"
            )
        }
    }

    internal fun construct(dataClass: KClass<out T>): Pair<T, Int> = synchronized(sync) {
        letMany(data, buffer?.asReadOnly()) { data, buffer ->
            initialize(dataClass)

            val componentValues = arrayOfNulls<Any?>(dataClassComponents.size)

            dataClassComponents.forEachIndexed { index, function ->
                val returnType = function.returnType
                val componentClass = returnType.jvmErasure

                var size = calcSize(packingInfo[index], componentClass, data.size - start.toInt())
                val signSize = size.size

                componentValues[index] = when {
                    componentClass == Byte::class -> readFromBuffer(buffer, start, size, false).toByte().fixSign(signSize)
                    componentClass == Short::class -> readFromBuffer(buffer, start, size, false).toShort().fixSign(signSize)
                    componentClass == Int::class -> readFromBuffer(buffer, start, size, false).toInt().fixSign(signSize)
                    componentClass == Long::class -> readFromBuffer(buffer, start, size, false).toLong().fixSign(signSize)
                    componentClass == Float::class -> readFromBuffer(buffer, start, size, true).toFloat()
                    componentClass == Double::class -> readFromBuffer(buffer, start, size, true).toDouble()
                    componentClass == Boolean::class -> readFromBuffer(buffer, start, size, false).toInt() != 0
                    componentClass == ByteArray::class -> ByteArray(signSize) { idx ->
                        val byteStart = start.checkInt()
                        if (byteStart + idx < data.size) data[byteStart + idx]
                        else 0
                    }
                    componentClass.isSubclassOf(Enum::class) -> {
                        val enumValue = readFromBuffer(buffer, start, size, false).toInt().fixSign(abs(signSize))
                        try {
                            @Suppress("UNCHECKED_CAST")
                            enumValue.toEnum(componentClass as KClass<out Enum<*>>)
                        } catch (e: Exception) {
                            if (returnType.isMarkedNullable) null
                            else throw Exception("StructData could not get enum-value $componentClass[$enumValue]", e)
                        }
                    }
                    componentClass == String::class -> {
                        val byteStart = start.checkInt()
                        val stringLength = (byteStart until data.size)
                                .firstOrNull { data[it] == 0.toByte() }
                                ?.let { it - byteStart }
                                ?: 0

                        if (size == 0()) {
                            size = stringLength() + 1 // one more for the '/u0000' char.
                        }
                        String(data, byteStart, stringLength)
                    }
                    componentClass.isSubclassOf(StructData::class) -> {
                        @Suppress("UNCHECKED_CAST")
                        val structClass = componentClass as KClass<StructData>
                        val factory = structClass.factory
                        val (struct, structSize) = factory.fromByteArrayWithSize(
                                structClass,
                                data, buffer.byteOrder(), start.checkInt()
                        )

                        size = structSize()
                        struct
                    }
                    else -> {
                        if (returnType.isMarkedNullable) null
                        else throw Exception("StructData does not support property class $componentClass")
                    }
                }

                start += size.abs()
            }
            dataClass.primaryConstructor!!.call(*componentValues) to (start.checkInt() - offset)
        }!!
    }

    internal inline fun deconstruct(dataObject: T) = deconstruct(dataObject::class, dataObject)

    internal fun deconstruct(dataObjectClass: KClass<out T>, dataObject: T? = null): Int = synchronized(sync) {
        initialize(dataObjectClass)

        dataClassComponents.forEachIndexed { index, function ->
            val value = if (dataObject != null) function.call(dataObject) else null

            var size = calcSize(packingInfo[index].abs(), value)
            val signSize = size.toInt()

            val buffer = buffer?.asWritable()

            when (value) {
                is Number -> buffer?.let { writeToBuffer(value, it, start, size, (value is Float) || (value is Double)) }
                is Boolean -> buffer?.let { writeToBuffer(if (value) 1 else 0, it, start, size, false) }
                is ByteArray -> buffer?.get(start)?.put(value, 0, min(signSize, value.size))
                is Enum<*> -> buffer?.let { writeToBuffer(value.value(), it, start, size, false) }
                is String -> {
                    if (size == 0()) {
                        size = value.length() + 1
                    }
                    val bytes = value.toByteArray()
                    val length = min(size.toInt() - 1, bytes.size)
                    buffer?.let {
                        it[start].put(bytes, 0, length)
                        it[start + length].byte = 0
                    }
                }
                is StructData -> {
                    val factory = value.factory
                    val structSize = if (data != null) {
                        val order = buffer?.byteOrder() ?: BLE_DEFAULBLE_BYTE_ORDER
                        factory.toByteArray(value, data, order, start.checkInt())
                    } else {
                        factory.sizeOf(value)
                    }
                    size = structSize()
                }
                else -> {
                    if (value == null) {
                        val valueClass = function.returnType.jvmErasure
                        val valueSize = calcSize(size, valueClass, 0)
                        when {
                            valueSize != 0() -> {
                                size = valueSize
                            }
                            valueClass == String::class -> {
                                if (size == 0()) size = 1()
                            }
                            valueClass.isSubclassOf(StructData::class) -> {
                                @Suppress("UNCHECKED_CAST")
                                val structClass = valueClass as KClass<T>
                                val factory = structClass.factory
                                size = factory.sizeOf(valueClass = structClass)()
                            }
                        }
                    }
                }
            }
            start += size
        }

        return start.checkInt() - offset
    }

    private inline fun calcSize(size: StructPacking, value: Any?): StructPacking {
        return when {
            size.size != 0 -> size()
            value is ByteArray -> value.size()
            else -> value?.packingInfo?.size?.let { it() } ?: 0()
        }
    }

    private inline fun calcSize(size: StructPacking, klass: KClass<out Any>, available: Int): StructPacking {
        return when {
            size.size != 0 -> size
            klass == ByteArray::class -> available()
            else -> klass.packingInfo?.size?.let { it() } ?: 0()
        }
    }
}

@Suppress("ArrayInDataClass")
private data class DataClassInfo(
        val packing: Array<StructPacking>?,
        val components: Array<KFunction<*>>?
)

private data class EnumClassInfo(
        val enumMap: MutableMap<Int, Enum<*>> = hashMapOf(),
        val valueMap: MutableMap<Enum<*>, Int> = hashMapOf()
) {
    operator fun plusAssign(item: Pair<Int, Enum<*>>) {
        enumMap[item.first] = item.second
        valueMap[item.second] = item.first
    }
}

private val packingInfoMap = ConcurrentHashMap<KClass<*>, Option<StructData.PackingInfo>>()
private val dataClassInfoMap = ConcurrentHashMap<KClass<*>, DataClassInfo>()
private val enumInfoMap = ConcurrentHashMap<KClass<*>, EnumClassInfo>()

@Suppress("UNCHECKED_CAST")
private fun packingInfoFromClass(klass: KClass<out Any>) = packingInfoMap.getOrPut(klass) {
    val packingInfo = klass.companionObjectInstance as? StructData.PackingInfo
    packingInfo?.some() ?: factoryInfoFromClass(klass as KClass<out StructData>).toOption()
}.getOrElse { null }

@Suppress("UNCHECKED_CAST")
private fun factoryInfoFromClass(klass: KClass<out StructData>) = packingInfoMap.getOrPut(klass) {
    var struct: KClass<out StructData>? = klass
    var compObj = struct?.factoryOrNull
    while (compObj == null && struct != null) {
        struct = struct.superclasses
                .firstOrNull { it.isSubclassOf(StructData::class) } as KClass<out StructData>?
        compObj = struct?.factoryOrNull
    }

    Option.fromNullable(compObj)
}.getOrElse { null } as? StructData.Factory

@Suppress("UNCHECKED_CAST")
private inline val KClass<out StructData>.factoryOrNull
    get() = this.companionObjectInstance  as? StructData.Factory

private inline val Any.packingInfo get() = this::class.packingInfo

private inline val KClass<out Any>.packingInfo get() = packingInfoFromClass(this)

private fun <T : StructData> dataClassInfoFromClass(dataClass: KClass<out T>) = dataClassInfoMap.getOrPut(dataClass) {
    val packingNumbers = dataClass.factory.packingInfo
    val packing = Array(packingNumbers.size) { packingNumbers[it]() }
    val components = dataClass.memberFunctions
            .asSequence()
            .filter { it.isOperator }
            .map {
                with(it.name) {
                    val i = indexOf(COMPONENT)
                    val idx = if (i == 0) substring(i + COMPONENT.length).toInt() else -1
                    idx to it
                }
            }
            .filter { it.first >= 0 }
            .sortedBy { it.first }
            .map { it.second }
            .toList()
            .toTypedArray()

    DataClassInfo(packing, components)
}

@Suppress("NOTHING_TO_INLINE")
private inline fun Enum<*>.value() = this::class.enumInfoFromClass.valueMap[this]!!

@Suppress("NOTHING_TO_INLINE")
private inline fun Int.toEnum(enumClass: KClass<out Enum<*>>) =
        enumClass.enumInfoFromClass.enumMap[this]!!

private const val VALUE_PROP_NAME = "value"

private val KClass<out Enum<*>>.enumInfoFromClass
    get() = enumInfoMap.getOrPut(this) {
        val map = EnumClassInfo()

        @Suppress("UNCHECKED_CAST")
        val valueProp = declaredMemberProperties.firstOrNull { it.name == VALUE_PROP_NAME }
                as KProperty1<in Enum<*>, Int>?

        java.enumConstants.asSequence()
                .map { (valueProp?.get(it) ?: it.ordinal) to it }
                .fold(map) { acc, pair -> acc += pair; acc }
    }