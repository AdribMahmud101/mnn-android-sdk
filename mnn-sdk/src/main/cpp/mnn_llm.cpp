#include <jni.h>
#include <string>
#include <sstream>
#include <chrono>
#include <android/log.h>

#include "llm/llm.hpp"

#define TAG "MNNLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Per-session metrics stored alongside the Llm handle.
struct LlmSession {
    MNN::Transformer::Llm* llm = nullptr;
    int64_t prefill_ms = 0;
    int64_t decode_ms = 0;
    int     prompt_tokens = 0;
    int     generated_tokens = 0;
};

inline LlmSession* toSession(jlong handle) {
    return reinterpret_cast<LlmSession*>(static_cast<intptr_t>(handle));
}

} // anonymous namespace

extern "C" {

// Create an LLM from a llm_config.json path. Returns 0 on failure.
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNLlm_nativeCreate(JNIEnv* env, jclass /*cls*/, jstring jConfigPath) {
    const char* path = env->GetStringUTFChars(jConfigPath, nullptr);
    std::string configPath(path);
    env->ReleaseStringUTFChars(jConfigPath, path);

    LOGI("createLLM: %s", configPath.c_str());
    MNN::Transformer::Llm* llm = MNN::Transformer::Llm::createLLM(configPath);
    if (!llm) {
        LOGE("createLLM returned null");
        return 0;
    }

    auto* session = new LlmSession{llm};
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

// Load (initialise) the model. Returns true on success.
JNIEXPORT jboolean JNICALL
Java_com_mnn_sdk_MNNLlm_nativeLoad(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    LlmSession* s = toSession(handle);
    if (!s || !s->llm) return JNI_FALSE;

    LOGI("Loading LLM model...");
    auto t0 = std::chrono::steady_clock::now();
    bool ok = s->llm->load();
    auto t1 = std::chrono::steady_clock::now();
    int64_t ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("load() finished in %lld ms, result=%d", (long long)ms, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Run inference and return the generated text.
JNIEXPORT jstring JNICALL
Java_com_mnn_sdk_MNNLlm_nativeResponse(JNIEnv* env, jclass /*cls*/,
                                        jlong handle, jstring jPrompt, jint maxNewTokens,
                                        jstring jStopString) {
    LlmSession* s = toSession(handle);
    if (!s || !s->llm) {
        return env->NewStringUTF("");
    }

    const char* promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    // Resolve optional stop string (null = rely on model's own EOS token)
    const char* stopStr = nullptr;
    std::string stopStringStorage;
    if (jStopString != nullptr) {
        const char* s2 = env->GetStringUTFChars(jStopString, nullptr);
        stopStringStorage = s2;
        env->ReleaseStringUTFChars(jStopString, s2);
        stopStr = stopStringStorage.c_str();
    }

    std::ostringstream oss;

    auto t0 = std::chrono::steady_clock::now();
    // Delegate to response(string) so MNN's ExecutorScope is set up correctly for VLM
    // image tokenisation (Omni::tokenizer_encode runs the vision encoder and needs the
    // executor context). Template wrapping is disabled permanently at load time via
    // nativeSetConfig({"use_template":false}), so our pre-built ChatML prompt passes
    // through to the tokenizer unchanged.
    s->llm->response(prompt, &oss, stopStr, maxNewTokens);
    auto t1 = std::chrono::steady_clock::now();

    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    std::string result = oss.str();

    // Rough token estimates (4 chars ≈ 1 token).
    s->prompt_tokens     = static_cast<int>(prompt.size() / 4 + 1);
    s->generated_tokens  = static_cast<int>(result.size() / 4 + 1);
    // Split time roughly 30% prefill / 70% decode
    s->prefill_ms = total_ms * 30 / 100;
    s->decode_ms  = total_ms * 70 / 100;

    LOGI("response(): %lld ms, ~%d tokens out", (long long)total_ms, s->generated_tokens);

    return env->NewStringUTF(result.c_str());
}

// Reset conversation context (clears KV cache).
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNLlm_nativeReset(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    LlmSession* s = toSession(handle);
    if (s && s->llm) s->llm->reset();
}

// Merge a JSON string into the model config (e.g. set jinja context for thinking mode).
// Example: nativeSetConfig(handle, "{\"jinja\":{\"context\":{\"enable_thinking\":true}}}")
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNLlm_nativeSetConfig(JNIEnv* env, jclass /*cls*/, jlong handle,
                                         jstring jConfigJson) {
    LlmSession* s = toSession(handle);
    if (!s || !s->llm) return;
    const char* cfg = env->GetStringUTFChars(jConfigJson, nullptr);
    s->llm->set_config(std::string(cfg));
    env->ReleaseStringUTFChars(jConfigJson, cfg);
}

// Destroy the LLM and free memory.
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNLlm_nativeDestroy(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    LlmSession* s = toSession(handle);
    if (!s) return;
    if (s->llm) {
        MNN::Transformer::Llm::destroy(s->llm);
    }
    delete s;
}

// ---- Metric accessors ----
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNLlm_nativeGetPrefillMs(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    return s ? static_cast<jlong>(s->prefill_ms) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNLlm_nativeGetDecodeMs(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    return s ? static_cast<jlong>(s->decode_ms) : 0;
}

JNIEXPORT jint JNICALL
Java_com_mnn_sdk_MNNLlm_nativeGetPromptTokens(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    return s ? static_cast<jint>(s->prompt_tokens) : 0;
}

JNIEXPORT jint JNICALL
Java_com_mnn_sdk_MNNLlm_nativeGetGeneratedTokens(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    return s ? static_cast<jint>(s->generated_tokens) : 0;
}

} // extern "C"
