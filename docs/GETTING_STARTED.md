# Getting Started with MNN Android SDK

This guide will help you get started with the MNN Android SDK in your Android application.

## Prerequisites

- Android Studio Arctic Fox or later
- Minimum Android SDK: API 21 (Android 5.0)
- Target Android SDK: API 34 (Android 14)
- Kotlin 1.9+

## Installation

### Step 1: Add Dependency

Add the MNN SDK dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.mnn:mnn-sdk:1.0.0")
}
```

### Step 2: Sync Project

Sync your project with Gradle files.

## Basic Integration

### 1. Initialize the Engine

Initialize the MNN engine in your Application class or Activity:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize MNN Engine
        MNNEngine.initialize(this)
    }
}
```

Or in an Activity:

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var mnnEngine: MNNEngine
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        mnnEngine = MNNEngine.initialize(this)
    }
}
```

### 2. Add Your Model

Place your `.mnn` model file in the `assets` folder:

```
app/
  src/
    main/
      assets/
        my_model.mnn
```

### 3. Load and Run the Model

```kotlin
// Load model
val model = mnnEngine.loadModelFromAssets("my_model.mnn")

// Create interpreter with configuration
val config = MNNConfig(
    numThreads = 4,
    precision = Precision.NORMAL
)
val interpreter = model.createInterpreter(config)

// Prepare input
val inputData = floatArrayOf(/* your input data */)
val inputShape = intArrayOf(1, 224, 224, 3)  // Example: image classification
val inputTensor = MNNTensor.fromFloatArray(inputData, inputShape)

// Run inference
val outputTensor = interpreter.run(inputTensor)

// Get results
val results = outputTensor.toFloatArray()

// Clean up when done
interpreter.close()
model.close()
```

## Image Classification Example

Here's a complete example for image classification:

```kotlin
import android.graphics.Bitmap
import androidx.lifecycle.lifecycleScope
import com.mnn.sdk.*
import kotlinx.coroutines.launch

class ImageClassifier(context: Context) {
    private val engine = MNNEngine.initialize(context)
    private val model = engine.loadModelFromAssets("mobilenet_v2.mnn")
    private val interpreter = model.createInterpreter(
        MNNConfig(
            numThreads = 4,
            precision = Precision.NORMAL
        )
    )
    
    suspend fun classify(bitmap: Bitmap): FloatArray {
        // Resize bitmap to model input size (e.g., 224x224)
        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        
        // Create tensor from bitmap
        val inputTensor = MNNTensor.fromBitmap(
            resized,
            normalize = true,
            meanValues = floatArrayOf(0.485f, 0.456f, 0.406f),
            stdValues = floatArrayOf(0.229f, 0.224f, 0.225f)
        )
        
        // Run inference asynchronously
        val outputTensor = interpreter.runAsync(inputTensor)
        
        // Return results
        return outputTensor.toFloatArray()
    }
    
    fun close() {
        interpreter.close()
        model.close()
    }
}

// Usage in Activity
class MainActivity : AppCompatActivity() {
    private lateinit var classifier: ImageClassifier
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        classifier = ImageClassifier(this)
        
        val bitmap = loadYourBitmap()
        
        lifecycleScope.launch {
            val results = classifier.classify(bitmap)
            // Process results
            displayResults(results)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        classifier.close()
    }
}
```

## Configuration Options

### Number of Threads

Control CPU thread usage:

```kotlin
val config = MNNConfig(numThreads = 2)  // Use 2 threads
```

### Precision

Choose computation precision:

```kotlin
// Normal precision (FP32) - default
val config = MNNConfig(precision = Precision.NORMAL)

// Low precision (FP16) - faster, less accurate
val config = MNNConfig(precision = Precision.LOW)

// High precision - slower, more accurate
val config = MNNConfig(precision = Precision.HIGH)
```

### Forward Type

Select computation backend:

```kotlin
val config = MNNConfig(
    forwardType = ForwardType.CPU  // CPU backend (default)
)

// Other options (if supported by device):
// ForwardType.OPENCL  // GPU via OpenCL
// ForwardType.VULKAN  // GPU via Vulkan
// ForwardType.OPENGL  // GPU via OpenGL
```

### Power Mode

Optimize for performance or battery:

```kotlin
// Normal mode
val config = MNNConfig(power = PowerMode.NORMAL)

// High performance (more battery usage)
val config = MNNConfig(power = PowerMode.HIGH)

// Low power (saves battery)
val config = MNNConfig(power = PowerMode.LOW)
```

## Best Practices

### 1. Reuse Interpreters

Create interpreters once and reuse them:

```kotlin
class MyModel(context: Context) {
    private val engine = MNNEngine.initialize(context)
    private val model = engine.loadModelFromAssets("model.mnn")
    private val interpreter = model.createInterpreter()
    
    fun run(input: MNNTensor): MNNTensor {
        return interpreter.run(input)
    }
    
    fun close() {
        interpreter.close()
        model.close()
    }
}
```

### 2. Use Coroutines for Heavy Operations

Always run inference in background:

```kotlin
lifecycleScope.launch(Dispatchers.Default) {
    val output = interpreter.runAsync(input)
    withContext(Dispatchers.Main) {
        // Update UI
    }
}
```

### 3. Handle Errors Gracefully

```kotlin
try {
    val output = interpreter.run(input)
    processResults(output)
} catch (e: MNNException) {
    Log.e(TAG, "Inference failed", e)
    showError(e.message)
}
```

### 4. Clean Up Resources

Always close resources when done:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    interpreter.close()
    model.close()
}
```

## Troubleshooting

### Native Library Not Found

If you get `UnsatisfiedLinkError`:

1. Ensure native libraries are included in the AAR
2. Check that your device architecture is supported
3. Verify the libraries are in the correct `libs/<abi>/` folders

### Model Loading Fails

If model loading fails:

1. Verify the model file exists in assets
2. Check the model file is a valid `.mnn` format
3. Ensure sufficient memory is available

### Poor Performance

To improve performance:

1. Use `Precision.LOW` for faster inference
2. Increase `numThreads` (typically 4 for mobile)
3. Consider using GPU backend if available
4. Optimize your model (quantization, pruning)

## Next Steps

- Check out the [API Reference](API.md) for detailed documentation
- See [Examples](EXAMPLES.md) for more use cases
- Read the [MNN Documentation](https://www.yuque.com/mnn/en) for model optimization tips

## Getting Help

- 📖 [Documentation](../README.md)
- 🐛 [Report Issues](../../issues)
- 💬 [Ask Questions](../../discussions)
