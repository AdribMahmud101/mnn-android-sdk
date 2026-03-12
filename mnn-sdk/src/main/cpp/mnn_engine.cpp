#include <jni.h>
#include <string>
#include <android/log.h>
#include <MNN/Interpreter.hpp>
#include <MNN/MNNDefine.h>
#include <MNN/Tensor.hpp>

#define LOG_TAG "MNN-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Load model from byte array and create MNN Interpreter
 */
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNEngine_nativeLoadModel(
    JNIEnv* env,
    jobject thiz,
    jbyteArray model_data) {
    
    if (model_data == nullptr) {
        LOGE("Model data is null");
        return 0;
    }
    
    jsize len = env->GetArrayLength(model_data);
    if (len <= 0) {
        LOGE("Model data length is invalid: %d", len);
        return 0;
    }
    
    jbyte* data = env->GetByteArrayElements(model_data, nullptr);
    if (data == nullptr) {
        LOGE("Failed to get model data bytes");
        return 0;
    }
    
    // Create MNN Interpreter from buffer
    auto* interpreter = MNN::Interpreter::createFromBuffer(
        reinterpret_cast<const void*>(data), 
        static_cast<size_t>(len)
    );
    
    env->ReleaseByteArrayElements(model_data, data, JNI_ABORT);
    
    if (interpreter == nullptr) {
        LOGE("Failed to create MNN interpreter from model data");
        return 0;
    }
    
    LOGI("Model loaded successfully, size: %d bytes", len);
    return reinterpret_cast<jlong>(interpreter);
}

/**
 * Load model from file path (for models with external weight files)
 */
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNEngine_nativeLoadModelFromPath(
    JNIEnv* env,
    jobject thiz,
    jstring model_path) {
    
    if (model_path == nullptr) {
        LOGE("Model path is null");
        return 0;
    }
    
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        LOGE("Failed to get model path string");
        return 0;
    }
    
    // Create MNN Interpreter from file
    // This allows MNN to automatically load external weight files (e.g., .mnn.weight)
    auto* interpreter = MNN::Interpreter::createFromFile(path);
    
    env->ReleaseStringUTFChars(model_path, path);
    
    if (interpreter == nullptr) {
        LOGE("Failed to create MNN interpreter from file: %s", path);
        return 0;
    }
    
    LOGI("Model loaded successfully from path: %s", path);
    return reinterpret_cast<jlong>(interpreter);
}

/**
 * Get MNN library version
 */
JNIEXPORT jstring JNICALL
Java_com_mnn_sdk_MNNEngine_nativeGetVersion(
    JNIEnv* env,
    jobject thiz) {
    
    const char* version = MNN::getVersion();
    LOGI("MNN Version: %s", version);
    return env->NewStringUTF(version);
}

/**
 * Release MNN Interpreter and free resources
 */
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNEngine_nativeReleaseModel(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr) {
    
    if (interpreter_ptr == 0) {
        LOGE("Interpreter pointer is null");
        return;
    }
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    delete interpreter;
    LOGI("Interpreter released (from MNNEngine)");
}

/**
 * Release MNN Interpreter - called from MNNModel
 */
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNModel_nativeReleaseModel(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr) {
    
    if (interpreter_ptr == 0) {
        LOGE("Interpreter pointer is null");
        return;
    }
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    delete interpreter;
    LOGI("Interpreter released (from MNNModel)");
}

} // extern "C"
