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
import java.io.IOException

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
            updateStatus("✅ MNN Engine initialized successfully!\nVersion: $version\n\nReady for inference.\n\nNote: To run inference, add a .mnn model file to assets/models/")
        } catch (e: Exception) {
            updateStatus("❌ Failed to initialize MNN: ${e.message}\n\nMake sure MNN native libraries are included.")
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
                
                // Try to load a model from assets
                try {
                    val modelBytes = assets.open("models/model.mnn").readBytes()
                    val model = mnnEngine.loadModelFromBytes(modelBytes)
                    
                    // Create interpreter with configuration
                    val config = MNNConfig(
                        numThreads = 4,
                        precision = Precision.HIGH
                    )
                    val interpreter = model.createInterpreter(config)
                    
                    // Example: Create input tensor (adjust dimensions for your model)
                    val input = MNNTensor.zeros(intArrayOf(1, 224, 224, 3))
                    
                    // Run inference
                    val output = interpreter.run(input)
                    
                    updateStatus("✅ Inference completed!\nOutput shape: ${output.getShape().contentToString()}\nFirst 5 values: ${output.getData().take(5)}")
                    
                    interpreter.close()
                    model.close()
                    
                } catch (e: IOException) {
                    // No model file found - show example usage
                    demonstrateTensorOperations()
                }
                
            } catch (e: Exception) {
                updateStatus("❌ Inference failed: ${e.message}\n\n${e.stackTraceToString()}")
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
            ℹ️ No model file found at assets/models/model.mnn
            
            Tensor Operations Demo:
            Created tensor:
            Shape: ${shape.contentToString()}
            Size: ${tensor.size()}
            Rank: ${tensor.rank()}
            Data: ${tensor.toFloatArray().contentToString()}
            
            MNN Config Example:
            ${createConfigExample()}
            
            📝 To run real inference:
            1. Convert your model to MNN format (.mnn)
            2. Place it in assets/models/model.mnn
            3. Tap "Run Inference" again
            
            For model conversion, see:
            https://mnn-docs.readthedocs.io/en/latest/tools/convert.html
            
            The SDK is READY for real inference! ✨
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
