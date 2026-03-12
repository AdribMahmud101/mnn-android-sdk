package com.mnn.sample

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Model downloader with progress tracking
 * Based on official MNN LLM Chat app
 */
class ModelDownloader(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelDownloader"
        private const val BUFFER_SIZE = 8192
        private const val CONNECT_TIMEOUT = 30000 // 30 seconds
        private const val READ_TIMEOUT = 30000
        
        /**
         * IMPORTANT: Update these URLs with your actual model download links!
         * 
         * To get MNN models:
         * 1. ModelScope (Alibaba's platform): https://www.modelscope.cn/models/MNN
         * 2. HuggingFace: https://huggingface.co/models?other=mnn
         * 3. Use MNN's export tools to convert your own models
         * 
         * Example ModelScope URL format:
         * https://www.modelscope.cn/models/MNN/Qwen2.5-0.5B-MNN/resolve/master/qwen2.5-0.5b-mnn.mnn
         * 
         * Or host your own models and provide direct download URLs
         */
        val AVAILABLE_MODELS = mapOf(
            "qwen-0.5b" to ModelInfo(
                name = "Qwen-0.5B (Example)",
                url = "YOUR_MODEL_URL_HERE", // Replace with actual URL
                size = 512_000_000, // ~512 MB
                description = "Update URL in ModelDownloader.kt"
            ),
            "test-model" to ModelInfo(
                name = "Test Model",
                url = "YOUR_MODEL_URL_HERE", // Replace with actual URL
                size = 100_000_000,
                description = "Add your model URL here"
            )
        )
    }
    
    data class ModelInfo(
        val name: String,
        val url: String,
        val size: Long,
        val description: String
    )
    
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val progress: Int,
            val downloadedBytes: Long,
            val totalBytes: Long,
            val speedBytesPerSec: Long
        ) : DownloadState()
        data class Completed(val filePath: String) : DownloadState()
        data class Failed(val error: String) : DownloadState()
    }
    
    /**
     * Download model with progress tracking
     */
    fun downloadModel(
        modelKey: String,
        customUrl: String? = null
    ): Flow<DownloadState> = flow {
        try {
            val modelInfo = AVAILABLE_MODELS[modelKey]
                ?: throw IllegalArgumentException("Unknown model: $modelKey")
            
            val downloadUrl = customUrl ?: modelInfo.url
            val modelDir = File(context.filesDir, "models")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }
            
            val fileName = "$modelKey.mnn"
            val outputFile = File(modelDir, fileName)
            
            // Check if already exists
            if (outputFile.exists()) {
                Log.d(TAG, "Model already exists: ${outputFile.absolutePath}")
                emit(DownloadState.Completed(outputFile.absolutePath))
                return@flow
            }
            
            // Download file
            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.requestMethod = "GET"
            
            try {
                connection.connect()
                
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorMsg = when (responseCode) {
                        HttpURLConnection.HTTP_UNAUTHORIZED -> {
                            "Server returned 401: Unauthorized\n\n" +
                            "The model URL requires authentication or is invalid.\n" +
                            "Please update the URL in ModelDownloader.kt with a valid, publicly accessible model URL.\n\n" +
                            "Options:\n" +
                            "1. Download models from ModelScope: https://www.modelscope.cn/models/MNN\n" +
                            "2. Use HuggingFace public models: https://huggingface.co/models?other=mnn\n" +
                            "3. Host your own model and provide a direct download link"
                        }
                        HttpURLConnection.HTTP_NOT_FOUND -> {
                            "Server returned 404: Model not found at URL"
                        }
                        HttpURLConnection.HTTP_FORBIDDEN -> {
                            "Server returned 403: Access forbidden"
                        }
                        else -> {
                            "Server returned $responseCode: ${connection.responseMessage}"
                        }
                    }
                    throw Exception(errorMsg)
                }
                
                val totalBytes = connection.contentLengthLong
                var downloadedBytes = 0L
                val startTime = System.currentTimeMillis()
                var lastUpdateTime = startTime
                var lastDownloadedBytes = 0L
                
                connection.inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            val currentTime = System.currentTimeMillis()
                            val timeDiff = currentTime - lastUpdateTime
                            
                            // Update progress every 500ms
                            if (timeDiff >= 500) {
                                val bytesDiff = downloadedBytes - lastDownloadedBytes
                                val speedBytesPerSec = if (timeDiff > 0) {
                                    (bytesDiff * 1000 / timeDiff)
                                } else {
                                    0L
                                }
                                
                                val progress = if (totalBytes > 0) {
                                    ((downloadedBytes * 100) / totalBytes).toInt()
                                } else {
                                    0
                                }
                                
                                emit(DownloadState.Downloading(
                                    progress = progress,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    speedBytesPerSec = speedBytesPerSec
                                ))
                                
                                lastUpdateTime = currentTime
                                lastDownloadedBytes = downloadedBytes
                            }
                        }
                    }
                }
                
                Log.d(TAG, "Download completed: ${outputFile.absolutePath}")
                emit(DownloadState.Completed(outputFile.absolutePath))
                
            } finally {
                connection.disconnect()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            emit(DownloadState.Failed(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Get list of downloaded models
     */
    suspend fun getDownloadedModels(): List<File> = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) {
            return@withContext emptyList()
        }
        
        modelDir.listFiles { file ->
            file.isFile && file.extension == "mnn"
        }?.toList() ?: emptyList()
    }
    
    /**
     * Delete a downloaded model
     */
    suspend fun deleteModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(modelPath)
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: $modelPath", e)
            false
        }
    }
    
    /**
     * Format bytes to human-readable size
     */
    fun formatSize(bytes: Long): String {
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
    
    /**
     * Format speed to human-readable format
     */
    fun formatSpeed(bytesPerSec: Long): String {
        return formatSize(bytesPerSec) + "/s"
    }
}
