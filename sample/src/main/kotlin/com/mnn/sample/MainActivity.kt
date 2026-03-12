package com.mnn.sample

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mnn.sdk.MNNEngine
import com.mnn.sdk.MNNLlm
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mnn.sample.model.AdvancedModelDownloader
import com.mnn.sample.model.ModelCatalog
import com.mnn.sample.model.ModelItem

/**
 * Enhanced LLM Chat Demo with Model Downloading and Performance Metrics
 * Based on official MNN LLM Chat app architecture
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var photoButton: ImageButton
    private lateinit var loadingProgress: ProgressBar
    private lateinit var metricsText: TextView
    private lateinit var thinkBadge: TextView
    private lateinit var visionBadge: TextView
    private lateinit var thinkingBar: LinearLayout
    private lateinit var thinkingSwitch: SwitchCompat
    private lateinit var imagePreviewBar: LinearLayout
    private lateinit var selectedImageThumb: ImageView
    private lateinit var selectedImageName: TextView
    private lateinit var removeImageButton: Button

    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()

    private lateinit var mnnEngine: MNNEngine
    private lateinit var advancedDownloader: AdvancedModelDownloader
    private var modelCatalog: ModelCatalog? = null
    private var isInitialized = false
    private var isProcessing = false
    private var lastMetrics: PerformanceMetrics? = null
    private var downloadJob: Job? = null
    // Persistent LLM session – loaded once, reused across messages.
    private var activeLlm: MNNLlm? = null
    private var activeLlmName: String = ""
    private var activeConfigFile: File? = null
    // Thinking mode toggle (persisted per session; applied before each inference call)
    private var thinkingEnabled: Boolean = false
    // Currently selected image path (null = no image attached)
    private var pendingImagePath: String? = null

    // Image picker: opens gallery and copies result to cache dir for stable file access
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val copied = copyUriToCache(uri)
            if (copied != null) {
                pendingImagePath = copied
                showImagePreview(copied, getFileName(uri))
            } else {
                Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_chat)
        
        setupViews()
        setupRecyclerView()
        
        advancedDownloader = AdvancedModelDownloader(this)
        
        // Fetch model catalog in background (uses cache for instant load)
        lifecycleScope.launch {
            advancedDownloader.fetchModelCatalog(forceRefresh = false).collect { state ->
                when (state) {
                    is AdvancedModelDownloader.CatalogState.Success -> {
                        modelCatalog = state.catalog
                        Log.d("MainActivity", "Model catalog loaded: ${state.catalog.models.size} models")
                    }
                    is AdvancedModelDownloader.CatalogState.Error -> {
                        Log.e("MainActivity", "Failed to load catalog: ${state.message}")
                    }
                    else -> {}
                }
            }
        }
        
        initializeMNN()
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_download_model -> {
                showModelDownloadDialog()
                true
            }
            R.id.action_manage_models -> {
                showModelManagementDialog()
                true
            }
            R.id.action_clear_chat -> {
                clearChat()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupViews() {
        statusText        = findViewById(R.id.statusText)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        inputEditText     = findViewById(R.id.inputEditText)
        sendButton        = findViewById(R.id.sendButton)
        photoButton       = findViewById(R.id.photoButton)
        loadingProgress   = findViewById(R.id.loadingProgress)
        metricsText       = findViewById(R.id.metricsText)
        thinkBadge        = findViewById(R.id.thinkBadge)
        visionBadge       = findViewById(R.id.visionBadge)
        thinkingBar       = findViewById(R.id.thinkingBar)
        thinkingSwitch    = findViewById(R.id.thinkingSwitch)
        imagePreviewBar   = findViewById(R.id.imagePreviewBar)
        selectedImageThumb = findViewById(R.id.selectedImageThumb)
        selectedImageName = findViewById(R.id.selectedImageName)
        removeImageButton = findViewById(R.id.removeImageButton)

        sendButton.setOnClickListener {
            val message = inputEditText.text.toString().trim()
            if (message.isNotEmpty() || pendingImagePath != null) {
                sendMessage(message)
            }
        }

        inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendButton.performClick()
                true
            } else false
        }

        // Thinking switch handler
        thinkingSwitch.setOnCheckedChangeListener { _, isChecked ->
            thinkingEnabled = isChecked
            activeLlm?.enableThinking = isChecked
        }

        // Photo button: opens gallery
        photoButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            imagePickerLauncher.launch(intent)
        }

        // Remove selected image
        removeImageButton.setOnClickListener {
            clearPendingImage()
        }
    }
    
    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }
    
    private fun initializeMNN() {
        lifecycleScope.launch {
            try {
                updateStatus("Initializing MNN Engine...")
                
                mnnEngine = MNNEngine.initialize(this@MainActivity)
                val version = mnnEngine.getVersion()
                
                updateStatus("✓ MNN v$version ready")
                
                // Check for downloaded models
                checkForModels()
                
                isInitialized = true
                
            } catch (e: Exception) {
                updateStatus("✗ Failed to initialize: ${e.message}")
                Toast.makeText(
                    this@MainActivity,
                    "MNN initialization failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private suspend fun checkForModels() = withContext(Dispatchers.IO) {
        val models = advancedDownloader.getDownloadedModels()
        withContext(Dispatchers.Main) {
            if (models.isEmpty()) {
                updateStatus("⚠ No models found")
                addSystemMessage(
                    "Welcome to MNN LLM Chat!\n\n" +
                    "📥 To chat with an LLM:\n" +
                    "1. Tap the menu (⋮) → Download Model\n" +
                    "2. Select a model (e.g., Qwen2.5-0.5B)\n" +
                    "3. Wait for all 4 files to download (~300 MB)\n" +
                    "4. Start chatting!"
                )
            } else {
                val modelNames = models.joinToString(", ") { it.parentFile?.name ?: it.nameWithoutExtension }
                updateStatus("✓ ${models.size} model(s) ready")
                addSystemMessage("Models available: $modelNames\n\nSend a message to start chatting!")
                // Pre-load the first model so first message is fast
                loadLlmModel(models.first())
            }
        }
    }

    private suspend fun loadLlmModel(configFile: File) = withContext(Dispatchers.IO) {
        val name = configFile.parentFile?.name ?: "model"
        if (activeLlmName == name && activeLlm != null) return@withContext // already loaded

        withContext(Dispatchers.Main) { updateStatus("⏳ Loading $name…") }

        activeLlm?.destroy()
        activeLlm = null
        activeLlmName = ""

        // Auto-repair any missing files for this model (embeddings, visual assets, etc.)
        // repairMissingFiles() uses ModelProfile to know what the model actually needs,
        // so it correctly handles Qwen2.5 (needs embeddings_bf16.bin), Qwen3.5 VLM (needs
        // visual.mnn), and Qwen3 text-only (needs neither extra file).
        val modelDir = configFile.parentFile
        if (modelDir != null) {
            withContext(Dispatchers.Main) { updateStatus("⏳ Checking files for $name…") }
            advancedDownloader.repairMissingFiles(modelDir).collect { state ->
                when (state) {
                    is AdvancedModelDownloader.DownloadState.Downloading -> {
                        val mb = state.downloadedBytes / (1024 * 1024)
                        val totalMb = state.totalBytes / (1024 * 1024)
                        withContext(Dispatchers.Main) {
                            updateStatus("⬇ ${state.currentFile.ifBlank { "Downloading" }}: ${mb}MB / ${totalMb}MB (${state.progress}%)")
                        }
                    }
                    is AdvancedModelDownloader.DownloadState.Preparing -> {
                        withContext(Dispatchers.Main) { updateStatus("⏳ Downloading missing files for $name…") }
                    }
                    else -> {}
                }
            }
        }

        val llm = MNNLlm.create(configFile.absolutePath)
        if (llm == null) {
            withContext(Dispatchers.Main) { updateStatus("✗ Failed to create LLM for $name") }
            return@withContext
        }
        val ok = llm.load()
        if (!ok) {
            llm.destroy()
            withContext(Dispatchers.Main) { updateStatus("✗ Failed to load LLM weights for $name") }
            return@withContext
        }
        activeLlm = llm
        activeLlmName = name
        activeConfigFile = configFile
        // Re-apply thinking preference to newly loaded model
        llm.enableThinking = thinkingEnabled && llm.supportsThinking
        val caps = buildString {
            if (llm.supportsThinking) append(" 🧠")
            if (llm.isVisual) append(" 👁")
        }
        withContext(Dispatchers.Main) {
            updateStatus("✓ $name loaded$caps – ready to chat!")
            // Show/hide thinking bar based on model capability
            thinkBadge.visibility  = if (llm.supportsThinking) View.VISIBLE else View.GONE
            visionBadge.visibility = if (llm.isVisual) View.VISIBLE else View.GONE
            thinkingBar.visibility = if (llm.supportsThinking) View.VISIBLE else View.GONE
            thinkingSwitch.isChecked = thinkingEnabled && llm.supportsThinking
            // Enable/disable photo button based on vision capability
            photoButton.isEnabled = llm.isVisual
            photoButton.alpha = if (llm.isVisual) 1.0f else 0.38f
            // If a VLM was just loaded but image already selected, keep preview visible
            if (!llm.isVisual) clearPendingImage()
        }
    }
    
    private fun sendMessage(text: String) {
        if (!isInitialized) {
            Toast.makeText(this, "MNN is still initializing...", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isProcessing) {
            Toast.makeText(this, "Please wait for current response...", Toast.LENGTH_SHORT).show()
            return
        }

        val displayText = text.ifBlank { if (pendingImagePath != null) "[image]" else return }
        val imagePath = pendingImagePath

        // Add user message (with thumbnail if image attached)
        addMessage(ChatMessage(displayText, isUser = true, imagePath = imagePath))
        inputEditText.text.clear()
        clearPendingImage()
        
        // Process with MNN
        lifecycleScope.launch {
            isProcessing = true
            sendButton.isEnabled = false
            showLoading(true)
            
            try {
                val response = runInferenceWithMetrics(text, imagePath)
                
                // Add AI response (with thinking block if present)
                val llm = activeLlm
                addMessage(ChatMessage(response, isUser = false,
                    thinkingText = llm?.lastThinking))
                
                lastMetrics?.let { metrics -> updateMetrics(metrics) }
                
            } catch (e: Exception) {
                addMessage(ChatMessage("Error: ${e.message}", isUser = false))
            } finally {
                isProcessing = false
                sendButton.isEnabled = true
                showLoading(false)
            }
        }
    }
    
    private suspend fun runInferenceWithMetrics(input: String, imagePath: String? = null): String = withContext(Dispatchers.IO) {
        try {
            val models = advancedDownloader.getDownloadedModels()

            if (models.isEmpty()) {
                return@withContext "⚠ No model downloaded yet.\n\nTap the menu (⋮) → Download Model to get started."
            }

            // Use the explicitly loaded model; only fall back to first model on first startup.
            val configFile = activeConfigFile ?: models.first()
            if (activeLlm == null) {
                loadLlmModel(configFile)
            }

            val llm = activeLlm
                ?: return@withContext "✗ Model failed to load. Check logs."

            // Apply current thinking toggle
            llm.enableThinking = thinkingEnabled

            // Run inference – pass imagePath only if model is visual
            val response = llm.response(
                userMessage = input.ifBlank { "Describe the image." },
                imagePath = if (llm.isVisual) imagePath else null,
                maxNewTokens = 512
            )

            // Log chain-of-thought if present
            llm.lastThinking?.let { thought ->
                Log.d("MainActivity", "[think] ${thought.take(200)}${if (thought.length > 200) "…" else ""}")
            }

            // Capture metrics
            val metrics = llm.lastMetrics()
            lastMetrics = PerformanceMetrics(
                promptTokens    = metrics.promptTokens,
                generatedTokens = metrics.generatedTokens,
                prefillTimeMs   = metrics.prefillMs,
                decodeTimeMs    = metrics.decodeMs,
                totalTimeMs     = metrics.prefillMs + metrics.decodeMs
            )

            response.ifBlank { "(empty response)" }

        } catch (e: Exception) {
            Log.e("MainActivity", "LLM inference error", e)
            "Error: ${e.message}"
        }
    }
    
    private fun showModelDownloadDialog() {
        // Build quick model list with popular models + catalog option
        val quickModelList = mutableListOf(
            "Qwen2.5-0.5B-Instruct (0.5 GB)",
            "Qwen3.5-0.8B (0.8 GB)",
            "Qwen3.5-2B (2.0 GB)",
            "Browse Full Catalog...",
            "Custom URL..."
        )
        
        AlertDialog.Builder(this)
            .setTitle("Download Model")
            .setItems(quickModelList.toTypedArray()) { _, which ->
                when (which) {
                    0 -> downloadPopularModel(0) // Qwen2.5-0.5B-Instruct
                    1 -> downloadPopularModel(1) // Qwen3.5-0.8B
                    2 -> downloadPopularModel(2) // Qwen3.5-2B
                    3 -> {
                        // Browse full catalog
                        if (modelCatalog != null) {
                            showModelCatalogDialog(modelCatalog!!)
                        } else {
                            // Load catalog
                            val loadingDialog = AlertDialog.Builder(this)
                                .setTitle("Loading Catalog")
                                .setMessage("Fetching models...")
                                .setCancelable(true)
                                .create()
                            loadingDialog.show()
                            
                            lifecycleScope.launch {
                                advancedDownloader.fetchModelCatalog(forceRefresh = false).collect { state ->
                                    when (state) {
                                        is AdvancedModelDownloader.CatalogState.Success -> {
                                            modelCatalog = state.catalog
                                            loadingDialog.dismiss()
                                            showModelCatalogDialog(state.catalog)
                                        }
                                        is AdvancedModelDownloader.CatalogState.Error -> {
                                            loadingDialog.dismiss()
                                            Toast.makeText(this@MainActivity, "Failed to load catalog: ${state.message}", Toast.LENGTH_LONG).show()
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                    4 -> showCustomUrlDialog() // Custom URL
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun downloadPopularModel(index: Int) {
        // Map to actual model info from catalog
        val popularModelNames = listOf(
            "Qwen2.5-0.5B-Instruct-MNN",
            "Qwen3.5-0.8B-MNN",
            "Qwen3.5-2B-MNN"
        )
        
        if (index >= popularModelNames.size) return
        
        val modelName = popularModelNames[index]
        
        // If catalog is loaded, use it
        if (modelCatalog != null) {
            val model = modelCatalog!!.models.find { it.modelName == modelName }
            if (model != null) {
                showSourceSelectionDialog(model)
                return
            }
        }
        
        // Fallback: construct basic model info and download from HuggingFace
        val repoPath = when (modelName) {
            "Qwen2.5-0.5B-Instruct-MNN" -> "taobao-mnn/Qwen2.5-0.5B-Instruct-MNN"
            "Qwen3.5-0.8B-MNN" -> "taobao-mnn/Qwen3.5-0.8B-MNN"
            "Qwen3.5-2B-MNN" -> "taobao-mnn/Qwen3.5-2B-MNN"
            else -> return
        }
        
        // Create temporary model item for download
        val tempModel = ModelItem(
            modelName = modelName,
            vendor = "MNN",
            sizeGb = when (modelName) {
                "Qwen3.5-0.8B-MNN" -> 0.8
                "Qwen3.5-2B-MNN" -> 2.0
                "Qwen2.5-0.5B-MNN" -> 0.5
                "DeepSeek-R1-0.5B-MNN" -> 0.5
                else -> 1.0
            },
            tags = listOf("Chat"),
            categories = listOf("recommended"),
            sources = mapOf("HuggingFace" to repoPath),
            fileSize = 0L
        )
        
        startAdvancedModelDownload(tempModel, "HuggingFace")
    }
    
    private fun showModelCatalogDialog(catalog: ModelCatalog) {
        // Show only top recommended models for faster loading
        val models = catalog.models
            .filter { it.categories.contains("recommended") }
            .take(10) // Show top 10 for quick display
        
        if (models.isEmpty()) {
            // Fallback to any models if no recommended
            Toast.makeText(this, "No models available", Toast.LENGTH_SHORT).show()
            return
        }
        
        val modelNames = models.map { model ->
            "${model.modelName} (${model.getFormattedSize()})"
        }.toMutableList()
        
        // Add custom URL option
        modelNames.add("📎 Custom URL...")
        
        AlertDialog.Builder(this)
            .setTitle("Download Model")
            .setMessage("Top ${models.size} models:")
            .setItems(modelNames.toTypedArray()) { _, which ->
                if (which == models.size) {
                    // Custom URL option
                    showCustomUrlDialog()
                } else {
                    val selectedModel = models[which]
                    showSourceSelectionDialog(selectedModel)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showSourceSelectionDialog(model: ModelItem) {
        if (model.sources.size == 1) {
            // Only one source, download directly
            startAdvancedModelDownload(model, model.sources.keys.first())
            return
        }
        
        val sources = model.sources.keys.toList()
        AlertDialog.Builder(this)
            .setTitle("Select Download Source for ${model.modelName}")
            .setItems(sources.toTypedArray()) { _, which ->
                startAdvancedModelDownload(model, sources[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun startAdvancedModelDownload(model: ModelItem, source: String) {
        var isCancelled = false
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Downloading ${model.modelName}")
            .setMessage("Preparing download from $source...")
            .setCancelable(false)
            .setNegativeButton("Hide") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "Download continues in background", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cancel") { _, _ ->
                isCancelled = true
                downloadJob?.cancel()
                Toast.makeText(this@MainActivity, "Download cancelled", Toast.LENGTH_SHORT).show()
            }
            .create()
        
        progressDialog.setOnDismissListener {
            // Only cancel if user explicitly clicked Cancel, not on dismiss
            if (!isCancelled && downloadJob?.isActive == true) {
                // Download continues in background - no action needed
                android.util.Log.d("MainActivity", "Dialog dismissed, download continues in background")
            }
        }
        
        progressDialog.show()
        
        downloadJob = lifecycleScope.launch {
            advancedDownloader.downloadModel(model, source).collect { state ->
                when (state) {
                    is AdvancedModelDownloader.DownloadState.Preparing -> {
                        if (progressDialog.isShowing) {
                            progressDialog.setMessage("Preparing download...")
                        }
                    }
                    is AdvancedModelDownloader.DownloadState.Downloading -> {
                        if (progressDialog.isShowing) {
                            val message = buildString {
                                if (state.currentFile.isNotEmpty()) {
                                    append("File: ${state.currentFile}\n\n")
                                }
                                append("Progress: ${state.progress}%\n")
                                append("Downloaded: ${advancedDownloader.formatSize(state.downloadedBytes)}")
                                append(" / ${advancedDownloader.formatSize(state.totalBytes)}\n")
                                append("Speed: ${advancedDownloader.formatSpeed(state.speedBytesPerSec)}")
                            }
                            progressDialog.setMessage(message)
                        }
                    }
                    is AdvancedModelDownloader.DownloadState.Completed -> {
                        if (progressDialog.isShowing) {
                            progressDialog.dismiss()
                        }
                        val modelName = File(state.filePath).parentFile?.name ?: model.modelName
                        Toast.makeText(
                            this@MainActivity,
                            "✓ $modelName downloaded!",
                            Toast.LENGTH_LONG
                        ).show()

                        // Auto-load the newly downloaded model
                        lifecycleScope.launch {
                            updateStatus("⏳ Loading $modelName…")
                            loadLlmModel(File(state.filePath))
                        }
                    }
                    is AdvancedModelDownloader.DownloadState.Failed -> {
                        if (progressDialog.isShowing) {
                            progressDialog.dismiss()
                        }
                        // Don't show error dialog for user cancellation
                        if (!state.error.contains("cancelled", ignoreCase = true)) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Download Failed")
                                .setMessage(state.error)
                                .setPositiveButton("OK", null)
                                .setNeutralButton("Try Different Source") { _, _ ->
                                    showSourceSelectionDialog(model)
                                }
                                .show()
                        }
                    }
                    else -> {}
                }
            }
        }
    }
    
    private fun showCustomUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://your-server.com/model.mnn"
            setText("https://")
        }
        
        AlertDialog.Builder(this)
            .setTitle("Custom Model URL")
            .setMessage("Enter a direct download URL to an .mnn model file:")
            .setView(input)
            .setPositiveButton("Download") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty() && url.startsWith("http")) {
                    startCustomModelDownload(url)
                } else {
                    Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun startCustomModelDownload(customUrl: String) {
        Toast.makeText(this, "Please select a model from the catalog for now.\nCustom URL support coming soon!", Toast.LENGTH_LONG).show()
    }

    // ---- Image helpers ----

    private fun showImagePreview(path: String, name: String) {
        val bmp = BitmapFactory.decodeFile(path)
        if (bmp != null) selectedImageThumb.setImageBitmap(bmp)
        selectedImageName.text = name
        imagePreviewBar.visibility = View.VISIBLE
    }

    private fun clearPendingImage() {
        pendingImagePath = null
        imagePreviewBar.visibility = View.GONE
        selectedImageThumb.setImageDrawable(null)
        selectedImageName.text = ""
    }

    /** Copy a content URI to the app's cache dir so the native code can read it by path. */
    private fun copyUriToCache(uri: Uri): String? {
        return try {
            val name = getFileName(uri).ifBlank { "image_${System.currentTimeMillis()}.jpg" }
            val outFile = File(cacheDir, "img_$name")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { out -> input.copyTo(out) }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to copy URI to cache", e)
            null
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = ""
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx) ?: ""
        }
        return name
    }

    override fun onDestroy() {
        super.onDestroy()
        activeLlm?.destroy()
        activeLlm = null
    }

    private fun showModelManagementDialog() {
        lifecycleScope.launch {
            val models = advancedDownloader.getDownloadedModels()
            
            if (models.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("No Models")
                    .setMessage("You haven't downloaded any models yet.\n\nUse the 'Download Model' option to get started.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }
            
            val modelNames = models.map { configFile ->
                val dir = configFile.parentFile
                val name = dir?.name ?: configFile.nameWithoutExtension
                val size = dir?.let { d -> d.listFiles()?.sumOf { it.length() } ?: 0L } ?: 0L
                "$name (${advancedDownloader.formatSize(size)})"
            }.toTypedArray()
            
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Downloaded Models")
                .setItems(modelNames) { _, which ->
                    showModelOptionsDialog(models[which])
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }
    
    private fun showModelOptionsDialog(modelFile: File) {
        val dir = modelFile.parentFile
        val name = dir?.name ?: modelFile.nameWithoutExtension
        val size = dir?.listFiles()?.sumOf { it.length() } ?: 0L
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Size: ${advancedDownloader.formatSize(size)}\n\nWhat would you like to do?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val path = dir?.absolutePath ?: modelFile.absolutePath
                    if (advancedDownloader.deleteModel(path)) {
                        if (activeLlmName == name) {
                            activeLlm?.destroy()
                            activeLlm = null
                            activeLlmName = ""
                            activeConfigFile = null
                        }
                        Toast.makeText(this@MainActivity, "Model deleted", Toast.LENGTH_SHORT).show()
                        updateStatus("⚠ No model loaded")
                    }
                }
            }
            .setNeutralButton("Load") { _, _ ->
                lifecycleScope.launch {
                    loadLlmModel(modelFile)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun clearChat() {
        messages.clear()
        chatAdapter.submitList(emptyList())
        lastMetrics = null
        updateMetrics(null)
        // Also reset LLM conversation history so it doesn't bleed into new chat
        activeLlm?.clearHistory()
        addSystemMessage("Chat cleared. Start a new conversation!")
    }
    
    private fun addMessage(message: ChatMessage) {
        messages.add(message)
        chatAdapter.submitList(messages.toList()) {
            messagesRecyclerView.scrollToPosition(messages.size - 1)
        }
    }
    
    private fun addSystemMessage(text: String) {
        addMessage(ChatMessage(text, isUser = false))
    }
    
    private fun updateStatus(status: String) {
        statusText.text = status
    }
    
    private fun updateMetrics(metrics: PerformanceMetrics?) {
        metricsText.text = if (metrics != null) {
            "⚡ Prefill: ${metrics.formatPrefillSpeed()} | Decode: ${metrics.formatDecodeSpeed()} | Overall: ${metrics.formatOverallSpeed()}"
        } else {
            "Performance metrics will appear here after inference"
        }
    }
    
    private fun showLoading(show: Boolean) {
        loadingProgress.visibility = if (show) View.VISIBLE else View.GONE
    }
}
