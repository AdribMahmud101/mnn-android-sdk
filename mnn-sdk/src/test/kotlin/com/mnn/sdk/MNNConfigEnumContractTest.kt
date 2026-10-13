package com.mnn.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the public config constants to the values MNN actually uses.
 *
 * `mnn_interpreter.cpp` casts these ints straight into `MNNForwardType` and
 * `MNN::BackendConfig::{PrecisionMode,PowerMode,MemoryMode}` with NO translation
 * table, so any drift here silently selects the wrong backend or inverts
 * precision/power. This test is the regression guard for that class of bug —
 * it failed before the constants were corrected (ForwardType.VULKAN was 4, which
 * is MNN_FORWARD_AUTO; Precision.LOW was 1, which is Precision_High).
 *
 * Source of truth: mnn-sdk/src/main/cpp/include/MNN/MNNForwardType.h (MNN 3.6.1).
 */
class MNNConfigEnumContractTest {

    @Test
    fun `forward type matches MNNForwardType enum`() {
        assertEquals(0, ForwardType.CPU)      // MNN_FORWARD_CPU
        assertEquals(1, ForwardType.METAL)    // MNN_FORWARD_METAL
        assertEquals(2, ForwardType.CUDA)     // MNN_FORWARD_CUDA
        assertEquals(3, ForwardType.OPENCL)   // MNN_FORWARD_OPENCL
        assertEquals(4, ForwardType.AUTO)     // MNN_FORWARD_AUTO
        assertEquals(5, ForwardType.NN)       // MNN_FORWARD_NN
        assertEquals(6, ForwardType.OPENGL)   // MNN_FORWARD_OPENGL
        assertEquals(7, ForwardType.VULKAN)   // MNN_FORWARD_VULKAN
        assertEquals(10, ForwardType.HEXAGON) // MNN_FORWARD_HEXAGON (3.6+)
        assertEquals(15, ForwardType.MUSA)    // MNN_FORWARD_MUSA    (3.6+)
    }

    @Test
    fun `precision matches BackendConfig PrecisionMode`() {
        // enum { Precision_Normal = 0, Precision_High, Precision_Low, Precision_Low_BF16 }
        assertEquals(0, Precision.NORMAL)
        assertEquals(1, Precision.HIGH)
        assertEquals(2, Precision.LOW)
        assertEquals(3, Precision.LOW_BF16)
    }

    @Test
    fun `power matches BackendConfig PowerMode`() {
        // enum { Power_Normal = 0, Power_High, Power_Low }
        assertEquals(0, PowerMode.NORMAL)
        assertEquals(1, PowerMode.HIGH)
        assertEquals(2, PowerMode.LOW)
    }

    @Test
    fun `memory matches BackendConfig MemoryMode`() {
        // enum { Memory_Normal = 0, Memory_High, Memory_Low }
        assertEquals(0, MemoryMode.NORMAL)
        assertEquals(1, MemoryMode.HIGH)
        assertEquals(2, MemoryMode.LOW)
    }

    @Test
    fun `removed constants are not reintroduced`() {
        // TENSORRT and HIAI were never valid MNNForwardType values on any MNN
        // version; they silently aliased CUDA/OPENGL/VULKAN. They must stay gone.
        val declared = ForwardType::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { it.name }
        assertTrue("TENSORRT should not be exposed", !declared.contains("TENSORRT"))
        assertTrue("HIAI should not be exposed", !declared.contains("HIAI"))
    }
}
