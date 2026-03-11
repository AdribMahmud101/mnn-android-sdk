# MNN Android SDK Examples

Practical examples demonstrating various use cases of the MNN Android SDK.

## Table of Contents

1. [Basic Inference](#basic-inference)
2. [Image Classification](#image-classification)
3. [Object Detection](#object-detection)
4. [Style Transfer](#style-transfer)
5. [Custom Preprocessing](#custom-preprocessing)
6. [Batch Processing](#batch-processing)
7. [Model Comparison](#model-comparison)

---

## Basic Inference

Simple model loading and inference:

```kotlin
class BasicInference(context: Context) {
    private val engine = MNNEngine.initialize(context)
    
    fun runSimpleModel(): FloatArray {
        // Load model
        val model = engine.loadModelFromAssets("simple_model.mnn")
        
        // Create interpreter
        val interpreter = model.createInterpreter()
        
        // Prepare input
        val input = MNNTensor.fromFloatArray(
            floatArrayOf(1f, 2f, 3f, 4f),
            intArrayOf(1, 4)
        )
        
        // Run inference
        val output = interpreter.run(input)
        
        // Get results
        val results = output.toFloatArray()
        
        // Cleanup
        interpreter.close()
        model.close()
        
        return results
    }
}
```

---

## Image Classification

Complete image classification example:

```kotlin
class ImageClassifier(private val context: Context) {
    
    private val engine = MNNEngine.initialize(context)
    private lateinit var model: MNNModel
    private lateinit var interpreter: MNNInterpreter
    private lateinit var labels: List<String>
    
    companion object {
        const val INPUT_SIZE = 224
        const val TOP_K = 5
    }
    
    fun initialize() {
        // Load model
        model = engine.loadModelFromAssets("mobilenet_v2_1.0_224.mnn")
        
        // Create interpreter with optimized config
        val config = MNNConfig(
            numThreads = 4,
            precision = Precision.LOW,
            power = PowerMode.HIGH
        )
        interpreter = model.createInterpreter(config)
        
        // Load labels
        labels = context.assets.open("labels.txt")
            .bufferedReader()
            .readLines()
    }
    
    suspend fun classify(bitmap: Bitmap): List<Classification> = withContext(Dispatchers.Default) {
        // Preprocess image
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val tensor = MNNTensor.fromBitmap(resized)
        
        // Run inference
        val output = interpreter.runAsync(tensor)
        val probabilities = output.toFloatArray()
        
        // Apply softmax
        val softmax = softmax(probabilities)
        
        // Get top-k results
        getTopK(softmax, labels)
    }
    
    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val exp = logits.map { kotlin.math.exp(it - maxLogit) }
        val sum = exp.sum()
        return exp.map { it / sum }.toFloatArray()
    }
    
    private fun getTopK(probabilities: FloatArray, labels: List<String>): List<Classification> {
        return probabilities
            .mapIndexed { index, probability -> 
                Classification(labels[index], probability) 
            }
            .sortedByDescending { it.confidence }
            .take(TOP_K)
    }
    
    fun close() {
        interpreter.close()
        model.close()
    }
}

data class Classification(val label: String, val confidence: Float)

// Usage in Activity
class ClassificationActivity : AppCompatActivity() {
    private lateinit var classifier: ImageClassifier
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        classifier = ImageClassifier(this)
        classifier.initialize()
        
        val bitmap = loadImageFromGallery()
        
        lifecycleScope.launch {
            val results = classifier.classify(bitmap)
            displayResults(results)
        }
    }
    
    private fun displayResults(results: List<Classification>) {
        results.forEach { classification ->
            Log.d("Result", "${classification.label}: ${classification.confidence}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        classifier.close()
    }
}
```

---

## Object Detection

Object detection with bounding boxes:

```kotlin
class ObjectDetector(context: Context) {
    
    private val engine = MNNEngine.initialize(context)
    private val model = engine.loadModelFromAssets("yolo_v5.mnn")
    private val interpreter = model.createInterpreter(
        MNNConfig(numThreads = 4, precision = Precision.NORMAL)
    )
    
    companion object {
        const val INPUT_SIZE = 640
        const val CONFIDENCE_THRESHOLD = 0.5f
        const val IOU_THRESHOLD = 0.4f
    }
    
    suspend fun detect(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.Default) {
        // Preprocess
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val tensor = MNNTensor.fromBitmap(resized, normalize = false)
        
        // Run inference
        val output = interpreter.runAsync(tensor)
        
        // Post-process
        parseDetections(output.toFloatArray(), bitmap.width, bitmap.height)
    }
    
    private fun parseDetections(
        output: FloatArray,
        originalWidth: Int,
        originalHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        
        // Parse output (format depends on your model)
        // This is a simplified example
        var i = 0
        while (i < output.size) {
            val confidence = output[i + 4]
            if (confidence > CONFIDENCE_THRESHOLD) {
                val x = output[i] * originalWidth / INPUT_SIZE
                val y = output[i + 1] * originalHeight / INPUT_SIZE
                val w = output[i + 2] * originalWidth / INPUT_SIZE
                val h = output[i + 3] * originalHeight / INPUT_SIZE
                val classId = output[i + 5].toInt()
                
                detections.add(Detection(x, y, w, h, classId, confidence))
            }
            i += 6  // Depends on output format
        }
        
        // Apply NMS
        return nonMaxSuppression(detections, IOU_THRESHOLD)
    }
    
    private fun nonMaxSuppression(
        detections: List<Detection>,
        iouThreshold: Float
    ): List<Detection> {
        // Simplified NMS implementation
        return detections
            .sortedByDescending { it.confidence }
            .fold(mutableListOf<Detection>()) { acc, detection ->
                if (acc.none { iou(it, detection) > iouThreshold }) {
                    acc.add(detection)
                }
                acc
            }
    }
    
    private fun iou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.x, b.x)
        val y1 = maxOf(a.y, b.y)
        val x2 = minOf(a.x + a.width, b.x + b.width)
        val y2 = minOf(a.y + a.height, b.y + b.height)
        
        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val union = areaA + areaB - intersection
        
        return intersection / union
    }
    
    fun close() {
        interpreter.close()
        model.close()
    }
}

data class Detection(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val classId: Int,
    val confidence: Float
)
```

---

## Style Transfer

Neural style transfer example:

```kotlin
class StyleTransfer(context: Context) {
    
    private val engine = MNNEngine.initialize(context)
    private val model = engine.loadModelFromAssets("style_transfer.mnn")
    private val interpreter = model.createInterpreter(
        MNNConfig(
            numThreads = 4,
            precision = Precision.LOW,
            power = PowerMode.HIGH
        )
    )
    
    suspend fun transfer(contentImage: Bitmap, styleImage: Bitmap): Bitmap {
        return withContext(Dispatchers.Default) {
            // Prepare inputs
            val contentTensor = preprocessImage(contentImage)
            val styleTensor = preprocessImage(styleImage)
            
            val inputs = mapOf(
                "content" to contentTensor,
                "style" to styleTensor
            )
            
            // Run inference
            val outputs = interpreter.runAsync(inputs)
            val outputTensor = outputs["output"] ?: throw MNNException("No output")
            
            // Convert back to bitmap
            tensorToBitmap(outputTensor, contentImage.width, contentImage.height)
        }
    }
    
    private fun preprocessImage(bitmap: Bitmap): MNNTensor {
        val resized = Bitmap.createScaledBitmap(bitmap, 512, 512, true)
        return MNNTensor.fromBitmap(resized, normalize = true)
    }
    
    private fun tensorToBitmap(tensor: MNNTensor, width: Int, height: Int): Bitmap {
        val data = tensor.toFloatArray()
        val pixels = IntArray(width * height)
        
        for (i in pixels.indices) {
            val r = ((data[i * 3] * 255).toInt().coerceIn(0, 255))
            val g = ((data[i * 3 + 1] * 255).toInt().coerceIn(0, 255))
            val b = ((data[i * 3 + 2] * 255).toInt().coerceIn(0, 255))
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
    
    fun close() {
        interpreter.close()
        model.close()
    }
}
```

---

## Custom Preprocessing

Custom preprocessing pipeline:

```kotlin
class ImagePreprocessor {
    
    fun preprocessForModel(
        bitmap: Bitmap,
        targetSize: Int = 224,
        normalize: Boolean = true
    ): MNNTensor {
        // Resize
        var processed = resizeWithAspectRatio(bitmap, targetSize)
        
        // Crop or pad to square
        processed = centerCrop(processed, targetSize)
        
        // Convert to tensor
        return MNNTensor.fromBitmap(
            processed,
            normalize = normalize,
            meanValues = floatArrayOf(0.485f, 0.456f, 0.406f),
            stdValues = floatArrayOf(0.229f, 0.224f, 0.225f)
        )
    }
    
    private fun resizeWithAspectRatio(bitmap: Bitmap, targetSize: Int): Bitmap {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height
        val (newWidth, newHeight) = if (aspectRatio > 1) {
            targetSize to (targetSize / aspectRatio).toInt()
        } else {
            (targetSize * aspectRatio).toInt() to targetSize
        }
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    private fun centerCrop(bitmap: Bitmap, size: Int): Bitmap {
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }
}
```

---

## Batch Processing

Process multiple images efficiently:

```kotlin
class BatchProcessor(context: Context) {
    
    private val engine = MNNEngine.initialize(context)
    private val model = engine.loadModelFromAssets("model.mnn")
    private val interpreter = model.createInterpreter()
    
    suspend fun processBatch(images: List<Bitmap>): List<FloatArray> {
        return withContext(Dispatchers.Default) {
            images.map { bitmap ->
                async {
                    val tensor = MNNTensor.fromBitmap(bitmap)
                    val output = interpreter.runAsync(tensor)
                    output.toFloatArray()
                }
            }.awaitAll()
        }
    }
    
    fun close() {
        interpreter.close()
        model.close()
    }
}
```

---

## Model Comparison

Compare multiple models:

```kotlin
class ModelBenchmark(context: Context) {
    
    private val engine = MNNEngine.initialize(context)
    
    suspend fun compareModels(bitmap: Bitmap, modelPaths: List<String>) {
        modelPaths.forEach { modelPath ->
            val model = engine.loadModelFromAssets(modelPath)
            val interpreter = model.createInterpreter()
            
            val start = System.currentTimeMillis()
            
            val tensor = MNNTensor.fromBitmap(bitmap)
            val output = interpreter.runAsync(tensor)
            
            val duration = System.currentTimeMillis() - start
            
            Log.d("Benchmark", "$modelPath: ${duration}ms")
            
            interpreter.close()
            model.close()
        }
    }
}
```

---

## Tips

1. **Reuse interpreters** for better performance
2. **Use coroutines** for async operations
3. **Preprocess on background thread** to avoid blocking UI
4. **Monitor memory usage** when processing large batches
5. **Benchmark different configurations** to find optimal settings
6. **Cache preprocessed data** when possible
7. **Use lower precision** (FP16) for faster inference on supported devices

---

## Additional Resources

- [Getting Started Guide](GETTING_STARTED.md)
- [API Reference](API.md)
- [MNN Model Zoo](https://github.com/alibaba/MNN#model-zoo)
- [MNN Documentation](https://www.yuque.com/mnn/en)
