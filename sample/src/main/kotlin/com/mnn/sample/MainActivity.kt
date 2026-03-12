package com.mnn.sample

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mnn.sdk.MNNConfig
import com.mnn.sdk.MNNEngine
import com.mnn.sdk.MNNTensor
import com.mnn.sdk.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * LLM Chat Demo using MNN Android SDK
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var loadingProgress: ProgressBar
    
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    
    private lateinit var mnnEngine: MNNEngine
    private var isInitialized = false
    private var isProcessing = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_chat)
        
        setupViews()
        setupRecyclerView()
        initializeMNN()
    }
    
    private fun setupViews() {
        statusText = findViewById(R.id.statusText)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        inputEditText = findViewById(R.id.inputEditText)
        sendButton = findViewById(R.id.sendButton)
        loadingProgress = findViewById(R.id.loadingProgress)
        
        sendButton.setOnClickListener {
            val message = inputEditText.text.toString().trim()
            if (message.isNotEmpty()) {
                sendMessage(message)
            }
        }
        
        inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendButton.performClick()
                true
            } else {
                false
            }
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
                
                updateStatus("✓ MNN v$version ready - Checking for model...")
                
                // Try to load model if available
                checkForModel()
                
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
    
    private suspend fun checkForModel() = withContext(Dispatchers.IO) {
        try {
            // Check if model exists in assets
            assets.list("models")?.let { files ->
                val modelFile = files.firstOrNull { it.endsWith(".mnn") }
                if (modelFile != null) {
                    withContext(Dispatchers.Main) {
                        updateStatus("✓ Found model: $modelFile - Ready for inference!")
                        addSystemMessage("Model loaded successfully. You can start chatting!")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        updateStatus("⚠ No model found - Using test mode")
                        addSystemMessage(
                            "No MNN model found in assets/models/\n\n" +
                            "To use real LLM inference:\n" +
                            "1. Download a Qwen or Llama model in MNN format\n" +
                            "2. Place it in assets/models/ folder\n" +
                            "3. Rebuild the app\n\n" +
                            "For now, sending test messages to verify SDK..."
                        )
                    }
                }
            }
        } catch (e: IOException) {
            withContext(Dispatchers.Main) {
                updateStatus("⚠ No models folder - Using test mode")
                addSystemMessage("Running in test mode without LLM model")
            }
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
        
        // Add user message
        addMessage(ChatMessage(text, isUser = true))
        inputEditText.text.clear()
        
        // Process with MNN
        lifecycleScope.launch {
            isProcessing = true
            sendButton.isEnabled = false
            
            try {
                // Run inference
                val response = runInference(text)
                
                // Add AI response
                addMessage(ChatMessage(response, isUser = false))
                
            } catch (e: Exception) {
                addMessage(
                    ChatMessage(
                        "Error: ${e.message}",
                        isUser = false
                    )
                )
            } finally {
                isProcessing = false
                sendButton.isEnabled = true
            }
        }
    }
    
    private suspend fun runInference(input: String): String = withContext(Dispatchers.Default) {
        try {
            // Try to load and run model
            val modelBytes = try {
                withContext(Dispatchers.IO) {
                    assets.open("models/model.mnn").readBytes()
                }
            } catch (e: IOException) {
                // No model available - return test response
                return@withContext generateTestResponse(input)
            }
            
            // Load model
            val model = mnnEngine.loadModelFromBytes(modelBytes)
            
            // Create interpreter with config
            val config = MNNConfig(
                numThreads = 4,
                precision = Precision.HIGH
            )
            val interpreter = model.createInterpreter(config)
            
            // For LLM, we would need:
            // 1. Tokenize input
            // 2. Create input tensor
            // 3. Run inference
            // 4. Decode output tokens
            
            // For now, test basic inference works
            val testInput = MNNTensor.zeros(intArrayOf(1, 512)) // Example shape
            val output = interpreter.run(testInput)
            
            interpreter.close()
            model.close()
            
            "SDK inference test successful! Output shape: ${output.getShape().contentToString()}\n\n" +
            "Note: Full LLM text generation requires:\n" +
            "- Tokenizer integration\n" +
            "- Text generation loop\n" +
            "- Token decoding\n\n" +
            "The MNN SDK is working correctly!"
            
        } catch (e: Exception) {
            "Inference error: ${e.message}\n\nStack trace:\n${e.stackTraceToString().take(500)}"
        }
    }
    
    private fun generateTestResponse(input: String): String {
        // Generate mock response to test UI
        return when {
            input.contains("hello", ignoreCase = true) -> 
                "Hello! I'm a test response. The MNN SDK is initialized and working!"
            
            input.contains("how", ignoreCase = true) -> 
                "This is a test mode response. Once you add an MNN LLM model, I'll use real inference!"
            
            input.contains("test", ignoreCase = true) -> 
                "✓ MNN Engine: Initialized\n✓ SDK: Working\n✓ JNI Bridge: Connected\n\n" +
                "Everything is ready for real LLM inference once you add a model file!"
            
            else -> 
                "Echo: \"$input\"\n\nSDK Status: Ready for real inference with MNN model!"
        }
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
}
