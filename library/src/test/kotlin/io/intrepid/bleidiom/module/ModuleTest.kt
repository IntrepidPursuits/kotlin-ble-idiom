package io.intrepid.bleidiom.module

import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import com.polidea.rxandroidble2.exceptions.BleScanException
import com.polidea.rxandroidble2.exceptions.BleScanException.BLUETOOTH_CANNOT_START
import io.intrepid.bleidiom.test.BleBaseTestHelper
import io.reactivex.exceptions.UndeliverableException
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleTest {
    val testHelper = BleBaseTestHelper()

    @Before
    fun setup() {
        testHelper.setup(this)
    }

    @After
    fun tearDown() {
        testHelper.tearDown()
    }

    @Test
    fun test_dont_handle_non_UndeliverableExceptions1() {
        assertFalse(UndeliverableBleExceptionHandler(BleDisconnectedException("")))
    }

    @Test
    fun test_dont_handle_non_UndeliverableExceptions2() {
        assertFalse(UndeliverableBleExceptionHandler(NullPointerException("")))
    }

    @Test
    fun test_dont_handle_non_Ble_UndeliverableExceptions() {
        val t = UndeliverableException(NullPointerException(""))
        assertFalse(UndeliverableBleExceptionHandler(t))
    }

    @Test
    fun test_dont_handle_UndeliverableExceptions_without_cause() {
        val t = UndeliverableException(null)
        assertFalse(UndeliverableBleExceptionHandler(t))
    }

    @Test
    fun test_dont_handle_UndeliverableExceptions_with_same_cause() {
        // JDK 7 no longer permits Throwable to have a cause that
        // is the same as the Throwable itself. Still, check this
        // in case we have exceptions from an older JDK.
        val cause = object: IllegalStateException() {
            override val cause: Throwable? get() = this
        }
        val t = UndeliverableException(cause)
        assertFalse(UndeliverableBleExceptionHandler(t))
    }

    @Test
    fun test_handle_Ble_UndeliverableExceptions() {
        val t = UndeliverableException(BleDisconnectedException(""))
        assertTrue(UndeliverableBleExceptionHandler(t))
    }

    @Test
    fun test_handle_nested_Ble_UndeliverableExceptions() {
        val t = UndeliverableException(
                IllegalArgumentException(
                        IllegalStateException(
                                BleScanException(BLUETOOTH_CANNOT_START)
                        )
                )
        )
        assertTrue(UndeliverableBleExceptionHandler(t))
    }
}