# Building MNN Android SDK from Source

This guide explains how to build the MNN Android SDK from source and integrate MNN native libraries.

## Prerequisites

### Required Tools

- JDK 17 or later
- Android SDK with API 21+ and API 34
- Android NDK (for building native components)
- Gradle 8.0+
- Git

### Optional Tools

- [Devbox](https://www.jetpack.io/devbox) - for reproducible environment
- [direnv](https://direnv.net/) - for automatic environment setup
- CMake 3.10+ (if building MNN from source)

## Development Environment Setup

### Using Devbox (Recommended)

This project includes devbox configuration for reproducible builds:

```bash
# Install devbox
curl -fsSL https://get.jetpack.io/devbox | bash

# Install direnv
# Ubuntu/Debian:
sudo apt install direnv

# macOS:
brew install direnv

# Add to your shell profile (~/.bashrc, ~/.zshrc, etc.):
eval "$(direnv hook bash)"  # or zsh, fish, etc.

# Clone and enter project
git clone <repository-url>
cd mnn_android_sdk

# Allow direnv (this loads the environment)
direnv allow

# Environment is now ready!
```

### Manual Setup

If not using devbox:

```bash
# Set environment variables
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

# Verify Java version
java -version  # Should be 17+

# Verify Android SDK
which adb
```

## Building the SDK

### 1. Clone the Repository

```bash
git clone <repository-url>
cd mnn_android_sdk
```

### 2. Obtain MNN Native Libraries

You have two options:

#### Option A: Download Prebuilt Libraries (Recommended)

1. Visit [MNN Releases](https://github.com/alibaba/MNN/releases)
2. Download the Android native libraries (`.so` files)
3. Extract and place them in the appropriate directories:

```bash
mnn-sdk/libs/
├── arm64-v8a/
│   └── libMNN.so
├── armeabi-v7a/
│   └── libMNN.so
├── x86/
│   └── libMNN.so
└── x86_64/
    └── libMNN.so
```

#### Option B: Build MNN from Source

```bash
# Clone MNN repository
git clone https://github.com/alibaba/MNN.git
cd MNN

# Build for Android
./schema/generate.sh
mkdir build_android
cd build_android

# For arm64-v8a
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI="arm64-v8a" \
  -DANDROID_PLATFORM=android-21 \
  -DMNN_BUILD_SHARED_LIBS=ON \
  -DMNN_BUILD_FOR_ANDROID_COMMAND=ON \
  -DMNN_USE_LOGCAT=ON

make -j$(nproc)

# Copy built library
cp libMNN.so /path/to/mnn_android_sdk/mnn-sdk/libs/arm64-v8a/

# Repeat for other architectures (armeabi-v7a, x86, x86_64)
```

For detailed MNN build instructions, see [MNN Android Build Guide](https://www.yuque.com/mnn/en/build_android).

### 3. Build the SDK

```bash
cd /path/to/mnn_android_sdk

# Build debug version
./gradlew :mnn-sdk:assembleDebug

# Build release version
./gradlew :mnn-sdk:assembleRelease

# Build both with tests
./gradlew build

# Output will be in:
# mnn-sdk/build/outputs/aar/
```

### 4. Build the Sample App

```bash
# Build sample app
./gradlew :sample:assembleDebug

# Install on connected device
./gradlew :sample:installDebug

# Or build and install in one command
./gradlew :sample:installDebug

# Run on device
adb shell am start -n com.mnn.sample/.MainActivity
```

## Publishing

### Publish to Maven Local

For testing in other projects:

```bash
./gradlew :mnn-sdk:publishToMavenLocal
```

Then in your test project's `build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()
    // other repositories
}

dependencies {
    implementation("com.mnn:mnn-sdk:1.0.0")
}
```

### Publish to Maven Central

(To be configured)

```bash
./gradlew :mnn-sdk:publish
```

## Project Structure

```
mnn_android_sdk/
├── build.gradle.kts           # Root build configuration
├── settings.gradle.kts        # Project settings
├── gradle.properties          # Build properties
├── devbox.json               # Devbox configuration
├── .envrc                    # direnv configuration
├── mnn-sdk/                  # SDK library module
│   ├── build.gradle.kts      # Library build config
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/       # Kotlin wrapper code
│   │   │   │   └── com/mnn/sdk/
│   │   │   │       ├── MNNEngine.kt
│   │   │   │       ├── MNNModel.kt
│   │   │   │       ├── MNNInterpreter.kt
│   │   │   │       ├── MNNTensor.kt
│   │   │   │       ├── MNNConfig.kt
│   │   │   │       └── MNNException.kt
│   │   │   ├── cpp/          # JNI bridge (if needed)
│   │   │   └── AndroidManifest.xml
│   │   └── test/             # Unit tests
│   └── libs/                 # Native libraries (.so)
│       ├── arm64-v8a/
│       ├── armeabi-v7a/
│       ├── x86/
│       └── x86_64/
├── sample/                   # Sample application
│   ├── build.gradle.kts
│   └── src/
└── docs/                     # Documentation
```

## Gradle Tasks

### Common Tasks

```bash
# List all available tasks
./gradlew tasks

# Clean build
./gradlew clean

# Build everything
./gradlew build

# Run unit tests
./gradlew test

# Run Android instrumentation tests
./gradlew connectedAndroidTest

# Generate documentation
./gradlew dokkaHtml
```

### SDK Module Tasks

```bash
# Build SDK AAR
./gradlew :mnn-sdk:assembleRelease

# Run SDK tests
./gradlew :mnn-sdk:test

# Publish to Maven Local
./gradlew :mnn-sdk:publishToMavenLocal
```

### Sample App Tasks

```bash
# Build sample APK
./gradlew :sample:assembleDebug

# Install on device
./gradlew :sample:installDebug

# Uninstall
./gradlew :sample:uninstallDebug
```

## Troubleshooting

### Gradle Build Fails

**Issue:** `Could not resolve dependencies`

**Solution:** Check your internet connection and Gradle cache:
```bash
./gradlew build --refresh-dependencies
```

### Native Library Not Found

**Issue:** `UnsatisfiedLinkError: no libMNN.so found`

**Solution:** Ensure `.so` files are in correct directories:
```bash
ls -R mnn-sdk/libs/
```

### NDK Not Found

**Issue:** `NDK not configured`

**Solution:** Install NDK via Android Studio or set path:
```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
```

### OutOfMemoryError

**Issue:** Gradle runs out of memory

**Solution:** Increase heap size in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4096m
```

### devbox Issues

**Issue:** `devbox not found` or packages not installing

**Solution:**
```bash
# Reinstall devbox
curl -fsSL https://get.jetpack.io/devbox | bash

# Clear cache
devbox cache clear

# Update packages
devbox update
```

### direnv Not Loading

**Issue:** Environment variables not set

**Solution:**
```bash
# Allow .envrc
direnv allow

# Verify it's working
direnv status

# Check environment
echo $ANDROID_HOME
```

## Continuous Integration

### GitHub Actions Example

```yaml
name: Build

on: [push, pull_request]

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
    
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew
    
    - name: Build with Gradle
      run: ./gradlew build
    
    - name: Upload AAR
      uses: actions/upload-artifact@v3
      with:
        name: mnn-sdk
        path: mnn-sdk/build/outputs/aar/*.aar
```

## Development Tips

### Fast Iteration

For faster builds during development:

```bash
# Only build what changed
./gradlew :mnn-sdk:assembleDebug --no-rebuild

# Skip tests
./gradlew build -x test

# Build offline (use cached dependencies)
./gradlew build --offline
```

### Debugging

Enable verbose logging:

```bash
# Gradle debug output
./gradlew build --debug

# More info
./gradlew build --info --stacktrace
```

### Code Style

Format code before committing:

```bash
# Format Kotlin code (if ktlint configured)
./gradlew ktlintFormat
```

## Next Steps

- Read the [Getting Started Guide](GETTING_STARTED.md)
- Check the [API Reference](API.md)
- See [Examples](EXAMPLES.md) for usage patterns
- Explore the [sample app](../sample/)

## Getting Help

- 📖 [Documentation](../README.md)
- 🐛 [Report Build Issues](../../issues)
- 💬 [Discussion Forum](../../discussions)
- 🔧 [MNN Build Guide](https://www.yuque.com/mnn/en/build_android)
