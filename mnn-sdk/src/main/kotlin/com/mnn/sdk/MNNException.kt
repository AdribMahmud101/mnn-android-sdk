package com.mnn.sdk

/**
 * Base exception class for MNN SDK errors.
 */
open class MNNException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when model loading fails.
 */
class ModelLoadException(message: String, cause: Throwable? = null) : MNNException(message, cause)

/**
 * Exception thrown when inference fails.
 */
class InferenceException(message: String, cause: Throwable? = null) : MNNException(message, cause)

/**
 * Exception thrown when tensor operations fail.
 */
class TensorException(message: String, cause: Throwable? = null) : MNNException(message, cause)

/**
 * Exception thrown when native library operations fail.
 */
class NativeException(message: String, cause: Throwable? = null) : MNNException(message, cause)
