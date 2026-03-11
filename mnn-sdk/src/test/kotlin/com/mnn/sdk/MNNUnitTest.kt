package com.mnn.sdk

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for MNN SDK core classes (non-Android dependent).
 */
class MNNTensorTest {
    
    @Test
    fun testTensorFromFloatArray() {
        val data = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val shape = intArrayOf(2, 3)
        val tensor = MNNTensor.fromFloatArray(data, shape)
        
        assertEquals(6, tensor.size())
        assertEquals(2, tensor.rank())
        assertArrayEquals(shape, tensor.getShape())
        assertArrayEquals(data, tensor.getData(), 0.001f)
    }
    
    @Test
    fun testTensorZeros() {
        val zeros = MNNTensor.zeros(intArrayOf(2, 3))
        
        assertEquals(6, zeros.size())
        assertArrayEquals(FloatArray(6) { 0f }, zeros.getData(), 0.001f)
    }
    
    @Test
    fun testTensorFull() {
        val ones = MNNTensor.full(intArrayOf(2, 2), 5.5f)
        
        assertEquals(4,ones.size())
        assertArrayEquals(FloatArray(4) { 5.5f }, ones.getData(), 0.001f)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testTensorSizeMismatch() {
        val data = floatArrayOf(1f, 2f, 3f)
        val shape = intArrayOf(2, 3) // Should be 6 elements, but data has 3
        
        // Should throw IllegalArgumentException
        MNNTensor.fromFloatArray(data, shape)
    }
    
    @Test
    fun testTensorToFloatArray() {
        val data = floatArrayOf(1f, 2f, 3f, 4f)
        val tensor = MNNTensor.fromFloatArray(data, intArrayOf(2, 2))
        
        val copy = tensor.toFloatArray()
        assertArrayEquals(data, copy, 0.001f)
        
        // Verify it's a copy, not the same reference
        assertNotSame(data, copy)
    }
}

class MNNConfigTest {
    
    @Test
    fun testDefaultConfig() {
        val config = MNNConfig()
        
        assertEquals(4, config.numThreads)
        assertEquals(ForwardType.CPU, config.forwardType)
        assertEquals(Precision.NORMAL, config.precision)
        assertEquals(PowerMode.NORMAL, config.power)
        assertEquals(MemoryMode.NORMAL, config.memory)
    }
    
    @Test
    fun testCustomConfig() {
        val config = MNNConfig(
            numThreads = 8,
            forwardType = ForwardType.OPENCL,
            precision = Precision.LOW,
            power = PowerMode.HIGH,
            memory = MemoryMode.LOW
        )
        
        assertEquals(8, config.numThreads)
        assertEquals(ForwardType.OPENCL, config.forwardType)
        assertEquals(Precision.LOW, config.precision)
        assertEquals(PowerMode.HIGH, config.power)
        assertEquals(MemoryMode.LOW, config.memory)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testInvalidNumThreads() {
        // Should throw IllegalArgumentException
        MNNConfig(numThreads = 0)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testNegativeNumThreads() {
        // Should throw IllegalArgumentException
        MNNConfig(numThreads = -1)
    }
}

class MNNExceptionTest {
    
    @Test
    fun testMNNException() {
        val exception = MNNException("Test error")
        assertEquals("Test error", exception.message)
        assertNull(exception.cause)
    }
    
    @Test
    fun testMNNExceptionWithCause() {
        val cause = RuntimeException("Root cause")
        val exception = MNNException("Test error", cause)
        
        assertEquals("Test error", exception.message)
        assertSame(cause, exception.cause)
    }
    
    @Test
    fun testModelLoadException() {
        val exception = ModelLoadException("Failed to load")
        assertTrue(exception is MNNException)
        assertEquals("Failed to load", exception.message)
    }
    
    @Test
    fun testInferenceException() {
        val exception = InferenceException("Inference failed")
        assertTrue(exception is MNNException)
        assertEquals("Inference failed", exception.message)
    }
    
    @Test
    fun testTensorException() {
        val exception = TensorException("Tensor error")
        assertTrue(exception is MNNException)
        assertEquals("Tensor error", exception.message)
    }
    
    @Test
    fun testNativeException() {
        val exception = NativeException("Native error")
        assertTrue(exception is MNNException)
        assertEquals("Native error", exception.message)
    }
}
