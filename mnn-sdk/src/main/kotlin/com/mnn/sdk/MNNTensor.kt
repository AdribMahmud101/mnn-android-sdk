package com.mnn.sdk

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrapper class for MNN tensor data.
 * Handles input/output data for model inference.
 *
 * @property data Tensor data as FloatArray
 * @property shape Tensor shape/dimensions
 */
class MNNTensor(
    private val data: FloatArray,
    private val shape: IntArray
) {
    
    /**
     * Get tensor data.
     *
     * @return FloatArray containing the tensor data
     */
    fun getData(): FloatArray = data
    
    /**
     * Get tensor shape.
     *
     * @return IntArray containing the tensor dimensions
     */
    fun getShape(): IntArray = shape
    
    /**
     * Get tensor data as FloatArray (alias for getData).
     *
     * @return FloatArray containing the tensor data
     */
    fun toFloatArray(): FloatArray = data.copyOf()
    
    /**
     * Get total number of elements in the tensor.
     *
     * @return Number of elements
     */
    fun size(): Int = shape.fold(1) { acc, dim -> acc * dim }
    
    /**
     * Get number of dimensions.
     *
     * @return Number of dimensions
     */
    fun rank(): Int = shape.size
    
    override fun toString(): String {
        return "MNNTensor(shape=${shape.contentToString()}, size=${size()})"
    }
    
    companion object {
        /**
         * Create a tensor from a FloatArray.
         *
         * @param data Data array
         * @param shape Tensor shape
         * @return MNNTensor instance
         */
        fun fromFloatArray(data: FloatArray, shape: IntArray): MNNTensor {
            val expectedSize = shape.fold(1) { acc, dim -> acc * dim }
            require(data.size == expectedSize) {
                "Data size (${data.size}) doesn't match shape size ($expectedSize)"
            }
            return MNNTensor(data, shape)
        }
        
        /**
         * Create a tensor from a Bitmap.
         * Converts ARGB bitmap to RGB float tensor with values in [0, 1].
         *
         * @param bitmap Input bitmap
         * @param normalize Whether to normalize values to [0, 1] (default: true)
         * @param meanValues Mean values for normalization (default: [0.485, 0.456, 0.406])
         * @param stdValues Standard deviation values for normalization (default: [0.229, 0.224, 0.225])
         * @return MNNTensor instance with shape [1, height, width, 3]
         */
        fun fromBitmap(
            bitmap: Bitmap,
            normalize: Boolean = true,
            meanValues: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f),
            stdValues: FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f)
        ): MNNTensor {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            val data = FloatArray(width * height * 3)
            var idx = 0
            
            for (pixel in pixels) {
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                
                if (normalize) {
                    data[idx++] = (r - meanValues[0]) / stdValues[0]
                    data[idx++] = (g - meanValues[1]) / stdValues[1]
                    data[idx++] = (b - meanValues[2]) / stdValues[2]
                } else {
                    data[idx++] = r
                    data[idx++] = g
                    data[idx++] = b
                }
            }
            
            val shape = intArrayOf(1, height, width, 3)
            return MNNTensor(data, shape)
        }
        
        /**
         * Create a tensor from a ByteBuffer.
         *
         * @param buffer ByteBuffer containing float data
         * @param shape Tensor shape
         * @return MNNTensor instance
         */
        fun fromByteBuffer(buffer: ByteBuffer, shape: IntArray): MNNTensor {
            buffer.order(ByteOrder.nativeOrder())
            val floatBuffer = buffer.asFloatBuffer()
            val data = FloatArray(floatBuffer.remaining())
            floatBuffer.get(data)
            return fromFloatArray(data, shape)
        }
        
        /**
         * Create an empty tensor with specified shape.
         *
         * @param shape Tensor shape
         * @return MNNTensor instance filled with zeros
         */
        fun zeros(shape: IntArray): MNNTensor {
            val size = shape.fold(1) { acc, dim -> acc * dim }
            return MNNTensor(FloatArray(size), shape)
        }
        
        /**
         * Create a tensor filled with a specific value.
         *
         * @param shape Tensor shape
         * @param value Fill value
         * @return MNNTensor instance
         */
        fun full(shape: IntArray, value: Float): MNNTensor {
            val size = shape.fold(1) { acc, dim -> acc * dim }
            return MNNTensor(FloatArray(size) { value }, shape)
        }
    }
}
