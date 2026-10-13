package com.mnn.sdk

/**
 * Pure prompt/capability logic extracted from [MNNLlm] so it can be unit-tested
 * on the JVM without loading any native library.
 *
 * These functions are the correctness-critical part of the SDK: they decide the
 * chat template, whether the model emits `<think>` blocks, how thinking content
 * is split from the answer, and how images are embedded. They are deliberately
 * free of `org.json`, file IO and JNI so the test suite can exercise every branch.
 */
internal object LlmConfigAnalyzer {

    /**
     * Decide whether a model uses ChatML (`<|im_start|>` / `<|im_end|>`) or the
     * generic `User:/Assistant:` fallback.
     *
     * Qwen3/Qwen3.5 expose ChatML only through `jinja.chat_template`, so that
     * template is inspected too — models with none of these markers fall back to
     * [MNNLlm.ChatStyle.GENERIC], which produces correct-but-suboptimal prompting.
     */
    fun detectChatStyle(
        promptTemplate: String,
        userPromptTemplate: String,
        systemPromptTemplate: String,
        jinjaChatTemplate: String
    ): MNNLlm.ChatStyle {
        val isChatML =
            promptTemplate.contains(CHATML_START) || promptTemplate.contains(CHATML_END) ||
                userPromptTemplate.contains(CHATML_START) ||
                systemPromptTemplate.contains(CHATML_START) ||
                jinjaChatTemplate.contains(CHATML_START)
        return if (isChatML) MNNLlm.ChatStyle.QWEN_CHATML else MNNLlm.ChatStyle.GENERIC
    }

    /**
     * Stop string passed to `Llm::response`.
     *
     * An explicit `jinja.eos` always wins. Otherwise ChatML models get `""`,
     * which makes MNN stop on the model's own EOS token, while GENERIC models
     * get `null` (rely on EOS as well, no sentinel terminator).
     */
    fun detectStopString(jinjaEos: String?, chatStyle: MNNLlm.ChatStyle): String? = when {
        !jinjaEos.isNullOrBlank() -> jinjaEos
        chatStyle == MNNLlm.ChatStyle.QWEN_CHATML -> ""
        else -> null
    }

    /**
     * Whether the model supports chain-of-thought output.
     *
     * Signalled by a `thinking_template` field, an explicit `enable_thinking`
     * flag, or a literal `<think>` in either prompt template.
     */
    fun detectThinkingSupport(
        hasThinkingTemplate: Boolean,
        enableThinkingFlag: Boolean,
        promptTemplate: String,
        jinjaChatTemplate: String
    ): Boolean = hasThinkingTemplate || enableThinkingFlag ||
        promptTemplate.contains(THINK_OPEN) || jinjaChatTemplate.contains(THINK_OPEN)

    /** `is_visual` is only honoured when the vision encoder is actually present. */
    fun detectVisual(configSaysVisual: Boolean, visualWeightsPresent: Boolean): Boolean =
        configSaysVisual && visualWeightsPresent

    const val CHATML_START = "<|im_start|>"
    const val CHATML_END = "<|im_end|>"
    const val THINK_OPEN = "<think>"
    const val THINK_CLOSE = "</think>"
}

/**
 * Builds the final prompt string handed to `Llm::response`.
 */
internal object PromptComposer {

    /**
     * Split raw model output into (thinking, answer).
     *
     * Two shapes are handled:
     *  1. The model emitted a complete `<think>…</think>` block.
     *  2. We injected an opening `<think>` into the prompt prefix (thinking mode
     *     on), so the output starts with thinking content and only ever closes
     *     the tag. In that case the text before `</think>` is the thinking.
     *
     * Returns `(null, raw)` when the output contains no thinking at all.
     */
    fun extractThinking(raw: String, thinkPrefixInjected: Boolean): Pair<String?, String> {
        val start = raw.indexOf(LlmConfigAnalyzer.THINK_OPEN)
        val end = raw.indexOf(LlmConfigAnalyzer.THINK_CLOSE)

        if (start != -1 && end != -1 && end > start) {
            val thinking = raw.substring(start + LlmConfigAnalyzer.THINK_OPEN.length, end).trim()
            val answer = raw.substring(end + LlmConfigAnalyzer.THINK_CLOSE.length).trim()
            return thinking.ifEmpty { null } to answer
        }
        if (thinkPrefixInjected && end != -1) {
            val thinking = raw.substring(0, end).trim()
            val answer = raw.substring(end + LlmConfigAnalyzer.THINK_CLOSE.length).trim()
            return thinking.ifEmpty { null } to answer
        }
        return null to raw
    }

    /**
     * Compose a full ChatML prompt: optional BOS, system turn, all history turns,
     * the current user turn, and an assistant prefix that steers thinking mode.
     *
     * The image tag is embedded directly before the user text with no separator
     * (`<img>path</img>text`), matching MNN's own `PromptUtils`, and is only
     * emitted when the model was detected as vision-capable.
     */
    fun composeChatML(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        imagePath: String?,
        isVisual: Boolean,
        bosToken: String?,
        supportsThinking: Boolean,
        enableThinking: Boolean
    ): String = buildString {
        if (!bosToken.isNullOrEmpty()) append(bosToken).append('\n')
        append("system\n").append(systemPrompt).append('\n')
        for ((u, a) in history) {
            append("user\n").append(u).append('\n')
            append("assistant\n").append(a).append('\n')
        }
        append("user\n")
        if (imagePath != null && isVisual) append("<img>").append(imagePath).append("</img>")
        append(userMessage).append('\n')
        append("assistant\n")
        if (supportsThinking) {
            if (enableThinking) {
                append("<think>\n")
            } else {
                // Qwen3 budget-forcing: a pre-closed empty think block makes the
                // model skip reasoning entirely and answer directly.
                append("<think>\n\n</think>\n")
            }
        }
    }

    /** Fallback template for models with no ChatML markers. */
    fun composeGeneric(
        history: List<Pair<String, String>>,
        userMessage: String
    ): String = buildString {
        for ((u, a) in history) append("User: ").append(u).append("\nAssistant: ").append(a).append('\n')
        append("User: ").append(userMessage).append("\nAssistant:")
    }
}
