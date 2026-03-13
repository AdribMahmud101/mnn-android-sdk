package com.mnn.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MNNLlmTokenMetricsTest {

    @Test
    fun normalizeCount_returnsValueForZeroAndPositive() {
        assertEquals(0, TokenCountUtils.normalizeCount(0))
        assertEquals(17, TokenCountUtils.normalizeCount(17))
    }

    @Test
    fun normalizeCount_returnsNullForNegative() {
        assertNull(TokenCountUtils.normalizeCount(-1))
        assertNull(TokenCountUtils.normalizeCount(-999))
    }
}
