package com.mnn.sdk

import android.content.Context
import java.io.File

/**
 * Main entry point for the MNN Android SDK.
 * Manages native library initialization and model loading.
 *
 * Example usage:
 * ```
 * val engine = MNNEngine.initialize(context)
 * val model = engine.loadModel("model.mnn")
 * ```
 */
class MNNEngine private constructor(private val context: Context) {
    
    init {
        // Load native MNN library
        try {
            System.loadLibrary("MNN")
        } catch (e: UnsatisfiedLinkError) {
            throw MNNException("Failed to load MNN native library", e)
        }
    }
    
    /**
     * Load a model from the assets folder.
     *
     * @param assetPath Path to the model file in assets
     * @return MNNModel instance
     * @throws MNNException if model loading fails
     */
    fun loadModelFromAssets(assetPath: String): MNNModel {
        val inputStream = context.assets.open(assetPath)
        val bytes = inputStream.readBytes()
        inputStream.close()
        return loadModelFromBytes(bytes)
    }
    
    /**
     * Load a model from a file.
     *
     * @param file Model file
     * @return MNNModel instance
     * @throws MNNException if model loading fails
     */
    fun loadModelFromFile(file: File): MNNModel {
        if (!file.exists()) {
            throw MNNException("Model file does not exist: ${file.absolutePath}")
        }
        val bytes = file.readBytes()
        return loadModelFromBytes(bytes)
    }
    
    /**
     * Load a model from a file path.
     *
     * @param path Path to the model file
     * @return MNNModel instance
     * @throws MNNException if model loading fails
     */
    fun loadModelFromPath(path: String): MNNModel {
        return loadModelFromFile(File(path))
    }
    
    /**
     * Load a model from a byte array.
     *
     * @param bytes Model data as byte array
     * @return MNNModel instance
     * @throws MNNException if model loading fails
     */
    fun loadModelFromBytes(bytes: ByteArray): MNNModel {
        val nativeHandle = nativeLoadModel(bytes)
        if (nativeHandle == 0L) {
            throw MNNException("Failed to load model from bytes")
        }
        return MNNModel(nativeHandle)
    }
    
    /**
     * Get MNN version information.
     *
     * @return Version string
     */
    fun getVersion(): String {
        return nativeGetVersion()
    }
    
    // Native methods
    private external fun nativeLoadModel(modelData: ByteArray): Long
    private external fun nativeGetVersion(): String
    
    companion object {
        @Volatile
        private var instance: MNNEngine? = null
        
        /**
         * Initialize the MNN engine.
         * This must be called before using any MNN functionality.
         *
         * @param context Application context
         * @return MNNEngine instance
         */
        fun initialize(context: Context): MNNEngine {
            return instance ?: synchronized(this) {
                instance ?: MNNEngine(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        /**
         * Get the initialized MNN engine instance.
         *
         * @return MNNEngine instance
         * @throws IllegalStateException if not initialized
         */
        fun getInstance(): MNNEngine {
            return instance ?: throw IllegalStateException(
                "MNNEngine not initialized. Call initialize(context) first."
            )
        }
    }
}
