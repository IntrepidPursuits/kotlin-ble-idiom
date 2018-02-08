package io.intrepid.bleidiom.services

import org.junit.Test
import java.nio.ByteOrder
import java.util.*
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 */
class StructDataTest {
    @Test
    fun test_packingInfo_with_all_positive_sizes() {
        val sizeTest = SizeTestFactory(arrayOf(1, 2, 3(), 4, 5))
        assertEquals(15, sizeTest.size)
    }

    @Test
    fun test_packingInfo_with_all_positive_and_negative_sizes() {
        val sizeTest = SizeTestFactory(arrayOf(1, -2(), 3, -4, 5))
        assertEquals(15, sizeTest.size)
    }

    @Test
    fun test_packingInfo_with_zero_size_at_start() {
        val sizeTest = SizeTestFactory(arrayOf(0(0), -2, 3, -4, 5))
        assertEquals(0, sizeTest.size)
    }

    @Test
    fun test_packingInfo_with_zero_size_at_end() {
        val sizeTest = SizeTestFactory(arrayOf(1, -2, 3, -4, 0))
        assertEquals(0, sizeTest.size)
    }

    @Test
    fun test_packingInfo_with_zero_size_in_middle() {
        val sizeTest = SizeTestFactory(arrayOf(1, 0, 0, -4, 5))
        assertEquals(0, sizeTest.size)
    }

    @Test
    fun test_byte_array_with_byte_length() {
        val data = ByteArray(5) { it.toByte() }
        val dataWithLength = data.withLength<ArrayWithLength>(UINT8)

        val result = dataWithLength.deconstruct()
        val resultWithLength = StructData.construct(ArrayWithByteLength::class, result)

        assertTrue(Arrays.equals(data, resultWithLength.payload))
        assertEquals(dataWithLength, resultWithLength)
    }

    @Test
    fun test_byte_array_with_short_length() {
        val data = ByteArray(500) { it.toByte() }
        val dataWithLength = data.withLength<ArrayWithLength>(UINT16)

        val result = dataWithLength.deconstruct()
        val resultWithLength = StructData.construct(ArrayWithShortLength::class, result)

        assertTrue(Arrays.equals(data, resultWithLength.payload))
        assertEquals(dataWithLength, resultWithLength)
    }

    @Test
    fun test_byte_array_with_int_length() {
        val data = ByteArray(100_000) { it.toByte() }
        val dataWithLength = data.withLength<ArrayWithLength>(UINT32)

        val result = dataWithLength.deconstruct()
        val resultWithLength = StructData.construct(ArrayWithIntLength::class, result)

        assertTrue(Arrays.equals(data, resultWithLength.payload))
        assertEquals(dataWithLength, resultWithLength)
    }

    @Test
    fun test_deconstruct_and_construct() {
        val expectedByteVal = -1
        val expectedShortVal = 28943
        val expectedArray1: ArrayWithByteLength = ByteArray(5) { it.toByte() }.withLength(UINT8)
        val expectedEnum = TestEnum.B
        val expectedString = "Hello There!"
        val expectedSByteVal = -1

        val subData = SubData(
                expectedByteVal,
                expectedShortVal,
                expectedArray1,
                expectedEnum,
                expectedString,
                expectedSByteVal
        )

        val expectedIntVal = 343823323
        val expectedLongVal = Long.MAX_VALUE - 33247834278
        val expectedArray2: ArrayWithShortLength = ByteArray(5) { it.toByte() }.withLength(UINT16)
        val expectedFloatVal = 3243.5f
        val expectedDoubleVal = 493489.91
        val expectedArray = ByteArray(4) { it.toByte() }

        val data = Data(
                expectedIntVal,
                expectedLongVal,
                expectedArray2,
                subData,
                expectedFloatVal,
                expectedDoubleVal,
                expectedArray
        )

        val structData = data.deconstruct()
        val result = StructData.construct(Data::class, structData)
        val subResult = result.subData

        assertTrue(Arrays.equals(byteArrayOf(
                -37, 83, 126, 20,
                89, 79, 70, 66, -8, -1, -1, 127,
                5, 0, 0, 1, 2, 3, 4,
                    -1,
                    15, 113,
                    5, 0, 1, 2, 3, 4,
                    1, 0,
                    72, 101, 108, 108, 111, 32, 84, 104, 101, 114, 101, 33, 0,
                    -1,
                0, -72, 74, 69,
                61, 10, -41, -93, -57, 30, 30, 65,
                0, 1, 2, 3, 0, 0
        ), structData))

        assertEquals(256 + expectedByteVal, subResult.byteVal)
        assertEquals(expectedShortVal, subResult.shortVal)
        assertEquals(expectedArray1, subResult.array)
        assertEquals(expectedEnum, subResult.enumVal)
        assertEquals(expectedString, subResult.stringVal)
        assertEquals(expectedSByteVal, subResult.signedByteVal)

        assertEquals(expectedIntVal, result.intVal)
        assertEquals(expectedLongVal, result.longVal)
        assertEquals(expectedArray2, result.array)
        assertEquals(expectedFloatVal, result.floatValue)
        assertEquals(expectedDoubleVal, result.doubleValue)
        assertTrue(Arrays.equals(byteArrayOf(0, 1, 2, 3, 0, 0), result.arrayValue))
    }

    @Test
    fun test_ConnectionParams_deconstruct_and_construct() {
        val connectionParams = ConnectionParams(1, 2, 3, 4)

        val structData = connectionParams.deconstruct()
        val result = StructData.construct(ConnectionParams::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0), structData))

        assertEquals(8, structData.size)
        assertEquals(connectionParams, result)
    }

    @Test
    fun test_packing_from_int() {
        val packing = StructPacking(-4)

        assertEquals(-4, packing.size)
        assertEquals(4, packing.numberOfBytes)
        assertEquals(32, packing.numberOfBits)
    }

    @Test
    fun test_packing_from_ext_function() {
        val packing = -2(9)

        assertEquals(-2, packing.size)
        assertEquals(2, packing.numberOfBytes)
        assertEquals(9, packing.numberOfBits)
    }

    @Test
    fun test_packing_unary_minus() {
        var packing = 2(12)
        packing = -packing

        assertEquals(-2, packing.size)
        assertEquals(2, packing.numberOfBytes)
        assertEquals(12, packing.numberOfBits)
    }

    @Test
    fun test_packing_addition() {
        val packing = 2(12) + -4(28)

        assertEquals(5, packing.size)
        assertEquals(5, packing.numberOfBytes)
        assertEquals(40, packing.numberOfBits)
    }

    @Test
    fun test_packing_addition_with_number() {
        val packing = 2(12) + -4

        assertEquals(6, packing.size)
        assertEquals(6, packing.numberOfBytes)
        assertEquals(44, packing.numberOfBits)
    }

    @Test
    fun test_packing_equal_to_0() {
        assertTrue(0() == 0())
    }

    @Test
    fun test_packing_not_equal_to_0() {
        assertTrue(0() != 1())
    }

    @Test
    fun test_packing_not_equal_to_0_2() {
        assertTrue(2(12) != 0())
    }

    @Test
    fun test_packing_equal_to_other() {
        assertTrue(4(29) == 4(29))
    }

    @Test
    fun test_packing_not_equal_to_other_size() {
        assertFalse(4(29) == -4(29))
    }

    @Test
    fun test_packing_not_equal_to_other_bits() {
        assertFalse(4(29) == 4(31))
    }

    @Test
    fun test_packing_compare1() {
        assertTrue(4(30) < -5)
    }

    @Test
    fun test_packing_compare2() {
        assertTrue(-4(30) < 4(31))
    }

    @Test
    fun test_packing_pool() {
        assertTrue(0() === 0())

        assertTrue(UINT8() === UINT8())
        assertTrue(UINT8(1) === UINT8(1))
        assertTrue(UINT8(2) === UINT8(2))
        assertTrue(UINT8(3) === UINT8(3))
        assertTrue(UINT8(4) === UINT8(4))
        assertTrue(UINT8(5) === UINT8(5))
        assertTrue(UINT8(6) === UINT8(6))
        assertTrue(UINT8(7) === UINT8(7))
        assertTrue(UINT8(8) === UINT8())

        assertTrue(UINT16() === UINT16())
        assertTrue(FLOAT() === FLOAT())
        assertTrue(DOUBLE() === DOUBLE())

        assertTrue(INT8() === INT8())
        assertTrue(INT16() === -(UINT16()))
        assertTrue(INT32() === -UINT32())
        assertTrue(INT64() === INT64())
    }

    @Test
    fun test_BitMask_deconstruct_and_construct() {
        val bitmask = BitMask(
                301,
                true, 2, false, 13,
                0x0F, 0x00000567,
                true, 0x7654321A
        )

        val structData = bitmask.deconstruct()
        val result = StructData.construct(BitMask::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(
                45, 1, 0, 0,
                0b11010101.toByte(),
                0x7F, 0x56,
                0b00110101, 0x64, 0xA8.toByte(), 0xEC.toByte()
        ), structData))

        assertEquals(bitmask, result)
    }

    @Test
    fun test_BitMask_with_odd_boundaries_deconstruct_and_construct() {
        val bitmask = BitMaskWithOddBoundaries()

        val structData = bitmask.deconstruct()
        val result = StructData.construct(BitMaskWithOddBoundaries::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(
                0xEB.toByte(), 0xCD.toByte(),
                0xF1.toByte(), 0xEA.toByte(), 0x91.toByte(),
                0xAB.toByte(), 0x54.toByte(), 0xFF.toByte(), 0x7F, 0x0, 0x0,
                0x46, 0x38, 0x30, 0x28, 0x20, 0x18, 0x10, 0x48, 0x3, 0xC3.toByte(), 0x82.toByte(), 0x82.toByte()
        ), structData))

        assertEquals(bitmask, result)
    }

    @Test
    fun test_enum_with_value() {
        val expectedData = EnumData()

        val structData = expectedData.deconstruct()
        val result = StructData.construct(EnumData::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(1, 0, 102, 0), structData))

        assertEquals(expectedData, result)
    }

    @Test
    fun test_array_with_calculated_item_sizes() {
        val array = arrayOf(
                ItemDataWithCalculatedSize(enum = TestEnumWithValue.A),
                ItemDataWithCalculatedSize(enum = TestEnumWithValue.B),
                ItemDataWithCalculatedSize(enum = TestEnumWithValue.C)
        )
        val expectedData = StructArray1TestData(array = array.structsToBytes())

        val structData = expectedData.deconstruct()
        val result = StructData.construct(StructArray1TestData::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(
                79, 116, 104, 101, 114, 32, 68, 97, 116, 97, 0,
                100, 0, 17, 0, 0, 0, 101, 0, 17, 0, 0, 0, 102, 0, 17, 0, 0, 0
        ), structData))

        assertEquals(expectedData.otherData, result.otherData)
        assertTrue(Arrays.equals(
                expectedData.array.bytesToStructs<ItemDataWithCalculatedSize>(),
                result.array.bytesToStructs<ItemDataWithCalculatedSize>()
        ))
    }

    @Test
    fun test_array_with_fixed_item_sizes() {
        val array = arrayOf(
                ItemDataWithFixedSize(enum = TestEnumWithValue.A),
                ItemDataWithFixedSize(enum = TestEnumWithValue.B),
                ItemDataWithFixedSize(enum = TestEnumWithValue.C)
        )
        val expectedData = StructArray2TestData(array = array.structsToBytes())

        val structData = expectedData.deconstruct()
        val result = StructData.construct(StructArray2TestData::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(
                79, 116, 104, 101, 114, 32, 68, 97, 116, 97, 0,
                100, 37, 0, 0, 0, 101, 37, 0, 0, 0, 102, 37, 0, 0, 0
        ), structData))

        assertEquals(expectedData.otherData, result.otherData)
        assertTrue(Arrays.equals(
                expectedData.array.bytesToStructs<ItemDataWithFixedSize>(),
                result.array.bytesToStructs<ItemDataWithFixedSize>()
        ))
    }

    @Test
    fun test_array_with_bytes() {
        val expectedData = StructArray2TestData(array = Array(5) { it.toByte() } .numbersToBytes())

        val structData = expectedData.deconstruct()
        val result = StructData.construct(StructArray2TestData::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(
                79, 116, 104, 101, 114, 32, 68, 97, 116, 97, 0,
                0, 1, 2, 3, 4
        ), structData))

        assertTrue(Arrays.equals(
                expectedData.array.bytesToNumbers<Byte>(),
                result.array.bytesToNumbers<Byte>()
        ))
    }

    @Test
    fun test_array_with_shorts() {
        val expectedData = StructArray2TestData(array = Array(5) { it.toShort() } .numbersToBytes())

        val structData = expectedData.deconstruct()
        val result = StructData.construct(StructArray2TestData::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(
                79, 116, 104, 101, 114, 32, 68, 97, 116, 97, 0,
                0, 0, 1, 0, 2, 0, 3, 0, 4, 0
        ), structData))

        assertTrue(Arrays.equals(
                expectedData.array.bytesToNumbers<Short>(),
                result.array.bytesToNumbers<Short>()
        ))
    }

    @Test
    fun test_array_with_ints() {
        val expectedData = StructArray2TestData(array = Array(5) { it } .numbersToBytes())

        val structData = expectedData.deconstruct()
        val result = StructData.construct(StructArray2TestData::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(
                79, 116, 104, 101, 114, 32, 68, 97, 116, 97, 0,
                0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0
        ), structData))

        assertTrue(Arrays.equals(
                expectedData.array.bytesToNumbers<Int>(),
                result.array.bytesToNumbers<Int>()
        ))
    }

    @Test
    fun test_array_with_longs() {
        val expectedData = StructArray2TestData(array = Array(5) { it.toLong() } .numbersToBytes())

        val structData = expectedData.deconstruct()
        val result = StructData.construct(StructArray2TestData::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(
                79, 116, 104, 101, 114, 32, 68, 97, 116, 97, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0,
                3, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0
        ), structData))

        assertTrue(Arrays.equals(
                expectedData.array.bytesToNumbers<Long>(),
                result.array.bytesToNumbers<Long>()
        ))
    }

    @Test
    fun test_array_with_floats() {
        val expectedData = StructArray2TestData(array = Array(5) { it.toFloat() } .numbersToBytes())

        val structData = expectedData.deconstruct()
        val result = StructData.construct(StructArray2TestData::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(
                79, 116, 104, 101, 114, 32, 68, 97, 116, 97, 0,
                0, 0, 0, 0, 0, 0, -128, 63, 0, 0, 0, 64, 0, 0, 64, 64, 0, 0, -128, 64
        ), structData))

        assertTrue(Arrays.equals(
                expectedData.array.bytesToNumbers<Float>(),
                result.array.bytesToNumbers<Float>()
        ))
    }

    @Test
    fun test_array_with_doubles() {
        val expectedData = StructArray2TestData(array = Array(5) { it.toDouble() } .numbersToBytes())

        val structData = expectedData.deconstruct()
        val result = StructData.construct(StructArray2TestData::class, structData)

        assertTrue(Arrays.equals(byteArrayOf(
                79, 116, 104, 101, 114, 32, 68, 97, 116, 97, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -16, 63, 0, 0, 0, 0, 0, 0, 0, 64,
                0, 0, 0, 0, 0, 0, 8, 64, 0, 0, 0, 0, 0, 0, 16, 64
        ), structData))

        assertTrue(Arrays.equals(
                expectedData.array.bytesToNumbers<Double>(),
                result.array.bytesToNumbers<Double>()
        ))
    }
}

private class SizeTestFactory(override val packingInfo: Array<Number>) : StructData.Factory {
    override fun fromByteArray(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int): StructData {
        throw NotImplementedError()
    }

    override fun fromByteArrayWithSize(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int): Pair<StructData, Int> {
        throw NotImplementedError()
    }

    override fun toByteArray(value: StructData, bytes: ByteArray, order: ByteOrder, offset: Int): Int {
        throw NotImplementedError()
    }
}

enum class TestEnum {
    A, B, C;

    companion object : StructData.PackingInfo {
        override val size = UINT16
    }
}

enum class TestEnumWithValue(val value: Int) {
    A(100), B(101), C(102);

    companion object : StructData.PackingInfo {
        override val size = UINT16
    }
}

internal data class SubData(
        val byteVal: Int,
        val shortVal: Int,
        val array: ArrayWithByteLength,
        val enumVal: TestEnum,
        val stringVal: String,
        val signedByteVal: Int
) : StructData() {
    companion object : StructData.DataFactory {
        override val packingInfo = arrayOf<Number>(UINT8, UINT16, 0, 0, 0, INT8)
    }
}

internal data class Data(
        val intVal: Int,
        val longVal: Long,
        val array: ArrayWithShortLength,
        val subData: SubData,
        val floatValue: Float,
        val doubleValue: Double,
        @Suppress("ArrayInDataClass") val arrayValue: ByteArray
) : StructData() {
    companion object : StructData.DataFactory {
        override val packingInfo = arrayOf<Number>(UINT32, INT64, 0, 0, FLOAT, DOUBLE, 6)
    }
}

internal data class BitMask(
        val value: Int,

        val boolean1: Boolean,
        val fourValues: Int,
        val boolean2: Boolean,
        val nibble1: Int,

        val nibble2: Int,
        val shortShort: Int,

        val boolean3: Boolean,
        val shortInt: Int
) : StructData() {
    companion object : StructData.DataFactory {
        override val packingInfo = arrayOf(
                UINT32,
                UINT8(1), UINT8(2), UINT8(1), UINT8(4),
                UINT8(4), UINT16(12),
                UINT8(1), UINT32(31)
        )
    }
}

internal data class BitMaskWithOddBoundaries(
        val value1: Int = 3,
        val byteValue: Int = 0x7A,
        val value2: Int = 0x66,

        val value3: Int = 1,
        val shortValue: Int = 0x1ABC,
        val value4: Int = 0x0123,

        val value5: Boolean = true,
        val intValue: Int = 0x3FFFAA55,
        val value6: Int = 0x00000000,

        val value7: Int = 6,
        val longValue: Long = 0x0102030405060708,
        val value8: Long = 0x000000020A0B0C0D
) : StructData() {
    companion object : StructData.DataFactory {
        override val packingInfo = arrayOf<Number>(
                UINT8(2), UINT8(7), UINT8(7),     // total of 16 bits
                UINT8(2), UINT16(13), UINT16(9),  // total of 24 bits
                UINT8(1), UINT32(30), UINT32(17), // total of 48 bits
                UINT8(3), INT64(59), INT64(34)    // total of 96 bits
        )
    }
}

internal data class EnumData(
        val enum1: TestEnum = TestEnum.B,
        val enum2: TestEnumWithValue = TestEnumWithValue.C
) : StructData() {
    companion object: StructData.DataFactory {
        override val packingInfo= arrayOf<Number>(0, 0)
    }
}

internal data class ItemDataWithCalculatedSize(
        val enum: TestEnumWithValue,
        val number: Int = 17
): StructData() {
    companion object: StructData.DataFactory {
        // An item in 'packingInfo' is 0 -> size needs to be calculated
        override val packingInfo= arrayOf<Number>(0, UINT32)
    }
}

internal data class ItemDataWithFixedSize(
        val enum: TestEnumWithValue,
        val number: Int = 37
): StructData() {
    companion object: StructData.DataFactory {
        // No items in 'packingInfo' are 0. Size is fixed.
        override val packingInfo= arrayOf<Number>(UINT8, UINT32)
    }
}

internal data class StructArray1TestData(
        val otherData: String = "Other Data",
        @Suppress("ArrayInDataClass") val array: ByteArray
) : StructData() {
    companion object: StructData.DataFactory {
        override val packingInfo = arrayOf<Number>(0, 0)
    }
}

internal data class StructArray2TestData(
        val otherData: String = "Other Data",
        @Suppress("ArrayInDataClass") val array: ByteArray
) : StructData() {
    companion object: StructData.DataFactory {
        override val packingInfo = arrayOf<Number>(0, 0)
    }
}
