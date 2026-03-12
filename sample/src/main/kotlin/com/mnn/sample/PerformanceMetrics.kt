package com.mnn.sample

/**
 * Performance metrics returned from MNN inference
 * Based on official MNN LLM Chat implementation
 */
data class PerformanceMetrics(
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val prefillTimeMs: Long = 0,
    val decodeTimeMs: Long = 0,
    val totalTimeMs: Long = 0
) {
    /**
     * Calculate prefill speed in tokens/second
     * Prefill = processing prompt tokens
     */
    val prefillSpeed: Double
        get() = if (prefillTimeMs > 0) {
            (promptTokens * 1000.0) / prefillTimeMs
        } else 0.0
    
    /**
     * Calculate decode/generation speed in tokens/second
     * Decode = generating new tokens
     */
    val decodeSpeed: Double
        get() = if (decodeTimeMs > 0) {
            (generatedTokens * 1000.0) / decodeTimeMs
        } else 0.0
    
    /**
     * Calculate overall speed in tokens/second
     */
    val overallSpeed: Double
        get() = if (totalTimeMs > 0) {
            ((promptTokens + generatedTokens) * 1000.0) / totalTimeMs
        } else 0.0
    
    /**
     * Format prefill speed for display
     */
    fun formatPrefillSpeed(): String {
        return if (prefillSpeed > 0) {
            "%.1f t/s".format(prefillSpeed)
        } else {
            "N/A"
        }
    }
    
    /**
     * Format decode speed for display
     */
    fun formatDecodeSpeed(): String {
        return if (decodeSpeed > 0) {
            "%.1f t/s".format(decodeSpeed)
        } else {
            "N/A"
        }
    }
    
    /**
     * Format overall speed for display
     */
    fun formatOverallSpeed(): String {
        return if (overallSpeed > 0) {
            "%.1f t/s".format(overallSpeed)
        } else {
            "N/A"
        }
    }
    
    /**
     * Get formatted summary string
     */
    fun getSummary(): String {
        return buildString {
            append("📊 Performance Metrics:\n")
            append("Prompt: $promptTokens tokens\n")
            append("Generated: $generatedTokens tokens\n")
            append("Prefill: ${formatPrefillSpeed()} (${prefillTimeMs}ms)\n")
            append("Decode: ${formatDecodeSpeed()} (${decodeTimeMs}ms)\n")
            append("Total: ${formatOverallSpeed()} (${totalTimeMs}ms)")
        }
    }
    
    companion object {
        /**
         * Parse metrics from HashMap returned by JNI
         * Format matches official MNN LLM Chat app
         */
        fun fromHashMap(map: HashMap<String, Any>): PerformanceMetrics {
            val promptLen = (map["prompt_len"] as? Long ?: 0L).toInt()
            val decodeLen = (map["decode_len"] as? Long ?: 0L).toInt()
            
            // Times are in microseconds from native code
            val prefillTimeUs = map["prefill_time"] as? Long ?: 0L
            val decodeTimeUs = map["decode_time"] as? Long ?: 0L
            
            // Convert to milliseconds
            val prefillTimeMs = prefillTimeUs / 1000
            val decodeTimeMs = decodeTimeUs / 1000
            val totalTimeMs = prefillTimeMs + decodeTimeMs
            
            return PerformanceMetrics(
                promptTokens = promptLen,
                generatedTokens = decodeLen,
                prefillTimeMs = prefillTimeMs,
                decodeTimeMs = decodeTimeMs,
                totalTimeMs = totalTimeMs
            )
        }
    }
}
