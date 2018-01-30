package io.intrepid.bleidiom.services

import org.junit.Test
import java.nio.ByteOrder
import java.util.*
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 */
class StructDataTest {
    @Test
    fun test_packingInfo_with_all_positive_sizes() {
        val sizeTest = SizeTestFactory(intArrayOf(1, 2, 3, 4, 5))
        assertEquals(15, sizeTest.size)
    }

    @Test
    fun test_packingInfo_with_all_positive_and_negative_sizes() {
        val sizeTest = SizeTestFactory(intArrayOf(1, -2, 3, -4, 5))
        assertEquals(15, sizeTest.size)
    }

    @Test
    fun test_packingInfo_with_zero_size_at_start() {
        val sizeTest = SizeTestFactory(intArrayOf(0, -2, 3, -4, 5))
        assertEquals(0, sizeTest.size)
    }

    @Test
    fun test_packingInfo_with_zero_size_at_end() {
        val sizeTest = SizeTestFactory(intArrayOf(1, -2, 3, -4, 0))
        assertEquals(0, sizeTest.size)
    }


    @Test
    fun test_packingInfo_with_zero_size_in_middle() {
        val sizeTest = SizeTestFactory(intArrayOf(1, 0, 0, -4, 5))
        assertEquals(0, sizeTest.size)
    }

    @Test
    fun test_byte_array_with_byte_length() {
        val data = ByteArray(5) { it.toByte() }
        val dataWithLength = data.withLength<ArrayWithLength>(1)

        val result = dataWithLength.deconstruct()
        val resultWithLength = StructData.construct(ArrayWithByteLength::class, result)

        assertTrue(Arrays.equals(data, resultWithLength.payload))
        assertEquals(dataWithLength, resultWithLength)
    }

    @Test
    fun test_byte_array_with_short_length() {
        val data = ByteArray(500) { it.toByte() }
        val dataWithLength = data.withLength<ArrayWithLength>(2)

        val result = dataWithLength.deconstruct()
        val resultWithLength = StructData.construct(ArrayWithShortLength::class, result)

        assertTrue(Arrays.equals(data, resultWithLength.payload))
        assertEquals(dataWithLength, resultWithLength)
    }

    @Test
    fun test_byte_array_with_int_length() {
        val data = ByteArray(100_000) { it.toByte() }
        val dataWithLength = data.withLength<ArrayWithLength>(4)

        val result = dataWithLength.deconstruct()
        val resultWithLength = StructData.construct(ArrayWithIntLength::class, result)

        assertTrue(Arrays.equals(data, resultWithLength.payload))
        assertEquals(dataWithLength, resultWithLength)
    }

    @Test
    fun test_deconstruct_and_construct() {
        val expectedByteVal = -1
        val expectedShortVal = 28943
        val expectedArray1: ArrayWithByteLength = ByteArray(5) { it.toByte() }.withLength(1)
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
        val expectedArray2: ArrayWithShortLength = ByteArray(5) { it.toByte() }.withLength(2)
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

        assertEquals(8, structData.size)
        assertEquals(connectionParams, result)
    }
}

private class SizeTestFactory(override val packingInfo: IntArray) : StructData.Factory {
    override fun fromByteArray(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int): StructData {
        TODO("not implemented")
    }

    override fun fromByteArrayWithSize(dataClass: KClass<out StructData>, bytes: ByteArray, order: ByteOrder, offset: Int): Pair<StructData, Int> {
        TODO("not implemented")
    }

    override fun toByteArray(value: StructData, bytes: ByteArray, order: ByteOrder, offset: Int): Int {
        TODO("not implemented")
    }
}

enum class TestEnum {
    A, B, C;

    companion object : StructData.PackingInfo {
        override val size = 2
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
    companion object : StructData.DataFactory() {
        override val packingInfo = intArrayOf(1, 2, 0, 0, 0, -1)
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
    companion object : StructData.DataFactory() {
        override val packingInfo = intArrayOf(4, -8, 0, 0, 4, 8, 6)
    }
}
