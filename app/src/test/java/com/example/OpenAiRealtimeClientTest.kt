package com.example

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * Tests that verify the actual JSON sent over WebSocket by OpenAiRealtimeClient.
 *
 * WHY THIS EXISTS:
 *   Unit tests for ViewModel only check state transitions — they never see what JSON
 *   is actually sent to OpenAI. API format bugs (wrong field names, missing sections)
 *   pass ViewModel tests undetected and only fail at runtime.
 *
 *   Example: requestGreeting() sent {"response":{"modalities":["audio"]}} which is
 *   not a valid field for gpt-realtime-2, causing "Unknown parameter: response.modalities"
 *   at runtime. The ViewModel test didn't catch it because ready=false makes all send
 *   calls no-ops in that test context.
 *
 * STRATEGY:
 *   Inject a fake WebSocket via reflection that records all sent strings.
 *   Set ready=true and lastVoiceName via reflection.
 *   Call the method under test.
 *   Assert on the captured JSON.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenAiRealtimeClientTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // ── Reflection helpers ────────────────────────────────────────────────────

    private fun field(clazz: Class<*>, name: String): Field =
        clazz.getDeclaredField(name).also { it.isAccessible = true }

    /** A fake WebSocket that records every string message sent to it. */
    private class RecordingWebSocket : WebSocket {
        val sent = mutableListOf<String>()
        override fun request() = throw NotImplementedError()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean = false
        override fun cancel() {}
    }

    /**
     * Builds a client wired to a RecordingWebSocket with ready=true so that
     * send methods are not no-ops.
     */
    private fun buildReadyClient(voice: String = "ash"): Pair<OpenAiRealtimeClient, RecordingWebSocket> {
        val client = OpenAiRealtimeClient(
            kotlinx.coroutines.CoroutineScope(testDispatcher)
        )
        val fake = RecordingWebSocket()
        field(OpenAiRealtimeClient::class.java, "webSocket").set(client, fake)
        field(OpenAiRealtimeClient::class.java, "ready").set(client, true)
        field(OpenAiRealtimeClient::class.java, "lastVoiceName").set(client, voice)
        return client to fake
    }

    // ── updateInstructions ────────────────────────────────────────────────────

    @Test
    fun `updateInstructions sends audio pcm format so second phrase plays as audio`() =
        runTest(testDispatcher) {
            val (client, fake) = buildReadyClient("ash")

            client.updateInstructions("test prompt")

            assertEquals("Expected exactly one WebSocket message", 1, fake.sent.size)
            val json = JSONObject(fake.sent[0])
            assertEquals("session.update", json.getString("type"))

            val session = json.getJSONObject("session")
            val audioOutput = session.getJSONObject("audio").getJSONObject("output")
            val format = audioOutput.getJSONObject("format")

            assertEquals(
                "audio output type must be audio/pcm — otherwise AudioTrack gets wrong bytes",
                "audio/pcm",
                format.getString("type")
            )
            assertEquals(
                "sample rate must be 24000 to match AudioTrack configuration",
                24000,
                format.getInt("rate")
            )
        }

    @Test
    fun `updateInstructions includes output_modalities audio`() =
        runTest(testDispatcher) {
            val (client, fake) = buildReadyClient()

            client.updateInstructions("test prompt")

            val session = JSONObject(fake.sent[0]).getJSONObject("session")
            val modalities = session.getJSONArray("output_modalities")

            assertEquals(1, modalities.length())
            assertEquals("audio", modalities.getString(0))
        }

    @Test
    fun `updateInstructions preserves voice name`() =
        runTest(testDispatcher) {
            val (client, fake) = buildReadyClient("cedar")

            client.updateInstructions("test prompt")

            val session = JSONObject(fake.sent[0]).getJSONObject("session")
            val voice = session.getJSONObject("audio").getJSONObject("output").getString("voice")

            assertEquals("cedar", voice)
        }

    // ── requestGreeting ───────────────────────────────────────────────────────

    @Test
    fun `requestGreeting sends bare response_create without unknown fields`() =
        runTest(testDispatcher) {
            val (client, fake) = buildReadyClient()

            client.requestGreeting()

            assertEquals(1, fake.sent.size)
            val json = JSONObject(fake.sent[0])
            assertEquals("response.create", json.getString("type"))

            assertTrue(
                "response.create must NOT contain a 'response' body with unknown params " +
                    "(gpt-realtime-2 rejects e.g. response.modalities). Keys found: ${json.keys().asSequence().toList()}",
                !json.has("response")
            )
        }

    // ── respondToFunctionAndSpeak ─────────────────────────────────────────────

    @Test
    fun `respondToFunctionAndSpeak sends function_call_output then response_create`() =
        runTest(testDispatcher) {
            val (client, fake) = buildReadyClient()

            client.respondToFunctionAndSpeak("call_abc", "No answer.")

            assertEquals("Expected exactly 2 messages", 2, fake.sent.size)

            val fnOutput = JSONObject(fake.sent[0])
            assertEquals("conversation.item.create", fnOutput.getString("type"))
            val item = fnOutput.getJSONObject("item")
            assertEquals("function_call_output", item.getString("type"))
            assertEquals("call_abc", item.getString("call_id"))

            val responseCreate = JSONObject(fake.sent[1])
            assertEquals(
                "Second message must be response.create to trigger AI speech",
                "response.create",
                responseCreate.getString("type")
            )
            assertTrue(
                "instructions must contain the phrase text",
                responseCreate.getJSONObject("response").getString("instructions").contains("No answer.")
            )
        }

    @Test
    fun `respondToFunctionAndSpeak does NOT send session_update before response_create`() =
        runTest(testDispatcher) {
            val (client, fake) = buildReadyClient()

            client.respondToFunctionAndSpeak("call_xyz", "Произнеси: \"Tom!\"")

            val types = fake.sent.map { JSONObject(it).getString("type") }
            assertTrue(
                "session.update must NOT be sent before response.create — it resets audio config and causes silence. Types sent: $types",
                "session.update" !in types
            )
        }

    // ── commitAndRespond ──────────────────────────────────────────────────────

    @Test
    fun `commitAndRespond sends buffer commit then response_create`() =
        runTest(testDispatcher) {
            val (client, fake) = buildReadyClient()

            client.commitAndRespond()

            assertEquals(2, fake.sent.size)
            assertEquals("input_audio_buffer.commit", JSONObject(fake.sent[0]).getString("type"))
            assertEquals("response.create", JSONObject(fake.sent[1]).getString("type"))
        }
}
