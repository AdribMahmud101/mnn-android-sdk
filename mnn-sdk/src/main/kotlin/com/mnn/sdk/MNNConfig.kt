package com.mnn.sdk

/**
 * Configuration options for MNN interpreter.
 *
 * @property numThreads Number of threads to use (default: 4)
 * @property forwardType Forward type (CPU, GPU, etc.) See [ForwardType]
 * @property precision Computation precision. See [Precision]
 * @property power Power mode. See [PowerMode]
 * @property memory Memory mode. See [MemoryMode]
 */
data class MNNConfig(
    val numThreads: Int = 4,
    val forwardType: Int = ForwardType.CPU,
    val precision: Int = Precision.NORMAL,
    val power: Int = PowerMode.NORMAL,
    val memory: Int = MemoryMode.NORMAL
) {
    init {
        require(numThreads > 0) { "Number of threads must be positive" }
    }
}

/**
 * Forward type options for MNN.
 */
object ForwardType {
    const val CPU = 0
    const val METAL = 1
    const val OPENCL = 2
    const val OPENGL = 3
    const val VULKAN = 4
    const val TENSORRT = 5
    const val CUDA = 6
    const val HIAI = 7
    const val NN = 8
}

/**
 * Precision options for computation.
 */
object Precision {
    /** Normal precision (FP32) */
    const val NORMAL = 0
    /** Low precision (FP16) - faster but less accurate */
    const val LOW = 1
    /** High precision - slower but more accurate */
    const val HIGH = 2
    /** Low precision for BF16 */
    const val LOW_BF16 = 3
}

/**
 * Power mode options.
 */
object PowerMode {
    /** Normal power mode */
    const val NORMAL = 0
    /** Low power mode - saves battery */
    const val LOW = 1
    /** High performance mode - uses more battery */
    const val HIGH = 2
}

/**
 * Memory mode options.
 */
object MemoryMode {
    /** Normal memory usage */
    const val NORMAL = 0
    /** Low memory usage - slower but uses less memory */
    const val LOW = 1
}
