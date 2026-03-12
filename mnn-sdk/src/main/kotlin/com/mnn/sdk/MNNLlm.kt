package com.mnn.sdk

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
    /** True if the model supports chain-of-thought thinking (e.g. Qwen3-Instruct). */
    val supportsThinking: Boolean,
    /** True if the model was loaded with visual.mnn present (VLM capable). */
    val isVisual: Boolean
) {

    /** Format detected from llm_config.json prompt_template. */
    enum class ChatStyle { QWEN_CHATML, GENERIC }

    data class Metrics(
        val prefillMs: Long,
        val decodeMs: Long,
        val promptTokens: Int,
        val generatedTokens: Int
    ) {
        val tokensPerSec: Double
            get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else 0.0

        fun summary(): String =
            "Prefill: ${prefillMs}ms | Decode: ${decodeMs}ms | " +
            "Generated: $generatedTokens tokens | ${String.format("%.1f", tokensPerSec)} tok/s"
    }

    // Conversation history: list of (userMessage, cleanAssistantReply — no think block)
    private val history = mutableListOf<Pair<String, String>>()

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

    fun load(): Boolean {
        val ok = nativeLoad(handle)
        if (ok) {
            // Disable MNN's built-in jinja/prompt template so our pre-built ChatML prompt
            // (with <think> prefix and history) is passed directly to the tokenizer without
            // being re-wrapped as a "user" message. This also preserves the ExecutorScope
            // that response(string) sets up, which is required for VLM image tokenisation.
            nativeSetConfig(handle, """{"use_template":false}""")
        }
        return ok
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
    fun response(userMessage: String, imagePath: String? = null, maxNewTokens: Int = 512): String {
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
    private fun extractThinking(raw: String, thinkPrefixInjected: Boolean): Pair<String?, String> {
        val start = raw.indexOf("<think>")
        val end   = raw.indexOf("</think>")
        // Case 1: full <think>…</think> block present in the raw output.
        if (start != -1 && end != -1 && end > start) {
            val thinking   = raw.substring(start + 7, end).trim()
            val afterThink = raw.substring(end + 8).trim()
            return Pair(thinking.ifEmpty { null }, afterThink)
        }
        // Case 2: we injected <think> in the prompt prefix, so raw starts with thinking content.
        if (thinkPrefixInjected && end != -1) {
            val thinking   = raw.substring(0, end).trim()
            val afterThink = raw.substring(end + 8).trim()
            return Pair(thinking.ifEmpty { null }, afterThink)
        }
        return Pair(null, raw)
    }

    /**
     * Build the full formatted prompt including system message, conversation history,
     * and the current user turn. Controls the assistant prefix for thinking mode.
     */
    private fun buildPrompt(userMessage: String, imagePath: String?): String = buildString {
        when (chatStyle) {
            ChatStyle.QWEN_CHATML -> {
                append("<|im_start|>system\n")
                append(systemPrompt)
                append("<|im_end|>\n")
                for ((u, a) in history) {
                    append("<|im_start|>user\n$u<|im_end|>\n")
                    append("<|im_start|>assistant\n$a<|im_end|>\n")
                }
                // User turn — embed image tag immediately before text (no separator)
                // matching MNN's official PromptUtils: "<img>path</img>text"
                append("<|im_start|>user\n")
                if (imagePath != null && isVisual) {
                    append("<img>$imagePath</img>")
                }
                append("$userMessage<|im_end|>\n")
                // Assistant prefix — controls thinking mode
                append("<|im_start|>assistant\n")
                if (supportsThinking) {
                    if (enableThinking) {
                        append("<think>\n")           // model will produce thoughts then </think>
                    } else {
                        append("<think>\n\n</think>\n") // budget-force: skip thinking entirely
                    }
                }
            }
            ChatStyle.GENERIC -> {
                for ((u, a) in history) append("User: $u\nAssistant: $a\n")
                append("User: $userMessage\nAssistant:")
            }
        }
    }

    /** Clear conversation history and KV-cache. */
    fun clearHistory() {
        history.clear()
        lastThinking = null
        nativeReset(handle)
    }

    /** Release native resources. Call when done. */
    fun destroy() = nativeDestroy(handle)

    /** Metrics from the last [response] call. */
    fun lastMetrics(): Metrics = Metrics(
        prefillMs        = nativeGetPrefillMs(handle),
        decodeMs         = nativeGetDecodeMs(handle),
        promptTokens     = nativeGetPromptTokens(handle),
        generatedTokens  = nativeGetGeneratedTokens(handle)
    )

    companion object {
        init {
            System.loadLibrary("llm")
            System.loadLibrary("mnn-jni-bridge")
        }

        fun create(configPath: String): MNNLlm? {
            val handle = nativeCreate(configPath)
            if (handle == 0L) return null

            val chatStyle: ChatStyle
            val stopString: String?
            val supportsThinking: Boolean
            val isVisual: Boolean
            try {
                val configJson = org.json.JSONObject(File(configPath).readText())
                val template = configJson.optString("prompt_template", "")
                // Qwen3/Qwen3.5 models use per-role template fields instead of prompt_template
                val userTemplate = configJson.optString("user_prompt_template", "")
                val sysTemplate  = configJson.optString("system_prompt_template", "")
                val jinjaObj = configJson.optJSONObject("jinja")
                val jinjaTemplate = jinjaObj?.optString("chat_template", "") ?: ""
                val jinjaEos = jinjaObj?.optString("eos")

                // Qwen3.5 VLM and other newer models only expose ChatML via jinja.chat_template;
                // they have no top-level prompt_template / user_prompt_template at all.
                val isChatML = template.contains("<|im_start|>") || template.contains("<|im_end|>") ||
                               userTemplate.contains("<|im_start|>") || sysTemplate.contains("<|im_start|>") ||
                               jinjaTemplate.contains("<|im_start|>")
                chatStyle = if (isChatML) ChatStyle.QWEN_CHATML else ChatStyle.GENERIC
                stopString = when {
                    jinjaEos != null && jinjaEos.isNotBlank() -> jinjaEos
                    isChatML -> "<|im_end|>"
                    else -> null
                }

                // Thinking: model advertises support via "thinking_template" field,
                // an explicit "enable_thinking" bool, <think> in prompt_template, or
                // <think> in the jinja chat_template (Qwen3/Qwen3.5 style).
                supportsThinking = configJson.has("thinking_template") ||
                                   configJson.optBoolean("enable_thinking", false) ||
                                   template.contains("<think>") ||
                                   jinjaTemplate.contains("<think>")

                // Vision: true only when patchConfigDisableVisual() left is_visual=true,
                // meaning visual.mnn was actually present on disk.
                isVisual = configJson.optBoolean("is_visual", false)

            } catch (_: Exception) {
                return MNNLlm(handle, ChatStyle.QWEN_CHATML, "<|im_end|>",
                               supportsThinking = false, isVisual = false)
            }

            return MNNLlm(handle, chatStyle, stopString, supportsThinking, isVisual)
        }

        @JvmStatic private external fun nativeCreate(configPath: String): Long
        @JvmStatic private external fun nativeLoad(handle: Long): Boolean
        @JvmStatic private external fun nativeResponse(handle: Long, prompt: String, maxNewTokens: Int, stopString: String?): String
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeSetConfig(handle: Long, configJson: String)
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeGetPrefillMs(handle: Long): Long
        @JvmStatic private external fun nativeGetDecodeMs(handle: Long): Long
        @JvmStatic private external fun nativeGetPromptTokens(handle: Long): Int
        @JvmStatic private external fun nativeGetGeneratedTokens(handle: Long): Int
    }
}
