# MNN SDK Integration Plan
**Goal**: Transform stub implementation into fully functional MNN inference SDK

## Phase 1: Obtain MNN Native Libraries

### Option A: Build from Source (Recommended)
**Pros**: Latest version, full control, custom optimizations
**Cons**: Takes time, requires NDK setup

```bash
# Clone MNN repository
git clone https://github.com/alibaba/MNN.git
cd MNN

# Build for Android (all ABIs)
./schema/generate.sh
./build_android.sh

# This produces:
# - libMNN.so (all ABIs: arm64-v8a, armeabi-v7a, x86, x86_64)
# - include/MNN/*.h (C++ headers)
```

**Build flags to consider**:
- `-DMNN_BUILD_CONVERTER=OFF` (we only need runtime)
- `-DMNN_OPENCL=ON` (GPU acceleration)
- `-DMNN_VULKAN=ON` (Vulkan support)
- `-DMNN_BUILD_BENCHMARK=OFF`

### Option B: Download Pre-built Binaries
**Pros**: Faster, simpler
**Cons**: May not have latest features, limited customization

```bash
# Download from MNN releases
wget https://github.com/alibaba/MNN/releases/latest/download/MNN-Android-CPU.zip
unzip MNN-Android-CPU.zip
```

### Integration Steps
1. Copy `.so` files to project structure:
```
mnn-sdk/src/main/jniLibs/
├── arm64-v8a/
│   └── libMNN.so
├── armeabi-v7a/
│   └── libMNN.so
├── x86/
│   └── libMNN.so
└── x86_64/
    └── libMNN.so
```

2. Copy MNN headers for JNI bridge:
```
mnn-sdk/src/main/cpp/include/
└── MNN/
    ├── Interpreter.hpp
    ├── Tensor.hpp
    ├── MNNDefine.h
    └── ... (other headers)
```

## Phase 2: Implement JNI Bridge

### Create CMake Build Configuration

**File**: `mnn-sdk/src/main/cpp/CMakeLists.txt`
```cmake
cmake_minimum_required(VERSION 3.18.1)
project("mnn-jni-bridge")

set(CMAKE_CXX_STANDARD 17)

# Find MNN library
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so
)

# Include MNN headers
include_directories(include)

# Build JNI bridge
add_library(mnn-jni-bridge SHARED
    mnn_engine.cpp
    mnn_tensor.cpp
    mnn_interpreter.cpp
)

target_link_libraries(mnn-jni-bridge
    MNN
    android
    log
)
```

### Update Gradle Build

**File**: `mnn-sdk/build.gradle.kts`
```kotlin
android {
    // Add CMake configuration
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    defaultConfig {
        // Add CMake arguments
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-21"
                )
                cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
            }
        }
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }
}
```

### Implement JNI Methods

**File**: `mnn-sdk/src/main/cpp/mnn_engine.cpp`
```cpp
#include <jni.h>
#include <string>
#include <android/log.h>
#include <MNN/Interpreter.hpp>

#define LOG_TAG "MNN-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// Load model from byte array
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNEngine_nativeLoadModel(
    JNIEnv* env,
    jobject thiz,
    jbyteArray model_data) {
    
    jsize len = env->GetArrayLength(model_data);
    jbyte* data = env->GetByteArrayElements(model_data, nullptr);
    
    // Create MNN Interpreter
    auto* interpreter = MNN::Interpreter::createFromBuffer(
        reinterpret_cast<const char*>(data), len
    );
    
    env->ReleaseByteArrayElements(model_data, data, JNI_ABORT);
    
    if (interpreter == nullptr) {
        LOGE("Failed to create interpreter");
        return 0;
    }
    
    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(interpreter);
}

// Get MNN version
JNIEXPORT jstring JNICALL
Java_com_mnn_sdk_MNNEngine_nativeGetVersion(
    JNIEnv* env,
    jobject thiz) {
    
    return env->NewStringUTF(MNN::getVersion());
}

// Create session
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeCreateSession(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jint forward_type,
    jint num_threads,
    jint precision,
    jint power_mode) {
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    
    MNN::ScheduleConfig config;
    config.type = static_cast<MNNForwardType>(forward_type);
    config.numThread = num_threads;
    
    // Set backend precision
    MNN::BackendConfig backend_config;
    backend_config.precision = static_cast<MNN::BackendConfig::PrecisionMode>(precision);
    backend_config.power = static_cast<MNN::BackendConfig::PowerMode>(power_mode);
    config.backendConfig = &backend_config;
    
    auto session = interpreter->createSession(config);
    return reinterpret_cast<jlong>(session);
}

// Run inference
JNIEXPORT jboolean JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeRun(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong session_ptr) {
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    
    auto error_code = interpreter->runSession(session);
    return error_code == MNN::NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

// Get input tensor
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeGetInputTensor(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong session_ptr,
    jstring name) {
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    
    const char* tensor_name = env->GetStringUTFChars(name, nullptr);
    auto* tensor = interpreter->getSessionInput(session, tensor_name);
    env->ReleaseStringUTFChars(name, tensor_name);
    
    return reinterpret_cast<jlong>(tensor);
}

// Get output tensor
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeGetOutputTensor(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong session_ptr,
    jstring name) {
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    
    const char* tensor_name = env->GetStringUTFChars(name, nullptr);
    auto* tensor = interpreter->getSessionOutput(session, tensor_name);
    env->ReleaseStringUTFChars(name, tensor_name);
    
    return reinterpret_cast<jlong>(tensor);
}

// Cleanup
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeReleaseSession(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr,
    jlong session_ptr) {
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    auto* session = reinterpret_cast<MNN::Session*>(session_ptr);
    interpreter->releaseSession(session);
}

JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNEngine_nativeReleaseModel(
    JNIEnv* env,
    jobject thiz,
    jlong interpreter_ptr) {
    
    auto* interpreter = reinterpret_cast<MNN::Interpreter*>(interpreter_ptr);
    delete interpreter;
}

} // extern "C"
```

**File**: `mnn-sdk/src/main/cpp/mnn_tensor.cpp`
```cpp
#include <jni.h>
#include <MNN/Tensor.hpp>

extern "C" {

// Copy data to native tensor
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeCopyToTensor(
    JNIEnv* env,
    jobject thiz,
    jlong tensor_ptr,
    jfloatArray data) {
    
    auto* tensor = reinterpret_cast<MNN::Tensor*>(tensor_ptr);
    
    jsize len = env->GetArrayLength(data);
    jfloat* src_data = env->GetFloatArrayElements(data, nullptr);
    
    auto* host_tensor = new MNN::Tensor(tensor, MNN::Tensor::CAFFE);
    ::memcpy(host_tensor->host<float>(), src_data, len * sizeof(float));
    tensor->copyFromHostTensor(host_tensor);
    
    delete host_tensor;
    env->ReleaseFloatArrayElements(data, src_data, JNI_ABORT);
}

// Copy data from native tensor
JNIEXPORT jfloatArray JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeCopyFromTensor(
    JNIEnv* env,
    jobject thiz,
    jlong tensor_ptr) {
    
    auto* tensor = reinterpret_cast<MNN::Tensor*>(tensor_ptr);
    
    auto* host_tensor = new MNN::Tensor(tensor, MNN::Tensor::CAFFE);
    tensor->copyToHostTensor(host_tensor);
    
    jsize size = host_tensor->elementSize();
    jfloatArray result = env->NewFloatArray(size);
    env->SetFloatArrayRegion(result, 0, size, host_tensor->host<float>());
    
    delete host_tensor;
    return result;
}

// Get tensor shape
JNIEXPORT jintArray JNICALL
Java_com_mnn_sdk_MNNInterpreter_nativeGetTensorShape(
    JNIEnv* env,
    jobject thiz,
    jlong tensor_ptr) {
    
    auto* tensor = reinterpret_cast<MNN::Tensor*>(tensor_ptr);
    auto dims = tensor->shape();
    
    jintArray result = env->NewIntArray(dims.size());
    env->SetIntArrayRegion(result, 0, dims.size(), dims.data());
    
    return result;
}

} // extern "C"
```

## Phase 3: Update Kotlin Implementation

### Refactor MNNInterpreter.kt
**Changes needed**:
1. Add native method declarations for JNI bridge
2. Remove stub implementation logic
3. Implement tensor data transfer between Kotlin and native
4. Add proper session management

**Key methods to implement**:
```kotlin
private external fun nativeCreateSession(
    interpreterPtr: Long,
    forwardType: Int,
    numThreads: Int,
    precision: Int,
    powerMode: Int
): Long

private external fun nativeRun(interpreterPtr: Long, sessionPtr: Long): Boolean

private external fun nativeGetInputTensor(
    interpreterPtr: Long,
    sessionPtr: Long,
    name: String
): Long

private external fun nativeGetOutputTensor(
    interpreterPtr: Long,
    sessionPtr: Long,
    name: String
): Long

private external fun nativeCopyToTensor(tensorPtr: Long, data: FloatArray)
private external fun nativeCopyFromTensor(tensorPtr: Long): FloatArray
private external fun nativeGetTensorShape(tensorPtr: Long): IntArray
```

### Update MNNEngine.kt
**Changes needed**:
1. Change library loading from "MNN" to "mnn-jni-bridge"
2. Add native cleanup methods
3. Implement proper resource management

```kotlin
init {
    System.loadLibrary("mnn-jni-bridge")
}

private external fun nativeReleaseModel(interpreterPtr: Long)

fun cleanup() {
    models.values.forEach { model ->
        nativeReleaseModel(model.interpreterPtr)
    }
    models.clear()
}
```

## Phase 4: Testing Strategy

### 4.1 Unit Tests
**Location**: `mnn-sdk/src/androidTest/kotlin/`

Create instrumented tests (need Android device/emulator):

```kotlin
@RunWith(AndroidJUnit4::class)
class MNNInferenceTest {
    
    @Test
    fun testRealInference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = MNNEngine.initialize(context)
        
        // Load test model (MobileNetV2 or SqueezeNet)
        val modelBytes = loadModelFromAssets("mobilenet_v2.mnn")
        val model = engine.loadModel(modelBytes)
        
        // Create interpreter
        val interpreter = MNNInterpreter(model, MNNConfig())
        
        // Prepare input (224x224x3 for MobileNet)
        val input = MNNTensor.zeros(intArrayOf(1, 224, 224, 3))
        
        // Run inference
        val output = interpreter.run(mapOf("input" to input))
        
        assertNotNull(output)
        assertTrue(output.containsKey("output"))
        
        interpreter.close()
    }
    
    @Test
    fun testMultipleInferences() {
        // Test memory management
    }
    
    @Test
    fun testThreadSafety() {
        // Test concurrent inference
    }
}
```

### 4.2 Integration Tests
**Test with real models**:
- Download test models: MobileNetV2, SqueezeNet, YOLO-tiny
- Place in `sample/src/main/assets/models/`
- Test image classification, object detection

### 4.3 Performance Benchmarks
Create benchmark suite:
```kotlin
@RunWith(AndroidJUnit4::class)
class MNNBenchmarkTest {
    
    @Test
    fun benchmarkInferenceSpeed() {
        // Measure FPS for different models
        // Test CPU vs GPU performance
        // Memory usage profiling
    }
}
```

## Phase 5: Sample App Enhancement

### Update to Use Real MNN
**File**: `sample/src/main/kotlin/com/mnn/sample/MainActivity.kt`

Changes:
```kotlin
// Replace MNNEngineStub with MNNEngine
private lateinit var mnnEngine: MNNEngine

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    mnnEngine = MNNEngine.initialize(this)
    
    // Load real model from assets
    val modelBytes = assets.open("models/mobilenet_v2.mnn").readBytes()
    val model = mnnEngine.loadModel(modelBytes)
}
```

### Add Real Model Files
1. Download or convert models to MNN format:
```bash
# Using MNN model converter
python -m MNN.tools.mnnconvert -f ONNX --modelFile mobile_v2.onnx --MNNModel mobile_v2.mnn
```

2. Place in `sample/src/main/assets/models/`

### Enhance UI
- Add model selection spinner
- Show real inference results
- Display inference time
- Add camera preview for real-time detection

## Phase 6: Documentation Updates

### Update README.md
- Remove "stub implementation" warnings
- Add performance benchmarks
- Include model conversion guide
- Add troubleshooting section

### Update API.md
- Document native library requirements
- Add memory management best practices
- Include thread safety guidelines

### Create CONTRIBUTING.md
- Build instructions
- Testing requirements
- Code style guide
- PR template

## Phase 7: CI/CD Setup

### GitHub Actions Workflow
**File**: `.github/workflows/build.yml`

```yaml
name: Build and Test

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
    
    - name: Setup Android SDK
      uses: android-actions/setup-android@v2
    
    - name: Build SDK
      run: ./gradlew mnn-sdk:assembleRelease
    
    - name: Run Unit Tests
      run: ./gradlew mnn-sdk:test
    
    - name: Upload AAR
      uses: actions/upload-artifact@v3
      with:
        name: mnn-sdk-release
        path: mnn-sdk/build/outputs/aar/
    
  instrumented-test:
    runs-on: macos-latest  # For hardware acceleration
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Run Instrumented Tests
      uses: reactivecircus/android-emulator-runner@v2
      with:
        api-level: 29
        arch: x86_64
        script: ./gradlew connectedAndroidTest
```

## Phase 8: Licensing & Legal

### Check MNN License
- MNN is Apache 2.0 licensed
- Compatible with most use cases
- Include MNN attribution in README

### Add LICENSE File
```
Apache License 2.0
Include MNN copyright notice
Add your copyright notice
```

### Add NOTICE File
List all third-party dependencies and licenses

## Phase 9: Release Preparation

### Version 1.0.0 Checklist
- [ ] All JNI methods implemented
- [ ] Unit tests passing (100% critical path coverage)
- [ ] Instrumented tests passing
- [ ] Sample app works with real models
- [ ] Documentation complete
- [ ] CI/CD pipeline green
- [ ] Performance benchmarks documented
- [ ] Memory leak testing passed
- [ ] Thread safety verified
- [ ] API stability reviewed

### Publishing Options

#### Option 1: Maven Central
**Pros**: Official, widely used
**Steps**:
1. Register Sonatype account
2. Configure GPG signing
3. Set up publishing in `build.gradle.kts`
4. Submit to staging repository

#### Option 2: JitPack
**Pros**: Easier setup, builds from GitHub
**Steps**:
1. Create GitHub release
2. Add JitPack badge to README
3. Users add JitPack repository

#### Option 3: GitHub Packages
**Pros**: Integrated with GitHub
**Cons**: Requires authentication

### Semantic Versioning
- 1.0.0: First stable release
- 1.1.0: New features (backward compatible)
- 1.0.1: Bug fixes
- 2.0.0: Breaking changes

## Timeline Estimate

| Phase | Estimated Time | Priority |
|-------|----------------|----------|
| 1. Obtain MNN Libraries | 2-4 hours | Critical |
| 2. JNI Bridge Implementation | 1-2 days | Critical |
| 3. Kotlin Integration | 4-6 hours | Critical |
| 4. Testing | 1-2 days | High |
| 5. Sample App | 4-6 hours | High |
| 6. Documentation | 2-3 hours | Medium |
| 7. CI/CD | 2-3 hours | Medium |
| 8. Licensing | 1 hour | High |
| 9. Release Prep | 2-3 hours | Medium |
| **Total** | **5-7 days** | |

## Risk Assessment

### High Risk
- **JNI bridge bugs**: Memory leaks, crashes
  - *Mitigation*: Extensive testing, Valgrind analysis
  
- **ABI compatibility**: Native libs for all architectures
  - *Mitigation*: Test on multiple devices

### Medium Risk
- **Performance issues**: Slower than expected
  - *Mitigation*: Profiling, optimization

- **Model compatibility**: Some models may not work
  - *Mitigation*: Document supported model types

### Low Risk
- **Documentation gaps**: Missing edge cases
  - *Mitigation*: Community feedback, examples

## Next Immediate Steps

1. **Choose MNN library approach** (build vs download)
2. **Create JNI bridge skeleton** (CMake + basic methods)
3. **Test single inference** with simple model
4. **Iterate on remaining methods**
5. **Comprehensive testing**
6. **Prepare for release**

---

**Ready to start?** First step is obtaining MNN native libraries.
Would you like to build from source or download pre-built binaries?
