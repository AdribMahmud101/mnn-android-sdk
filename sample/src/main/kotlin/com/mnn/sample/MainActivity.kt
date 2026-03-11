package com.mnn.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mnn.sdk.MNNConfig
import com.mnn.sdk.MNNEngine
import com.mnn.sdk.MNNTensor
import com.mnn.sdk.Precision
import kotlinx.coroutines.launch

/**
 * Sample application demonstrating MNN Android SDK usage.
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var runButton: Button
    private lateinit var mnnEngine: MNNEngine
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        runButton = findViewById(R.id.runButton)
        
        // Initialize MNN Engine
        try {
            mnnEngine = MNNEngine.initialize(this)
            val version = mnnEngine.getVersion()
            updateStatus("MNN Engine initialized\nVersion: $version")
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
        
        updateStatus(
            """
            Tensor Operations Demo:
            
            Created tensor:
            Shape: ${shape.contentToString()}
            Size: ${tensor.size()}
            Rank: ${tensor.rank()}
            Data: ${tensor.toFloatArray().contentToString()}
            
            MNN Config Example:
            ${createConfigExample()}
            
            To run a real model:
            1. Add your .mnn model to assets/
            2. Uncomment the model loading code
            3. Prepare input tensors
            4. Run inference
            """.trimIndent()
        )
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
