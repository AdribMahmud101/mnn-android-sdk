package com.mnn.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MNN Interpreter for running model inference.
 * Created from a MNNModel instance.
 */
class MNNInterpreter internal constructor(private val nativeHandle: Long) {
    
    private var isClosed = false
    
    /**
     * Run inference with input tensors.
     *
     * @param inputs Map of input tensor name to MNNTensor
     * @return Map of output tensor name to MNNTensor
     * @throws MNNException if inference fails
     */
    fun run(inputs: Map<String, MNNTensor>): Map<String, MNNTensor> {
        checkNotClosed()
        
        // Set inputs
        for ((name, tensor) in inputs) {
            nativeSetInput(nativeHandle, name, tensor.getData(), tensor.getShape())
        }
        
        // Run inference
        val success = nativeRun(nativeHandle)
        if (!success) {
            throw MNNException("Inference failed")
        }
        
        // Get outputs
        val outputNames = nativeGetOutputNames(nativeHandle)
        val outputs = mutableMapOf<String, MNNTensor>()
        
        for (name in outputNames) {
            val data = nativeGetOutput(nativeHandle, name)
            val shape = nativeGetOutputShape(nativeHandle, name)
            outputs[name] = MNNTensor(data, shape)
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
        val inputNames = nativeGetInputNames(nativeHandle)
        if (inputNames.isEmpty()) {
            throw MNNException("Model has no inputs")
        }
        
        val inputs = mapOf(inputNames[0] to input)
        val outputs = run(inputs)
        
        return outputs.values.first()
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
     * @param inputName Name of the input tensor
     * @param dims New dimensions
     */
    fun resizeInput(inputName: String, dims: IntArray) {
        checkNotClosed()
        nativeResizeInput(nativeHandle, inputName, dims)
    }
    
    /**
     * Close the interpreter and release resources.
     */
    fun close() {
        if (!isClosed) {
            nativeReleaseInterpreter(nativeHandle)
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
    
    // Native methods
    private external fun nativeSetInput(handle: Long, name: String, data: FloatArray, shape: IntArray)
    private external fun nativeRun(handle: Long): Boolean
    private external fun nativeGetOutput(handle: Long, name: String): FloatArray
    private external fun nativeGetOutputShape(handle: Long, name: String): IntArray
    private external fun nativeGetInputNames(handle: Long): Array<String>
    private external fun nativeGetOutputNames(handle: Long): Array<String>
    private external fun nativeResizeInput(handle: Long, name: String, dims: IntArray)
    private external fun nativeReleaseInterpreter(handle: Long)
}
