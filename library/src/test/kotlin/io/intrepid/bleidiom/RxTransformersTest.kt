package io.intrepid.bleidiom

import io.intrepid.bleidiom.test.BleBaseTestHelper
import io.intrepid.bleidiom.util.asString
import io.intrepid.bleidiom.util.get
import io.intrepid.bleidiom.util.plus
import io.intrepid.bleidiom.util.times
import io.reactivex.Observable
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.powermock.modules.junit4.PowerMockRunner
import kotlin.test.assertEquals

@Suppress("FunctionName")
@RunWith(PowerMockRunner::class)
class RxTransformersTest {
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
        var result = Observable.just(10) + 15
        assertEquals(25, result.blockingLast())

        result = 15 + Observable.just(10)
        assertEquals(25, result.blockingLast())

        result = Observable.just(15) + Observable.just(10)
        assertEquals(25, result.blockingLast())
    }

    @Test
    fun test_plus_operators_for_bytes() {
        var result = Observable.just(10.toByte()) + 15.toByte()
        assertEquals(25, result.blockingLast())

        result = 15.toByte() + Observable.just(10.toByte())
        assertEquals(25, result.blockingLast())

        result = Observable.just(15.toByte()) + Observable.just(10.toByte())
        assertEquals(25, result.blockingLast())
    }

    @Test
    fun test_plus_operators_for_short() {
        var result = Observable.just(10.toShort()) + 15.toShort()
        assertEquals(25, result.blockingLast())

        result = 15.toShort() + Observable.just(10.toShort())
        assertEquals(25, result.blockingLast())

        result = Observable.just(15.toShort()) + Observable.just(10.toShort())
        assertEquals(25, result.blockingLast())
    }

    @Test
    fun test_plus_operators_for_long() {
        var result = Observable.just(10L) + 15L
        assertEquals(25L, result.blockingLast())

        result = 15L + Observable.just(10L)
        assertEquals(25L, result.blockingLast())

        result = Observable.just(15L) + Observable.just(10L)
        assertEquals(25L, result.blockingLast())
    }

    @Test
    fun test_plus_operators_for_float() {
        var result = Observable.just(10f) + 15f
        assertEquals(25f, result.blockingLast())

        result = 15f + Observable.just(10f)
        assertEquals(25f, result.blockingLast())

        result = Observable.just(15f) + Observable.just(10f)
        assertEquals(25f, result.blockingLast())
    }

    @Test
    fun test_plus_operators_for_double() {
        var result = Observable.just(10.0) + 15.0
        assertEquals(25.0, result.blockingLast())

        result = 15.0 + Observable.just(10.0)
        assertEquals(25.0, result.blockingLast())

        result = Observable.just(15.0) + Observable.just(10.0)
        assertEquals(25.0, result.blockingLast())
    }

    @Test
    fun test_plus_operators_for_string1() {
        var result = Observable.just("10") + "15"
        assertEquals("1015", result.blockingLast())

        result = Observable.just("15") + Observable.just("10")
        assertEquals("1510", result.blockingLast())
    }

    @Test
    fun test_plus_operators_for_string2() {
        var result = Observable.just("") + "15"
        assertEquals("15", result.blockingLast())

        result = Observable.just("15") + Observable.just("")
        assertEquals("15", result.blockingLast())
    }

    @Test
    fun test_plus_operators_for_string3() {
        var result = Observable.just("10") + ""
        assertEquals("10", result.blockingLast())

        result = Observable.just("") + Observable.just("10")
        assertEquals("10", result.blockingLast())
    }

    @Test
    fun test_times_operators_for_ints() {
        var result = Observable.just(10) * 15
        assertEquals(150, result.blockingLast())

        result = 15 * Observable.just(10)
        assertEquals(150, result.blockingLast())

        result = Observable.just(15) * Observable.just(10)
        assertEquals(150, result.blockingLast())
    }

    @Test
    fun test_times_operators_for_bytes() {
        var result = Observable.just(10.toByte()) * 15.toByte()
        assertEquals(150, result.blockingLast())

        result = 15.toByte() * Observable.just(10.toByte())
        assertEquals(150, result.blockingLast())

        result = Observable.just(15.toByte()) * Observable.just(10.toByte())
        assertEquals(150, result.blockingLast())
    }

    @Test
    fun test_times_operators_for_short() {
        var result = Observable.just(10.toShort()) * 15.toShort()
        assertEquals(150, result.blockingLast())

        result = 15.toShort() * Observable.just(10.toShort())
        assertEquals(150, result.blockingLast())

        result = Observable.just(15.toShort()) * Observable.just(10.toShort())
        assertEquals(150, result.blockingLast())
    }

    @Test
    fun test_times_operators_for_long() {
        var result = Observable.just(10L) * 15L
        assertEquals(150L, result.blockingLast())

        result = 15L * Observable.just(10L)
        assertEquals(150L, result.blockingLast())

        result = Observable.just(15L) * Observable.just(10L)
        assertEquals(150L, result.blockingLast())
    }

    @Test
    fun test_times_operators_for_float() {
        var result = Observable.just(10f) * 15f
        assertEquals(150f, result.blockingLast())

        result = 15f * Observable.just(10f)
        assertEquals(150f, result.blockingLast())

        result = Observable.just(15f) * Observable.just(10f)
        assertEquals(150f, result.blockingLast())
    }

    @Test
    fun test_times_operators_for_double() {
        var result = Observable.just(10.0) * 15.0
        assertEquals(150.0, result.blockingLast())

        result = 15.0 * Observable.just(10.0)
        assertEquals(150.0, result.blockingLast())

        result = Observable.just(15.0) * Observable.just(10.0)
        assertEquals(150.0, result.blockingLast())
    }

    @Test
    fun test_array_get_for_bytes() {
        val arrayObs = Observable.just(ByteArray(1, { 10.toByte() }))
        assertEquals(10.toByte(), arrayObs[0].blockingLast())
    }

    @Test
    fun test_array_get_for_short() {
        val arrayObs = Observable.just(ShortArray(1, { 10.toShort() }))
        assertEquals(10.toShort(), arrayObs[0].blockingLast())
    }

    @Test
    fun test_array_get_for_int() {
        val arrayObs = Observable.just(IntArray(1, { 10 }))
        assertEquals(10, arrayObs[0].blockingLast())
    }

    @Test
    fun test_array_get_for_long() {
        val arrayObs = Observable.just(LongArray(1, { 10 }))
        assertEquals(10L, arrayObs[0].blockingLast())
    }

    @Test
    fun test_array_get_for_float() {
        val arrayObs = Observable.just(FloatArray(1, { 10f }))
        assertEquals(10f, arrayObs[0].blockingLast())
    }

    @Test
    fun test_array_get_for_double() {
        val arrayObs = Observable.just(DoubleArray(1, { 10.0 }))
        assertEquals(10.0, arrayObs[0].blockingLast())
    }

    @Test
    fun test_array_get_for_String() {
        val arrayObs = Observable.just("A")
        assertEquals('A', arrayObs[0].blockingLast())
    }

    @Test
    fun test_array_get_for_array() {
        val expectedValue = "Hello"
        val arrayObs = Observable.just(arrayOf(expectedValue))
        assertEquals(expectedValue, arrayObs[0].blockingLast())
    }

    @Test
    fun test_obs_as_string() {
        val objectObs = Observable.just(200)
        assertEquals("200", objectObs.asString().blockingLast())
    }
}
