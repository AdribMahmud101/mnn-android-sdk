package com.mnn.sample.model

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.mnn.sample.model.ModelCatalog
import com.mnn.sample.model.ModelItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Advanced Model Downloader with catalog fetching from official MNN model market
 * Based on official MNN LLM Chat app architecture
 */
class AdvancedModelDownloader(private val context: Context) {
    
    companion object {
        private const val TAG = "AdvancedModelDownloader"
        private const val CATALOG_URL = "https://meta.alicdn.com/data/mnn/apis/model_market.json"
        private const val CATALOG_CACHE_FILE = "model_catalog_cache.json"
        private const val CACHE_VALID_HOURS = 24
        private const val BUFFER_SIZE = 8192
private const val CONNECT_TIMEOUT = 30L // seconds
        private const val READ_TIMEOUT = 60L // seconds
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    sealed class CatalogState {
        object Loading : CatalogState()
        data class Success(val catalog: ModelCatalog) : CatalogState()
        data class Error(val message: String) : CatalogState()
    }
    
    sealed class DownloadState {
        object Idle : DownloadState()
        object Preparing : DownloadState()
        data class Downloading(
            val progress: Int,
            val downloadedBytes: Long,
            val totalBytes: Long,
            val speedBytesPerSec: Long,
            val currentFile: String = ""
        ) : DownloadState()
        data class Completed(val filePath: String) : DownloadState()
        data class Failed(val error: String) : DownloadState()
    }

    /**
     * Describes exactly which files a specific model needs, derived from its llm_config.json.
     *
     * MNN models can require very different file sets:
     *  - All models: llm.mnn, llm.mnn.weight, llm_config.json, tokenizer.txt
     *  - Vision models (is_visual=true): visual.mnn, visual.mnn.weight, vit_config.json
     *  - Models with tied embeddings (e.g. Qwen2.5): embeddings_bf16.bin or embeddings.bin
     *  - Audio models: audio_encoder.mnn
     */
    data class ModelProfile(
        /** Files that MUST download for the model to work (404 = fatal). */
        val requiredFiles: List<String>,
        /** Files to attempt but silently skip on 404/error. */
        val optionalFiles: List<String>,
        /** True if the model declared is_visual=true and visual.mnn was found on the server. */
        val isVisual: Boolean = false
    ) {
        val allFiles: List<String> get() = requiredFiles + optionalFiles

        companion object {
            /** Minimal profile used when the config is unavailable before download. */
            val FALLBACK = ModelProfile(
                requiredFiles = listOf("llm.mnn", "llm.mnn.weight", "llm_config.json", "tokenizer.txt"),
                optionalFiles = listOf("embeddings_bf16.bin", "embeddings.bin")
            )

            /**
             * Build a profile from a parsed llm_config.json (as a JSONObject).
             * This is called after we have downloaded the config, so we know what the model needs.
             */
            fun fromConfig(config: org.json.JSONObject, hasVisualAssets: Boolean): ModelProfile {
                val required = mutableListOf(
                    "llm.mnn",
                    "llm.mnn.weight",
                    "llm_config.json",
                    "tokenizer.txt"
                )
                val optional = mutableListOf<String>()

                // ---------- tied / untied embeddings ----------
                // embeddings_bf16.bin / embeddings.bin hold the LM-head weights when they are
                // NOT tied to the input embeddings inside llm.mnn.
                // Qwen2.5 uses embeddings_bf16.bin; some older models use embeddings.bin.
                // We probe both as optional so models without them work fine.
                val embFile = config.optString("embedding_file", "")
                when {
                    embFile.isNotBlank()       -> required.add(embFile)       // explicit in config
                    config.has("tie_embeddings") -> { /* no extra file — embeddings baked into llm.mnn */ }
                    else -> {
                        // Qwen2.5 0.5B has no tie_embeddings key → needs embeddings_bf16.bin
                        optional.add("embeddings_bf16.bin")
                        optional.add("embeddings.bin")
                    }
                }

                // ---------- vision / multimodal ----------
                val isVisual = config.optBoolean("is_visual", false)
                if (isVisual && hasVisualAssets) {
                    required.add("visual.mnn")
                    required.add("visual.mnn.weight")
                    // vit_config.json is optional – not all visual models have it
                    optional.add("vit_config.json")
                }
                // Qwen3.5 / Qwen-VL image-pad tokens sometimes stored externally
                if (config.has("image_pad_token_file")) {
                    optional.add(config.optString("image_pad_token_file"))
                }

                // ---------- audio models ----------
                if (config.optBoolean("is_audio", false)) {
                    required.add("audio_encoder.mnn")
                    optional.add("audio_encoder.mnn.weight")
                }

                // ---------- extra tokenizer files ----------
                val tokFile = config.optString("tokenizer_file", "")
                if (tokFile.isNotBlank() && tokFile != "tokenizer.txt" && !required.contains(tokFile)) {
                    optional.add(tokFile)
                }

                return ModelProfile(
                    requiredFiles = required.distinct(),
                    optionalFiles = optional.distinct().filterNot { required.contains(it) },
                    isVisual = isVisual && hasVisualAssets
                )
            }
        }
    }

    /**
     * Probe the remote repo for a model's llm_config.json, parse it, and return
     * a [ModelProfile] that describes exactly which files the model needs.
     *
     * Visual assets (visual.mnn) are probed with a HEAD request; if absent on
     * the server they are excluded so text-only inference still works.
     *
     * Returns [ModelProfile.FALLBACK] on any error so download can still proceed.
     */
    private suspend fun probeModelProfile(
        sourceType: String,
        repoPath: String
    ): ModelProfile = withContext(Dispatchers.IO) {
        try {
            val configUrl = constructDownloadUrl(sourceType, repoPath, "llm_config.json")
            val probeClient = httpClient.newBuilder().followRedirects(true).build()

            val configJson = probeClient.newCall(
                Request.Builder().url(configUrl).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext ModelProfile.FALLBACK
                resp.body?.string() ?: return@withContext ModelProfile.FALLBACK
            }

            val config = try {
                org.json.JSONObject(configJson)
            } catch (_: Exception) {
                return@withContext ModelProfile.FALLBACK
            }

            // Check whether visual.mnn actually exists on the server
            val hasVisualAssets = if (config.optBoolean("is_visual", false)) {
                val visualUrl = constructDownloadUrl(sourceType, repoPath, "visual.mnn")
                try {
                    probeClient.newCall(
                        Request.Builder().url(visualUrl).head().build()
                    ).execute().use { it.isSuccessful }
                } catch (_: Exception) { false }
            } else false

            ModelProfile.fromConfig(config, hasVisualAssets).also {
                Log.i(TAG, "ModelProfile for $repoPath: required=${it.requiredFiles}, " +
                           "optional=${it.optionalFiles}, isVisual=${it.isVisual}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not probe model profile for $repoPath: ${e.message}")
            ModelProfile.FALLBACK
        }
    }

    /**
     * Build a [ModelProfile] from an already-downloaded llm_config.json on disk.
     */
    private fun profileFromDisk(modelDir: File): ModelProfile {
        val configFile = File(modelDir, "llm_config.json")
        if (!configFile.exists()) return ModelProfile.FALLBACK
        return try {
            val config = org.json.JSONObject(configFile.readText())
            // For local validation: only mark visual as required when visual.mnn is present on disk
            val hasVisualAssets = File(modelDir, "visual.mnn").exists()
            ModelProfile.fromConfig(config, hasVisualAssets)
        } catch (_: Exception) {
            ModelProfile.FALLBACK
        }
    }
    
    /**
     * Fetch model catalog from official MNN CDN with caching
     */
    suspend fun fetchModelCatalog(forceRefresh: Boolean = false): Flow<CatalogState> = flow {
        emit(CatalogState.Loading)
        
        try {
            // Try cache first (any age) for instant display
            if (!forceRefresh) {
                val cached = loadAnyCachedCatalog()
                if (cached != null) {
                    emit(CatalogState.Success(cached))
                    // If cache is still fresh, don't fetch from network
                    if (isCacheFresh()) {
                        return@flow
                    }
                    // Otherwise, continue to fetch updated data in background
                }
            }
            
            // Fetch from network
            val request = Request.Builder()
                .url(CATALOG_URL)
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            
            val json = response.body?.string() ?: throw Exception("Empty response body")
            val catalog = gson.fromJson(json, ModelCatalog::class.java)
            
            // Cache the result
            saveCatalogCache(json)
            
            emit(CatalogState.Success(catalog))
            Log.i(TAG, "Fetched catalog v${catalog.version} with ${catalog.models.size} models")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch catalog", e)
            
            // Try to fall back to cache on error (any age)
            val cached = loadAnyCachedCatalog()
            if (cached != null) {
                emit(CatalogState.Success(cached))
            } else {
                emit(CatalogState.Error(e.message ?: "Unknown error"))
            }
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Download a model from the catalog.
     *
     * Files are stored in models/{modelBaseName}/ with their ORIGINAL names so that
     * MNN::Transformer::Llm::createLLM(configPath) can find them.
     *
     * The exact file list is determined dynamically:
     *  1. llm_config.json is downloaded first.
     *  2. A [ModelProfile] is built from the config, revealing which additional files
     *     the model needs (vit weights for vision models, embeddings files, audio encoder, …).
     *  3. Everything else is downloaded according to the profile.
     */
    fun downloadModel(
        modelItem: ModelItem,
        preferredSource: String = "HuggingFace"
    ): Flow<DownloadState> = flow {
        val downloadedFiles = mutableListOf<File>()
        try {
            emit(DownloadState.Preparing)

            val modelsRoot = File(context.filesDir, "models")
            if (!modelsRoot.exists()) modelsRoot.mkdirs()

            // Determine source
            val repoPath = modelItem.sources[preferredSource]
                ?: modelItem.getPrimarySource()?.second
                ?: throw Exception("No download source available for ${modelItem.modelName}")

            val currentSource = modelItem.sources.filterValues { it == repoPath }.keys.firstOrNull()
                ?: preferredSource

            val modelBaseName = modelItem.modelName.replace("/", "_").replace(" ", "_")
            val modelDir = File(modelsRoot, modelBaseName)
            if (!modelDir.exists()) modelDir.mkdirs()

            // Save source metadata so we can repair missing files later
            File(modelDir, "source.json").writeText(
                "{\"source\":\"$currentSource\",\"repo\":\"$repoPath\"}")

            val configFile = File(modelDir, "llm_config.json")

            // -- Step 1: download llm_config.json first so we can build the profile --
            if (!configFile.exists() || configFile.length() < 10) {
                val configUrl = constructDownloadUrl(currentSource, repoPath, "llm_config.json")
                Log.i(TAG, "Fetching config: $configUrl")
                downloadFile(configUrl, configFile, 50_000L, "llm_config.json").collect { state ->
                    when (state) {
                        is DownloadState.Failed -> throw Exception("Failed to download llm_config.json: ${state.error}")
                        else -> { /* progress not reported at this stage */ }
                    }
                }
                downloadedFiles.add(configFile)
            }

            // -- Step 2: build profile from local config + server probe for visual assets --
            val profile: ModelProfile = run {
                val config = try {
                    org.json.JSONObject(configFile.readText())
                } catch (_: Exception) {
                    return@run ModelProfile.FALLBACK
                }
                val hasVisualAssets = if (config.optBoolean("is_visual", false)) {
                    val probeClient = httpClient.newBuilder().followRedirects(true).build()
                    val visualUrl = constructDownloadUrl(currentSource, repoPath, "visual.mnn")
                    try {
                        probeClient.newCall(Request.Builder().url(visualUrl).head().build())
                            .execute().use { it.isSuccessful }
                    } catch (_: Exception) { false }
                } else false

                ModelProfile.fromConfig(config, hasVisualAssets).also {
                    Log.i(TAG, "Profile for ${modelItem.modelName}: " +
                               "required=${it.requiredFiles}, optional=${it.optionalFiles}, visual=${it.isVisual}")
                }
            }

            // Patch config if visual assets aren't available on the server
            if (!profile.isVisual) patchConfigDisableVisual(modelDir)

            // -- Step 3: download remaining files according to profile --
            // Skip llm_config.json – already downloaded above
            val remainingFiles = profile.allFiles.filter { it != "llm_config.json" }

            // Check if everything is already present
            val missingRequired = profile.requiredFiles.filter { it != "llm_config.json" }
                .filter { name -> !File(modelDir, name).let { it.exists() && it.length() > 100 } }
            if (missingRequired.isEmpty()) {
                Log.d(TAG, "Model already fully downloaded: ${modelDir.absolutePath}")
                emit(DownloadState.Completed(configFile.absolutePath))
                return@flow
            }

            remainingFiles.forEachIndexed { index, fileName ->
                val isOptional = fileName in profile.optionalFiles
                val fileUrl = constructDownloadUrl(currentSource, repoPath, fileName)
                val outputFile = File(modelDir, fileName)

                Log.i(TAG, "Downloading ${index + 1}/${remainingFiles.size}: $fileName")

                if (outputFile.exists() && outputFile.length() > 100) {
                    Log.d(TAG, "Already cached: $fileName")
                    downloadedFiles.add(outputFile)
                    emit(DownloadState.Downloading(
                        progress = ((index + 1) * 100) / remainingFiles.size,
                        downloadedBytes = 0,
                        totalBytes = modelItem.fileSize,
                        speedBytesPerSec = 0,
                        currentFile = "$fileName (cached)"
                    ))
                    return@forEachIndexed
                }

                if (outputFile.exists()) outputFile.delete()
                val estimatedFileSize = if (modelItem.fileSize > 0) modelItem.fileSize / remainingFiles.size else 0L
                var downloadSuccessful = false

                try {
                    downloadFile(fileUrl, outputFile, estimatedFileSize, fileName).collect { state ->
                        when (state) {
                            is DownloadState.Downloading -> emit(state.copy(
                                progress = ((index * 100 + state.progress) / remainingFiles.size),
                                currentFile = "${index + 1}/${remainingFiles.size}: $fileName"
                            ))
                            is DownloadState.Completed -> {
                                Log.d(TAG, "Downloaded: $fileName")
                                downloadedFiles.add(outputFile)
                                downloadSuccessful = true
                            }
                            is DownloadState.Failed -> {
                                if (isOptional) {
                                    Log.w(TAG, "Optional file not available: $fileName – skipping")
                                    downloadSuccessful = true
                                } else {
                                    throw Exception("Failed to download $fileName: ${state.error}")
                                }
                            }
                            else -> emit(state)
                        }
                    }
                } catch (e: Exception) {
                    if (isOptional) {
                        Log.w(TAG, "Optional $fileName unavailable (${e.message}) – skipping")
                        if (outputFile.exists()) outputFile.delete()
                        downloadSuccessful = true
                    } else throw e
                }

                if (!downloadSuccessful)
                    throw Exception("No success state received for $fileName")
            }

            // Final verification (required files only)
            val allRequired = profile.requiredFiles.all { name ->
                File(modelDir, name).let { it.exists() && it.length() > 100 }
            }
            if (allRequired) {
                emit(DownloadState.Completed(configFile.absolutePath))
            } else {
                val missing = profile.requiredFiles.filter { !File(modelDir, it).exists() }
                throw Exception("Download incomplete – missing: $missing")
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG, "Download cancelled for ${modelItem.modelName}")
            downloadedFiles.forEach { if (it.exists()) it.delete() }
            emit(DownloadState.Failed("Download cancelled by user"))
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${modelItem.modelName}", e)
            downloadedFiles.forEach { if (it.exists()) it.delete() }
            emit(DownloadState.Failed(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Construct download URL based on source type
     */
    private fun constructDownloadUrl(sourceType: String, repoPath: String, fileName: String): String {
        return when (sourceType) {
            "HuggingFace" -> {
                // HuggingFace format: https://huggingface.co/{repo}/resolve/main/{file}
                "https://huggingface.co/$repoPath/resolve/main/$fileName"
            }
            "ModelScope" -> {
                // ModelScope format: https://modelscope.cn/api/v1/models/{repo}/repo?Revision=master&FilePath={file}
                "https://www.modelscope.cn/api/v1/models/$repoPath/repo?Revision=master&FilePath=$fileName"
            }
            "Modelers" -> {
                // Modelers format: similar to ModelScope
                "https://www.modelers.cn/api/v1/models/$repoPath/repo?Revision=master&FilePath=$fileName"
            }
            else -> {
                throw IllegalArgumentException("Unknown source type: $sourceType")
            }
        }
    }
    
    /**
     * Download file with progress tracking
     */
    private fun downloadFile(
        url: String,
        outputFile: File,
        expectedSize: Long,
        fileName: String = ""
    ): Flow<DownloadState> = flow {
        val request = Request.Builder()
            .url(url)
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            val errorMsg = when (response.code) {
                401 -> "Unauthorized: This model may have moved or been renamed.\n\n" +
                       "Try:\n" +
                       "1. Use 'Browse Full Catalog' for updated models\n" +
                       "2. Try a different model from the list\n" +
                       "3. Visit HuggingFace to check model availability"
                403 -> "Forbidden: Access denied to this model"
                404 -> "Not Found: Model file does not exist at this URL.\n\n" +
                       "The model may have been renamed or moved.\n" +
                       "Try 'Browse Full Catalog' for current models."
                else -> "HTTP ${response.code}: ${response.message}"
            }
            throw Exception(errorMsg)
        }
        
        val totalBytes = response.body?.contentLength() ?: expectedSize
        var downloadedBytes = 0L
        val startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        var lastDownloadedBytes = 0L
        
        response.body?.byteStream()?.use { input ->
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
                            (bytesDiff * 1000) / timeDiff
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
    }
    
    /**
     * Return the llm_config.json File for every fully-downloaded model.
     * "Fully downloaded" is determined per-model using its [ModelProfile], so a vision
     * model is only considered complete when its visual.mnn is also present, while a
     * text-only model is complete with just the four base files.
     */
    suspend fun getDownloadedModels(): List<File> = withContext(Dispatchers.IO) {
        val modelsRoot = File(context.filesDir, "models")
        if (!modelsRoot.exists()) return@withContext emptyList()

        migrateOldFlatFiles(modelsRoot)

        modelsRoot.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val configFile = File(dir, "llm_config.json")
                if (!configFile.exists() || configFile.length() < 10) return@mapNotNull null

                // Build profile from on-disk config – uses actual local file presence for visual
                val profile = profileFromDisk(dir)

                // Only required files must be present; optional files are truly optional
                val complete = profile.requiredFiles.all { name ->
                    File(dir, name).let { it.exists() && it.length() > 100 }
                }
                if (!complete) return@mapNotNull null

                // Apply visual patch only if needed (idempotent)
                if (!File(dir, "visual.mnn").exists()) patchConfigDisableVisual(dir)

                configFile
            }
            ?: emptyList()
    }

    /**
     * Scan a model directory for files that the model's [ModelProfile] says are needed
     * but are not yet present on disk, then download them.
     *
     * This handles heterogeneous models correctly:
     *  - Qwen2.5 0.5B needs embeddings_bf16.bin  → downloaded if absent
     *  - Qwen3.5 2B VLM needs visual.mnn/weight  → downloaded if absent (and available)
     *  - Qwen3 1.7B text-only needs neither       → nothing extra downloaded
     */
    suspend fun repairMissingFiles(modelDir: File): Flow<DownloadState> = flow {
        val configFile = File(modelDir, "llm_config.json")
        if (!configFile.exists()) {
            emit(DownloadState.Completed(configFile.absolutePath))
            return@flow
        }

        // Read saved source metadata
        val sourceFile = File(modelDir, "source.json")
        val (source, repo) = if (sourceFile.exists()) {
            try {
                val json = org.json.JSONObject(sourceFile.readText())
                Pair(json.getString("source"), json.optString("repo").takeIf { it.isNotBlank() })
            } catch (_: Exception) { Pair("HuggingFace", null) }
        } else Pair("HuggingFace", null)

        // Infer repo from directory name if not saved
        val repoPath: String = repo ?: run {
            val name = modelDir.name
            val probeClient = httpClient.newBuilder().followRedirects(true).build()
            val candidates = listOf("taobao-mnn/$name", "MNN/$name", "alibaba-mnn/$name")
            candidates.firstOrNull { candidate ->
                val url = constructDownloadUrl("HuggingFace", candidate, "llm_config.json")
                try {
                    probeClient.newCall(Request.Builder().url(url).head().build())
                        .execute().use { it.isSuccessful }
                } catch (_: Exception) { false }
            } ?: run {
                Log.w(TAG, "Could not locate remote repo for ${modelDir.name} – skipping repair")
                emit(DownloadState.Completed(configFile.absolutePath))
                return@flow
            }
        }

        // Save repo path if we had to probe for it
        if (repo == null) sourceFile.writeText("{\"source\":\"$source\",\"repo\":\"$repoPath\"}")

        // Build profile from config + live server probe for visual assets
        val profile = probeModelProfile(source, repoPath)

        // Find which required+optional files are genuinely missing
        val missing = profile.allFiles.filter { name ->
            !File(modelDir, name).let { it.exists() && it.length() > 100 }
        }

        if (missing.isEmpty()) {
            Log.d(TAG, "No missing files for ${modelDir.name}")
            // Apply visual patch in case it's needed (idempotent)
            if (!profile.isVisual) patchConfigDisableVisual(modelDir)
            emit(DownloadState.Completed(configFile.absolutePath))
            return@flow
        }

        Log.i(TAG, "Repairing ${modelDir.name}: missing files = $missing")
        emit(DownloadState.Preparing)

        missing.forEachIndexed { index, fileName ->
            val isOptional = fileName in profile.optionalFiles
            val outputFile = File(modelDir, fileName)
            val url = constructDownloadUrl(source, repoPath, fileName)

            Log.i(TAG, "Repair download ${index + 1}/${missing.size}: $fileName")
            try {
                downloadFile(url, outputFile, 0L, fileName).collect { state ->
                    when (state) {
                        is DownloadState.Downloading -> emit(state.copy(
                            currentFile = "Repairing ${index + 1}/${missing.size}: $fileName"
                        ))
                        is DownloadState.Failed -> {
                            if (isOptional) {
                                Log.w(TAG, "Optional $fileName unavailable during repair – skipping")
                            } else {
                                Log.e(TAG, "Required $fileName repair failed: ${state.error}")
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                if (isOptional) {
                    Log.w(TAG, "Optional $fileName repair error (${e.message}) – skipping")
                    if (outputFile.exists()) outputFile.delete()
                } else {
                    Log.e(TAG, "Required $fileName repair exception", e)
                }
            }
        }

        // Apply visual patch if visual.mnn was not obtained (or never needed)
        if (!File(modelDir, "visual.mnn").exists()) patchConfigDisableVisual(modelDir)

        emit(DownloadState.Completed(configFile.absolutePath))
    }.flowOn(Dispatchers.IO)

    /**
     * If llm_config.json says is_visual = true but visual.mnn is absent, patch it to false
     * so the model can still be used for text-only inference.
     */
    private fun patchConfigDisableVisual(modelDir: File) {
        if (File(modelDir, "visual.mnn").exists()) return
        val configFile = File(modelDir, "llm_config.json")
        if (!configFile.exists()) return
        try {
            val json = gson.fromJson(configFile.readText(), com.google.gson.JsonObject::class.java)
            if (json.get("is_visual")?.asBoolean == true) {
                json.addProperty("is_visual", false)
                // Also remove vision-specific fields that MNN might reject without visual.mnn
                listOf("image_size", "vision_start", "vision_end", "image_pad",
                       "num_grid_per_side", "has_deepstack", "image_mean", "image_norm")
                    .forEach { json.remove(it) }
                configFile.writeText(gson.toJson(json))
                Log.i(TAG, "Patched is_visual=false in ${configFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not patch visual config: ${e.message}")
        }
    }

    /**
     * Migrate files saved in the old flat layout (ModelName.mnn, ModelName.mnn.weight,
     * ModelName_config.json, tokenizer.txt) into the new subdirectory layout.
     */
    private fun migrateOldFlatFiles(modelsRoot: File) {
        modelsRoot.listFiles { f -> f.isFile && f.extension == "mnn" && !f.name.endsWith(".weight") }
            ?.forEach { mnnFile ->
                val modelBaseName = mnnFile.nameWithoutExtension
                val subDir = File(modelsRoot, modelBaseName)
                if (subDir.exists()) return@forEach // already migrated
                subDir.mkdirs()

                val migrations = mapOf(
                    mnnFile to File(subDir, "llm.mnn"),
                    File(modelsRoot, "${modelBaseName}.mnn.weight") to File(subDir, "llm.mnn.weight"),
                    File(modelsRoot, "${modelBaseName}_config.json") to File(subDir, "llm_config.json"),
                    File(modelsRoot, "tokenizer.txt") to File(subDir, "tokenizer.txt")
                )
                migrations.forEach { (old, new) ->
                    if (old.exists()) {
                        if (old.renameTo(new)) Log.i(TAG, "Migrated ${old.name} → ${new.absolutePath}")
                        else Log.w(TAG, "Could not migrate ${old.name}")
                    }
                }
                Log.i(TAG, "Migration complete for $modelBaseName")
                patchConfigDisableVisual(subDir)
            }
    }

    /**
     * Delete a downloaded model directory.
     */
    suspend fun deleteModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            File(modelPath).let { f ->
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: $modelPath", e)
            false
        }
    }
    
    /**
     * Load cached catalog (respects age limit)
     */
    private suspend fun loadCachedCatalog(): ModelCatalog? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(context.cacheDir, CATALOG_CACHE_FILE)
            if (!cacheFile.exists()) {
                return@withContext null
            }
            
            // Check cache age
            val ageHours = (System.currentTimeMillis() - cacheFile.lastModified()) / (1000 * 60 * 60)
            if (ageHours > CACHE_VALID_HOURS) {
                Log.d(TAG, "Cache expired ($ageHours hours old)")
                return@withContext null
            }
            
            val json = cacheFile.readText()
            gson.fromJson(json, ModelCatalog::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached catalog", e)
            null
        }
    }
    
    /**
     * Load any cached catalog regardless of age (for fast display)
     */
    private suspend fun loadAnyCachedCatalog(): ModelCatalog? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(context.cacheDir, CATALOG_CACHE_FILE)
            if (!cacheFile.exists()) {
                return@withContext null
            }
            
            val json = cacheFile.readText()
            val catalog = gson.fromJson(json, ModelCatalog::class.java)
            Log.d(TAG, "Loaded cached catalog (any age)")
            catalog
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load any cached catalog", e)
            null
        }
    }
    
    /**
     * Check if cache is still fresh
     */
    private fun isCacheFresh(): Boolean {
        val cacheFile = File(context.cacheDir, CATALOG_CACHE_FILE)
        if (!cacheFile.exists()) return false
        
        val ageHours = (System.currentTimeMillis() - cacheFile.lastModified()) / (1000 * 60 * 60)
        return ageHours <= CACHE_VALID_HOURS
    }
    
    /**
     * Save catalog to cache
     */
    private suspend fun saveCatalogCache(json: String) = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(context.cacheDir, CATALOG_CACHE_FILE)
            cacheFile.writeText(json)
            Log.d(TAG, "Catalog cached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache catalog", e)
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
