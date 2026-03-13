package com.mnn.sdk

internal object TokenCountUtils {
    fun normalizeCount(rawCount: Int): Int? = if (rawCount >= 0) rawCount else null
}
