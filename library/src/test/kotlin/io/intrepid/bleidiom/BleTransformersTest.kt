package io.intrepid.bleidiom

import io.intrepid.bleidiom.test.BleBaseTestHelper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.powermock.modules.junit4.PowerMockRunner
import kotlin.test.assertEquals

@Suppress("FunctionName")
@RunWith(PowerMockRunner::class)
class BleTransformersTest {
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
    fun test_fix_sign_for_bytes_unsigned() {
        assertEquals(1, 1.toByte().toPositiveInt())
        assertEquals(255, (-1).toByte().toPositiveInt())
    }

    @Test
    fun test_fix_sign_for_short_unsigned() {
        assertEquals(1, 1.toShort().toPositiveInt())
        assertEquals(65535, (-1).toShort().toPositiveInt())
    }

    @Test
    fun test_fix_sign_for_ints_unsigned() {
        assertEquals(1, 1.toPositiveLong())
        assertEquals(4294967295, (-1).toPositiveLong())
    }
}
