package io.intrepid.bleidiom

import io.intrepid.bleidiom.test.BleBaseTestHelper
import io.intrepid.bleidiom.util.asString
import io.intrepid.bleidiom.util.get
import io.intrepid.bleidiom.util.plus
import io.intrepid.bleidiom.util.times
import io.reactivex.Single
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.powermock.modules.junit4.PowerMockRunner
import kotlin.test.assertEquals

@Suppress("FunctionName")
@RunWith(PowerMockRunner::class)
class RxTransformersForSinglesTest {
    private val testHelper = BleBaseTestHelper()

    @Before
    fun setup() {
        testHelper.setup(this)
    }

    @After
    fun tearDown() {
        testHelper.tearDown()
    }

    @Test
    fun test_plus_operators_for_ints() {
        var result = Single.just(10) + 15
        assertEquals(25, result.blockingGet())

        result = 15 + Single.just(10)
        assertEquals(25, result.blockingGet())

        result = Single.just(15) + Single.just(10)
        assertEquals(25, result.blockingGet())
    }

    @Test
    fun test_plus_operators_for_bytes() {
        var result = Single.just(10.toByte()) + 15.toByte()
        assertEquals(25, result.blockingGet())

        result = 15.toByte() + Single.just(10.toByte())
        assertEquals(25, result.blockingGet())

        result = Single.just(15.toByte()) + Single.just(10.toByte())
        assertEquals(25, result.blockingGet())
    }

    @Test
    fun test_plus_operators_for_short() {
        var result = Single.just(10.toShort()) + 15.toShort()
        assertEquals(25, result.blockingGet())

        result = 15.toShort() + Single.just(10.toShort())
        assertEquals(25, result.blockingGet())

        result = Single.just(15.toShort()) + Single.just(10.toShort())
        assertEquals(25, result.blockingGet())
    }

    @Test
    fun test_plus_operators_for_long() {
        var result = Single.just(10L) + 15L
        assertEquals(25L, result.blockingGet())

        result = 15L + Single.just(10L)
        assertEquals(25L, result.blockingGet())

        result = Single.just(15L) + Single.just(10L)
        assertEquals(25L, result.blockingGet())
    }

    @Test
    fun test_plus_operators_for_float() {
        var result = Single.just(10f) + 15f
        assertEquals(25f, result.blockingGet())

        result = 15f + Single.just(10f)
        assertEquals(25f, result.blockingGet())

        result = Single.just(15f) + Single.just(10f)
        assertEquals(25f, result.blockingGet())
    }

    @Test
    fun test_plus_operators_for_double() {
        var result = Single.just(10.0) + 15.0
        assertEquals(25.0, result.blockingGet())

        result = 15.0 + Single.just(10.0)
        assertEquals(25.0, result.blockingGet())

        result = Single.just(15.0) + Single.just(10.0)
        assertEquals(25.0, result.blockingGet())
    }

    @Test
    fun test_plus_operators_for_string1() {
        var result = Single.just("10") + "15"
        assertEquals("1015", result.blockingGet())

        result = Single.just("15") + Single.just("10")
        assertEquals("1510", result.blockingGet())
    }

    @Test
    fun test_plus_operators_for_string2() {
        var result = Single.just("") + "15"
        assertEquals("15", result.blockingGet())

        result = Single.just("15") + Single.just("")
        assertEquals("15", result.blockingGet())
    }

    @Test
    fun test_plus_operators_for_string3() {
        var result = Single.just("10") + ""
        assertEquals("10", result.blockingGet())

        result = Single.just("") + Single.just("10")
        assertEquals("10", result.blockingGet())
    }

    @Test
    fun test_times_operators_for_ints() {
        var result = Single.just(10) * 15
        assertEquals(150, result.blockingGet())

        result = 15 * Single.just(10)
        assertEquals(150, result.blockingGet())

        result = Single.just(15) * Single.just(10)
        assertEquals(150, result.blockingGet())
    }

    @Test
    fun test_times_operators_for_bytes() {
        var result = Single.just(10.toByte()) * 15.toByte()
        assertEquals(150, result.blockingGet())

        result = 15.toByte() * Single.just(10.toByte())
        assertEquals(150, result.blockingGet())

        result = Single.just(15.toByte()) * Single.just(10.toByte())
        assertEquals(150, result.blockingGet())
    }

    @Test
    fun test_times_operators_for_short() {
        var result = Single.just(10.toShort()) * 15.toShort()
        assertEquals(150, result.blockingGet())

        result = 15.toShort() * Single.just(10.toShort())
        assertEquals(150, result.blockingGet())

        result = Single.just(15.toShort()) * Single.just(10.toShort())
        assertEquals(150, result.blockingGet())
    }

    @Test
    fun test_times_operators_for_long() {
        var result = Single.just(10L) * 15L
        assertEquals(150L, result.blockingGet())

        result = 15L * Single.just(10L)
        assertEquals(150L, result.blockingGet())

        result = Single.just(15L) * Single.just(10L)
        assertEquals(150L, result.blockingGet())
    }

    @Test
    fun test_times_operators_for_float() {
        var result = Single.just(10f) * 15f
        assertEquals(150f, result.blockingGet())

        result = 15f * Single.just(10f)
        assertEquals(150f, result.blockingGet())

        result = Single.just(15f) * Single.just(10f)
        assertEquals(150f, result.blockingGet())
    }

    @Test
    fun test_times_operators_for_double() {
        var result = Single.just(10.0) * 15.0
        assertEquals(150.0, result.blockingGet())

        result = 15.0 * Single.just(10.0)
        assertEquals(150.0, result.blockingGet())

        result = Single.just(15.0) * Single.just(10.0)
        assertEquals(150.0, result.blockingGet())
    }

    @Test
    fun test_array_get_for_bytes() {
        val arrayObs = Single.just(ByteArray(1, { 10.toByte() }))
        assertEquals(10.toByte(), arrayObs[0].blockingGet())
    }

    @Test
    fun test_array_get_for_short() {
        val arrayObs = Single.just(ShortArray(1, { 10.toShort() }))
        assertEquals(10.toShort(), arrayObs[0].blockingGet())
    }

    @Test
    fun test_array_get_for_int() {
        val arrayObs = Single.just(IntArray(1, { 10 }))
        assertEquals(10, arrayObs[0].blockingGet())
    }

    @Test
    fun test_array_get_for_long() {
        val arrayObs = Single.just(LongArray(1, { 10 }))
        assertEquals(10L, arrayObs[0].blockingGet())
    }

    @Test
    fun test_array_get_for_float() {
        val arrayObs = Single.just(FloatArray(1, { 10f }))
        assertEquals(10f, arrayObs[0].blockingGet())
    }

    @Test
    fun test_array_get_for_double() {
        val arrayObs = Single.just(DoubleArray(1, { 10.0 }))
        assertEquals(10.0, arrayObs[0].blockingGet())
    }

    @Test
    fun test_array_get_for_String() {
        val arrayObs = Single.just("A")
        assertEquals('A', arrayObs[0].blockingGet())
    }

    @Test
    fun test_array_get_for_array() {
        val expectedValue = "Hello"
        val arrayObs = Single.just(arrayOf(expectedValue))
        assertEquals(expectedValue, arrayObs[0].blockingGet())
    }

    @Test
    fun test_obs_as_string() {
        val objectObs = Single.just(200)
        assertEquals("200", objectObs.asString().blockingGet())
    }
}
