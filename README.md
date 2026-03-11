# MNN Android SDK

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9.20-orange.svg)](https://kotlinlang.org)

An easy-to-use Kotlin Android SDK for [MNN (Mobile Neural Network)](https://github.com/alibaba/MNN), Alibaba's lightweight deep learning inference framework.

## Features

✨ **Simple API** - Intuitive Kotlin DSL for model loading and inference
🚀 **Coroutines Support** - Async/await pattern for non-blocking operations
🔒 **Type-Safe** - Leverage Kotlin's type system for safer code
🎯 **Memory Efficient** - Automatic resource management with lifecycle awareness
⚡ **High Performance** - Direct JNI bindings to native MNN library
📦 **Easy Integration** - Single AAR dependency with bundled native libraries

## Quick Start

### Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.mnn:mnn-sdk:1.0.0")
}
```

Or use Maven local for development:

```bash
./gradlew :mnn-sdk:publishToMavenLocal
```

```kotlin
dependencies {
    implementation("com.mnn:mnn-sdk:1.0.0")
}
```

### Basic Usage

```kotlin
// Initialize the MNN engine
val engine = MNNEngine.initialize(context)

// Load a model from assets
val model = engine.loadModelFromAssets("mobilenet.mnn")

// Configure the interpreter
val config = MNNConfig(
    numThreads = 4,
    precision = Precision.NORMAL
)
val interpreter = model.createInterpreter(config)

// Prepare input tensor
val inputTensor = MNNTensor.fromBitmap(bitmap)

// Run inference
val outputTensor = interpreter.run(inputTensor)

// Process results
val results = outputTensor.toFloatArray()

// Clean up
interpreter.close()
model.close()
```

### Using Coroutines

```kotlin
lifecycleScope.launch {
    val output = interpreter.runAsync(inputTensor)
    // Handle results on main thread
    updateUI(output)
}
```

## Documentation

- [Getting Started Guide](docs/GETTING_STARTED.md)
- [API Reference](docs/API.md)
- [Examples](docs/EXAMPLES.md)
- [Building from Source](docs/BUILDING.md)

## Project Structure

```
mnn_android_sdk/
├── mnn-sdk/              # Core SDK library
│   ├── src/main/kotlin/  # Kotlin wrapper code
│   └── libs/             # Native MNN libraries (.so)
├── sample/               # Sample application
├── docs/                 # Documentation
└── PLAN.md              # Development plan
```

## Requirements

- Android API 21+ (Android 5.0 Lollipop)
- Kotlin 1.9+
- Android Gradle Plugin 8.0+
- JDK 17

## Development Setup

This project uses [devbox](https://www.jetpack.io/devbox) and [direnv](https://direnv.net/) for reproducible development environments.

### Prerequisites

```bash
# Install devbox
curl -fsSL https://get.jetpack.io/devbox | bash

# Install direnv
# On Ubuntu/Debian:
sudo apt install direnv

# Add to your shell profile (~/.bashrc, ~/.zshrc, etc.):
eval "$(direnv hook bash)"  # or zsh, fish, etc.
```

### Setup

```bash
# Clone the repository
git clone <repository-url>
cd mnn_android_sdk

# Allow direnv
direnv allow

# Enter devbox shell (optional, direnv does this automatically)
devbox shell

# Build the project
./gradlew build

# Run sample app
./gradlew :sample:installDebug
```

## Building

```bash
# Build SDK library
./gradlew :mnn-sdk:assembleRelease

# Build sample app
./gradlew :sample:assembleDebug

# Run tests
./gradlew test

# Publish to Maven Local
./gradlew :mnn-sdk:publishToMavenLocal
```

## MNN Native Libraries

The SDK requires MNN native libraries (`.so` files) for different architectures:

- `arm64-v8a` - 64-bit ARM devices (most modern phones)
- `armeabi-v7a` - 32-bit ARM devices
- `x86` - 32-bit x86 (emulators)
- `x86_64` - 64-bit x86 (emulators)

### Obtaining MNN Libraries

1. **Download prebuilt binaries** from [MNN Releases](https://github.com/alibaba/MNN/releases)
2. **Build from source** following [MNN Android Build Guide](https://www.yuque.com/mnn/en/build_android)
3. Place `.so` files in `mnn-sdk/libs/<abi>/`

## Sample App

The included sample app demonstrates:
- Engine initialization
- Model loading
- Tensor creation and manipulation
- Synchronous and asynchronous inference
- Resource cleanup

Run with:
```bash
./gradlew :sample:installDebug
```

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

## License

This SDK wrapper is licensed under Apache 2.0. MNN itself is also Apache 2.0 licensed.

See [LICENSE](LICENSE) for details.

## Acknowledgments

- [MNN](https://github.com/alibaba/MNN) - Mobile Neural Network inference framework by Alibaba
- Inspired by TensorFlow Lite Android API design

## Support

- 📖 [MNN Documentation](https://www.yuque.com/mnn/en)
- 🐛 [Issue Tracker](../../issues)
- 💬 [Discussions](../../discussions)

## Roadmap

- [x] Basic model loading and inference
- [x] Coroutines support
- [x] Tensor utilities (Bitmap conversion, etc.)
- [ ] Image preprocessing utilities
- [ ] Model encryption support
- [ ] Performance benchmarking tools
- [ ] Additional backends (GPU, NPU)
- [ ] Pre-trained model examples
