package com.mnn.sdk

/**
 * Represents a loaded MNN model.
 * Use this to create interpreters for inference.
 */
class MNNModel internal constructor(private val nativeHandle: Long) {
    
    private var isClosed = false
    
    /**
     * Create an interpreter for running inference.
     *
     * @param config Configuration options (optional)
     * @return MNNInterpreter instance
     * @throws MNNException if interpreter creation fails
     */
    fun createInterpreter(config: MNNConfig = MNNConfig()): MNNInterpreter {
        checkNotClosed()
        return MNNInterpreter(nativeHandle, config)
    }
    
    /**
     * Get model input tensor names.
     *
     * @return List of input tensor names (currently returns empty list)
     */
    fun getInputNames(): List<String> {
        checkNotClosed()
        // TODO: Implement in JNI
        return emptyList()
    }
    
    /**
     * Get model output tensor names.
     *
     * @return List of output tensor names (currently returns empty list)
     */
    fun getOutputNames(): List<String> {
        checkNotClosed()
        // TODO: Implement in JNI
        return emptyList()
    }
    
    /**
     * Close the model and release resources.
     * After calling this, the model cannot be used anymore.
     */
    fun close() {
        if (!isClosed) {
            nativeReleaseModel(nativeHandle)
            isClosed = true
        }
    }
    
    private fun checkNotClosed() {
        if (isClosed) {
            throw IllegalStateException("Model has been closed")
        }
    }
    
    @Throws(Throwable::class)
    protected fun finalize() {
        close()
    }
    
    // Native methods
    private external fun nativeReleaseModel(modelHandle: Long)
}
