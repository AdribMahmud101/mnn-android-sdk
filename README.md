<div align="center">

<img src="mnn_banner.png" alt="MNN Android SDK" width="100%">

# MNN Android SDK

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20API%2021%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9.20-orange.svg)](https://kotlinlang.org)
[![MNN](https://img.shields.io/badge/MNN-LLM-blueviolet.svg)](https://github.com/alibaba/MNN)

**A plug-and-play Kotlin Android SDK for on-device LLM inference powered by [MNN](https://github.com/alibaba/MNN) (Alibaba's Mobile Neural Network framework).**

Drop it in, call `MNNLlm.load(configPath)`, and you have a fully coroutine-native, streaming, multi-turn LLM — no boilerplate, no manual thread management, no native lifecycle to manage.

Supports multi-turn chat, chain-of-thought thinking, vision/multimodal input, token streaming, prompt customisation, automatic model downloading, and real-time performance metrics — all running fully on-device.

</div>

---

## Features

- **Plug-and-play** — One suspend call to load. One suspend call to chat. Zero setup ceremony.
- **Token Streaming** — `chatFlow()` and `responseFlow()` emit tokens in real time via a real C++ callback streambuf — no polling, no blocking UI
- **Typed Streaming** — `ChatFlow` emits `ThinkingToken` / `AnswerToken` / `Done` events so thinking and answer are separated automatically — no `<think>` tag parsing in your UI code
- **Coroutine-native** — `chat()` and `MNNLlm.load()` are `suspend` functions that dispatch to `Dispatchers.IO` internally; collect flows on any thread
- **`Closeable`** — use `llm.use { }` blocks; native memory cannot leak
- **Prompt Customisation** — Set `promptBuilder` to inject RAG context, few-shot examples, or tool results without touching SDK internals
- **LLM Chat** — Multi-turn conversations with any MNN-format LLM (Qwen2.5, Qwen3, Qwen3.5, etc.)
- **Thinking Mode** — Toggle chain-of-thought reasoning on/off per message (Qwen3/Qwen3.5 `<think>` budget-forcing)
- **Vision / VLM** — Send images alongside text to multimodal models (Qwen3.5-VL)
- **Model Downloader** — Fetch models from HuggingFace or ModelScope with live progress
- **Auto Model Repair** — Detects and re-downloads missing files (embeddings, visual weights) before inference
- **Performance Metrics** — Prefill speed, decode speed, tokens/sec after every response
- **Full JNI Bridge** — Direct C++ bindings to `MNN::Transformer::Llm`, no overhead layer

---

## Project Structure

```
mnn_android_sdk/
├── mnn-sdk/                          # Core SDK (Android library module)
│   ├── src/main/kotlin/com/mnn/sdk/
│   │   ├── MNNLlm.kt                 # ← Main LLM wrapper (chat, streaming, thinking, vision)
│   │   ├── MNNEngine.kt              # MNN runtime for generic (non-LLM) inference
│   │   ├── MNNConfig.kt              # Inference configuration (threads, backend)
│   │   ├── MNNInterpreter.kt         # General-purpose inference session
│   │   ├── MNNModel.kt               # Model loading helpers
│   │   └── MNNTensor.kt              # Tensor data helpers
│   ├── src/main/cpp/
│   │   ├── mnn_llm.cpp               # JNI bridge → MNN::Transformer::Llm
│   │   ├── CMakeLists.txt
│   │   └── include/llm/llm.hpp       # MNN LLM header (vtable-matched)
│   └── src/main/jniLibs/             # Pre-built MNN native libraries
│       ├── arm64-v8a/                # libMNN.so, libllm.so, libMNN_Express.so …
│       └── armeabi-v7a/
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

Or build and use the AAR directly:

```bash
./gradlew :mnn-sdk:assembleRelease
# Output: mnn-sdk/build/outputs/aar/mnn-sdk-release.aar
```

### 2. Load a model — one line

```kotlin
// suspend — dispatches to IO internally, throws with a clear message on failure
val llm = MNNLlm.load("/data/user/0/com.example/files/models/Qwen3-0.6B-MNN/llm_config.json")
```

No `MNNEngine.initialize()`. No two-step create + load. No null check. Just load and chat.

### 3. Chat

```kotlin
// One-shot — returns text + thinking together
val result = llm.chat("What is the capital of France?")
println(result.text)      // "Paris"
println(result.thinking)  // chain-of-thought block, or null

// With thinking enabled
llm.enableThinking = true
val result = llm.chat("Explain quantum entanglement.")

// With image (VLM models only)
val result = llm.chat("Describe this image.", imagePath = "/sdcard/photo.jpg")
```

### 4. Stream tokens — thinking and answer separated

```kotlin
llm.chatFlow("Solve step by step: 14 × 37").collect { event ->
    when (event) {
        is MNNLlm.ChatEvent.ThinkingToken -> reasoningView.append(event.token)
        is MNNLlm.ChatEvent.AnswerToken   -> answerView.append(event.token)
        is MNNLlm.ChatEvent.Done          -> showMetrics(llm.lastMetrics())
    }
}
```

### 5. Customise the prompt (RAG, tools, few-shot)

```kotlin
llm.promptBuilder = { history, message, _, system ->
    buildString {
        append("<|im_start|>system\n$system\n\nContext: $retrievedDocs<|im_end|>\n")
        for ((u, a) in history)
            append("<|im_start|>user\n$u<|im_end|>\n<|im_start|>assistant\n$a<|im_end|>\n")
        append("<|im_start|>user\n$message<|im_end|>\n<|im_start|>assistant\n")
    }
}
```

### 6. Clean up — or let `use {}` do it

```kotlin
llm.clearHistory()   // Reset conversation without unloading
llm.close()          // Free native resources (same as destroy())

// Or idiomatically:
MNNLlm.load(configPath).use { llm ->
    println(llm.chat("Hello!").text)
}   // native memory freed automatically here
```

---

## `MNNLlm` API Reference

### Factory methods

```kotlin
// Preferred: single suspend call — loads and warms up the model on Dispatchers.IO
// Throws IllegalArgumentException if config is invalid, IllegalStateException if weights fail
suspend fun MNNLlm.Companion.load(configPath: String): MNNLlm

// Low-level: create handle without loading weights (use when you need two-step control)
fun MNNLlm.Companion.create(configPath: String): MNNLlm?
fun MNNLlm.load(): Boolean
```

`MNNLlm.load()` auto-detects from `llm_config.json`:
- **Chat format** — `QWEN_CHATML` (from `prompt_template`, `user_prompt_template`, or `jinja.chat_template`) or `GENERIC`
- **`supportsThinking`** — true when model has `thinking_template`, `enable_thinking`, or `<think>` in its jinja template
- **`isVisual`** — true when `is_visual=true` in config **and** `visual.mnn` is present on disk

### Properties

| Property | Type | Description |
|---|---|---|
| `supportsThinking` | `Boolean` (read-only) | Whether this model supports `<think>` reasoning blocks |
| `isVisual` | `Boolean` (read-only) | Whether this model can process images |
| `enableThinking` | `Boolean` | Toggle thinking mode for the next inference call |
| `systemPrompt` | `String` | System prompt prepended to every conversation |
| `lastThinking` | `String?` (read-only) | Raw thinking block from the last `response()` call |
| `promptBuilder` | `((history, message, imagePath, system) -> String)?` | When set, completely overrides built-in prompt formatting |

### Inference methods

```kotlin
// Coroutine — suspend, dispatches to IO, returns text + thinking together
suspend fun chat(
    userMessage: String,
    imagePath: String? = null,
    maxNewTokens: Int = 1024
): LlmResult   // data class: text: String, thinking: String?

// Typed streaming — emits ThinkingToken / AnswerToken / Done events
// thinking and answer are separated by the SDK — no <think> parsing needed
fun chatFlow(
    userMessage: String,
    imagePath: String? = null,
    maxNewTokens: Int = 1024
): Flow<ChatEvent>

// Raw streaming — emits every decoded token as a plain String (includes <think> tags raw)
fun responseFlow(
    userMessage: String,
    imagePath: String? = null,
    maxNewTokens: Int = 1024
): Flow<String>

// Blocking — call on Dispatchers.IO; returns clean answer, sets lastThinking as side-effect
fun response(
    userMessage: String,
    imagePath: String? = null,
    maxNewTokens: Int = 1024
): String
```

### `ChatEvent` sealed class

```kotlin
sealed class ChatEvent {
    data class ThinkingToken(val token: String) : ChatEvent()  // inside <think>…</think>
    data class AnswerToken(val token: String)   : ChatEvent()  // final answer content
    data class Done(val result: LlmResult)      : ChatEvent()  // generation complete
}
```

### Lifecycle

```kotlin
fun clearHistory()          // Reset conversation + KV-cache; model stays loaded
fun destroy()               // Free native resources
override fun close()        // Alias for destroy(); enables use {} blocks
fun lastMetrics(): Metrics  // prefillMs, decodeMs, promptTokens, generatedTokens, tokensPerSec
```

### Thinking behaviour

| `enableThinking` | Prompt suffix injected | Output |
|---|---|---|
| `true` | `<\|im_start\|>assistant\n<think>\n` | reasoning…`</think>`\nanswer |
| `false` | `<\|im_start\|>assistant\n<think>\n\n</think>\n` | answer only (budget-forcing) |

`chat()` and `chatFlow(Done)` always return the **clean answer** — thinking is stripped automatically. `chatFlow(ThinkingToken)` gives you the reasoning tokens in real time.

---

## Model Downloader

`AdvancedModelDownloader` handles fetching models from HuggingFace or ModelScope:

```kotlin
val downloader = AdvancedModelDownloader(context)

// Download a model
downloader.downloadModel(modelItem, preferredSource = "HuggingFace")
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

// Re-download any missing files for an existing model directory
downloader.repairMissingFiles(modelDir).collect { ... }

// Delete a model
downloader.deleteModel(modelDir.absolutePath)
```

### ModelProfile — required files per model

| Config field | Files required |
|---|---|
| `tie_embeddings` present | No extra embedding file needed |
| `tie_embeddings` absent | `embeddings_bf16.bin` (+ `embeddings.bin` probed optionally) |
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
unset JAVA_HOME && ./gradlew :sample:assembleDebug

# Build release AAR of the SDK only
./gradlew :mnn-sdk:assembleRelease

# Install to connected device
adb install -r sample/build/outputs/apk/debug/sample-debug.apk

# Publish SDK to Maven Local
./gradlew :mnn-sdk:publishToMavenLocal
```

> **Note:** `unset JAVA_HOME` before running Gradle if you see toolchain conflicts with a system JDK.

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

### Token Streaming

`chatFlow()` / `responseFlow()` are backed by a `CallbackStreambuf` in C++ (`mnn_llm.cpp`) that fires a `TokenCallback.onToken(String)` JNI call for each chunk written by `MNN::Transformer::Llm::response()`. This means tokens arrive as the model decodes them — no buffering, no polling. The Kotlin side uses `callbackFlow { }` + `trySend()` so collection is safe from any thread.

### Multi-Turn History

Full ChatML is rebuilt from scratch on every call and the KV-cache is reset via `nativeReset()`. The assistant reply stored in history is always the **clean** reply — the `<think>…</think>` block is stripped before being saved, so reasoning never bleeds into subsequent turns.

---

## Known Caveats & Roadmap

These are the current limitations. Contributions welcome.

- **[ ] KV-cache not reused across turns** — The entire prompt (system + all history + new message) is re-prefilled on every call. This is correct and simple, but means prefill time grows linearly with conversation length. A proper incremental KV-cache would fix this but requires MNN to expose a session-continuation API.

- **[x] Token count metrics are exact** — `promptTokens` and `generatedTokens` are now computed using JNI tokenization (`llm->tokenizer_encode(text).size()`) via `nativeCountTokens`, replacing all prior `length / 4` estimates.

- **[ ] `responseFlow()` streams raw `<think>` tags** — When thinking is enabled, `responseFlow()` emits thinking tokens and answer tokens in the same stream without labelling them. Use `chatFlow()` instead — it separates them into typed `ThinkingToken` / `AnswerToken` events automatically.

- **[ ] `promptBuilder` owns history formatting entirely** — When `promptBuilder` is set, the SDK passes a read-only `List<Pair<String,String>>` snapshot but the caller is responsible for including it in the output string. If history is omitted in the custom builder, the model loses context. This is intentional (full control) but easy to get wrong.

- **[ ] No cancellation support in streaming** — Cancelling the coroutine that collects `chatFlow()` / `responseFlow()` does not interrupt the underlying `nativeResponseStreaming()` C++ call, which runs to completion on its IO thread. Proper cancellation would require MNN to expose a `llm->stop()` API.

- **[ ] ChatML / GENERIC are the only built-in formats** — Models using Llama-3, Phi, Mistral, Gemma, or other non-ChatML prompt formats will fall back to the `GENERIC` (`User: … Assistant:`) format, which is suboptimal. Use `promptBuilder` to override for these models until proper auto-detection is added.

- **[ ] `MNNEngine` is not required for LLM use** — `MNNEngine` is a separate class for generic (non-LLM) MNN inference. `MNNLlm` loads its own native libraries independently. If you are only doing LLM chat you do not need to call `MNNEngine.initialize()` at all.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

MNN itself is also Apache 2.0: [github.com/alibaba/MNN](https://github.com/alibaba/MNN).

