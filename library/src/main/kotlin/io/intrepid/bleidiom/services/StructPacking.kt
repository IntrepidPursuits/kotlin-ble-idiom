package io.intrepid.bleidiom.services

import kotlin.math.abs

const val INT8 = -1
const val UINT8 = 1
const val INT16 = -2
const val UINT16 = 2
const val INT32 = -4
const val UINT32 = 4
const val INT64 = -8
const val FLOAT = 4
const val DOUBLE = 8

@Suppress("NOTHING_TO_INLINE")
/**
 * Packing info item, specifying how a data item in a [StructData] is packed into a byte-array.
 * Construction one can be done by the following syntax:
 *
 *       4()     // Creates an unsigned 'int' packing with all 32 bits used
 *      -2(12)   // Creates a signed 'short' packing with only 12 of the 16 bits used.
 *       1(1)    // Often used for a one-bit boolean
 *
 * This is much like the packing-information in C/C++ structs:
 *
 *     typedef struct {
 *          uint32_t intNumber;         // StructPacking would be 4()
 *          int16_t  shortNumber;       // StructPacking would be -2()
 *          uint8_t  flag: 1;           // StructPacking would be 1(1)
 *          uint8_t  mask: 3;           // StructPacking would be 1(3)
 *          int16_t anotherShort: 12    // StructPacking would be -2(12)
 *     } MyStruct;
 *
 */
class StructPacking private constructor(val size: Int, bits: Int = 0) : Number() {
    companion object {
        // Pool often used packing-infos.
        private val EMPTY_PACKING = StructPacking(0, 0)
        private val UINT8_1 = StructPacking(1, 1)
        private val UINT8_2 = StructPacking(1, 2)
        private val UINT8_3 = StructPacking(1, 3)
        private val UINT8_4 = StructPacking(1, 4)
        private val UINT8_5 = StructPacking(1, 5)
        private val UINT8_6 = StructPacking(1, 6)
        private val UINT8_7 = StructPacking(1, 7)
        private val UINT8_0 = StructPacking(1)
        private val UINT16_0 = StructPacking(2)
        private val UINT32_0 = StructPacking(4)
        private val UINT64_0 = StructPacking(8)
        private val INT8_0 = StructPacking(-1)
        private val INT16_0 = StructPacking(-2)
        private val INT32_0 = StructPacking(-4)
        private val INT64_0 = StructPacking(-8)

        /**
         * Returns a [StructPacking] with the give byte- and bit-sizes
         * @param size byte-size
         * @param bits bit-size
         */
        operator fun invoke(size: Int, bits: Int = 0) = when (size) {
            0 -> EMPTY_PACKING
            1 -> {
                when (bits) {
                    0, 8 -> UINT8_0
                    1 -> UINT8_1
                    2 -> UINT8_2
                    3 -> UINT8_3
                    4 -> UINT8_4
                    5 -> UINT8_5
                    6 -> UINT8_6
                    7 -> UINT8_7
                    else -> StructPacking(size, bits)
                }
            }
            2 -> if (bits % 16 == 0) UINT16_0 else StructPacking(size, bits)
            4 -> if (bits % 32 == 0) UINT32_0 else StructPacking(size, bits)
            8 -> if (bits % 64 == 0) UINT64_0 else StructPacking(size, bits)
            -1 -> if (bits % 8 == 0) INT8_0 else StructPacking(size, bits)
            -2 -> if (bits % 16 == 0) INT16_0 else StructPacking(size, bits)
            -4 -> if (bits % 32 == 0) INT32_0 else StructPacking(size, bits)
            -8 -> if (bits % 64 == 0) INT64_0 else StructPacking(size, bits)
            else -> StructPacking(size, bits)
        }
    }

    val numberOfBits = {
        val maxSize = kotlin.math.abs(size * 8)
        when {
            bits < 0 -> throw IllegalArgumentException("Parameter bits must be value between 0..$maxSize: $bits")
            bits == 0 -> maxSize
            bits <= maxSize -> bits
            else -> throw IllegalArgumentException("Parameter bits must be less or equal than $maxSize: $bits")
        }
    }()

    val numberOfBytes = if (numberOfBits > 0) (1 + (numberOfBits - 1) / 8) else 0

    override fun toByte() = numberOfBytes.toByte()
    override fun toChar() = numberOfBytes.toChar()
    override fun toDouble() = numberOfBytes.toDouble()
    override fun toFloat() = numberOfBytes.toFloat()
    override fun toInt() = numberOfBytes
    override fun toLong() = numberOfBytes.toLong()
    override fun toShort() = numberOfBytes.toShort()

    /**
     * Returns a [StructPacking] whose sign of [size] has switched, switching from unsigned to signed and vice-versa.
     * @return StructPacking with its sign switched.
     */
    inline operator fun unaryMinus() = if (size != 0) StructPacking.invoke(-size, numberOfBits) else this

    /**
     * Adds this and another [StructPacking] and returns the result.
     */
    inline operator fun plus(other: Number): StructPacking {
        val bits = numberOfBits +
                ((other as? StructPacking)?.numberOfBits ?: abs(other.toInt()) * 8)
        val bytes = if (bits > 0) 1 + (bits - 1) / 8 else 0
        return StructPacking.invoke(bytes, bits)
    }

    /**
     * Returns a [StructPacking] whose sign of [size] is non-negative, returning an unsigned packing.
     */
    inline fun abs() = if (size < 0) StructPacking.invoke(abs(size), numberOfBits) else this

    /**
     * Compares the number of total bits in this packing.
     */
    inline operator fun compareTo(other: Any) = when (other) {
        is StructPacking -> numberOfBits.compareTo(other.numberOfBits)
        is Number -> numberOfBits.compareTo(abs(other.toInt()) * 8)
        else -> throw IllegalArgumentException("other is not a Number: $other")
    }

    override fun equals(other: Any?) =
            (other is StructPacking && size == other.size && numberOfBits == other.numberOfBits)

    override fun hashCode(): Int {
        var result = numberOfBits
        result = 31 * result + size
        return result
    }

    override fun toString() = "$size($numberOfBits)"

    internal inline fun checkInt() = if (numberOfBits % 8 == 0) {
        numberOfBytes
    } else {
        throw IllegalStateException("numberOfBits is not a multiple of 8: $numberOfBits")
    }
}

@Suppress("NOTHING_TO_INLINE")
/**
 * 'This' Number is the number of bytes that stores the value.
 *
 * This extension function will allow constructs like this:
 *
 *      4()   // will create a packing of 4 bytes (int) using all 32 bits
 *      2(12) // will create a packing of 2 bytes (short) using only 12 of the 16 bits.
 *
 * @param bits The number of actual bits (less or equal to this * 8) that this value needs to use.
 */
inline operator fun Number.invoke(bits: Int = 0) = when {
    this is StructPacking -> if (bits == 0) this else StructPacking(size, bits)
    else -> StructPacking(toInt(), bits)
}