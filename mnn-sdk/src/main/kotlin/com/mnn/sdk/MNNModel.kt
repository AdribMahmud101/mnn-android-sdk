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
        val interpreterHandle = nativeCreateInterpreter(
            nativeHandle,
            config.numThreads,
            config.forwardType,
            config.precision,
            config.power,
            config.memory
        )
        if (interpreterHandle == 0L) {
            throw MNNException("Failed to create interpreter")
        }
        return MNNInterpreter(interpreterHandle)
    }
    
    /**
     * Get model input tensor names.
     *
     * @return List of input tensor names
     */
    fun getInputNames(): List<String> {
        checkNotClosed()
        return nativeGetInputNames(nativeHandle).toList()
    }
    
    /**
     * Get model output tensor names.
     *
     * @return List of output tensor names
     */
    fun getOutputNames(): List<String> {
        checkNotClosed()
        return nativeGetOutputNames(nativeHandle).toList()
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
    private external fun nativeCreateInterpreter(
        modelHandle: Long,
        numThreads: Int,
        forwardType: Int,
        precision: Int,
        power: Int,
        memory: Int
    ): Long
    
    private external fun nativeGetInputNames(modelHandle: Long): Array<String>
    private external fun nativeGetOutputNames(modelHandle: Long): Array<String>
    private external fun nativeReleaseModel(modelHandle: Long)
}
