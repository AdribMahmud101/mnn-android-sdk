#include <jni.h>
#include <string>
#include <cstring>
#include <android/log.h>
#include <MNN/Tensor.hpp>
#include <MNN/MNNDefine.h>

#define LOG_TAG "MNN-JNI-Tensor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Copy float array data to MNN tensor
 */
JNIEXPORT jboolean JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeCopyToTensor(
    JNIEnv* env,
    jobject thiz,
    jlong tensor_ptr,
    jfloatArray data) {
    
    if (tensor_ptr == 0 || data == nullptr) {
        LOGE("Tensor pointer or data is null");
        return JNI_FALSE;
    }
    
    auto* tensor = reinterpret_cast<MNN::Tensor*>(tensor_ptr);
    
    jsize data_len = env->GetArrayLength(data);
    jfloat* src_data = env->GetFloatArrayElements(data, nullptr);
    
    if (src_data == nullptr) {
        LOGE("Failed to get float array elements");
        return JNI_FALSE;
    }
    
    // Create host tensor for data transfer
    auto* host_tensor = new MNN::Tensor(tensor, MNN::Tensor::CAFFE);
    
    // Copy data to host tensor
    size_t byte_size = data_len * sizeof(float);
    ::memcpy(host_tensor->host<float>(), src_data, byte_size);
    
    // Transfer from host tensor to device tensor
    tensor->copyFromHostTensor(host_tensor);
    
    delete host_tensor;
    env->ReleaseFloatArrayElements(data, src_data, JNI_ABORT);
    
    LOGD("Copied %d floats to tensor", data_len);
    return JNI_TRUE;
}

/**
 * Copy MNN tensor data to float array
 */
JNIEXPORT jfloatArray JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeCopyFromTensor(
    JNIEnv* env,
    jobject thiz,
    jlong tensor_ptr) {
    
    if (tensor_ptr == 0) {
        LOGE("Tensor pointer is null");
        return nullptr;
    }
    
    auto* tensor = reinterpret_cast<MNN::Tensor*>(tensor_ptr);
    
    // Create host tensor for data transfer
    auto* host_tensor = new MNN::Tensor(tensor, MNN::Tensor::CAFFE);
    
    // Transfer from device tensor to host tensor
    tensor->copyToHostTensor(host_tensor);
    
    // Get tensor size
    jsize element_count = host_tensor->elementSize();
    
    // Create Java float array
    jfloatArray result = env->NewFloatArray(element_count);
    if (result == nullptr) {
        LOGE("Failed to create float array");
        delete host_tensor;
        return nullptr;
    }
    
    // Copy data to Java array
    env->SetFloatArrayRegion(result, 0, element_count, host_tensor->host<float>());
    
    delete host_tensor;
    
    LOGD("Copied %d floats from tensor", element_count);
    return result;
}

/**
 * Get tensor shape/dimensions
 */
JNIEXPORT jintArray JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeGetTensorShape(
    JNIEnv* env,
    jobject thiz,
    jlong tensor_ptr) {
    
    if (tensor_ptr == 0) {
        LOGE("Tensor pointer is null");
        return nullptr;
    }
    
    auto* tensor = reinterpret_cast<MNN::Tensor*>(tensor_ptr);
    
    // Get tensor dimensions
    auto dims = tensor->shape();
    jsize dim_count = dims.size();
    
    // Create Java int array for dimensions
    jintArray result = env->NewIntArray(dim_count);
    if (result == nullptr) {
        LOGE("Failed to create int array for shape");
        return nullptr;
    }
    
    // Copy dimensions to Java array
    env->SetIntArrayRegion(result, 0, dim_count, dims.data());
    
    LOGD("Tensor shape has %d dimensions", dim_count);
    return result;
}

/**
 * Get tensor element count
 */
JNIEXPORT jint JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeGetTensorSize(
    JNIEnv* env,
    jobject thiz,
    jlong tensor_ptr) {
    
    if (tensor_ptr == 0) {
        LOGE("Tensor pointer is null");
        return 0;
    }
    
    auto* tensor = reinterpret_cast<MNN::Tensor*>(tensor_ptr);
    jint element_count = tensor->elementSize();
    
    LOGD("Tensor has %d elements", element_count);
    return element_count;
}

/**
 * Get tensor data type
 */
JNIEXPORT jint JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeGetTensorDataType(
    JNIEnv* env,
    jobject thiz,
    jlong tensor_ptr) {
    
    if (tensor_ptr == 0) {
        LOGE("Tensor pointer is null");
        return -1;
    }
    
    auto* tensor = reinterpret_cast<MNN::Tensor*>(tensor_ptr);
    auto dtype = tensor->getType();
    
    // Map MNN data type to integer
    // MNN::halide_type_float = 0, halide_type_int = 1, etc.
    jint type_code = static_cast<jint>(dtype.code);
    
    LOGD("Tensor data type code: %d", type_code);
    return type_code;
}

} // extern "C"
