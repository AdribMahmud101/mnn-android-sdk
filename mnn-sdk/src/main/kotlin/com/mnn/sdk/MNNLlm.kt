package com.mnn.sdk

import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.File

/**
 * Kotlin wrapper around the MNN LLM C++ API (MNN::Transformer::Llm).
 *
 * Handles multi-turn conversation by rebuilding the full Qwen/ChatML formatted
 * context on every call (with nativeReset to clear KV-cache first). This is
 * simpler and more reliable than trying to thread the KV-cache across calls.
 *
 * Features:
 *  - Thinking mode: for Qwen3-style models, toggle chain-of-thought on/off per call.
 *    When enabled the model produces a <think>…</think> block before the answer;
 *    the raw thought is exposed via [lastThinking] and stripped from the returned text.
 *  - Vision (multimodal): pass an absolute image path to [response] for VLM models.
 *    MNN's Llm::response() natively handles <img>path</img> tags inside the prompt.
 */
class MNNLlm private constructor(
    private val handle: Long,
    private val chatStyle: ChatStyle,
    private val stopString: String?,
    private val bosToken: String?,
    /** True if the model supports chain-of-thought thinking (e.g. Qwen3-Instruct). */
    val supportsThinking: Boolean,
    /** True if the model was loaded with visual.mnn present (VLM capable). */
    val isVisual: Boolean
) : java.io.Closeable {

    /** Format detected from llm_config.json prompt_template. */
    enum class ChatStyle { QWEN_CHATML, GENERIC }

    /**
     * Result of a single inference call — the clean assistant reply and the raw
     * thinking content (non-null only when thinking was enabled and the model produced
     * a `<think>…</think>` block).
     */
    data class LlmResult(val text: String, val thinking: String?)

    /**
     * Typed event emitted by [chatFlow]. Separates chain-of-thought tokens from answer
     * tokens in real time so callers never need to parse `<think>` tags themselves.
     */
    sealed class ChatEvent {
        /** A token generated inside the `<think>…</think>` block. */
        data class ThinkingToken(val token: String) : ChatEvent()
        /** A token that is part of the final answer. */
        data class AnswerToken(val token: String) : ChatEvent()
        /** Generation is complete. [result] contains the deduplicated full reply. */
        data class Done(val result: LlmResult) : ChatEvent()
    }

    data class Metrics(
        val prefillMs: Long,
        val decodeMs: Long,
        val promptTokens: Int,
        val generatedTokens: Int,
        /** Time spent in the sampler, when reported by the engine. */
        val sampleMs: Long = 0L,
        /** Time to first answer token. */
        val ttfaMs: Long = 0L
    ) {
        val tokensPerSec: Double
            get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0

        /** Prefill throughput. */
        val prefillTokensPerSec: Double
            get() = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else 0.0

        fun summary(): String =
            "Prefill: ${prefillMs}ms | Decode: ${decodeMs}ms | " +
            "TTFA: ${ttfaMs}ms | " +
            "Generated: $generatedTokens tokens | ${String.format("%.1f", tokensPerSec)} tok/s"
    }

    // Conversation history: list of (userMessage, cleanAssistantReply — no think block)
    private val history = mutableListOf<Pair<String, String>>()

    // The user turn currently being generated, so finalizeResponse() can record it
    // in history. Set by both the blocking and streaming paths before generation starts.
    private var currentUserTurn: String = ""

    // Detached scope for streaming generations. Deliberately NOT a child of any flow's
    // scope, so downstream cancellation returns immediately instead of blocking until
    // MNN finishes a generation that cannot be interrupted.
    private val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Number of native generations currently executing. destroy() blocks on this
    // reaching zero before releasing the native handle.
    private val inFlightGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var destroyed = false

    var systemPrompt: String = "You are a helpful assistant. Always respond in the same language the user writes in."

    /**
     * Whether to activate chain-of-thought thinking for the *next* [response] call.
     * Only effective when [supportsThinking] is true; silently ignored otherwise.
     *
     * Thinking ON  → assistant prefix: `<|im_start|>assistant\n<think>\n`
     * Thinking OFF → assistant prefix: `<|im_start|>assistant\n<think>\n\n</think>\n`
     *   (the empty think block tells the model to skip reasoning — Qwen3 budget-forcing)
     */
    var enableThinking: Boolean = false

    /**
     * Raw thinking block from the last [response] call, if thinking was enabled and
     * the model produced a <think>…</think> block. Null otherwise.
     */
    var lastThinking: String? = null
        private set

    /**
     * Optional custom prompt builder. When set, completely overrides the built-in
     * ChatML/Generic formatter for every [response], [chat], [responseFlow], and
     * [chatFlow] call.
     *
     * Receives the current conversation history (read-only snapshot), the new user
     * message, an optional image path, and the current [systemPrompt]; must return
     * the complete raw prompt string to pass directly to the tokenizer.
     *
     * Useful for RAG context injection, few-shot examples, function-calling blocks,
     * or any non-standard prompt format:
     * ```kotlin
     * llm.promptBuilder = { history, message, _, system ->
     *     buildString {
     *         append("<|im_start|>system\n$system\n\nContext: $retrievedContext<|im_end|>\n")
     *         for ((u, a) in history)
     *             append("<|im_start|>user\n$u<|im_end|>\n<|im_start|>assistant\n$a<|im_end|>\n")
     *         append("<|im_start|>user\n$message<|im_end|>\n<|im_start|>assistant\n")
     *     }
     * }
     * ```
     */
    var promptBuilder: ((history: List<Pair<String, String>>, userMessage: String, imagePath: String?, systemPrompt: String) -> String)? = null

    fun load(): Boolean {
        val ok = nativeLoad(handle)
        if (ok) {
            // Disable MNN's built-in jinja/prompt template so our pre-built ChatML prompt
            // (with <think> prefix and history) is passed directly to the tokenizer without
            // being re-wrapped as a "user" message. This also preserves the ExecutorScope
            // that response(string) sets up, which is required for VLM image tokenisation.
            //
            // Arm the generation timeout at the same time: MNN re-reads timeout_ms from the
            // merged config on every generation and aborts with LlmStatus::TIMEOUT once
            // prefill+decode exceeds it. That turns an unbounded generation (which cannot
            // be cancelled - see [destroy]) into a bounded one.
            applyEngineConfig()
        }
        return ok
    }

    /**
     * Hard wall-clock limit for a single generation, in milliseconds.
     *
     * MNN checks this after prefill and on every decode iteration, then aborts the
     * request with `LlmStatus::TIMEOUT`. Because MNN offers no way to *cancel* a
     * generation (its decode loop only honours `mContext->status == USER_CANCEL`,
     * which is unreachable from outside the class), this timeout is the only reliable
     * way to stop a runaway or pathologically slow generation.
     *
     * Set to `0` (or a negative value) to disable and let generations run unbounded.
     * Defaults to [DEFAULT_GENERATION_TIMEOUT_MS].
     */
    var generationTimeoutMs: Int = DEFAULT_GENERATION_TIMEOUT_MS
        set(value) {
            field = value
            if (handle != 0L) applyEngineConfig()
        }

    private fun applyEngineConfig() {
        val timeout = generationTimeoutMs
        val json = if (timeout > 0) {
            """{"use_template":false,"timeout_ms":$timeout}"""
        } else {
            """{"use_template":false}"""
        }
        nativeSetConfig(handle, json)
    }

    /**
     * Run the LLM and return generated text. Blocks the calling thread.
     *
     * @param userMessage  The user's text message.
     * @param imagePath    Absolute path to an image file on device storage. Only used when
     *                     [isVisual] is true; silently ignored for text-only models.
     * @param maxNewTokens Maximum tokens to generate.
     * @return The assistant's reply (thinking block stripped; access via [lastThinking]).
     */
    fun response(userMessage: String, imagePath: String? = null, maxNewTokens: Int = 1024): String {
        // Track whether we injected <think> as an open prefix (so extractThinking knows
        // the raw output starts with thinking content, not a full <think>…</think> block).
        val thinkPrefixInjected = supportsThinking && enableThinking
        val fullPrompt = buildPrompt(userMessage, imagePath)
        // Reset KV-cache so the full history prefill starts from scratch.
        nativeReset(handle)
        val raw = nativeResponse(handle, fullPrompt, maxNewTokens, stopString).trim()

        // Split out the thinking block.
        // When thinkPrefixInjected=true the raw output is: "thoughts\n</think>\nAnswer"
        // (no opening <think> tag because we already put it in the prompt).
        val (thinking, afterThink) = extractThinking(raw, thinkPrefixInjected)
        lastThinking = thinking

        val clean = afterThink
            .let { if (stopString != null) it.removeSuffix(stopString) else it }
            .removeSuffix("<|endoftext|>")
            .trim()

        updateExactTokenMetrics(fullPrompt, clean)

        // Only the clean answer is stored in history (no think block in replay prompts).
        history.add(Pair(userMessage, clean))
        return clean
    }

    /**
     * Parse a raw model output and separate the thinking block from the answer.
     *
     * Handles two cases:
     *  1. Full block in output: `<think>thoughts</think>answer`  (e.g. model emits it itself)
     *  2. Open prefix injected into prompt: raw = `thoughts</think>answer`
     *     (thinkPrefixInjected=true — we already put `<think>\n` at end of prompt)
     */
    private fun extractThinking(raw: String, thinkPrefixInjected: Boolean): Pair<String?, String> =
        PromptComposer.extractThinking(raw, thinkPrefixInjected)

    /**
     * Build the full formatted prompt including system message, conversation history,
     * and the current user turn. Controls the assistant prefix for thinking mode.
     * Delegates to [promptBuilder] when set.
     */
    private fun buildPrompt(userMessage: String, imagePath: String?): String {
        promptBuilder?.let { return it(history.toList(), userMessage, imagePath, systemPrompt) }
        val h = history.toList()
        return when (chatStyle) {
            ChatStyle.QWEN_CHATML -> PromptComposer.composeChatML(
                systemPrompt = systemPrompt,
                history = h,
                userMessage = userMessage,
                imagePath = imagePath,
                isVisual = isVisual,
                bosToken = bosToken,
                supportsThinking = supportsThinking,
                enableThinking = enableThinking
            )
            ChatStyle.GENERIC -> PromptComposer.composeGeneric(h, userMessage)
        }
    }

    /**
     * Typed streaming variant: emits [ChatEvent] tokens so thinking and answer content
     * are separated in real time — no caller-side `<think>` state machine needed.
     *
     * ```kotlin
     * llm.chatFlow("Solve this step by step: 14 * 37").collect { event ->
     *     when (event) {
     *         is MNNLlm.ChatEvent.ThinkingToken -> reasoningView.append(event.token)
     *         is MNNLlm.ChatEvent.AnswerToken   -> answerView.append(event.token)
     *         is MNNLlm.ChatEvent.Done          -> showMetrics(llm.lastMetrics())
     *     }
     * }
     * ```
     */
    fun chatFlow(
        userMessage: String,
        imagePath: String? = null,
        maxNewTokens: Int = 1024
    ): Flow<ChatEvent> = callbackFlow {
        val thinkPrefixInjected = supportsThinking && enableThinking
        val fullPrompt = buildPrompt(userMessage, imagePath)
        nativeReset(handle)

        val accumulated = StringBuilder()
        // When thinkPrefixInjected=true we start in thinking state immediately;
        // otherwise we start in answer state and switch if the model emits <think>.
        var inThinking = thinkPrefixInjected

        nativeResponseStreaming(handle, fullPrompt, maxNewTokens, stopString,
            TokenCallback { token ->
                accumulated.append(token)
                // Track whether any send failed so we can signal the C++ side to stop.
                var failed = false
                when {
                    token.contains("</think>") -> {
                        // Flush any thinking content before the closing tag.
                        val before = token.substringBefore("</think>")
                        if (before.isNotEmpty() && inThinking)
                            failed = failed || trySend(ChatEvent.ThinkingToken(before)).isFailure
                        inThinking = false
                        val after = token.substringAfter("</think>")
                        if (after.isNotEmpty())
                            failed = failed || trySend(ChatEvent.AnswerToken(after)).isFailure
                    }
                    token.contains("<think>") -> {
                        inThinking = true
                        val after = token.substringAfter("<think>")
                        if (after.isNotEmpty())
                            failed = failed || trySend(ChatEvent.ThinkingToken(after)).isFailure
                    }
                    inThinking -> failed = trySend(ChatEvent.ThinkingToken(token)).isFailure
                    else       -> failed = trySend(ChatEvent.AnswerToken(token)).isFailure
                }
                // Channel closed (consumer cancelled) — stop C++ generation immediately.
                if (failed) nativeStop(handle)
            }
        )

        val raw = accumulated.toString().trim()
        val (thinking, afterThink) = extractThinking(raw, thinkPrefixInjected)
        lastThinking = thinking
        val clean = afterThink
            .let { if (stopString != null) it.removeSuffix(stopString) else it }
            .removeSuffix("<|endoftext|>")
            .trim()
        history.add(Pair(userMessage, clean))
        updateExactTokenMetrics(fullPrompt, clean)
        trySend(ChatEvent.Done(LlmResult(clean, thinking)))

        close()
        awaitClose { nativeStop(handle) }
    }.flowOn(Dispatchers.IO)

    /** Release native resources. Alias for [destroy]; enables `use { }` blocks. */
    override fun close() = destroy()

    /**
     * Coroutine-friendly wrapper: runs [response] on [Dispatchers.IO] and returns the
     * reply together with the thinking content in a single [LlmResult].
     *
     * ```kotlin
     * val result = llm.chat("What is 2+2?")
     * println(result.text)     // "4"
     * println(result.thinking) // chain-of-thought, or null
     * ```
     */
    suspend fun chat(
        userMessage: String,
        imagePath: String? = null,
        maxNewTokens: Int = 1024
    ): LlmResult {
        val budget = generationTimeoutMs
        // MNN blocks inside its decode loop and, on a memory-starved device, can stay
        // there indefinitely at 0% CPU. Run the blocking call on a worker and race it
        // against a deadline so a stalled generation surfaces as an actionable error
        // instead of a call that never returns.
        //
        // The call stays counted in inFlightGeneration for its whole life - including
        // after we abandon it - so destroy() can never free the native handle
        // underneath a still-running generation.
        inFlightGeneration.incrementAndGet()
        try {
            return withContext(Dispatchers.IO) {
                val work = async { response(userMessage, imagePath, maxNewTokens) }
                try {
                    if (budget <= 0) {
                        LlmResult(work.await(), lastThinking)
                    } else {
                        withTimeout(budget.toLong()) { LlmResult(work.await(), lastThinking) }
                    }
                } catch (e: TimeoutCancellationException) {
                    // Really interrupt it: nativeStop sets LlmContext::status =
                    // USER_CANCEL, which MNN's decode loop breaks on at its next
                    // iteration. Then allow a short grace period for it to unwind.
                    nativeStop(handle)
                    withTimeoutOrNull(CANCEL_GRACE_MS) { work.await() }
                    throw MNNException(
                        "Generation exceeded the ${budget}ms budget. The device may be " +
                        "low on memory (MNN needs roughly model_size x 1.5 of free RAM)."
                    )
                }
            }
        } finally {
            inFlightGeneration.decrementAndGet()
        }
    }

    /**
     * Token-streaming variant: returns a cold [Flow] that emits each decoded token as
     * it is generated. The flow runs on [Dispatchers.IO] — collect on any thread.
     *
     * After the flow completes, [lastThinking] is populated as usual.
     *
     * ```kotlin
     * llm.responseFlow("Tell me a story").collect { token ->
     *     textView.append(token)  // update UI every token
     * }
     * ```
     *
     * Note: raw output is streamed (including the `<think>…</think>` block when
     * thinking is enabled). [lastThinking] is extracted after the flow closes.
     */
    fun responseFlow(
        userMessage: String,
        imagePath: String? = null,
        maxNewTokens: Int = 1024
    ): Flow<String> = callbackFlow {
        val thinkPrefixInjected = supportsThinking && enableThinking
        val fullPrompt = buildPrompt(userMessage, imagePath)
        nativeReset(handle)
        currentUserTurn = userMessage

        val accumulated = StringBuilder()

        // IMPORTANT: MNN cannot be interrupted mid-generation from outside its own
        // class. `Llm::generate` breaks out of its decode loop only on EOS /
        // max_new_tokens, or mContext->status == USER_CANCEL - and `mContext` is a
        // protected member whose only accessor, getContext(), is defined inline and
        // therefore exported as no symbol. MNN also never inspects the output
        // stream's state, so making the streambuf fail cannot stop it either (it only
        // suppresses further callbacks).
        //
        // Consequence: a cancelled collector must NOT wait for the native call. The
        // generation is dispatched onto [generationScope], a detached scope rather
        // than this flow's own scope, so cancelling the collector returns immediately
        // and the abandoned generation finishes in the background (bounded by
        // maxNewTokens). destroy() waits for in-flight generations before freeing
        // native memory.
        val worker = generationScope.launch {
            inFlightGeneration.incrementAndGet()
            try {
                nativeResponseStreaming(handle, fullPrompt, maxNewTokens, stopString,
                    TokenCallback { token ->
                        accumulated.append(token)
                        // Non-suspending, and safe from the native callback thread.
                        trySend(token)
                    }
                )
                finalizeResponse(fullPrompt, accumulated.toString(), thinkPrefixInjected)
                close()
            } catch (t: Throwable) {
                close(t)
            } finally {
                inFlightGeneration.decrementAndGet()
            }
        }

        // Fires on normal completion AND on downstream cancellation. Cancelling the
        // worker cannot interrupt the blocking JNI call, but it does drop token
        // forwarding so we stop touching the (already closed) channel.
        awaitClose { worker.cancel() }
    }.flowOn(Dispatchers.IO)

    /**
     * Post-generation bookkeeping shared by the blocking and streaming paths: split
     * thinking from the answer, record the turn in history, and refresh token metrics.
     */
    private fun finalizeResponse(fullPrompt: String, rawOutput: String, thinkPrefixInjected: Boolean) {
        val raw = rawOutput.trim()
        val (thinking, afterThink) = extractThinking(raw, thinkPrefixInjected)
        lastThinking = thinking
        val clean = afterThink
            .let { if (stopString != null) it.removeSuffix(stopString) else it }
            .removeSuffix("<|endoftext|>")
            .trim()
        history.add(Pair(currentUserTurn, clean))
        updateExactTokenMetrics(fullPrompt, clean)
    }

    /** Clear conversation history and KV-cache. */
    fun clearHistory() {
        history.clear()
        lastThinking = null
        nativeReset(handle)
    }

    private fun updateExactTokenMetrics(prompt: String, cleanGenerated: String) {
        val promptTokens = safeCountTokens(prompt)
        if (promptTokens != null) nativeSetPromptTokens(handle, promptTokens)

        val generatedTokens = safeCountTokens(cleanGenerated)
        if (generatedTokens != null) nativeSetGeneratedTokens(handle, generatedTokens)
    }

    private fun safeCountTokens(text: String): Int? = try {
        TokenCountUtils.normalizeCount(nativeCountTokens(handle, text))
    } catch (_: Throwable) {
        null
    }

    /**
     * Release native resources. Call when done.
     *
     * Blocks until any in-flight generation has returned, because MNN 3.6 offers no
     * way to interrupt one and freeing the handle underneath it would be a
     * use-after-free. An abandoned generation can therefore delay this call by up to
     * the remaining token budget.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        generationScope.cancel()
        while (inFlightGeneration.get() > 0) {
            try {
                Thread.sleep(20)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        nativeDestroy(handle)
    }

    /** Metrics from the last [response] call. */
    fun lastMetrics(): Metrics = Metrics(
        prefillMs        = nativeGetPrefillMs(handle),
        decodeMs         = nativeGetDecodeMs(handle),
        promptTokens     = nativeGetPromptTokens(handle),
        generatedTokens  = nativeGetGeneratedTokens(handle),
        sampleMs         = nativeGetSampleMs(handle),
        ttfaMs           = nativeGetTtfaMs(handle)
    )

    companion object {
        /**
         * Default per-generation wall-clock budget: generous enough that a normal answer
         * is never truncated, tight enough that a pathological run cannot wedge the app.
         */
        const val DEFAULT_GENERATION_TIMEOUT_MS = 120_000

        /** How long to wait for MNN to unwind after a cancel before giving up. */
        private const val CANCEL_GRACE_MS = 5_000L

        init {
            System.loadLibrary("llm")
            System.loadLibrary("mnn-jni-bridge")
        }

        /**
         * SAM interface used by [responseFlow] to receive tokens from the native layer.
         * Declared here so JNI can look up `onToken(Ljava/lang/String;)V` on the object.
         */
        fun interface TokenCallback {
            fun onToken(token: String)
        }

        /**
         * Single-step coroutine factory: creates AND loads the model on [Dispatchers.IO].
         *
         * This is the preferred entry point for new code:
         * ```kotlin
         * val llm = MNNLlm.load("/path/to/llm_config.json")
         * val result = llm.chat("Hello!")
         * ```
         *
         * @throws IllegalArgumentException if the config path is invalid or native create fails.
         * @throws IllegalStateException if the model weights fail to load.
         */
        suspend fun load(configPath: String): MNNLlm = withContext(Dispatchers.IO) {
            val llm = create(configPath)
                ?: throw IllegalArgumentException(
                    "Failed to create LLM — check that '$configPath' exists and is valid JSON"
                )
            if (!llm.load()) {
                llm.destroy()
                throw IllegalStateException(
                    "Failed to load model weights — files may be corrupt or missing"
                )
            }
            llm
        }

        fun create(configPath: String): MNNLlm? {
            val chatStyle: ChatStyle
            val stopString: String?
            val supportsThinking: Boolean
            val isVisual: Boolean
            var bosToken: String? = null
            try {
                val configFile = File(configPath)
                val configJson = org.json.JSONObject(configFile.readText())
                
                // Fix LFM slow prefill by ensuring is_single is false
                if (configJson.optString("model_type").startsWith("lfm") && !configJson.has("is_single")) {
                    configJson.put("is_single", false)
                    configFile.writeText(configJson.toString(4))
                }
                
                val template = configJson.optString("prompt_template", "")
                // Qwen3/Qwen3.5 models use per-role template fields instead of prompt_template
                val userTemplate = configJson.optString("user_prompt_template", "")
                val sysTemplate  = configJson.optString("system_prompt_template", "")
                val jinjaObj = configJson.optJSONObject("jinja")
                val jinjaTemplate = jinjaObj?.optString("chat_template", "") ?: ""
                val jinjaEos = jinjaObj?.optString("eos")
                bosToken = jinjaObj?.optString("bos")

                // Qwen3.5 VLM and other newer models only expose ChatML via jinja.chat_template;
                // they have no top-level prompt_template / user_prompt_template at all.
                chatStyle = LlmConfigAnalyzer.detectChatStyle(
                    promptTemplate = template,
                    userPromptTemplate = userTemplate,
                    systemPromptTemplate = sysTemplate,
                    jinjaChatTemplate = jinjaTemplate
                )
                stopString = LlmConfigAnalyzer.detectStopString(jinjaEos, chatStyle)


                // Thinking: model advertises support via "thinking_template" field,
                // an explicit "enable_thinking" bool, <think> in prompt_template, or
                // <think> in the jinja chat_template (Qwen3/Qwen3.5 style).
                supportsThinking = LlmConfigAnalyzer.detectThinkingSupport(
                    hasThinkingTemplate = configJson.has("thinking_template"),
                    enableThinkingFlag = configJson.optBoolean("enable_thinking", false),
                    promptTemplate = template,
                    jinjaChatTemplate = jinjaTemplate
                )

                // Vision: true only when patchConfigDisableVisual() left is_visual=true,
                // meaning visual.mnn was actually present on disk.
                isVisual = configJson.optBoolean("is_visual", false)

            } catch (e: Exception) {
                // The config could not be parsed as JSON. MNN parses llm_config.json
                // with rapidjson, so a file org.json rejects is a file MNN cannot use
                // either. Returning a handle here would hand callers a live MNNLlm
                // guaranteed to fail later inside load() with a far less useful
                // message, so fail fast at the call site instead.
                Log.w("MNNLlm", "Could not parse $configPath as JSON: ${e.message}")
                return null
            }

            val handle = nativeCreate(configPath)
            if (handle == 0L) return null

            if (chatStyle == ChatStyle.GENERIC) {
                // A non-ChatML model gets the plain "User:/Assistant:" fallback. That is
                // a real degradation: the model is almost certainly trained on a
                // different template (Llama-3, Phi, Gemma, Mistral, ...), so output
                // quality suffers. Say so loudly rather than silently returning garbage.
                Log.w(
                    "MNNLlm",
                    "No ChatML markers found in $configPath - falling back to the " +
                        "generic User:/Assistant: template. This model is probably " +
                        "trained on a different chat template and may answer poorly."
                )
            }
            return MNNLlm(handle, chatStyle, stopString, bosToken, supportsThinking, isVisual)
        }

        @JvmStatic private external fun nativeCreate(configPath: String): Long
        @JvmStatic private external fun nativeLoad(handle: Long): Boolean
        @JvmStatic private external fun nativeResponse(handle: Long, prompt: String, maxNewTokens: Int, stopString: String?): String
        @JvmStatic private external fun nativeResponseStreaming(handle: Long, prompt: String, maxNewTokens: Int, stopString: String?, callback: TokenCallback)
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeSetConfig(handle: Long, configJson: String)
        @JvmStatic private external fun nativeSetPromptTokens(handle: Long, count: Int)
        @JvmStatic private external fun nativeSetGeneratedTokens(handle: Long, count: Int)
        @JvmStatic private external fun nativeCountTokens(handle: Long, text: String): Int
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeGetPrefillMs(handle: Long): Long
        @JvmStatic private external fun nativeGetDecodeMs(handle: Long): Long
        @JvmStatic private external fun nativeGetSampleMs(handle: Long): Long
        @JvmStatic private external fun nativeGetTtfaMs(handle: Long): Long
        @JvmStatic private external fun nativeGetPromptTokens(handle: Long): Int
        @JvmStatic private external fun nativeGetGeneratedTokens(handle: Long): Int
    }
}
