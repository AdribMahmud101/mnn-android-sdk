package com.mnn.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MNN Interpreter for running model inference.
 * Uses MNN Session internally for efficient inference.
 */
class MNNInterpreter internal constructor(
    private val interpreterPtr: Long,
    private val config: MNNConfig
) {
    
    private var sessionPtr: Long = 0L
    private var isClosed = false
    
    init {
        // Create MNN session with configuration
        sessionPtr = nativeCreateSession(
            interpreterPtr,
            config.forwardType,
            config.numThreads,
            config.precision,
            config.power
        )
        if (sessionPtr == 0L) {
            throw MNNException("Failed to create MNN session")
        }
    }
    
    /**
     * Run inference with input tensors.
     *
     * @param inputs Map of input tensor name to MNNTensor
     * @return Map of output tensor name to MNNTensor
     * @throws MNNException if inference fails
     */
    fun run(inputs: Map<String, MNNTensor>): Map<String, MNNTensor> {
        checkNotClosed()
        
        // Set input data
        for ((name, tensor) in inputs) {
            val inputTensorPtr = nativeGetInputTensor(interpreterPtr, sessionPtr, name)
            if (inputTensorPtr == 0L) {
                throw MNNException("Failed to get input tensor: $name")
            }
            
            // Copy data to native tensor
            val success = nativeCopyToTensor(inputTensorPtr, tensor.getData())
            if (!success) {
                throw MNNException("Failed to copy data to input tensor: $name")
            }
        }
        
        // Run inference
        val success = nativeRun(interpreterPtr, sessionPtr)
        if (!success) {
            throw MNNException("Inference failed")
        }
        
        // Get outputs - for now, assume default output
        val outputs = mutableMapOf<String, MNNTensor>()
        
        // Get default output tensor (empty string means default)
        val outputTensorPtr = nativeGetOutputTensor(interpreterPtr, sessionPtr, null)
        if (outputTensorPtr != 0L) {
            val data = nativeCopyFromTensor(outputTensorPtr)
            val shape = nativeGetTensorShape(outputTensorPtr)
            outputs["output"] = MNNTensor(data, shape)
        }
        
        return outputs
    }
    
    /**
     * Run inference with a single input tensor.
     * Assumes the model has only one input.
     *
     * @param input Input tensor
     * @return Output tensor (first output if multiple outputs exist)
     * @throws MNNException if inference fails
     */
    fun run(input: MNNTensor): MNNTensor {
        checkNotClosed()
        
        // Get default input tensor (null means default/first input)
        val inputTensorPtr = nativeGetInputTensor(interpreterPtr, sessionPtr, null)
        if (inputTensorPtr == 0L) {
            throw MNNException("Failed to get input tensor")
        }
        
        // Copy data to native tensor
        val success = nativeCopyToTensor(inputTensorPtr, input.getData())
        if (!success) {
            throw MNNException("Failed to copy data to input tensor")
        }
        
        // Run inference
        val runSuccess = nativeRun(interpreterPtr, sessionPtr)
        if (!runSuccess) {
            throw MNNException("Inference failed")
        }
        
        // Get output tensor
        val outputTensorPtr = nativeGetOutputTensor(interpreterPtr, sessionPtr, null)
        if (outputTensorPtr == 0L) {
            throw MNNException("Failed to get output tensor")
        }
        
        val data = nativeCopyFromTensor(outputTensorPtr)
        val shape = nativeGetTensorShape(outputTensorPtr)
        
        return MNNTensor(data, shape)
    }
    
    /**
     * Run inference asynchronously using coroutines.
     *
     * @param inputs Map of input tensor name to MNNTensor
     * @return Map of output tensor name to MNNTensor
     * @throws MNNException if inference fails
     */
    suspend fun runAsync(inputs: Map<String, MNNTensor>): Map<String, MNNTensor> {
        return withContext(Dispatchers.Default) {
            run(inputs)
        }
    }
    
    /**
     * Run inference asynchronously with a single input.
     *
     * @param input Input tensor
     * @return Output tensor
     * @throws MNNException if inference fails
     */
    suspend fun runAsync(input: MNNTensor): MNNTensor {
        return withContext(Dispatchers.Default) {
            run(input)
        }
    }
    
    /**
     * Resize input tensor dimensions.
     *
     * @param inputName Name of the input tensor (null for default)
     * @param dims New dimensions
     */
    fun resizeInput(inputName: String?, dims: IntArray) {
        checkNotClosed()
        
        val inputTensorPtr = nativeGetInputTensor(interpreterPtr, sessionPtr, inputName)
        if (inputTensorPtr == 0L) {
            throw MNNException("Failed to get input tensor for resize")
        }
        
        nativeResizeTensor(interpreterPtr, inputTensorPtr, dims)
        nativeResizeSession(interpreterPtr, sessionPtr)
    }
    
    /**
     * Close the interpreter and release resources.
     */
    fun close() {
        if (!isClosed) {
            if (sessionPtr != 0L) {
                nativeReleaseSession(interpreterPtr, sessionPtr)
                sessionPtr = 0L
            }
            isClosed = true
        }
    }
    
    private fun checkNotClosed() {
        if (isClosed) {
            throw IllegalStateException("Interpreter has been closed")
        }
    }
    
    @Throws(Throwable::class)
    protected fun finalize() {
        close()
    }
    
    // Native methods matching JNI implementation
    private external fun nativeCreateSession(
        interpreterPtr: Long,
        forwardType: Int,
        numThreads: Int,
        precision: Int,
        powerMode: Int
    ): Long
    
    private external fun nativeRun(interpreterPtr: Long, sessionPtr: Long): Boolean
    
    private external fun nativeGetInputTensor(
        interpreterPtr: Long,
        sessionPtr: Long,
        name: String?
    ): Long
    
    private external fun nativeGetOutputTensor(
        interpreterPtr: Long,
        sessionPtr: Long,
        name: String?
    ): Long
    
    private external fun nativeCopyToTensor(tensorPtr: Long, data: FloatArray): Boolean
    private external fun nativeCopyFromTensor(tensorPtr: Long): FloatArray
    private external fun nativeGetTensorShape(tensorPtr: Long): IntArray
    private external fun nativeResizeTensor(interpreterPtr: Long, tensorPtr: Long, dims: IntArray): Boolean
    private external fun nativeResizeSession(interpreterPtr: Long, sessionPtr: Long): Boolean
    private external fun nativeReleaseSession(interpreterPtr: Long, sessionPtr: Long)
}
