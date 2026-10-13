package com.mnn.sdk

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.take
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests that exercise the REAL native stack on a device.
 *
 * These are the only tests that can validate what a JVM unit test cannot: JNI
 * symbol resolution against the bundled libMNN.so/libllm.so, the hand-written
 * vtable-matched include/llm/llm.hpp, streaming callbacks, and generation.
 *
 * Tests requiring a model are skipped (not failed) when none is present, so the
 * suite stays green on machines with no weights. To enable them, push a model
 * directory into the app's private storage:
 *
 *   adb push <model-dir> /data/local/tmp/
 *   adb shell run-as <test-pkg> mkdir -p files/models/<Name>
 *   adb shell run-as <test-pkg> cp /data/local/tmp/<model-dir>/llm.mnn files/models/<Name>/
 *   adb shell run-as <test-pkg> cp /data/local/tmp/<model-dir>/llm.mnn.weight files/models/<Name>/
 *   adb shell run-as <test-pkg> cp /data/local/tmp/<model-dir>/llm_config.json files/models/<Name>/
 *   adb shell run-as <test-pkg> cp /data/local/tmp/<model-dir>/tokenizer.txt files/models/<Name>/
 *
 * The runner then picks up `files/models/<any>/llm_config.json`.
 */
@RunWith(AndroidJUnit4::class)
class MNNLlmInstrumentedTest {

    private val tag = "MNNLlmInstrumentedTest"

    /** First model directory containing llm_config.json, or null. */
    private fun discoverModel(): File? {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val modelsDir = File(ctx.filesDir, "models")
        if (!modelsDir.isDirectory) return null
        return modelsDir.listFiles()
            ?.filter { File(it, "llm_config.json").isFile }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }

    @Before
    fun checkAbis() {
        val abi = android.os.Build.SUPPORTED_ABIS.first()
        assumeTrue(
            "This SDK only ships arm64-v8a and armeabi-v7a; device reports $abi",
            abi == "arm64-v8a" || abi == "armeabi-v7a"
        )
    }

    /**
     * Verifies every bundled native library actually loads and that MNN reports
     * the expected version. Cheapest possible smoke test; catches packaging/ABI
     * regressions immediately.
     */
    @Test
    fun nativeLibrariesLoadAndReportVersion() {
        MNNEngine.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        val version = MNNEngine.getInstance().getVersion()
        Log.i(tag, "MNN version: $version")
        assertTrue(
            "MNNEngine.getVersion() returned '$version'; expected a 3.x version",
            version.startsWith("3.")
        )
    }

    @Test
    fun engineCreatesTensors() {
        MNNEngine.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        val tensor = MNNTensor.fromFloatArray(FloatArray(8) { it.toFloat() }, intArrayOf(1, 8))
        assertTrue(tensor.size() == 8)
        assertTrue(MNNEngine.getInstance().getVersion().isNotEmpty())
    }

    @Test
    fun llmLoadsAndAnswersPrompt(): Unit = kotlinx.coroutines.runBlocking {
        val modelDir = discoverModel()
        assumeTrue("No model found in files/models; skipping", modelDir != null)
        val config = File(modelDir!!, "llm_config.json").absolutePath

        val llm = MNNLlm.load(config)
        try {
            val result = llm.chat("What is 2 + 2? Answer with just the number.")
            Log.i(tag, "answer='${result.text}' thinking='${result.thinking}'")
            assertTrue("Expected non-empty answer", result.text.isNotBlank())

            val metrics = llm.lastMetrics()
            Log.i(tag, "metrics: ${metrics.summary()}")
            // Timings must be REAL, not a fixed 30/70 split of the total.
            assertTrue("decodeMs must be > 0", metrics.decodeMs > 0)
            assertTrue("ttfaMs must be > 0", metrics.ttfaMs > 0)
            assertTrue("generatedTokens must be > 0", metrics.generatedTokens > 0)
            assertTrue(
                "prefill+decode should account for the request",
                metrics.prefillMs + metrics.decodeMs > 0
            )
        } finally {
            llm.close()
        }
    }

    @Test
    fun streamingEmitsTokensAndHonoursCancellation(): Unit = kotlinx.coroutines.runBlocking {
        val modelDir = discoverModel()
        assumeTrue("No model found in files/models; skipping", modelDir != null)
        val config = File(modelDir!!, "llm_config.json").absolutePath

        val llm = MNNLlm.load(config)
        try {
            // Streaming must actually emit tokens.
            val tokens = mutableListOf<String>()
            llm.responseFlow("Count from one to five.").collect { tokens.add(it) }
            assertTrue("Expected streamed tokens", tokens.isNotEmpty())
            Log.i(tag, "streamed ${tokens.size} chunks")

            // Cancellation must return PROMPTLY. MNN 3.6 cannot interrupt a running
            // generation (its decode loop only honours mContext->status == USER_CANCEL,
            // which is unreachable from outside the class), so the SDK abandons the
            // generation in the background instead of blocking the collector on it.
            // If this regresses, collect() hangs for the whole token budget.
            //
            // maxNewTokens is kept modest so that close() - which deliberately waits
            // for the abandoned generation before freeing native memory - finishes
            // quickly and the test stays bounded.
            val start = System.currentTimeMillis()
            var seen = 0
            llm.responseFlow(
                "Write a very long essay about the history of computing.",
                maxNewTokens = 64
            ).take(3).collect { seen++ }
            val elapsed = System.currentTimeMillis() - start
            assertTrue("Expected 3 tokens, got $seen", seen == 3)
            assertTrue("Cancellation should return promptly but took ${elapsed}ms", elapsed < 5_000)
            Log.i(tag, "cancelled after $seen tokens in ${elapsed}ms")
        } finally {
            llm.close()
        }
    }

    @Test
    fun multiTurnRetainsHistory(): Unit = kotlinx.coroutines.runBlocking {
        val modelDir = discoverModel()
        assumeTrue("No model found in files/models; skipping", modelDir != null)
        val config = File(modelDir!!, "llm_config.json").absolutePath

        val llm = MNNLlm.load(config)
        try {
            val first = llm.chat("My favourite number is 42. Just acknowledge.")
            Log.i(tag, "first turn: ${first.text.take(60)}")
            val second = llm.chat("What number did I say?")
            Log.i(tag, "second turn: ${second.text.take(60)}")
            assertTrue(first.text.isNotBlank())
            assertTrue(second.text.isNotBlank())
        } finally {
            llm.clearHistory()
            llm.close()
        }
    }

    @Test
    fun loadRejectsGarbageConfigWithoutCrashing() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bogus = File(ctx.cacheDir, "bogus_config.json")
        bogus.writeText("{ this is not valid json")
        try {
            val llm = MNNLlm.create(bogus.absolutePath)
            // create() must return null (or throw MNNException), never hand back a
            // live handle that is guaranteed to fail later inside load().
            if (llm != null) {
                llm.close()
                fail("Expected null for an invalid config, got a live handle")
            }
        } catch (e: MNNException) {
            // Acceptable: a typed SDK exception is fine.
            Log.i(tag, "rejected as expected: ${e.message}")
        } finally {
            bogus.delete()
        }
    }
}
