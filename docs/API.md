# MNN Android SDK API Reference

Complete API documentation for the MNN Android SDK.

## Core Classes

### MNNEngine

Main entry point for the SDK. Manages native library initialization and model loading.

#### Methods

##### `initialize(context: Context): MNNEngine`

Initialize the MNN engine. Must be called before using any MNN functionality.

**Parameters:**
- `context`: Application context

**Returns:** MNNEngine instance (singleton)

**Throws:** `MNNException` if native library fails to load

**Example:**
```kotlin
val engine = MNNEngine.initialize(context)
```

##### `getInstance(): MNNEngine`

Get the initialized engine instance.

**Returns:** MNNEngine instance

**Throws:** `IllegalStateException` if not initialized

##### `loadModelFromAssets(assetPath: String): MNNModel`

Load a model from the assets folder.

**Parameters:**
- `assetPath`: Path to model file in assets

**Returns:** MNNModel instance

**Throws:** `MNNException` if loading fails

**Example:**
```kotlin
val model = engine.loadModelFromAssets("mobilenet.mnn")
```

##### `loadModelFromFile(file: File): MNNModel`

Load a model from a file.

**Parameters:**
- `file`: Model file

**Returns:** MNNModel instance

**Throws:** `MNNException` if loading fails

##### `loadModelFromPath(path: String): MNNModel`

Load a model from a file path.

**Parameters:**
- `path`: Path to model file

**Returns:** MNNModel instance

**Throws:** `MNNException` if loading fails

##### `loadModelFromBytes(bytes: ByteArray): MNNModel`

Load a model from a byte array.

**Parameters:**
- `bytes`: Model data

**Returns:** MNNModel instance

**Throws:** `MNNException` if loading fails

##### `getVersion(): String`

Get MNN version information.

**Returns:** Version string

---

### MNNModel

Represents a loaded MNN model.

#### Methods

##### `createInterpreter(config: MNNConfig = MNNConfig()): MNNInterpreter`

Create an interpreter for running inference.

**Parameters:**
- `config`: Configuration options (optional)

**Returns:** MNNInterpreter instance

**Throws:** `MNNException` if creation fails

**Example:**
```kotlin
val config = MNNConfig(numThreads = 4)
val interpreter = model.createInterpreter(config)
```

##### `getInputNames(): List<String>`

Get model input tensor names.

**Returns:** List of input names

##### `getOutputNames(): List<String>`

Get model output tensor names.

**Returns:** List of output names

##### `close()`

Close the model and release resources. After calling, the model cannot be used.

---

### MNNInterpreter

Handles model inference.

#### Methods

##### `run(inputs: Map<String, MNNTensor>): Map<String, MNNTensor>`

Run inference with multiple inputs.

**Parameters:**
- `inputs`: Map of input name to tensor

**Returns:** Map of output name to tensor

**Throws:** `MNNException` if inference fails

**Example:**
```kotlin
val inputs = mapOf("input" to inputTensor)
val outputs = interpreter.run(inputs)
```

##### `run(input: MNNTensor): MNNTensor`

Run inference with a single input (assumes model has one input).

**Parameters:**
- `input`: Input tensor

**Returns:** Output tensor (first output if multiple)

**Throws:** `MNNException` if inference fails

**Example:**
```kotlin
val output = interpreter.run(inputTensor)
```

##### `suspend runAsync(inputs: Map<String, MNNTensor>): Map<String, MNNTensor>`

Run inference asynchronously using coroutines.

**Parameters:**
- `inputs`: Map of input name to tensor

**Returns:** Map of output name to tensor

**Throws:** `MNNException` if inference fails

**Example:**
```kotlin
lifecycleScope.launch {
    val outputs = interpreter.runAsync(inputs)
}
```

##### `suspend runAsync(input: MNNTensor): MNNTensor`

Run inference asynchronously with a single input.

**Parameters:**
- `input`: Input tensor

**Returns:** Output tensor

**Throws:** `MNNException` if inference fails

##### `resizeInput(inputName: String, dims: IntArray)`

Resize input tensor dimensions.

**Parameters:**
- `inputName`: Name of input tensor
- `dims`: New dimensions

##### `close()`

Close the interpreter and release resources.

---

### MNNTensor

Wrapper for tensor data.

#### Constructor

##### `MNNTensor(data: FloatArray, shape: IntArray)`

Create a tensor with data and shape.

**Parameters:**
- `data`: Tensor data
- `shape`: Tensor dimensions

#### Methods

##### `getData(): FloatArray`

Get tensor data.

**Returns:** FloatArray containing tensor data

##### `getShape(): IntArray`

Get tensor shape.

**Returns:** IntArray containing dimensions

##### `toFloatArray(): FloatArray`

Get a copy of tensor data.

**Returns:** FloatArray copy

##### `size(): Int`

Get total number of elements.

**Returns:** Element count

##### `rank(): Int`

Get number of dimensions.

**Returns:** Dimension count

#### Static Factory Methods

##### `fromFloatArray(data: FloatArray, shape: IntArray): MNNTensor`

Create tensor from float array.

**Parameters:**
- `data`: Data array
- `shape`: Tensor shape

**Returns:** MNNTensor instance

**Throws:** `IllegalArgumentException` if sizes don't match

**Example:**
```kotlin
val data = floatArrayOf(1f, 2f, 3f, 4f)
val tensor = MNNTensor.fromFloatArray(data, intArrayOf(2, 2))
```

##### `fromBitmap(bitmap: Bitmap, normalize: Boolean = true, meanValues: FloatArray = ..., stdValues: FloatArray = ...): MNNTensor`

Create tensor from Android Bitmap.

**Parameters:**
- `bitmap`: Input bitmap
- `normalize`: Whether to normalize (default: true)
- `meanValues`: Mean values for normalization (default: ImageNet means)
- `stdValues`: Std deviation for normalization (default: ImageNet stds)

**Returns:** MNNTensor with shape [1, height, width, 3]

**Example:**
```kotlin
val tensor = MNNTensor.fromBitmap(bitmap)
```

##### `fromByteBuffer(buffer: ByteBuffer, shape: IntArray): MNNTensor`

Create tensor from ByteBuffer.

**Parameters:**
- `buffer`: ByteBuffer with float data
- `shape`: Tensor shape

**Returns:** MNNTensor instance

##### `zeros(shape: IntArray): MNNTensor`

Create tensor filled with zeros.

**Parameters:**
- `shape`: Tensor shape

**Returns:** Zero-filled tensor

**Example:**
```kotlin
val tensor = MNNTensor.zeros(intArrayOf(1, 224, 224, 3))
```

##### `full(shape: IntArray, value: Float): MNNTensor`

Create tensor filled with a value.

**Parameters:**
- `shape`: Tensor shape
- `value`: Fill value

**Returns:** Filled tensor

**Example:**
```kotlin
val tensor = MNNTensor.full(intArrayOf(2, 3), 1.0f)
```

---

### MNNConfig

Configuration options for interpreter.

#### Constructor

```kotlin
MNNConfig(
    numThreads: Int = 4,
    forwardType: Int = ForwardType.CPU,
    precision: Int = Precision.NORMAL,
    power: Int = PowerMode.NORMAL,
    memory: Int = MemoryMode.NORMAL
)
```

**Parameters:**
- `numThreads`: Number of threads (must be > 0)
- `forwardType`: Computation backend
- `precision`: Computation precision
- `power`: Power mode
- `memory`: Memory mode

**Example:**
```kotlin
val config = MNNConfig(
    numThreads = 4,
    precision = Precision.LOW,
    power = PowerMode.HIGH
)
```

---

## Constants and Enums

### ForwardType

Computation backend options. These values mirror MNN's `MNNForwardType` enum and are
cast straight into `MNN::ScheduleConfig::type` by the JNI layer.

- `ForwardType.CPU` (= 0) - CPU backend (default, always available)
- `ForwardType.METAL` (= 1) - Metal (iOS only)
- `ForwardType.CUDA` (= 2) - CUDA (NVIDIA, desktop only)
- `ForwardType.OPENCL` (= 3) - OpenCL (Android GPU)
- `ForwardType.AUTO` (= 4) - first available non-CPU backend, falls back to CPU
- `ForwardType.NN` (= 5) - Android 8.1+ NNAPI
- `ForwardType.OPENGL` (= 6) - legacy OpenGL (Android GPU)
- `ForwardType.VULKAN` (= 7) - Vulkan (Android GPU)
- `ForwardType.HEXAGON` (= 10) - Qualcomm Hexagon HTP DSP (MNN 3.6+)
- `ForwardType.MUSA` (= 15) - Moore Threads MUSA GPU (MNN 3.6+)

> `TENSORRT` and `HIAI` were removed: neither is a valid `MNNForwardType`, and both
> silently resolved to the wrong backend.

### Precision

Computation precision, mirroring `MNN::BackendConfig::PrecisionMode`:

- `Precision.NORMAL` (= 0) - FP32 (default)
- `Precision.HIGH` (= 1) - higher precision, slower
- `Precision.LOW` (= 2) - FP16, faster and smaller, slightly less accurate
- `Precision.LOW_BF16` (= 3) - BF16, for hardware without fast FP16

### PowerMode

Power consumption mode, mirroring `MNN::BackendConfig::PowerMode`:

- `PowerMode.NORMAL` (= 0) - no throttling hint (default)
- `PowerMode.HIGH` (= 1) - maximum throughput, higher battery draw
- `PowerMode.LOW` (= 2) - saves battery at the cost of speed

### MemoryMode

Memory usage mode, mirroring `MNN::BackendConfig::MemoryMode`:

- `MemoryMode.NORMAL` (= 0) - default
- `MemoryMode.HIGH` (= 1) - favour buffer reuse over footprint
- `MemoryMode.LOW` (= 2) - minimise peak memory, slower

---

## Exceptions

### MNNException

Base exception for all MNN SDK errors.

### ModelLoadException

Thrown when model loading fails.

### InferenceException

Thrown when inference fails.

### TensorException

Thrown when tensor operations fail.

### NativeException

Thrown when native library operations fail.

**Example:**
```kotlin
try {
    val model = engine.loadModelFromAssets("model.mnn")
} catch (e: ModelLoadException) {
    Log.e(TAG, "Failed to load model", e)
}
```

---

## Type Aliases

None currently defined.

---

## Extension Functions

(Reserved for future extensions)

---

## Thread Safety

- `MNNEngine` is thread-safe (singleton with synchronization)
- `MNNModel` instances should not be shared across threads
- `MNNInterpreter` instances are NOT thread-safe - create one per thread
- `MNNTensor` instances are immutable and thread-safe for reading

---

## Memory Management

The SDK uses automatic resource management but requires explicit cleanup:

```kotlin
// Always close when done
interpreter.close()
model.close()
```

Or use `use` extension (if implemented):

```kotlin
model.use { model ->
    interpreter.use { interpreter ->
        // Use resources
    }
}
```

---

## Minimum Requirements

- Android API Level 21+ (Android 5.0 Lollipop)
- Kotlin 1.9+
- 64-bit or 32-bit ARM processor
- Minimum 50MB free RAM for inference
