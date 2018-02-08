package io.intrepid.bleidiom.services

import at.favre.lib.bytes.Bytes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Implementations of this interface should provide read and write access to
 * Byte, Short, Int, Longs, Float, Double and ByteArray members from a [StructData].
 * For the Numbers, then endianness for reading and writing them must be honored.
 */
internal interface StructBufferAccess {
    var byte: Byte
    var short: Short
    var int: Int
    var long: Long

    var float: Float
    var double: Double
    fun put(array: ByteArray, start: Int, length: Int)
}

private const val SHIFT_OVERFLOW_SIZE = 1

internal typealias ReadOnlyStructBuffer = StructBuffer
internal typealias WritableStructBuffer = StructBuffer

/**
 * A representation of a byte-array/buffer from which [StructData] can be read or written.
 */
internal class StructBuffer private constructor(
        private val array: ByteArray,
        private val order: ByteOrder,
        private val offset: Int
) : StructBufferAccess {
    companion object {
        /**
         * @param array The ByteArray that this StructData is read from or writing to.
         * @param order Determines the endianness of the [array]
         * @param offset The offset within the given [array], the start-position from which data
         */
        fun wrap(array: ByteArray, order: ByteOrder, offset: Int = 0): StructBuffer {
            return StructBuffer(array, order, offset)
        }
    }

    private val buffer by lazy { ByteBuffer.wrap(array, offset, array.size - offset).order(order) }

    /**
     * Returns a view/window of this [StructBufferAccess] that starts on the given [start] position
     * and has a length determined by the [size] and is able to handle values that could be shifted
     * by bits, i.e. values that don't fill full-bytes or values that don't start at bit-number 0.
     *
     * @param start The start of the sub-buffer the is returned
     * Note that only its [StructPacking.numberOfBits] is used.
     * @param size The size of the sub-buffer that is returned
     * Note that it needs to specify a proper packing, where [StructPacking.size] is not 0.
     * @return A [StructBufferAccess], a sub-buffer, a view/window on the underlying buffer of this instance.
     */
    internal operator fun get(start: StructPacking, size: StructPacking = 0(0)): StructBufferAccess {
        val packingSize = abs(size.size)
        val shift = start.numberOfBits % 8

        val isPartiallyPacked = (packingSize * 8 != size.numberOfBits)
        val isOnByteBoundary = (shift == 0)

        return if (!isOnByteBoundary || isPartiallyPacked) {
            val subBufferStart = offset + (start.numberOfBits / 8)
            val subBuffer = ByteArray(SHIFT_OVERFLOW_SIZE * 2 + packingSize) {
                val index = subBufferStart - SHIFT_OVERFLOW_SIZE + it
                if (it < SHIFT_OVERFLOW_SIZE || array.size <= index) 0
                else array[index]
            }

            ShiftedStructBuffer(
                    shift, size.numberOfBits,
                    Bytes.wrap(subBuffer, order), array, subBufferStart
            )
        } else {
            StructBuffer(array, order, offset + start.toInt())
        }
    }

    override var byte: Byte
        get() = array[offset]
        set(value) {
            array[offset] = value
        }

    override var short: Short
        get() = buffer.getShort(offset)
        set(value) {
            buffer.putShort(offset, value)
        }

    override var int: Int
        get() = buffer.getInt(offset)
        set(value) {
            buffer.putInt(offset, value)
        }

    override var long: Long
        get() = buffer.getLong(offset)
        set(value) {
            buffer.putLong(offset, value)
        }

    override var float: Float
        get() = buffer.float
        set(value) {
            buffer.putFloat(offset, value)
        }

    override var double: Double
        get() = buffer.double
        set(value) {
            buffer.putDouble(offset, value)
        }

    override fun put(array: ByteArray, start: Int, length: Int) {
        System.arraycopy(array, start, this.array, this.offset, length)
    }

    fun asReadOnly(): ReadOnlyStructBuffer = this

    fun asWritable(): WritableStructBuffer = this

    fun byteOrder() = order

    /**
     * This class implements read and write access to
     * Byte, Short, Int and Longs that can be shifted an arbitrary amount of bits
     * in a byte-array, i.e. their values don't start at bit number 0.
     *
     * In addition, read-write access must be provided for Float, Double and ByteArray
     * that are not shifted, i.e. their values do start at bit number 0.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inner class ShiftedStructBuffer(
            private val shift: Int, bits: Int, bytes: Bytes,
            private val dest: ByteArray,
            private val destOffset: Int
    ) : StructBufferAccess {
        private val outer = this@StructBuffer

        private val intMask = if (bits % 32 == 0) -1 else (1 shl bits) - 1

        private val longMask = if (bits % 64 == 0) -1L else (1L shl bits) - 1L

        private val rightShiftedBytes by lazy {
            when {
                shift == 0 -> bytes
                bytes.byteOrder() == ByteOrder.BIG_ENDIAN -> bytes.rightShift(shift)
                else -> bytes.reverse().rightShift(shift).reverse()
            }
        }

        private val appliedBytes by lazy {
            when {
                shift == 0 -> rightShiftedBytes
                rightShiftedBytes.byteOrder() == ByteOrder.BIG_ENDIAN -> rightShiftedBytes.leftShift(shift)
                else -> rightShiftedBytes.reverse().leftShift(shift).reverse()
            }
        }

        private val buffer by lazy { rightShiftedBytes.buffer() }

        override var byte: Byte
            get() = mask(buffer[SHIFT_OVERFLOW_SIZE])
            set(value) {
                buffer.put(SHIFT_OVERFLOW_SIZE, mask(value))
                apply()
            }

        override var short: Short
            get() = mask(buffer.getShort(SHIFT_OVERFLOW_SIZE))
            set(value) {
                buffer.putShort(SHIFT_OVERFLOW_SIZE, mask(value))
                apply()
            }

        override var int: Int
            get() = mask(buffer.getInt(SHIFT_OVERFLOW_SIZE))
            set(value) {
                buffer.putInt(SHIFT_OVERFLOW_SIZE, mask(value))
                apply()
            }

        override var long: Long
            get() = mask(buffer.getLong(SHIFT_OVERFLOW_SIZE))
            set(value) {
                buffer.putLong(SHIFT_OVERFLOW_SIZE, mask(value))
                apply()
            }

        override var float
            get() = outer.float
            set(value) {
                outer.float = value
            }

        override var double: Double
            get() = outer.double
            set(value) {
                outer.double = value
            }

        override fun put(array: ByteArray, start: Int, length: Int) {
            outer.put(array, start, length)
        }

        @Suppress("UNCHECKED_CAST")
        private inline fun <T : Number> mask(value: T) = when (value) {
            is Byte -> (value.toInt() and intMask).toByte()
            is Short -> (value.toInt() and intMask).toShort()
            is Int -> value.toInt() and intMask
            is Long -> value.toLong() and longMask
            else -> value
        } as T

        private inline fun apply() {
            val srcLen = appliedBytes.length() - SHIFT_OVERFLOW_SIZE
            val dstLen = dest.size - destOffset
            System.arraycopy(
                    appliedBytes.array(), SHIFT_OVERFLOW_SIZE,
                    dest, destOffset, min(srcLen, dstLen)
            )
        }
    }
}
