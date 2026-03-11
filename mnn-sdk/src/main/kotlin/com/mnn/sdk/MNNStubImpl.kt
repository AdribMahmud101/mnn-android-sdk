package com.mnn.sdk

import android.content.Context

/**
 * Stub implementation of MNN Engine for testing without native libraries.
 * This is a simplified version that demonstrates the API without actual MNN functionality.
 */
class MNNEngineStub private constructor(private val context: Context) {
    
    private val loadedModels = mutableMapOf<String, ByteArray>()
    
    /**
     * Load a model from the assets folder.
     */
    fun loadModelFromAssets(assetPath: String): MNNModelStub {
        val inputStream = context.assets.open(assetPath)
        val bytes = inputStream.readBytes()
        inputStream.close()
        return loadModelFromBytes(bytes, assetPath)
    }
    
    /**
     * Load a model from a byte array.
     */
    fun loadModelFromBytes(bytes: ByteArray, name: String = "model"): MNNModelStub {
        loadedModels[name] = bytes
        return MNNModelStub(name, bytes.size)
    }
    
    /**
     * Get MNN version information.
     */
    fun getVersion(): String {
        return "MNN SDK 1.0.0-STUB (No native libraries loaded)"
    }
    
    companion object {
        @Volatile
        private var instance: MNNEngineStub? = null
        
        fun initialize(context: Context): MNNEngineStub {
            return instance ?: synchronized(this) {
                instance ?: MNNEngineStub(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        fun getInstance(): MNNEngineStub {
            return instance ?: throw IllegalStateException(
                "MNNEngineStub not initialized. Call initialize(context) first."
            )
        }
    }
}

/**
 * Stub model implementation for testing.
 */
class MNNModelStub internal constructor(
    private val name: String,
    private val size: Int
) {
    private var isClosed = false
    
    fun createInterpreter(config: MNNConfig = MNNConfig()): MNNInterpreterStub {
        checkNotClosed()
        return MNNInterpreterStub(name, config)
    }
    
    fun getInputNames(): List<String> {
        checkNotClosed()
        return listOf("input")
    }
    
    fun getOutputNames(): List<String> {
        checkNotClosed()
        return listOf("output")
    }
    
    fun close() {
        isClosed = true
    }
    
    private fun checkNotClosed() {
        if (isClosed) {
            throw IllegalStateException("Model has been closed")
        }
    }
}

/**
 * Stub interpreter implementation for testing.
 */
class MNNInterpreterStub internal constructor(
    private val modelName: String,
    private val config: MNNConfig
) {
    private var isClosed = false
    
    fun run(inputs: Map<String, MNNTensor>): Map<String, MNNTensor> {
        checkNotClosed()
        
        // Stub implementation: just return the same data with some random values
        val outputs = mutableMapOf<String, MNNTensor>()
        
        inputs.forEach { (_, tensor) ->
            val outputData = FloatArray(tensor.size()) { kotlin.random.Random.nextFloat() }
            outputs["output"] = MNNTensor(outputData, tensor.getShape())
        }
        
        return outputs
    }
    
    fun run(input: MNNTensor): MNNTensor {
        val inputs = mapOf("input" to input)
        val outputs = run(inputs)
        return outputs.values.first()
    }
    
    suspend fun runAsync(inputs: Map<String, MNNTensor>): Map<String, MNNTensor> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            run(inputs)
        }
    }
    
    suspend fun runAsync(input: MNNTensor): MNNTensor {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            run(input)
        }
    }
    
    fun resizeInput(inputName: String, dims: IntArray) {
        checkNotClosed()
        // Stub: do nothing
    }
    
    fun close() {
        isClosed = true
    }
    
    private fun checkNotClosed() {
        if (isClosed) {
            throw IllegalStateException("Interpreter has been closed")
        }
    }
}
