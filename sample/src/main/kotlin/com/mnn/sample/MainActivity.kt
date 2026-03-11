package com.mnn.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mnn.sdk.MNNConfig
import com.mnn.sdk.MNNEngineStub
import com.mnn.sdk.MNNTensor
import com.mnn.sdk.Precision
import kotlinx.coroutines.launch

/**
 * Sample application demonstrating MNN Android SDK usage.
 * Using stub implementation for testing without native libraries.
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var runButton: Button
    private lateinit var mnnEngine: MNNEngineStub
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        runButton = findViewById(R.id.runButton)
        
        // Initialize MNN Engine (using stub for testing)
        try {
            mnnEngine = MNNEngineStub.initialize(this)
            val version = mnnEngine.getVersion()
            updateStatus("MNN Engine initialized (Stub Mode)\nVersion: $version\n\nNote: This is a stub implementation for testing.\nAdd MNN native libraries to enable real inference.")
        } catch (e: Exception) {
            updateStatus("Failed to initialize MNN: ${e.message}")
            runButton.isEnabled = false
            return
        }
        
        runButton.setOnClickListener {
            runInference()
        }
    }
    
    private fun runInference() {
        lifecycleScope.launch {
            try {
                updateStatus("Running inference...")
                runButton.isEnabled = false
                
                // Example: Load and run a model
                // Note: You'll need to add a real .mnn model file to assets
                // val model = mnnEngine.loadModelFromAssets("model.mnn")
                
                // For demonstration, we'll show how to create tensors
                demonstrateTensorOperations()
                
                updateStatus("Inference completed successfully!")
                
            } catch (e: Exception) {
                updateStatus("Inference failed: ${e.message}\n${e.stackTraceToString()}")
            } finally {
                runButton.isEnabled = true
            }
        }
    }
    
    private fun demonstrateTensorOperations() {
        // Create a tensor from float array
        val data = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f)
        val shape = intArrayOf(2, 3)
        val tensor = MNNTensor.fromFloatArray(data, shape)
        
        // Create a simple model with test data
        val testData = ByteArray(100) { it.toByte() }
        val model = mnnEngine.loadModelFromBytes(testData, "test_model")
        val interpreter = model.createInterpreter(MNNConfig(numThreads = 4))
        
        // Run inference with stub implementation
        val output = interpreter.run(tensor)
        
        updateStatus(
            """
            ✅ Stub Implementation Working!
            
            Tensor Operations Demo:
            Created tensor:
            Shape: ${shape.contentToString()}
            Size: ${tensor.size()}
            Rank: ${tensor.rank()}
            Input Data: ${tensor.toFloatArray().contentToString()}
            
            Model Info:
            Input Names: ${model.getInputNames()}
            Output Names: ${model.getOutputNames()}
            
            Inference Result:
            Output Shape: ${output.getShape().contentToString()}
            Output Data (sample): ${output.toFloatArray().take(6).joinToString(", ") { "%.3f".format(it) }}
            
            MNN Config Example:
            ${createConfigExample()}
            
            📝 Next Steps:
            1. Add MNN native libraries (.so files) to mnn-sdk/libs/<abi>/
            2. Implement JNI bridge in MNNEngine.kt
            3. Replace MNNEngineStub with MNNEngine
            4. Test with real MNN models
            
            The Architecture is FUNCTIONAL! ✨
            """.trimIndent()
        )
        
        // Cleanup
        interpreter.close()
        model.close()
    }
    
    private fun createConfigExample(): String {
        val config = MNNConfig(
            numThreads = 4,
            precision = Precision.NORMAL
        )
        return """
        numThreads: ${config.numThreads}
        precision: ${config.precision}
        forwardType: ${config.forwardType}
        """.trimIndent()
    }
    
    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }
}
