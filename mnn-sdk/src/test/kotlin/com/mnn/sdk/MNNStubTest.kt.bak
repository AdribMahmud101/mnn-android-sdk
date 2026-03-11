package com.mnn.sdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Instrumented test for MNN SDK stub implementation.
 *
 * This validates that the SDK architecture works correctly even without
 * native MNN libraries loaded.
 */
@RunWith(AndroidJUnit4::class)
class MNNStubTest {
    
    private lateinit var context: Context
    private lateinit var engine: MNNEngineStub
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        engine = MNNEngineStub.initialize(context)
    }
    
    @Test
    fun testEngineInitialization() {
        val version = engine.getVersion()
        assertNotNull("Version should not be null", version)
        assertTrue("Version should contain 'STUB'", version.contains("STUB"))
    }
    
    @Test
    fun testModelLoading() {
        val testData = ByteArray(100) { it.toByte() }
        val model = engine.loadModelFromBytes(testData, "test_model")
        
        assertNotNull("Model should not be null", model)
        
        val inputNames = model.getInputNames()
        val outputNames = model.getOutputNames()
        
        assertEquals("Should have 1 input", 1, inputNames.size)
        assertEquals("Should have 1 output", 1, outputNames.size)
        assertEquals("Input name should be 'input'", "input", inputNames[0])
        assertEquals("Output name should be 'output'", "output", outputNames[0])
        
        model.close()
    }
    
    @Test
    fun testInterpreterCreation() {
        val testData = ByteArray(100) { it.toByte() }
        val model = engine.loadModelFromBytes(testData, "test_model")
        
        val config = MNNConfig(
            numThreads = 4,
            precision = Precision.LOW
        )
        val interpreter = model.createInterpreter(config)
        
        assertNotNull("Interpreter should not be null", interpreter)
        
        interpreter.close()
        model.close()
    }
    
    @Test
    fun testTensorCreation() {
        val data = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val shape = intArrayOf(2, 3)
        val tensor = MNNTensor.fromFloatArray(data, shape)
        
        assertEquals("Size should be 6", 6, tensor.size())
        assertEquals("Rank should be 2", 2, tensor.rank())
        assertArrayEquals("Shape should match", shape, tensor.getShape())
        assertArrayEquals("Data should match", data, tensor.getData(), 0.001f)
    }
    
    @Test
    fun testSyncInference() {
        val testData = ByteArray(100) { it.toByte() }
        val model = engine.loadModelFromBytes(testData, "test_model")
        val interpreter = model.createInterpreter()
        
        val inputData = floatArrayOf(1f, 2f, 3f, 4f)
        val inputShape = intArrayOf(1, 4)
        val inputTensor = MNNTensor.fromFloatArray(inputData, inputShape)
        
        val outputTensor = interpreter.run(inputTensor)
        
        assertNotNull("Output tensor should not be null", outputTensor)
        assertEquals("Output should have same size as input", 4, outputTensor.size())
        assertArrayEquals("Output shape should match input", inputShape, outputTensor.getShape())
        
        interpreter.close()
        model.close()
    }
    
    @Test
    fun testAsyncInference() = runBlocking {
        val testData = ByteArray(100) { it.toByte() }
        val model = engine.loadModelFromBytes(testData, "test_model")
        val interpreter = model.createInterpreter()
        
        val inputData = floatArrayOf(1f, 2f, 3f, 4f)
        val inputShape = intArrayOf(1, 4)
        val inputTensor = MNNTensor.fromFloatArray(inputData, inputShape)
        
        val outputTensor = interpreter.runAsync(inputTensor)
        
        assertNotNull("Output tensor should not be null", outputTensor)
        assertEquals("Output should have same size as input", 4, outputTensor.size())
        
        interpreter.close()
        model.close()
    }
    
    @Test
    fun testMultipleInputsInference() {
        val testData = ByteArray(100) { it.toByte() }
        val model = engine.loadModelFromBytes(testData, "test_model")
        val interpreter = model.createInterpreter()
        
        val input1 = MNNTensor.fromFloatArray(floatArrayOf(1f, 2f), intArrayOf(1, 2))
        val input2 = MNNTensor.fromFloatArray(floatArrayOf(3f, 4f), intArrayOf(1, 2))
        
        val inputs = mapOf("input1" to input1, "input2" to input2)
        val outputs = interpreter.run(inputs)
        
        assertNotNull("Outputs should not be null", outputs)
        assertTrue("Should have at least one output", outputs.isNotEmpty())
        
        interpreter.close()
        model.close()
    }
    
    @Test
    fun testTensorFromBitmap() {
        // Create a simple bitmap (would need actual Bitmap in real test)
        // For now, just test the static methods that don't need Bitmap
        val zeros = MNNTensor.zeros(intArrayOf(2, 3))
        assertEquals("Zeros tensor size should be 6", 6, zeros.size())
        assertArrayEquals("Should be all zeros", FloatArray(6) { 0f }, zeros.getData(), 0.001f)
        
        val ones = MNNTensor.full(intArrayOf(2, 2), 1.5f)
        assertEquals("Full tensor size should be 4", 4, ones.size())
        assertArrayEquals("Should be all 1.5", FloatArray(4) { 1.5f }, ones.getData(), 0.001f)
    }
    
    @Test
    fun testConfigOptions() {
        val config = MNNConfig(
            numThreads = 8,
            forwardType = ForwardType.OPENCL,
            precision = Precision.HIGH,
            power = PowerMode.HIGH,
            memory = MemoryMode.LOW
        )
        
        assertEquals("NumThreads should be 8", 8, config.numThreads)
        assertEquals("ForwardType should be OPENCL", ForwardType.OPENCL, config.forwardType)
        assertEquals("Precision should be HIGH", Precision.HIGH, config.precision)
        assertEquals("PowerMode should be HIGH", PowerMode.HIGH, config.power)
        assertEquals("MemoryMode should be LOW", MemoryMode.LOW, config.memory)
    }
    
    @Test(expected = IllegalStateException::class)
    fun testClosedModelThrowsException() {
        val testData = ByteArray(100) { it.toByte() }
        val model = engine.loadModelFromBytes(testData, "test_model")
        model.close()
        
        // Should throw IllegalStateException
        model.getInputNames()
    }
    
    @Test(expected = IllegalStateException::class)
    fun testClosedInterpreterThrowsException() {
        val testData = ByteArray(100) { it.toByte() }
        val model = engine.loadModelFromBytes(testData, "test_model")
        val interpreter = model.createInterpreter()
        interpreter.close()
        
        val inputTensor = MNNTensor.zeros(intArrayOf(1, 4))
        
        // Should throw IllegalStateException
        interpreter.run(inputTensor)
    }
    
    @After
    fun tearDown() {
        // Cleanup if needed
    }
}
