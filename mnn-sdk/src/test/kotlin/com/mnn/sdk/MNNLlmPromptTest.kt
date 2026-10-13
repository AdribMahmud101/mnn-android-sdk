package com.mnn.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure prompt/capability logic in [LlmConfigAnalyzer] and
 * [PromptComposer]. These run on the JVM — no native library, no device.
 */
class MNNLlmPromptTest {

    private val imStart = "<" + "|" + "im_start" + "|>"
    private val imEnd = "<" + "|" + "im_end" + "|>"
    private val thinkOpen = "<think" + ">"
    private val thinkClose = "</think" + ">"

    // ---------------------------------------------------------------- chat style

    @Test
    fun `chatml detected from prompt_template`() {
        val style = LlmConfigAnalyzer.detectChatStyle(
            promptTemplate = "$imStart\nuser\n",
            userPromptTemplate = "",
            systemPromptTemplate = "",
            jinjaChatTemplate = ""
        )
        assertEquals(MNNLlm.ChatStyle.QWEN_CHATML, style)
    }

    @Test
    fun `chatml detected from jinja template only`() {
        // Qwen3/Qwen3.5 expose ChatML ONLY through jinja.chat_template.
        val style = LlmConfigAnalyzer.detectChatStyle(
            promptTemplate = "",
            userPromptTemplate = "",
            systemPromptTemplate = "",
            jinjaChatTemplate = "{{- '$imStart' }}"
        )
        assertEquals(MNNLlm.ChatStyle.QWEN_CHATML, style)
    }

    @Test
    fun `generic fallback when no chatml markers anywhere`() {
        val style = LlmConfigAnalyzer.detectChatStyle(
            promptTemplate = "Human: ",
            userPromptTemplate = "",
            systemPromptTemplate = "",
            jinjaChatTemplate = "{{ messages }}"
        )
        assertEquals(MNNLlm.ChatStyle.GENERIC, style)
    }

    // --------------------------------------------------------------- stop string

    @Test
    fun `explicit jinja eos wins over chatml default`() {
        assertEquals(imEnd, LlmConfigAnalyzer.detectStopString(imEnd, MNNLlm.ChatStyle.QWEN_CHATML))
    }

    @Test
    fun `blank jinja eos falls back to chatml sentinel`() {
        assertEquals("", LlmConfigAnalyzer.detectStopString("   ", MNNLlm.ChatStyle.QWEN_CHATML))
    }

    @Test
    fun `generic model gets null stop string`() {
        assertNull(LlmConfigAnalyzer.detectStopString(null, MNNLlm.ChatStyle.GENERIC))
    }

    // ------------------------------------------------------------------ thinking

    @Test
    fun `thinking detected via thinking_template flag`() {
        assertTrue(
            LlmConfigAnalyzer.detectThinkingSupport(
                hasThinkingTemplate = true,
                enableThinkingFlag = false,
                promptTemplate = "",
                jinjaChatTemplate = ""
            )
        )
    }

    @Test
    fun `thinking detected via enable_thinking flag`() {
        assertTrue(LlmConfigAnalyzer.detectThinkingSupport(false, true, "", ""))
    }

    @Test
    fun `thinking detected via think tag in jinja template`() {
        assertTrue(
            LlmConfigAnalyzer.detectThinkingSupport(false, false, "", "{{ '$thinkOpen' }}")
        )
    }

    @Test
    fun `no thinking when nothing signals it`() {
        assertFalse(LlmConfigAnalyzer.detectThinkingSupport(false, false, "", ""))
    }

    // --------------------------------------------------------------------- vision

    @Test
    fun `visual requires both config flag and weights on disk`() {
        assertTrue(LlmConfigAnalyzer.detectVisual(configSaysVisual = true, visualWeightsPresent = true))
        // Config says visual but visual.mnn is absent -> must not claim vision.
        assertFalse(LlmConfigAnalyzer.detectVisual(configSaysVisual = true, visualWeightsPresent = false))
        assertFalse(LlmConfigAnalyzer.detectVisual(configSaysVisual = false, visualWeightsPresent = true))
    }

    // ---------------------------------------------------------- extractThinking

    @Test
    fun `extracts full think block`() {
        val (thinking, answer) = PromptComposer.extractThinking(
            "$thinkOpen\nlet me reason</think>\n\nParis.", thinkPrefixInjected = false
        )
        assertEquals("let me reason", thinking)
        assertEquals("Paris.", answer)
    }

    @Test
    fun `extracts think block when prefix was injected`() {
        // We injected an opening <think> into the prompt, so the model only
        // ever emits the closing tag.
        val (thinking, answer) = PromptComposer.extractThinking(
            "reasoning here</think>The answer.", thinkPrefixInjected = true
        )
        assertEquals("reasoning here", thinking)
        assertEquals("The answer.", answer)
    }

    @Test
    fun `no thinking block returns raw text untouched`() {
        val (thinking, answer) = PromptComposer.extractThinking("Just an answer.", false)
        assertNull(thinking)
        assertEquals("Just an answer.", answer)
    }

    @Test
    fun `empty think block yields null thinking`() {
        val (thinking, answer) = PromptComposer.extractThinking("$thinkOpen</think>Answer", false)
        assertNull(thinking)
        assertEquals("Answer", answer)
    }

    @Test
    fun `unclosed think tag without injected prefix is treated as plain text`() {
        // Regression guard: a stray opening tag must not truncate the answer.
        val (thinking, answer) = PromptComposer.extractThinking("$thinkOpen partial", false)
        assertNull(thinking)
        assertEquals("$thinkOpen partial", answer)
    }

    @Test
    fun `multiline thinking block is fully captured`() {
        val (thinking, answer) = PromptComposer.extractThinking(
            "$thinkOpen\nline one\nline two\n</think>\nfinal", false
        )
        assertEquals("line one\nline two", thinking)
        assertEquals("final", answer)
    }

    // -------------------------------------------------------------- composeChatML

    @Test
    fun `chatml prompt has system user and assistant sections`() {
        val prompt = PromptComposer.composeChatML(
            systemPrompt = "You are helpful.",
            history = emptyList(),
            userMessage = "Hi",
            imagePath = null,
            isVisual = false,
            bosToken = null,
            supportsThinking = false,
            enableThinking = false
        )
        assertEquals("system\nYou are helpful.\nuser\nHi\nassistant\n", prompt)
    }

    @Test
    fun `bos token is prepended when present`() {
        val prompt = PromptComposer.composeChatML(
            systemPrompt = "S", history = emptyList(), userMessage = "Hi",
            imagePath = null, isVisual = false, bosToken = imStart,
            supportsThinking = false, enableThinking = false
        )
        assertTrue(prompt.startsWith(imStart))
    }

    @Test
    fun `history turns are replayed in order`() {
        val prompt = PromptComposer.composeChatML(
            systemPrompt = "S",
            history = listOf("first" to "one", "second" to "two"),
            userMessage = "third",
            imagePath = null, isVisual = false, bosToken = null,
            supportsThinking = false, enableThinking = false
        )
        val i1 = prompt.indexOf("first"); val i2 = prompt.indexOf("one")
        val i3 = prompt.indexOf("second"); val i4 = prompt.indexOf("two")
        val i5 = prompt.indexOf("third")
        assertTrue("history must be ordered", i1 < i2 && i2 < i3 && i3 < i4 && i4 < i5)
    }

    @Test
    fun `thinking enabled injects open think tag`() {
        val prompt = PromptComposer.composeChatML(
            systemPrompt = "S", history = emptyList(), userMessage = "Q",
            imagePath = null, isVisual = false, bosToken = null,
            supportsThinking = true, enableThinking = true
        )
        assertTrue(prompt.endsWith("assistant\n$thinkOpen\n"))
    }

    @Test
    fun `thinking disabled injects pre-closed empty think block`() {
        val prompt = PromptComposer.composeChatML(
            systemPrompt = "S", history = emptyList(), userMessage = "Q",
            imagePath = null, isVisual = false, bosToken = null,
            supportsThinking = true, enableThinking = false
        )
        assertTrue(prompt.endsWith("assistant\n$thinkOpen\n\n$thinkClose\n"))
    }

    @Test
    fun `no think tags injected for non-thinking model`() {
        val prompt = PromptComposer.composeChatML(
            systemPrompt = "S", history = emptyList(), userMessage = "Q",
            imagePath = null, isVisual = false, bosToken = null,
            supportsThinking = false, enableThinking = true
        )
        assertFalse(prompt.contains(thinkOpen))
    }

    @Test
    fun `image tag is emitted for visual model`() {
        val prompt = PromptComposer.composeChatML(
            systemPrompt = "S", history = emptyList(), userMessage = "What is this?",
            imagePath = "/data/pic.jpg", isVisual = true, bosToken = null,
            supportsThinking = false, enableThinking = false
        )
        // Must be adjacent to the user text with no separator, matching MNN PromptUtils.
        assertTrue(prompt.contains("<img>/data/pic.jpg</img>What is this?"))
    }

    @Test
    fun `image tag is suppressed for non-visual model`() {
        val prompt = PromptComposer.composeChatML(
            systemPrompt = "S", history = emptyList(), userMessage = "What is this?",
            imagePath = "/data/pic.jpg", isVisual = false, bosToken = null,
            supportsThinking = false, enableThinking = false
        )
        assertFalse(prompt.contains("<img>"))
        assertFalse(prompt.contains("/data/pic.jpg"))
    }

    // -------------------------------------------------------------- composeGeneric

    @Test
    fun `generic prompt format`() {
        val prompt = PromptComposer.composeGeneric(listOf("hi" to "hello"), "how are you")
        assertEquals("User: hi\nAssistant: hello\nUser: how are you\nAssistant:", prompt)
    }

    @Test
    fun `generic prompt with no history`() {
        assertEquals("User: hi\nAssistant:", PromptComposer.composeGeneric(emptyList(), "hi"))
    }

    // -------------------------------------------------------------------- metrics

    @Test
    fun `tokens per second derives from decode time`() {
        val m = MNNLlm.Metrics(prefillMs = 100, decodeMs = 500, promptTokens = 10, generatedTokens = 50)
        assertEquals(100.0, m.tokensPerSec, 0.001)
    }

    @Test
    fun `tokens per second is zero not infinite when decode time missing`() {
        val m = MNNLlm.Metrics(prefillMs = 0, decodeMs = 0, promptTokens = 10, generatedTokens = 50)
        assertEquals(0.0, m.tokensPerSec, 0.001)
    }

    @Test
    fun `prefill throughput derives from prefill time`() {
        val m = MNNLlm.Metrics(prefillMs = 200, decodeMs = 0, promptTokens = 40, generatedTokens = 0)
        assertEquals(200.0, m.prefillTokensPerSec, 0.001)
    }

    @Test
    fun `summary reports ttfa`() {
        val m = MNNLlm.Metrics(
            prefillMs = 10, decodeMs = 20, promptTokens = 1, generatedTokens = 2, ttfaMs = 5
        )
        assertTrue(m.summary().contains("TTFA: 5ms"))
    }

    @Test
    fun `llm result default thinking is null`() {
        assertNull(MNNLlm.LlmResult("text", null).thinking)
    }
}
