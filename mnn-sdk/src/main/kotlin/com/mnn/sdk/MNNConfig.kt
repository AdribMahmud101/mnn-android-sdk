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
 *
 * These MUST match the `MNNForwardType` enum in `MNN/MNNForwardType.h` exactly —
 * the JNI layer casts them straight into `MNN::ScheduleConfig::type` with no
 * translation table. Re-check that header when upgrading the bundled engine.
 */
object ForwardType {
    /** `MNN_FORWARD_CPU` — default, always available. */
    const val CPU = 0

    /** `MNN_FORWARD_METAL` — iOS only. */
    const val METAL = 1

    /** `MNN_FORWARD_CUDA` — NVIDIA, desktop only. */
    const val CUDA = 2

    /** `MNN_FORWARD_OPENCL` — Android GPU via OpenCL. */
    const val OPENCL = 3

    /** `MNN_FORWARD_AUTO` — first available non-CPU backend, falling back to CPU. */
    const val AUTO = 4

    /** `MNN_FORWARD_NN` — Android 8.1+ NNAPI. */
    const val NN = 5

    /** `MNN_FORWARD_OPENGL` — legacy Android GPU path. */
    const val OPENGL = 6

    /** `MNN_FORWARD_VULKAN` — Android GPU via Vulkan. */
    const val VULKAN = 7

    /** `MNN_FORWARD_HEXAGON` — Qualcomm Hexagon HTP DSP (MNN 3.6+). */
    const val HEXAGON = 10

    /** `MNN_FORWARD_MUSA` — Moore Threads MUSA GPU (MNN 3.6+). */
    const val MUSA = 15
}

/**
 * Precision options for computation.
 *
 * Maps to `MNN::BackendConfig::PrecisionMode`. Note that a *higher* enum value is
 * a *lower* precision for `LOW`/`LOW_BF16`.
 */
object Precision {
    /** `Precision_Normal` — FP32 (default). */
    const val NORMAL = 0

    /** `Precision_High` — slower, more accurate. */
    const val HIGH = 1

    /** `Precision_Low` — FP16, faster and smaller, slightly less accurate. */
    const val LOW = 2

    /** `Precision_Low_BF16` — BF16, for hardware without fast FP16. */
    const val LOW_BF16 = 3
}

/**
 * Power mode options. Maps to `MNN::BackendConfig::PowerMode`.
 */
object PowerMode {
    /** `Power_Normal` — no throttling hint (default). */
    const val NORMAL = 0

    /** `Power_High` — maximum throughput, higher battery draw. */
    const val HIGH = 1

    /** `Power_Low` — saves battery at the cost of speed. */
    const val LOW = 2
}

/**
 * Memory mode options. Maps to `MNN::BackendConfig::MemoryMode`.
 */
object MemoryMode {
    /** `Memory_Normal` — default. */
    const val NORMAL = 0

    /** `Memory_High` — favour large-buffer reuse over footprint. */
    const val HIGH = 1

    /** `Memory_Low` — minimise peak memory, slower. */
    const val LOW = 2
}
