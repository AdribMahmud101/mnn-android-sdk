#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <android/log.h>
#include <MNN/Interpreter.hpp>
#include <MNN/MNNDefine.h>
#include <MNN/MNNForwardType.h>
#include <MNN/Tensor.hpp>

#define LOG_TAG "MNN-JNI-Interpreter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Create MNN Session with specified configuration
 */
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeCreateSession(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jint forward_type,
    jint num_threads,
    jint precision,
    jint power_mode) {
    
    if (interpreter_ptr == 0) {
        LOGE("Interpreter pointer is null");
        return 0;
    }
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    
    // Configure session
    MNN::ScheduleConfig config;
    config.type = static_cast<MNNForwardType>(forward_type);
    config.numThread = num_threads;
    
    // Backend configuration
    MNN::BackendConfig backend_config;
    backend_config.precision = static_cast<MNN::BackendConfig::PrecisionMode>(precision);
    backend_config.power = static_cast<MNN::BackendConfig::PowerMode>(power_mode);
    config.backendConfig = &backend_config;
    
    // Create session
    auto session = interpreter->createSession(config);
    if (session == nullptr) {
        LOGE("Failed to create MNN session");
        return 0;
    }
    
    LOGI("Session created: forward_type=%d, threads=%d, precision=%d, power=%d",
         forward_type, num_threads, precision, power_mode);
    
    return reinterpret_cast<jlong>(session);
}

/**
 * Run inference on the session
 */
JNIEXPORT jboolean JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeRun(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong session_ptr) {
    
    if (interpreter_ptr == 0 || session_ptr == 0) {
        LOGE("Interpreter or session pointer is null");
        return JNI_FALSE;
    }
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    
    auto error_code = interpreter->runSession(session);
    
    if (error_code != MNN::NO_ERROR) {
        LOGE("Inference failed with error code: %d", error_code);
        return JNI_FALSE;
    }
    
    LOGD("Inference completed successfully");
    return JNI_TRUE;
}

/**
 * Get input tensor by name
 */
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeGetInputTensor(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong session_ptr,
    jstring name) {
    
    if (interpreter_ptr == 0 || session_ptr == 0) {
        LOGE("Interpreter or session pointer is null");
        return 0;
    }
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    
    const char* tensor_name = nullptr;
    if (name != nullptr) {
        tensor_name = env->GetStringUTFChars(name, nullptr);
    }
    
    auto* tensor = interpreter->getSessionInput(session, tensor_name);
    
    if (tensor_name != nullptr) {
        env->ReleaseStringUTFChars(name, tensor_name);
    }
    
    if (tensor == nullptr) {
        LOGE("Failed to get input tensor: %s", tensor_name ? tensor_name : "default");
        return 0;
    }
    
    LOGD("Got input tensor: %s", tensor_name ? tensor_name : "default");
    return reinterpret_cast<jlong>(tensor);
}

/**
 * Get output tensor by name
 */
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeGetOutputTensor(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong session_ptr,
    jstring name) {
    
    if (interpreter_ptr == 0 || session_ptr == 0) {
        LOGE("Interpreter or session pointer is null");
        return 0;
    }
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    
    const char* tensor_name = nullptr;
    if (name != nullptr) {
        tensor_name = env->GetStringUTFChars(name, nullptr);
    }
    
    auto* tensor = interpreter->getSessionOutput(session, tensor_name);
    
    if (tensor_name != nullptr) {
        env->ReleaseStringUTFChars(name, tensor_name);
    }
    
    if (tensor == nullptr) {
        LOGE("Failed to get output tensor: %s", tensor_name ? tensor_name : "default");
        return 0;
    }
    
    LOGD("Got output tensor: %s", tensor_name ? tensor_name : "default");
    return reinterpret_cast<jlong>(tensor);
}

/**
 * Release session
 */
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeReleaseSession(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong session_ptr) {
    
    if (interpreter_ptr == 0 || session_ptr == 0) {
        LOGE("Interpreter or session pointer is null");
        return;
    }
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    
    interpreter->releaseSession(session);
    LOGI("Session released");
}

/**
 * Resize tensor (for dynamic input sizes)
 */
JNIEXPORT jboolean JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeResizeTensor(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong tensor_ptr,
    jintArray dims) {
    
    if (interpreter_ptr == 0 || tensor_ptr == 0 || dims == nullptr) {
        LOGE("Invalid parameters for resize tensor");
        return JNI_FALSE;
    }
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* tensor = reinterpret_cast<MNN::Tensor*>(tensor_ptr);
    
    jsize dim_count = env->GetArrayLength(dims);
    jint* dim_values = env->GetIntArrayElements(dims, nullptr);
    
    std::vector<int> new_dims(dim_values, dim_values + dim_count);
    
    interpreter->resizeTensor(tensor, new_dims);
    
    env->ReleaseIntArrayElements(dims, dim_values, JNI_ABORT);
    
    LOGD("Tensor resized to new dimensions");
    return JNI_TRUE;
}

/**
 * Resize session (apply tensor resizing)
 */
JNIEXPORT jboolean JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeResizeSession(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong session_ptr) {
    
    if (interpreter_ptr == 0 || session_ptr == 0) {
        LOGE("Interpreter or session pointer is null");
        return JNI_FALSE;
    }
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    
    interpreter->resizeSession(session);
    
    LOGD("Session resized");
    return JNI_TRUE;
}

/**
 * Run inference with performance metrics
 * Returns HashMap with timing information similar to official MNN LLM Chat app
 */
JNIEXPORT jobject JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeRunWithMetrics(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong session_ptr) {
    
    // Create HashMap for result
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
    
    if (interpreter_ptr == 0 || session_ptr == 0) {
        LOGE("Interpreter or session pointer is null");
        env->CallObjectMethod(hashMap, putMethod, 
                            env->NewStringUTF("error"),
                            env->NewStringUTF("Invalid interpreter or session"));
        return hashMap;
    }
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    
    // Measure inference time
    auto start = std::chrono::high_resolution_clock::now();
    
    auto error_code = interpreter->runSession(session);
    
    auto end = std::chrono::high_resolution_clock::now();
    auto duration_us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
    
    if (error_code != MNN::NO_ERROR) {
        LOGE("Inference failed with error code: %d", error_code);
        env->CallObjectMethod(hashMap, putMethod,
                            env->NewStringUTF("error"),
                            env->NewStringUTF("Inference failed"));
        return hashMap;
    }
    
    // Add success flag
    jclass booleanClass = env->FindClass("java/lang/Boolean");
    jmethodID booleanInit = env->GetMethodID(booleanClass, "<init>", "(Z)V");
    jobject successObj = env->NewObject(booleanClass, booleanInit, JNI_TRUE);
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("success"), successObj);
    
    // Add timing information (in microseconds, matching official MNN app format)
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");
    
    // Prefill time = total inference time (for simple models without separate prefill/decode)
    jobject prefillTimeObj = env->NewObject(longClass, longInit, (jlong)duration_us);
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"), prefillTimeObj);
    
    // For models without token generation, decode_time is 0
    jobject decodeTimeObj = env->NewObject(longClass, longInit, (jlong)0);
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"), decodeTimeObj);
    
    // Note: For LLM models, these would be populated by the LLM runtime
    // For now, we set placeholder values
    jobject promptLenObj = env->NewObject(longClass, longInit, (jlong)0);
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"), promptLenObj);
    
    jobject decodeLenObj = env->NewObject(longClass, longInit, (jlong)0);
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"), decodeLenObj);
    
    LOGD("Inference completed successfully in %lld microseconds", (long long)duration_us);
    return hashMap;
}

} // extern "C"
