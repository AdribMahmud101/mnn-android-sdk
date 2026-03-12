package com.mnn.sample.model

import com.google.gson.annotations.SerializedName

/**
 * Model catalog data classes matching official MNN model_market.json format
 */

data class ModelCatalog(
    val version: String,
    val tagTranslations: Map<String, String>,
    val quickFilterTags: List<String>,
    val vendorOrder: List<String>,
    val models: List<ModelItem>
)

data class ModelItem(
    val modelName: String,
    val vendor: String,
    @SerializedName("size_gb") val sizeGb: Double,
    val tags: List<String>,
    val categories: List<String>,
    val sources: Map<String, String>, // e.g., {"HuggingFace": "taobao-mnn/Qwen3.5-2B-MNN"}
    @SerializedName("min_app_version") val minAppVersion: String? = null,
    val description: String? = null,
    @SerializedName("file_size") val fileSize: Long = 0L,
    @SerializedName("extra_tags") val extraTags: List<String> = emptyList()
) {
    /**
     * Get the primary source (prefer HuggingFace, then ModelScope, then first available)
     */
    fun getPrimarySource(): Pair<String, String>? {
        return when {
            sources.containsKey("HuggingFace") -> "HuggingFace" to sources["HuggingFace"]!!
            sources.containsKey("ModelScope") -> "ModelScope" to sources["ModelScope"]!!
            sources.containsKey("Modelers") -> "Modelers" to sources["Modelers"]!!
            sources.isNotEmpty() -> sources.entries.first().toPair()
            else -> null
        }
    }
    
    /**
     * Get formatted size string
     */
    fun getFormattedSize(): String {
        return when {
            fileSize > 0 -> formatBytes(fileSize)
            sizeGb > 0 -> "%.1f GB".format(sizeGb)
            else -> "Unknown size"
        }
    }
    
    /**
     * Get display tags (user-friendly)
     */
    fun getDisplayTags(): String {
        return tags.joinToString(", ")
    }
    
    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.1f KB".format(kb)
            else -> "$bytes B"
        }
    }
}
