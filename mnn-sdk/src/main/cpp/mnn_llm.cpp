#include <jni.h>
#include <string>
#include <sstream>
#include <chrono>
#include <atomic>
#include <exception>
#include <optional>
#include <android/log.h>

#include "llm/llm.hpp"

#define TAG "MNNLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Per-session metrics stored alongside the Llm handle.
struct LlmSession {
    MNN::Transformer::Llm* llm = nullptr;
    int64_t prefill_ms = 0;
    int64_t decode_ms = 0;
    int64_t sample_ms = 0;
    int64_t ttfa_ms = 0;
    int     prompt_tokens = 0;
    int     generated_tokens = 0;
    // Atomic flag: set to true by nativeStop() to interrupt streaming generation.
    std::atomic<bool> stop_flag{false};
};

inline LlmSession* toSession(jlong handle) {
    return reinterpret_cast<LlmSession*>(static_cast<intptr_t>(handle));
}

} // anonymous namespace

// Splits a generation request into prefill and decode using the arrival time of
// the FIRST token MNN emits.
//
//   [request start .... first token]  = prefill (prompt consumed, no output yet)
//   [first token .... generation end] = decode
//
// This is a real measurement, not a fixed ratio; the previous code hardcoded a
// 30/70 split. MNN 3.6 does measure prefill_us/decode_us internally, but it only
// exposes them through the inline Llm::getContext() accessor, which is not callable
// from outside the class (see include/llm/llm.hpp). Timestamping the first write is
// the only accurate boundary observable from the public API, and it is exactly
// equivalent to time-to-first-answer.
class TimingStreambuf : public std::streambuf {
public:
    void beginTiming() {
        mStart = std::chrono::steady_clock::now();
        mFirstWrite.reset();
    }

    /** True once MNN has emitted at least one token. */
    bool sawToken() const { return mFirstWrite.has_value(); }

    int64_t prefillMs() const {
        if (!mFirstWrite) return 0;
        return std::chrono::duration_cast<std::chrono::milliseconds>(*mFirstWrite - mStart).count();
    }

    int64_t decodeMs(int64_t totalMs) const {
        // If nothing was generated (empty answer, immediate stop, load failure) the
        // whole request is prefill-ish work; attribute it to decode rather than
        // reporting a zero decode that would make tokensPerSec infinite.
        if (!mFirstWrite) return totalMs;
        return totalMs - prefillMs();
    }

    int64_t ttfaMs() const { return prefillMs(); }

protected:
    void noteFirstWrite() {
        if (!mFirstWrite) mFirstWrite = std::chrono::steady_clock::now();
    }

private:
    std::chrono::steady_clock::time_point mStart;
    std::optional<std::chrono::steady_clock::time_point> mFirstWrite;
};

// Captures generated text in-process, used by the blocking response() path.
//
// This TEE'S into a real std::stringbuf rather than managing its own put area.
// A bare std::streambuf with no put area (pptr()==epptr()==nullptr) makes MNN's
// decode loop block forever with zero CPU - verified on-device against MNN 3.6.1.
// Delegating to std::stringbuf keeps the exact buffer semantics of
// std::ostringstream (which works), while still letting us timestamp the first write
// for the prefill/decode split.
class CaptureStreambuf : public TimingStreambuf {
public:
    std::string text() const { return mBuf.str(); }

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        noteFirstWrite();
        return mBuf.sputn(s, n);
    }

    int_type overflow(int_type ch) override {
        if (ch != traits_type::eof()) noteFirstWrite();
        return mBuf.sputc(ch);
    }

    int sync() override {
        return mBuf.pubsync();
    }

private:
    std::stringbuf mBuf;
};

// Custom streambuf that fires a Java TokenCallback.onToken(String) for each chunk
// written by MNN's response() — enabling token-by-token streaming from C++ to Kotlin.
// When stopFlag is set (via nativeStop), xsputn returns 0 which puts the ostream into
// a bad state; MNN checks os->good() after each token write and stops generation.
class CallbackStreambuf : public TimingStreambuf {
public:
    CallbackStreambuf(JNIEnv* e, jobject cb, jmethodID mid, std::atomic<bool>& stopFlag)
        : mEnv(e), mCallback(cb), mMethod(mid), mStopFlag(stopFlag) {}

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        // Return 0 to signal the ostream is done; MNN will observe !os->good() and stop.
        if (mStopFlag.load(std::memory_order_relaxed)) return 0;
        noteFirstWrite();
        // MNN writes one decoded token per xsputn call; copy to get null-termination.
        std::string token(s, static_cast<size_t>(n));
        jstring js = mEnv->NewStringUTF(token.c_str());
        if (js) {
            mEnv->CallVoidMethod(mCallback, mMethod, js);
            mEnv->DeleteLocalRef(js);
        }
        return n;
    }

    int overflow(int c) override {
        if (mStopFlag.load(std::memory_order_relaxed)) return EOF;
        if (c != EOF) {
            noteFirstWrite();
            char ch = static_cast<char>(c);
            std::string token(&ch, 1);
            jstring js = mEnv->NewStringUTF(token.c_str());
            if (js) {
                mEnv->CallVoidMethod(mCallback, mMethod, js);
                mEnv->DeleteLocalRef(js);
            }
        }
        return c;
    }

private:
    JNIEnv* mEnv;
    jobject mCallback;
    jmethodID mMethod;
    std::atomic<bool>& mStopFlag;
};

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

    auto t0 = std::chrono::steady_clock::now();
    // Delegate to response(string) so MNN's ExecutorScope is set up correctly for VLM
    // image tokenisation (Omni::tokenizer_encode runs the vision encoder and needs the
    // executor context). Template wrapping is disabled permanently at load time via
    // nativeSetConfig({"use_template":false}), so our pre-built ChatML prompt passes
    // through to the tokenizer unchanged.
    CaptureStreambuf capture;
    capture.beginTiming();
    std::ostream out(&capture);
    s->llm->response(prompt, &out, stopStr, maxNewTokens);
    auto t1 = std::chrono::steady_clock::now();

    std::string result = capture.text();

    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    s->prefill_ms = capture.prefillMs();
    s->decode_ms  = capture.decodeMs(total_ms);
    s->ttfa_ms    = capture.ttfaMs();

    LOGI("response(): total=%lldms prefill=%lldms decode=%lldms ttfa=%lldms emitted=%s",
         (long long)total_ms, (long long)s->prefill_ms, (long long)s->decode_ms,
         (long long)s->ttfa_ms, capture.sawToken() ? "yes" : "no");

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
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNLlm_nativeSetPromptTokens(JNIEnv*, jclass, jlong handle, jint count) {
    LlmSession* s = toSession(handle);
    if (s) s->prompt_tokens = static_cast<int>(count);
}

JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNLlm_nativeSetGeneratedTokens(JNIEnv*, jclass, jlong handle, jint count) {
    LlmSession* s = toSession(handle);
    if (s) s->generated_tokens = static_cast<int>(count);
}

JNIEXPORT jint JNICALL
Java_com_mnn_sdk_MNNLlm_nativeCountTokens(JNIEnv* env, jclass, jlong handle, jstring jText) {
    LlmSession* s = toSession(handle);
    if (!s || !s->llm || jText == nullptr) return -1;

    const char* textCStr = env->GetStringUTFChars(jText, nullptr);
    std::string text(textCStr ? textCStr : "");
    if (textCStr) env->ReleaseStringUTFChars(jText, textCStr);

    try {
        auto ids = s->llm->tokenizer_encode(text);
        return static_cast<jint>(ids.size());
    } catch (const std::exception& e) {
        LOGE("nativeCountTokens exception: %s", e.what());
        return -1;
    } catch (...) {
        LOGE("nativeCountTokens unknown exception");
        return -1;
    }
}

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

JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNLlm_nativeGetSampleMs(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    return s ? static_cast<jlong>(s->sample_ms) : 0;
}

// Time to first answer token (measured on our side, exposed as ms).
JNIEXPORT jlong JNICALL
Java_com_mnn_sdk_MNNLlm_nativeGetTtfaMs(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    return s ? static_cast<jlong>(s->ttfa_ms) : 0;
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

// Streaming inference: calls callback.onToken(String) for each decoded chunk.
// Blocks the calling thread until generation is complete (the JNI callback fires
// synchronously from within response()). This is called from Kotlin's callbackFlow
// on an IO thread so the main thread is never blocked.
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNLlm_nativeResponseStreaming(JNIEnv* env, jclass /*cls*/,
                                                  jlong handle, jstring jPrompt,
                                                  jint maxNewTokens, jstring jStopString,
                                                  jobject callback) {
    LlmSession* s = toSession(handle);
    if (!s || !s->llm) return;

    jclass cls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (!onToken) {
        LOGE("nativeResponseStreaming: could not find onToken(String) method");
        return;
    }

    const char* promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    const char* stopStr = nullptr;
    std::string stopStorage;
    if (jStopString != nullptr) {
        const char* s2 = env->GetStringUTFChars(jStopString, nullptr);
        stopStorage = s2;
        env->ReleaseStringUTFChars(jStopString, s2);
        stopStr = stopStorage.c_str();
    }

    s->stop_flag.store(false, std::memory_order_relaxed);
    CallbackStreambuf cbBuf(env, callback, onToken, s->stop_flag);
    cbBuf.beginTiming();
    std::ostream cbStream(&cbBuf);

    auto t0 = std::chrono::steady_clock::now();
    s->llm->response(prompt, &cbStream, stopStr, static_cast<int>(maxNewTokens));
    auto t1 = std::chrono::steady_clock::now();

    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    s->prefill_ms = cbBuf.prefillMs();
    s->decode_ms  = cbBuf.decodeMs(total_ms);
    s->ttfa_ms    = cbBuf.ttfaMs();
    LOGI("responseStreaming(): total=%lldms prefill=%lldms decode=%lldms",
         (long long)total_ms, (long long)s->prefill_ms, (long long)s->decode_ms);
}

// Interrupt an in-progress nativeResponseStreaming() call. Thread-safe: sets an atomic
// flag that CallbackStreambuf checks on every token; MNN sees !os->good() and stops.
JNIEXPORT void JNICALL
Java_com_mnn_sdk_MNNLlm_nativeStop(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    if (s) s->stop_flag.store(true, std::memory_order_relaxed);
}

} // extern "C"
