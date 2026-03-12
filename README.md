<div align="center">

<img src="mnn_banner.png" alt="MNN Android SDK" width="100%">

# MNN Android SDK

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20API%2021%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9.20-orange.svg)](https://kotlinlang.org)
[![MNN](https://img.shields.io/badge/MNN-LLM-blueviolet.svg)](https://github.com/alibaba/MNN)

**A Kotlin Android SDK for on-device LLM inference powered by [MNN](https://github.com/alibaba/MNN) (Alibaba's Mobile Neural Network framework).**

Supports multi-turn chat, chain-of-thought thinking, vision/multimodal input, automatic model downloading, and real-time performance metrics — all running fully on-device.

</div>

---

## Features

- **LLM Chat** — Multi-turn conversations with any MNN-format LLM (Qwen2.5, Qwen3, Qwen3.5, etc.)
- **Thinking Mode** — Toggle chain-of-thought reasoning on/off per message (Qwen3/Qwen3.5 style `<think>` blocks)
- **Vision / VLM** — Send images alongside text to multimodal models (Qwen3.5-VL)
- **Model Downloader** — Fetch models directly from HuggingFace or ModelScope with live progress
- **Auto Model Repair** — Detects and re-downloads missing files (embeddings, visual weights) before inference
- **Performance Metrics** — Prefill speed, decode speed, tokens/sec after every response
- **Model Management** — List, load, switch, and delete downloaded models at runtime
- **Full JNI Bridge** — Direct C++ bindings to `MNN::Transformer::Llm`, no overhead layer

---

## Project Structure

```
mnn_android_sdk/
├── mnn-sdk/                          # Core SDK (Android library module)
│   ├── src/main/kotlin/com/mnn/sdk/
│   │   ├── MNNLlm.kt                 # ← Main LLM wrapper (chat, thinking, vision)
│   │   ├── MNNEngine.kt              # MNN runtime initialisation
│   │   ├── MNNConfig.kt              # Inference configuration (threads, backend)
│   │   ├── MNNInterpreter.kt         # General-purpose inference session
│   │   ├── MNNModel.kt               # Model loading helpers
│   │   └── MNNTensor.kt              # Tensor data helpers
│   ├── src/main/cpp/
│   │   ├── mnn_llm.cpp               # JNI bridge → MNN::Transformer::Llm
│   │   ├── CMakeLists.txt
│   │   └── include/llm/llm.hpp       # Minimal MNN LLM header (vtable-matched)
│   └── src/main/jniLibs/             # Pre-built MNN native libraries
│       ├── arm64-v8a/                # libMNN.so, libllm.so, libMNN_Express.so …
│       ├── armeabi-v7a/
│       ├── x86/
│       └── x86_64/
└── sample/                           # Demo chat app
    └── src/main/kotlin/com/mnn/sample/
        ├── MainActivity.kt           # Chat UI + model management
        ├── ChatAdapter.kt            # RecyclerView adapter (thinking blocks, image thumbnails)
        ├── ChatMessage.kt            # Data class
        └── model/
            ├── AdvancedModelDownloader.kt  # HuggingFace/ModelScope downloader
            └── ModelCatalog.kt             # Catalog JSON models
```

---

## Quick Start

### 1. Add the SDK

The SDK is an Android library module. Include it in your project:

```kotlin
// settings.gradle.kts
include(":mnn-sdk")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":mnn-sdk"))
}
```

Or build and publish the AAR locally:

```bash
./gradlew :mnn-sdk:assembleRelease
# Output: mnn-sdk/build/outputs/aar/mnn-sdk-release.aar
```

### 2. Initialize

```kotlin
val engine = MNNEngine.initialize(context)
```

### 3. Load a model

```kotlin
val llm = MNNLlm.create("/data/user/0/com.example/files/models/Qwen3.5-0.8B-MNN/llm_config.json")
    ?: error("Failed to create LLM")
val loaded = llm.load()   // blocks — call on a background thread
```

### 4. Chat

```kotlin
// Simple text
val reply = llm.response("What is the capital of France?")

// With thinking enabled (Qwen3/Qwen3.5 models)
llm.enableThinking = true
val reply = llm.response("Explain quantum entanglement.")
println("Reasoning: ${llm.lastThinking}")
println("Answer:    $reply")

// With image (VLM models)
val reply = llm.response("Describe this image.", imagePath = "/sdcard/photo.jpg")
```

### 5. Clean up

```kotlin
llm.clearHistory()   // Reset conversation without unloading the model
llm.destroy()        // Free native resources
```

---

## `MNNLlm` API Reference

### Factory

```kotlin
MNNLlm.create(configPath: String): MNNLlm?
```

Reads `llm_config.json` to auto-detect:
- **Chat format** — `QWEN_CHATML` (detected from `prompt_template`, `user_prompt_template`, or `jinja.chat_template`) or `GENERIC`
- **`supportsThinking`** — true when model has `thinking_template`, `enable_thinking`, or `<think>` in its jinja template
- **`isVisual`** — true when `is_visual=true` in config **and** `visual.mnn` is present on disk

```kotlin
llm.load(): Boolean
```

Loads model weights into memory. Automatically calls `set_config({"use_template":false})` so MNN's internal template engine is bypassed and our fully-formatted prompts are passed through directly.

### Properties

| Property | Type | Description |
|---|---|---|
| `supportsThinking` | `Boolean` (read-only) | Whether this model supports `<think>` reasoning blocks |
| `isVisual` | `Boolean` (read-only) | Whether this model can process images |
| `enableThinking` | `Boolean` | Toggle thinking mode for the **next** `response()` call |
| `systemPrompt` | `String` | System prompt prepended to every conversation |
| `lastThinking` | `String?` (read-only) | Raw thinking block from the last `response()` call |

### Methods

```kotlin
// Run inference (blocking — call on Dispatchers.IO)
fun response(
    userMessage: String,
    imagePath: String? = null,   // absolute path; only used when isVisual = true
    maxNewTokens: Int = 512
): String

// Clear conversation history and KV-cache
fun clearHistory()

// Performance metrics from the last response() call
fun lastMetrics(): Metrics   // prefillMs, decodeMs, promptTokens, generatedTokens, tokensPerSec

// Free native resources
fun destroy()
```

### Thinking Behaviour

| `enableThinking` | Prompt suffix injected | Expected output |
|---|---|---|
| `true` | `<\|im_start\|>assistant\n<think>\n` | `reasoning…</think>\nanswer` |
| `false` | `<\|im_start\|>assistant\n<think>\n\n</think>\n` | `answer` (thinking skipped via budget-forcing) |

`lastThinking` is always `null` when `enableThinking = false`. The returned string from `response()` is always the **clean answer only** — the think block is stripped automatically.

---

## Model Downloader

`AdvancedModelDownloader` handles fetching models from HuggingFace or ModelScope:

```kotlin
val downloader = AdvancedModelDownloader(context)

// Download a model
downloader.downloadModel(modelItem, preferredSource = "HuggingFace")
    .flowOn(Dispatchers.IO)
    .collect { state ->
        when (state) {
            is DownloadState.Downloading -> println("${state.progress}% — ${state.currentFile}")
            is DownloadState.Completed   -> loadModel(state.filePath)
            is DownloadState.Failed      -> showError(state.error)
            else -> {}
        }
    }

// Get all fully-downloaded models (checks required files per ModelProfile)
val configs: List<File> = downloader.getDownloadedModels()

// Re-download any missing files for an already-present model
downloader.repairMissingFiles(modelDir).collect { ... }

// Delete a model
downloader.deleteModel(modelDir.absolutePath)
```

### ModelProfile

Downloaded file sets are computed dynamically from `llm_config.json`:

| Config field | Files added |
|---|---|
| `tie_embeddings` present | No extra embedding file needed |
| `tie_embeddings` absent | `embeddings_bf16.bin`, `embeddings.bin` (optional probe) |
| `is_visual = true` + `visual.mnn` on server | `visual.mnn`, `visual.mnn.weight`, `vit_config.json` (optional) |
| `is_audio = true` | `audio_encoder.mnn`, `audio_encoder.mnn.weight` |
| `embedding_file` set | That file added as required |

If `visual.mnn` is not available on the server the config is patched to `is_visual = false` so text-only inference still works.

---

## Building

### Requirements

- Android SDK API 34 (compileSdk), min API 21
- NDK + CMake 3.22.1 (for JNI bridge recompilation)
- Kotlin 1.9.20
- JDK 17

### Commands

```bash
# Build debug APK for the sample app
./gradlew :sample:assembleDebug

# Build release AAR of the SDK only
./gradlew :mnn-sdk:assembleRelease

# Install sample app to connected device
./gradlew :sample:installDebug
# or
adb install -r sample/build/outputs/apk/debug/sample-debug.apk

# Publish SDK to Maven Local
./gradlew :mnn-sdk:publishToMavenLocal
```

> **Note:** `unset JAVA_HOME` before running Gradle if you have a system JDK conflicting with the Android Studio toolchain.

---

## Supported Models

Any MNN-quantised LLM from [taobao-mnn](https://huggingface.co/taobao-mnn) on HuggingFace works. Tested models:

| Model | Thinking | Vision | Size |
|---|---|---|---|
| Qwen2.5-0.5B-Instruct-MNN | ✗ | ✗ | ~0.5 GB |
| Qwen3-0.6B-MNN | ✓ | ✗ | ~0.6 GB |
| Qwen3.5-0.8B-MNN | ✓ | ✓ | ~0.8 GB |
| Qwen3.5-2B-MNN | ✓ | ✓ | ~2.0 GB |

---

## Architecture Notes

### Why `use_template = false`

MNN's `Llm::response(string)` calls `applyTemplate()` internally when `use_template` is true, which re-wraps the entire input as a raw "user" message inside the jinja template. Since we build the full ChatML prompt in Kotlin (including history and the `<think>` prefix), we disable template wrapping at load time with `set_config({"use_template":false})`. The tokenizer then processes our pre-built prompt verbatim.

### VLM Image Handling

Images are injected as `<img>/absolute/path/to/image</img>` immediately before the user's text in the current turn. MNN's `Omni` VLM subclass tokenises this tag by running the vision encoder inside its `tokenizer_encode()` — which requires the `ExecutorScope` set up by `response(string)`. This is why we use `response(string)` (not bare `tokenizer_encode` + `response(vector<int>)`) even after disabling the template.

### Multi-Turn History

Full ChatML is rebuilt from scratch on every call and the KV-cache is reset via `nativeReset()`. The assistant reply stored in history is always the **clean** reply — the `<think>…</think>` block is stripped before being saved, so reasoning never bleeds into subsequent turns.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

MNN itself is also Apache 2.0: [github.com/alibaba/MNN](https://github.com/alibaba/MNN).

