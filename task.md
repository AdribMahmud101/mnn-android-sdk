# MNN Android SDK — Task Tracker

**Goal**: Production-quality, plug-and-play Kotlin Android SDK for on-device LLM inference via [MNN](https://github.com/alibaba/MNN).  
**Repo**: `git@github.com:AdribMahmud101/mnn-android-sdk.git` — branch `master`  
**Last push**: `98a1e4c` — dx: plug-and-play API (streaming, ChatEvent, Closeable, promptBuilder)

---

## Completed ✅

### Foundation
- [x] Pre-built MNN native libraries bundled (`arm64-v8a`, `armeabi-v7a`) — `libMNN.so`, `libllm.so`, `libMNN_Express.so`, etc.
- [x] JNI bridge `mnn_llm.cpp` — `nativeCreate`, `nativeLoad`, `nativeResponse`, `nativeReset`, `nativeSetConfig`, `nativeDestroy`, metric accessors
- [x] `CMakeLists.txt` linking MNN libraries via imported targets
- [x] `MNNLlm.kt` — primary SDK entry point, full ChatML/GENERIC auto-detection

### Inference correctness
- [x] `use_template=false` fix — disables MNN jinja re-wrapping so our pre-built ChatML prompt passes through verbatim
- [x] Thinking mode — Qwen3 budget-forcing (`<think>\n\n</think>`) correctly skips COT; thinking ON injects open `<think>` prefix
- [x] VLM image injection — `<img>path</img>` in user turn; `response(string)` preserves the `ExecutorScope` needed by the vision encoder
- [x] Multi-turn history — full ChatML rebuilt each call, KV-cache reset with `nativeReset()`, think block stripped from stored history
- [x] Stop string handling — `<|im_end|>` for ChatML, jinja `eos` field respected, `<|endoftext|>` stripped from tail

### Model detection
- [x] ChatML detection from `prompt_template`, `user_prompt_template`, `system_prompt_template`, `jinja.chat_template`
- [x] Thinking detection from `thinking_template`, `enable_thinking`, `<think>` in template or jinja
- [x] Visual detection — `is_visual=true` in config only after confirming `visual.mnn` exists on disk

### Developer experience (DX)
- [x] `suspend fun MNNLlm.load(configPath)` — single-step factory, dispatches to IO, throws with clear message on failure
- [x] `suspend fun chat()` — returns `LlmResult(text, thinking)` together; dispatches to IO internally
- [x] `fun chatFlow()` — typed streaming: emits `ChatEvent.ThinkingToken` / `AnswerToken` / `Done` — thinking and answer separated by SDK
- [x] `fun responseFlow()` — raw token streaming via `CallbackStreambuf` C++ → JNI `TokenCallback`
- [x] `Closeable` — `close()` / `use{}` block support; native memory cannot leak
- [x] `promptBuilder` lambda — overrides `buildPrompt()` entirely for RAG, few-shot, tool injection, non-ChatML formats
- [x] `LlmResult` data class — replaces `lastThinking` side-effect property
- [x] `ChatEvent` sealed class — `ThinkingToken`, `AnswerToken`, `Done(LlmResult)`
- [x] `maxNewTokens` default raised 512 → 1024
- [x] Removed `MNNEngine.initialize()` from LLM init path in sample — not required

### Model downloader
- [x] `AdvancedModelDownloader` — HuggingFace + ModelScope, live progress `Flow<DownloadState>`
- [x] `ModelProfile` — dynamic required-file detection from `llm_config.json` (tie_embeddings, visual.mnn, audio_encoder, embedding_file)
- [x] `repairMissingFiles()` — re-downloads only what's missing without re-downloading everything
- [x] `deleteModel()` — clean removal
- [x] Model catalog with online fetch + local cache

### Sample app
- [x] Full chat UI with RecyclerView — user bubbles, assistant bubbles, thinking collapsible, image thumbnails
- [x] Download dialog with per-file progress
- [x] Model management dialog (load/delete)
- [x] Thinking toggle switch (hidden for non-thinking models)
- [x] Vision badge + photo picker (hidden for text-only models)
- [x] Metrics bar (tok/s, prefill ms, decode ms)
- [x] Auto-repair on load

### Documentation
- [x] README fully rewritten — plug-and-play framing, complete API reference, Quick Start, Caveats & Roadmap
- [x] Architecture notes — `use_template`, VLM, streaming, multi-turn history

---

## Known Gaps / Active Roadmap 🔧

Each item below is a concrete engineering task with clear scope and rationale.

---

### GAP 1 — KV-cache not reused across turns
**Impact**: Prefill time grows linearly with conversation length. A 10-turn conversation re-processes all 10 turns on every message.  
**Root cause**: We call `nativeReset()` before every call and send the entire history as a single string. MNN's `Llm` class supports incremental generation but there's no `append_token` / `session_continue` API exposed yet.  
**Fix plan**:
- [ ] Research whether `MNN::Transformer::Llm::forward(vector<int>)` with a session continuation pointer is usable from our JNI surface
- [ ] If yes: add `nativeResponseIncremental(handle, newTokenIds)` JNI function that skips re-prefilling history
- [ ] Add `MNNLlm.fullRebuildEveryTurn: Boolean = true` flag so developers can opt into incremental mode once stable
- [ ] Benchmark: target < 100ms prefill for turn N+1 on Qwen3-0.6B

---

### GAP 2 — Token count metrics are exact ✅
**Impact (resolved)**: Metrics now reflect tokenizer-derived counts instead of character heuristics.  
**Implementation**:
- [x] Added `nativeCountTokens(handle: Long, text: String): Int` JNI function calling `llm->tokenizer_encode(text).size()`
- [x] Added `nativeSetPromptTokens` + `nativeSetGeneratedTokens` setters
- [x] Wired exact counting into `response()`, `chatFlow()`, and `responseFlow()` post-processing paths
- [x] Removed all `length / 4` token estimation from Kotlin and JNI inference paths
- [x] Added JVM unit tests for normalization/fallback logic in `TokenCountUtils`

---

### ✅ GAP 3 — Coroutine cancellation of in-flight generation (RESOLVED)
**Implementation**: `std::atomic<bool> stop_flag` added to `LlmSession`. `CallbackStreambuf::xsputn` checks the flag on every token; returning 0 puts the ostream into a bad state so MNN stops generation. `nativeStop()` JNI sets the flag. In Kotlin, `trySend().isFailure` (channel closed by cancellation) triggers `nativeStop()` immediately, and `awaitClose { nativeStop(handle) }` provides a secondary safety net.
- [x] Added `std::atomic<bool> stop_flag` to `LlmSession` struct
- [x] `CallbackStreambuf::xsputn` returns 0 / `overflow` returns EOF when flag is set
- [x] `nativeResponseStreaming` resets flag to false before each call
- [x] `nativeStop(handle: Long)` JNI function sets the flag
- [x] `chatFlow()` and `responseFlow()`: check `trySend().isFailure` and call `nativeStop()` inline; `awaitClose { nativeStop(handle) }` for cleanup
- [x] All builds pass, 16/16 unit tests green

---

### GAP 4 — Only ChatML and GENERIC prompt formats built-in
**Impact**: Llama-3, Phi-4, Mistral, Gemma, DeepSeek models fall back to `GENERIC` (`User: … Assistant:`) which degrades output quality significantly.  
**Root cause**: Format detection was built for Qwen's ChatML. Other formats were not priority.  
**Fix plan**:
- [ ] Add `ChatStyle.LLAMA3` — `<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n…`
- [ ] Add `ChatStyle.PHI` — `<|system|>\n…<|end|>\n<|user|>\n…`
- [ ] Add `ChatStyle.GEMMA` — `<start_of_turn>user\n…<end_of_turn>\n<start_of_turn>model\n`
- [ ] Extend `create()` config detection to identify these from `jinja.chat_template` content
- [ ] Add `ChatStyle.DEEPSEEK` for DeepSeek-R1 style (has native `<think>` too)
- [ ] Write unit tests for each template builder

---

### GAP 5 — `promptBuilder` history responsibility is a footgun
**Impact**: A developer who sets `promptBuilder` and forgets to include `history` in their formatted string loses all conversation context silently — the model gives responses as if every message is the first.  
**Root cause**: Full control means full responsibility. The SDK cannot enforce inclusion.  
**Fix plan**:
- [ ] Add `MNNLlm.historySnapshot(): List<Pair<String, String>>` as an explicit public API (currently only passed inside the lambda)
- [ ] In debug builds, add a `Log.w` if `promptBuilder` is set and `history.size > 0` but none of the history strings appear in the returned prompt
- [ ] Add documentation example showing common mistake and correct pattern

---

### GAP 6 — `responseFlow()` streams raw `<think>` tags (documentation gap)
**Impact**: Developers who discover `responseFlow()` first will see `<think>reasoning</think>answer` mixed in the stream and have to parse it themselves. The better API (`chatFlow()`) is not obvious.  
**Root cause**: Both APIs exist but the hierarchy isn't obvious enough in the docs.  
**Fix plan**:
- [x] `chatFlow()` documented prominently in README as the preferred streaming API
- [ ] Add `@Deprecated("Use chatFlow() for typed thinking/answer separation", ReplaceWith("chatFlow(userMessage, imagePath, maxNewTokens)"))` annotation to `responseFlow()` — or add a clear KDoc warning pointing to `chatFlow()`

---

### GAP 7 — No instrumented tests
**Impact**: Regressions in thinking extraction, prompt building, stop-string stripping, or history storage are only caught manually.  
**Fix plan**:
- [ ] Add `MNNLlmTest.kt` in `mnn-sdk/src/test/` — unit tests for `extractThinking()`, `buildPrompt()`, `ChatStyle` detection, `LlmResult`, `ChatEvent`, `promptBuilder` override
- [ ] Add `MNNLlmInstrumentedTest.kt` in `mnn-sdk/src/androidTest/` — integration test: load a tiny model, run `chat()`, verify non-empty `text`
- [ ] Hook into CI once GitHub Actions is configured

---

### GAP 8 — No CI/CD
**Impact**: Every push is manually verified. No automated build on PR.  
**Fix plan**:
- [ ] Add `.github/workflows/build.yml` — `./gradlew :sample:assembleDebug` on every push to `master` and on PRs
- [ ] Add lint check step — `./gradlew :mnn-sdk:lint`
- [ ] (Optional) Add emulator-based instrumented test run on CI

---

### GAP 9 — Metrics timing split is wrong in non-streaming path
**Impact**: `prefillMs` is always exactly 30% and `decodeMs` is always 70% of total time. This is a hardcoded ratio, not measured.  
**Root cause**: MNN's `Llm` does not expose separate prefill vs decode timings through the public header.  
**Fix plan**:
- [ ] Research whether `Llm::runtime_manager()` or performance counters expose per-phase timing
- [ ] If yes: plumb real values through `LlmSession` and update `nativeGetPrefillMs` / `nativeGetDecodeMs`
- [ ] If no: time the first token arrival in `CallbackStreambuf::xsputn` as a proxy for prefill completion, use that as the split point

---

## Priority Order

| Priority | Gap | Effort | Impact |
|---|---|---|---|
| 🟡 Medium | GAP 1 — KV-cache reuse | High | Performance at long context |
| 🟡 Medium | GAP 4 — More prompt formats | Medium | Model compatibility |
| 🟡 Medium | GAP 7 — Unit tests | Medium | Regression safety |
| 🟡 Medium | GAP 8 — CI/CD | Low | Dev workflow |
| 🟢 Low | GAP 5 — promptBuilder footgun | Low | DX polish |
| 🟢 Low | GAP 6 — responseFlow() warning | Low | API clarity |
| 🟢 Low | GAP 9 — Real metrics timing | High | Observability |

---

## Current Status

| Area | Status |
|---|---|
| Native libraries (arm64, armeabi-v7a) | ✅ Bundled and working |
| JNI bridge (`mnn_llm.cpp`) | ✅ Complete |
| Core inference — text, thinking, vision | ✅ Working |
| Coroutine API (`chat`, `chatFlow`, `responseFlow`) | ✅ Complete |
| Token streaming (C++ CallbackStreambuf) | ✅ Working |
| Typed ChatEvent (ThinkingToken / AnswerToken) | ✅ Complete |
| Prompt customisation (`promptBuilder`) | ✅ Complete |
| `Closeable` / `use{}` support | ✅ Complete |
| Model downloader + repair | ✅ Working |
| Sample app | ✅ Working |
| README + docs | ✅ Up to date |
| KV-cache incremental updates | ❌ Not implemented (GAP 1) |
| Exact token count metrics | ✅ Implemented (GAP 2 resolved) |
| Generation cancellation | ✅ Implemented (GAP 3 resolved) |
| Non-ChatML prompt formats | ❌ Falls back to GENERIC (GAP 4) |
| Unit / instrumented tests | ❌ Not written (GAP 7) |
| CI/CD | ❌ Not configured (GAP 8) |

- **Full Production Ready**: 5-7 days

## Blockers
None - ready to proceed with Phase 1

---

**See [INTEGRATION_PLAN.md](INTEGRATION_PLAN.md) for detailed implementation guide**

---

## Update: MNN 3.4.1 -> 3.6.1 (engine upgrade + correctness pass)

**Engine.** Bundled prebuilts and vendored headers upgraded to MNN 3.6.1. ABI-verified:
the `Llm` vtable order is unchanged from 3.4.1, and every MNN symbol the JNI bridge
imports resolves against the shipped `libMNN.so` / `libMNN_Express.so` / `libllm.so`
on both ABIs. CI re-runs both checks on every push.

**GAP 9 - metrics timing: RESOLVED (differently than planned).** MNN 3.6 *does* measure
`prefill_us` / `decode_us` / `ttfa_us` internally, but exposes them only via
`Llm::getContext()`, which is defined *inline* in the upstream header and reads a
protected member - so `libllm.so` exports no symbol for it and it cannot be called from
outside the class. Instead the first token MNN emits is timestamped in
`TimingStreambuf`, making `[start, first token)` the prefill window and the remainder
the decode window. The fabricated 30/70 split is gone. `Metrics` also gained
`sampleMs`, `ttfaMs` and `prefillTokensPerSec`.

**GAP 7 - tests: LARGELY RESOLVED on the JVM side.**
- 54 JVM unit tests (was 17). The previously untested `MNNLlm` prompt/capability logic
  is extracted into `LlmPrompt.kt` (`LlmConfigAnalyzer` + `PromptComposer`) and covered
  by 32 tests: ChatML/GENERIC detection, stop-string selection, thinking detection,
  vision gating, `extractThinking` edge cases, ChatML/GENERIC composition, image-tag
  emission and metric math.
- `MNNConfigEnumContractTest` pins every public config constant to MNN's real enum
  values. Mutation-tested: restoring `ForwardType.OPENCL = 2` makes it fail.
- `MNNLlmInstrumentedTest` covers the device-only surface (library load + version, real
  `chat()`, streaming, mid-generation cancellation, multi-turn, invalid-config
  handling). Model-dependent cases `Assume` so they skip without weights.

**GAP 8 - CI: RESOLVED.** `.github/workflows/build.yml` builds the AAR (compiling the
JNI bridge), runs unit tests and lint, builds the sample with R8 enabled, and asserts
(a) the declared MNN version matches the vendored headers and (b) no SDK class was
renamed by R8 (which would break `Java_com_mnn_sdk_*` symbol lookup).

**Bugs found and fixed during the upgrade:**
- `MNNConfig.ForwardType` / `Precision` / `PowerMode` did not match MNN's enums and were
  raw-cast by the JNI layer. `ForwardType.VULKAN` silently selected `AUTO`, `OPENCL`
  selected `CUDA`, and `Precision.LOW` actually *raised* precision. `TENSORRT` and
  `HIAI` were never valid MNN forward types and have been removed.
- `MNNConfig.memory` was accepted but never passed to JNI. Now wired through to
  `BackendConfig::memory`.
- `create()` returned a live handle for an unparseable `llm_config.json`, deferring a
  confusing failure into `load()`. It now returns null and logs why.
- Cancelling a stream collector hung indefinitely. The blocking JNI call ran on the
  `callbackFlow` producer coroutine, so `awaitClose` - the only hook that reaches
  `nativeStop` - could never run. Generation now runs on a detached scope so
  cancellation returns promptly; `destroy()` waits for in-flight generations before
  freeing the native handle to avoid a use-after-free.
- The blocking path's `CaptureStreambuf` originally managed its own put area; a bare
  `std::streambuf` with no put area makes MNN's decode loop block forever at 0% CPU
  (verified on-device). It now tees into a real `std::stringbuf`.
- Dead code removed: legacy `ModelDownloader.kt` (placeholder URLs, unreferenced),
  orphan `activity_main.xml`, disabled `MNNStubTest.kt.bak`, two unreferenced JNI entry
  points, the unused `mockk` dependency and the no-op `viewBinding` flag.
- Obsolete `LLM_DEMO_STATUS.md` deleted; `MODEL_DOWNLOAD_GUIDE.md`, `docs/BUILDING.md`
  and `docs/API.md` corrected to match the code.

**Still open / blocked:**
- **GAP 1 (KV-cache reuse) remains open.** 3.6.1 does export `reuse_kv()`,
  `setKVCacheInfo()`, `getCurrentHistory()`, `eraseHistory()` and `syncPromptCache()`,
  so the API gap is closed, but wiring it correctly (and proving the cache stays valid
  across turns) is real work, not a rename.
- **GAP 4 (non-ChatML templates) remains open.** Llama-3 / Phi / Gemma / DeepSeek still
  fall back to `GENERIC`.
- **Device coverage is narrow.** Validation ran against Qwen3-0.6B-MNN only. Vision
  (VLM) models have never been exercised on hardware, and the live thinking path was
  detected but never actually produced a `<think>` block in the runs observed.
