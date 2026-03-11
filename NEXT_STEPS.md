# Next Immediate Steps

## Quick Start: Making SDK Functional

### Step 1: Get MNN Native Libraries (Choose One)

#### Option A: Download Pre-built (Fastest) ⭐ RECOMMENDED FOR NOW
```bash
# Download MNN Android binaries
cd /tmp
wget https://github.com/alibaba/MNN/releases/download/2.8.0/MNN-Android-CPU-2.8.0.zip
unzip MNN-Android-CPU-2.8.0.zip

# Copy to project (adjust paths as needed)
cd ~/Documents/mnn_android_sdk
mkdir -p mnn-sdk/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}

# Copy .so files (check actual file structure in the zip)
cp /tmp/MNN-Android/lib/arm64-v8a/libMNN.so mnn-sdk/src/main/jniLibs/arm64-v8a/
cp /tmp/MNN-Android/lib/armeabi-v7a/libMNN.so mnn-sdk/src/main/jniLibs/armeabi-v7a/
cp /tmp/MNN-Android/lib/x86/libMNN.so mnn-sdk/src/main/jniLibs/x86/
cp /tmp/MNN-Android/lib/x86_64/libMNN.so mnn-sdk/src/main/jniLibs/x86_64/

# Copy headers
mkdir -p mnn-sdk/src/main/cpp/include
cp -r /tmp/MNN-Android/include/MNN mnn-sdk/src/main/cpp/include/
```

#### Option B: Build from Source (More Control)
```bash
# Clone MNN
cd /tmp
git clone https://github.com/alibaba/MNN.git
cd MNN

# Install Android NDK if not already installed
# Via devbox: devbox add android-ndk

# Generate schema
./schema/generate.sh

# Build for Android (takes 10-20 minutes)
./build_android.sh

# Libraries will be in: build_android/<abi>/libMNN.so
# Copy them to project as shown in Option A
```

### Step 2: Create JNI Bridge Skeleton

Create minimal JNI implementation to test the setup:

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

include_directories(include)

add_library(mnn-jni-bridge SHARED
    mnn_jni.cpp
)

target_link_libraries(mnn-jni-bridge
    MNN
    android
    log
)
```

**File**: `mnn-sdk/src/main/cpp/mnn_jni.cpp`
```cpp
#include <jni.h>
#include <string>
#include <android/log.h>
#include <MNN/Interpreter.hpp>

#define LOG_TAG "MNN-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_mnn_sdk_MNNEngine_nativeGetVersion(JNIEnv* env, jobject thiz) {
    const char* version = MNN::getVersion();
    LOGI("MNN Version: %s", version);
    return env->NewStringUTF(version);
}

JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNEngine_nativeLoadModel(
    JNIEnv* env, jobject thiz, jbyteArray model_data) {
    
    jsize len = env->GetArrayLength(model_data);
    jbyte* data = env->GetByteArrayElements(model_data, nullptr);
    
    auto* interpreter = MNN::Interpreter::createFromBuffer(
        reinterpret_cast<const char*>(data), len
    );
    
    env->ReleaseByteArrayElements(model_data, data, JNI_ABORT);
    
    if (interpreter == nullptr) {
        LOGI("Failed to create interpreter");
        return 0;
    }
    
    LOGI("Model loaded successfully, size: %d bytes", len);
    return reinterpret_cast<jlong>(interpreter);
}

} // extern "C"
```

### Step 3: Update Gradle Configuration

**File**: `mnn-sdk/build.gradle.kts` (add to android block)
```kotlin
android {
    // ... existing config ...
    
    // Add CMake configuration
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    defaultConfig {
        // ... existing config ...
        
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
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
}
```

### Step 4: Test Basic Setup

```bash
# Clean and rebuild
./gradlew clean
./gradlew mnn-sdk:assembleDebug

# Check if native library is included in AAR
unzip -l mnn-sdk/build/outputs/aar/mnn-sdk-debug.aar | grep "\.so"
# Should see: jni/arm64-v8a/libmnn-jni-bridge.so
#            jni/arm64-v8a/libMNN.so
#            jni/armeabi-v7a/libmnn-jni-bridge.so
#            jni/armeabi-v7a/libMNN.so
```

### Step 5: Create Simple Test

**File**: `mnn-sdk/src/androidTest/kotlin/com/mnn/sdk/MNNBasicTest.kt`
```kotlin
@RunWith(AndroidJUnit4::class)
class MNNBasicTest {
    
    @Test
    fun testMNNVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = MNNEngine.initialize(context)
        
        val version = engine.getVersion()
        assertNotNull(version)
        assertTrue(version.isNotEmpty())
        println("MNN Version: $version")
    }
}
```

Run test:
```bash
# Requires connected device or emulator
./gradlew mnn-sdk:connectedAndroidTest
```

## What to Do After These Steps

1. ✅ If version test passes → JNI bridge is working!
2. 📝 Implement remaining JNI methods (see INTEGRATION_PLAN.md Phase 2)
3. 🧪 Test with a real model
4. 📱 Update sample app
5. 📚 Update documentation
6. 🚀 Publish to GitHub

## Troubleshooting

### CMake can't find MNN.so
- Check paths in CMakeLists.txt
- Verify .so files are in correct directories

### UnsatisfiedLinkError
- Check library name matches: System.loadLibrary("mnn-jni-bridge")
- Verify JNI method signatures match exactly
- Check ABI compatibility

### Build fails with "MNN/Interpreter.hpp not found"
- Ensure headers are copied to mnn-sdk/src/main/cpp/include/MNN/
- Check include path in CMakeLists.txt

## Resources

- [MNN Documentation](https://mnn-docs.readthedocs.io/)
- [Android NDK Guide](https://developer.android.com/ndk/guides)
- [JNI Tips](https://developer.android.com/training/articles/perf-jni)
- Full implementation plan: [INTEGRATION_PLAN.md](INTEGRATION_PLAN.md)
