package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * Unit tests for VoiceAgentViewModel that catch the "SetupComplete loop" bug.
 *
 * THE BUG:
 *   Every `session.updated` from the OpenAI API triggered:
 *     SetupComplete event → startListening() → updateInstructions()
 *     → server echoes session.updated → another SetupComplete event
 *     → startListening() → ... (infinite loop)
 *
 * THE FIX:
 *   `setupCompletedEmitted` flag in OpenAiRealtimeClient. Once set to true after
 *   the first session.updated, subsequent session.updated messages do not re-emit
 *   SetupComplete.
 *
 * TEST STRATEGY:
 *   VoiceAgentViewModel constructs OpenAiRealtimeClient internally (not injected).
 *   We retrieve the client's private `_events: MutableSharedFlow` via reflection —
 *   this is the same object the ViewModel's collector subscribed to — and emit
 *   test events into it directly.
 *
 *   Key no-op guarantee: updateInstructions(), requestGreeting(), sendAudioChunk(),
 *   and commitAndRespond() all guard on `if (!ready) return`. A freshly constructed
 *   client (that never called connect()) has ready=false, so the entire chain
 *   startListening() → updateInstructions() → (nothing) is safe in tests.
 *
 *   Time is controlled with advanceTimeBy(100) rather than advanceUntilIdle().
 *   This processes all pending zero-delay work (the event handler, state updates)
 *   without advancing past the 2000 ms error-retry delay in VoiceRecorder.onError.
 *   That retry would cause an infinite test loop because Robolectric's AudioRecord
 *   always returns ERROR in unit tests.
 *
 *   All behavior is observed through `vm.state: StateFlow<AgentState>`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceAgentViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // Install test dispatcher as Main so viewModelScope coroutines run eagerly
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    private fun field(clazz: Class<*>, name: String): Field =
        clazz.getDeclaredField(name).also { it.isAccessible = true }

    /**
     * Builds a VoiceAgentViewModel and returns it with the internal MutableSharedFlow
     * that the ViewModel's event-collection coroutine already subscribed to.
     *
     * Emit into the returned flow to simulate OpenAI server events.
     */
    private fun buildVmAndFlow(): Pair<VoiceAgentViewModel, MutableSharedFlow<OpenAiRealtimeClient.Event>> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = VoiceAgentViewModel(app)

        val client = field(VoiceAgentViewModel::class.java, "realtimeClient")
            .get(vm) as OpenAiRealtimeClient

        @Suppress("UNCHECKED_CAST")
        val eventsFlow = field(OpenAiRealtimeClient::class.java, "_events")
            .get(client) as MutableSharedFlow<OpenAiRealtimeClient.Event>

        return vm to eventsFlow
    }

    /**
     * Seeds the ViewModel's transcript list so the SetupComplete handler takes
     * the startListening() branch (not speakFirstIntro()).
     */
    private fun seedTranscripts(vm: VoiceAgentViewModel) {
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field(VoiceAgentViewModel::class.java, "_transcripts")
            .get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<BubbleMessage>>
        stateFlow.value = listOf(BubbleMessage(id = 1L, sender = "User", text = "Привет"))
    }

    // -------------------------------------------------------------------------
    // Test 1 — SetupComplete with prior transcripts → LISTENING exactly once
    // -------------------------------------------------------------------------

    /**
     * One SetupComplete event with prior transcripts should set state to LISTENING
     * exactly once and no more.
     *
     * If the loop bug existed, repeated LISTENING transitions would appear because
     * each startListening() call would cause another SetupComplete (via
     * updateInstructions → session.updated). In the fixed code, updateInstructions()
     * is also a no-op here (ready=false), doubly confirming no loop can form.
     */
    @Test
    fun `SetupComplete with prior transcripts sets state to LISTENING exactly once`() =
        runTest(testDispatcher) {
            val (vm, flow) = buildVmAndFlow()
            seedTranscripts(vm)

            val observed = mutableListOf<AgentState>()
            val job = launch { vm.state.collect { observed.add(it) } }

            flow.emit(OpenAiRealtimeClient.Event.SetupComplete)
            // Advance enough to process event handler and state mutations, but NOT
            // past the 2000 ms error-retry delay in VoiceRecorder.onError.
            advanceTimeBy(100)

            job.cancel()

            assert(AgentState.LISTENING in observed) {
                "Expected state to reach LISTENING after SetupComplete+transcripts. " +
                    "Observed sequence: $observed"
            }

            val count = observed.count { it == AgentState.LISTENING }
            assertEquals(
                "LISTENING should appear exactly once — got $count (loop bug?). " +
                    "Full sequence: $observed",
                1,
                count
            )
        }

    // -------------------------------------------------------------------------
    // Test 2 — Two SetupComplete events → at most 2 LISTENING transitions
    // -------------------------------------------------------------------------

    /**
     * Two explicit SetupComplete events should produce at most 2 LISTENING transitions.
     *
     * This directly models the regression: if the `setupCompletedEmitted` flag were
     * removed, each startListening() call would trigger updateInstructions() →
     * session.updated → another SetupComplete → startListening() → ...
     *
     * Here updateInstructions() is a no-op (ready=false), so the test focus is on
     * whether the ViewModel itself contributes to extra state transitions when a
     * second SetupComplete arrives. LISTENING count must stay bounded (≤ 2 for 2 events).
     * An unbounded loop would cause counts in the dozens or more.
     */
    @Test
    fun `second SetupComplete does not cause unbounded LISTENING state churn`() =
        runTest(testDispatcher) {
            val (vm, flow) = buildVmAndFlow()
            seedTranscripts(vm)

            val observed = mutableListOf<AgentState>()
            val job = launch { vm.state.collect { observed.add(it) } }

            // First SetupComplete — legitimate initial setup
            flow.emit(OpenAiRealtimeClient.Event.SetupComplete)
            advanceTimeBy(100)

            // Second SetupComplete — simulates the loop bug leaking another event
            // (in production this is prevented by setupCompletedEmitted flag)
            flow.emit(OpenAiRealtimeClient.Event.SetupComplete)
            advanceTimeBy(100)

            job.cancel()

            val count = observed.count { it == AgentState.LISTENING }

            assert(count >= 1) {
                "Expected at least one LISTENING transition. Got: $observed"
            }
            assert(count <= 2) {
                "LISTENING entered $count times for only 2 explicit SetupComplete events. " +
                    "The ViewModel itself is amplifying SetupComplete events! " +
                    "Full state sequence: $observed"
            }
        }

    // -------------------------------------------------------------------------
    // Test 3 — Empty transcripts → PROCESSING (speakFirstIntro path)
    // -------------------------------------------------------------------------

    /**
     * When transcripts are empty (fresh first session), SetupComplete must take the
     * speakFirstIntro() branch: state = PROCESSING, requestGreeting() called (no-op).
     * State must NOT enter LISTENING.
     *
     * Verifies the branch guard in collectRealtimeEvents():
     *   if (_transcripts.value.isEmpty()) speakFirstIntro() else startListening()
     */
    @Test
    fun `SetupComplete with empty transcripts goes to PROCESSING not LISTENING`() =
        runTest(testDispatcher) {
            val (vm, flow) = buildVmAndFlow()
            // Do NOT seed transcripts — fresh first session

            val observed = mutableListOf<AgentState>()
            val job = launch { vm.state.collect { observed.add(it) } }

            flow.emit(OpenAiRealtimeClient.Event.SetupComplete)
            advanceTimeBy(100)
            job.cancel()

            assert(AgentState.PROCESSING in observed) {
                "Expected PROCESSING from speakFirstIntro() with empty transcripts. " +
                    "Got: $observed"
            }
            assert(AgentState.LISTENING !in observed) {
                "LISTENING appeared with empty transcripts — wrong branch was taken. " +
                    "Got: $observed"
            }
            assertEquals(
                "Final state should be PROCESSING (requestGreeting is no-op since ready=false). " +
                    "Observed sequence: $observed",
                AgentState.PROCESSING,
                vm.state.value
            )
        }

    // -------------------------------------------------------------------------
    // Test 4a — advance_book function call → book index increments and state = PROCESSING
    // -------------------------------------------------------------------------

    /**
     * When the AI calls advance_book(), the ViewModel must:
     *  1. Increment the book phrase index
     *  2. Call speakBookPhrase() which sets state = PROCESSING
     *
     * Regression: if the second phrase is shown as text but NOT spoken, it means
     * speakBookPhrase() wasn't called (state stayed LISTENING/SPEAKING), or
     * the audio format was lost. This test verifies the state machine side.
     *
     * Audio format correctness (updateInstructions must include audio/pcm config)
     * is tested separately; here we only verify the index+state contract.
     */
    @Test
    fun `advance_book FunctionCall increments book index and sets PROCESSING`() =
        runTest(testDispatcher) {
            val (vm, flow) = buildVmAndFlow()
            seedTranscripts(vm)

            // Bring ViewModel to LISTENING state first
            flow.emit(OpenAiRealtimeClient.Event.SetupComplete)
            advanceTimeBy(100)
            assertEquals("Should be LISTENING after SetupComplete", AgentState.LISTENING, vm.state.value)

            val indexBefore = vm.bookIndex.value

            // Simulate AI calling advance_book after user repeated the phrase correctly
            flow.emit(OpenAiRealtimeClient.Event.FunctionCall("advance_book", "call_test_id"))
            advanceTimeBy(100)

            assertEquals(
                "Book index must advance by 1 after advance_book",
                (indexBefore + 1) % vm.tomSawyerPhrases.size,
                vm.bookIndex.value
            )
            assertEquals(
                "State must be PROCESSING after advance_book (speakBookPhrase was called)",
                AgentState.PROCESSING,
                vm.state.value
            )
        }

    // -------------------------------------------------------------------------
    // Test 4b — repeat_phrase function call → book index unchanged and state = PROCESSING
    // -------------------------------------------------------------------------

    /**
     * When the AI calls repeat_phrase(), the phrase index must NOT change, but
     * speakBookPhrase() must be called (state = PROCESSING).
     */
    @Test
    fun `repeat_phrase FunctionCall keeps book index and sets PROCESSING`() =
        runTest(testDispatcher) {
            val (vm, flow) = buildVmAndFlow()
            seedTranscripts(vm)

            flow.emit(OpenAiRealtimeClient.Event.SetupComplete)
            advanceTimeBy(100)

            val indexBefore = vm.bookIndex.value

            flow.emit(OpenAiRealtimeClient.Event.FunctionCall("repeat_phrase", "call_test_id"))
            advanceTimeBy(100)

            assertEquals("Book index must NOT change after repeat_phrase", indexBefore, vm.bookIndex.value)
            assertEquals(
                "State must be PROCESSING after repeat_phrase (speakBookPhrase was called)",
                AgentState.PROCESSING,
                vm.state.value
            )
        }

    // -------------------------------------------------------------------------
    // Test 5 — White-box: setupCompletedEmitted flag in OpenAiRealtimeClient
    // -------------------------------------------------------------------------

    /**
     * Drives OpenAiRealtimeClient.parseMessage() directly via reflection to simulate
     * three consecutive `session.updated` messages arriving from the server.
     *
     * Expected: only the FIRST emits SetupComplete. The next two are silently ignored
     * because `setupCompletedEmitted` is true after the first emission.
     *
     * Regression detection: removing the flag causes this test to fail with count=3.
     * This test directly validates the bug fix at the source (OpenAiRealtimeClient)
     * rather than through the ViewModel.
     */
    @Test
    fun `OpenAiRealtimeClient emits SetupComplete only once per connection for multiple session_updated`() =
        runTest(testDispatcher) {
            val client = OpenAiRealtimeClient(this)

            val receivedEvents = mutableListOf<OpenAiRealtimeClient.Event>()
            val collectJob = launch { client.events.collect { receivedEvents.add(it) } }

            val parseMessage = OpenAiRealtimeClient::class.java
                .getDeclaredMethod("parseMessage", String::class.java)
                .also { it.isAccessible = true }

            // Minimal JSON matching what the OpenAI server sends for session.updated
            val sessionUpdatedJson =
                """{"type":"session.updated","session":{"audio":{"output":{"voice":"marin"}}}}"""

            // 1st: initial setup response → must emit SetupComplete
            parseMessage.invoke(client, sessionUpdatedJson)
            advanceTimeBy(100)

            // 2nd: echoed by updateInstructions() call → must NOT emit SetupComplete
            parseMessage.invoke(client, sessionUpdatedJson)
            advanceTimeBy(100)

            // 3rd: additional update → must still be silent
            parseMessage.invoke(client, sessionUpdatedJson)
            advanceTimeBy(100)

            collectJob.cancel()

            val count = receivedEvents.count { it is OpenAiRealtimeClient.Event.SetupComplete }

            assertEquals(
                "Expected exactly 1 SetupComplete for 3 session.updated messages. " +
                    "Got $count — the `setupCompletedEmitted` flag is missing or broken! " +
                    "All events received: $receivedEvents",
                1,
                count
            )
        }

    // -------------------------------------------------------------------------
    // Test 9 — BOOK_READING mode: TurnComplete reconnects instead of startListening
    // -------------------------------------------------------------------------

    /**
     * In BOOK_READING mode, TurnComplete must reconnect (state = PROCESSING)
     * rather than startListening (state = LISTENING).
     *
     * This keeps the per-phrase context minimal: each new WebSocket session
     * starts with only the system prompt + current phrase, preventing the
     * conversation history from growing across all 629 phrases.
     *
     * Without this fix: input token cost grows quadratically with phrase count
     * (~10M tokens for chapter 1 vs ~345K with reconnect).
     */
    @Test
    fun `TurnComplete in BOOK_READING mode reconnects instead of startListening`() =
        runTest(testDispatcher) {
            val (vm, flow) = buildVmAndFlow()
            seedTranscripts(vm)
            vm.toggleReadingModeDirectly() // switch to BOOK_READING

            flow.emit(OpenAiRealtimeClient.Event.SetupComplete)
            advanceTimeBy(100)
            assertEquals("Should be LISTENING after SetupComplete", AgentState.LISTENING, vm.state.value)

            // Record states only after TurnComplete fires
            val observedAfter = mutableListOf<AgentState>()
            val job = launch { vm.state.collect { observedAfter.add(it) } }

            flow.emit(OpenAiRealtimeClient.Event.TurnComplete)
            advanceTimeBy(500) // covers the 200ms reconnect delay

            job.cancel()

            // StateFlow emits its current value (LISTENING) immediately to new collectors,
            // so LISTENING appears first in observedAfter. What matters is that the state
            // then transitioned OUT of LISTENING via the reconnect path — never back into it.
            // In tests there is no API key, so reconnectSession() ends in PAUSED.
            // In production it reaches PROCESSING then opens a new WebSocket.
            val reconnectPathTaken = AgentState.PROCESSING in observedAfter || AgentState.PAUSED in observedAfter
            assert(reconnectPathTaken) {
                "Reconnect path (PROCESSING or PAUSED) must be taken after TurnComplete in BOOK_READING. " +
                "Got states: $observedAfter"
            }
            assertEquals(
                "Final state must not be LISTENING — reconnect path was not taken. Got: $observedAfter",
                false,
                observedAfter.last() == AgentState.LISTENING
            )
        }

    // =========================================================================
    // FakeAudioSink + audio pipeline tests
    // =========================================================================

    /**
     * A fake AudioSink that records every call made to it.
     * Injected into VoiceAgentViewModel via reflection to observe audio behaviour
     * without needing a real AudioTrack (which fails in unit tests).
     */
    class FakeAudioSink : AudioSink {
        var startStreamingCalls = 0
        val chunksWritten = mutableListOf<String>()
        var drainCalls = 0
        var stopAllCalls = 0

        override fun startStreamingPlayback(sampleRate: Int) { startStreamingCalls++ }
        override fun writePcmChunk(base64Pcm: String) { chunksWritten.add(base64Pcm) }
        override suspend fun drainAndStopStreaming() { drainCalls++ }
        override fun stopStreaming() {}
        override fun stopAll() { stopAllCalls++ }
    }

    /** Builds a VM+flow and injects a FakeAudioSink, returning all three. */
    private fun buildVmFlowAndAudio(): Triple<VoiceAgentViewModel, MutableSharedFlow<OpenAiRealtimeClient.Event>, FakeAudioSink> {
        val (vm, flow) = buildVmAndFlow()
        val fake = FakeAudioSink()
        field(VoiceAgentViewModel::class.java, "audioPlayer").set(vm, fake)
        return Triple(vm, flow, fake)
    }

    // -------------------------------------------------------------------------
    // Test 6 — advance_book + AudioChunk → audio starts and data is written
    // -------------------------------------------------------------------------

    /**
     * THE CORE REGRESSION TEST for the silent-second-phrase bug.
     *
     * After advance_book the ViewModel must:
     *  1. Leave state = PROCESSING (so chunks are accepted)
     *  2. On first AudioChunk → call startStreamingPlayback() to open a new track
     *  3. On every AudioChunk → call writePcmChunk() with the data
     *
     * If any of these fail the phrase is silent (user sees text, hears nothing).
     * Previously this was untestable because AudioPlayer was not injectable.
     */
    @Test
    fun `advance_book then AudioChunk triggers startStreamingPlayback and writePcmChunk`() =
        runTest(testDispatcher) {
            val (vm, flow, audio) = buildVmFlowAndAudio()
            seedTranscripts(vm)

            flow.emit(OpenAiRealtimeClient.Event.SetupComplete)
            advanceTimeBy(100)

            // Simulate AI praising + calling advance_book
            flow.emit(OpenAiRealtimeClient.Event.FunctionCall("advance_book", "call_1"))
            advanceTimeBy(100)

            assertEquals(
                "State must be PROCESSING before chunks arrive so they are not ignored",
                AgentState.PROCESSING, vm.state.value
            )

            // First audio chunk for the second phrase
            flow.emit(OpenAiRealtimeClient.Event.AudioChunk("AAEC"))
            advanceTimeBy(100)

            assertEquals(
                "startStreamingPlayback must be called exactly once on the first chunk",
                1, audio.startStreamingCalls
            )
            assertEquals(
                "writePcmChunk must be called with the chunk data",
                1, audio.chunksWritten.size
            )
            assertEquals("AAEC", audio.chunksWritten[0])
        }

    // -------------------------------------------------------------------------
    // Test 7 — AudioChunk while LISTENING is silently ignored
    // -------------------------------------------------------------------------

    /**
     * Stale audio chunks that arrive while the ViewModel is listening (e.g. from
     * a previous response that crossed the boundary) must be dropped silently.
     * startStreamingPlayback must NOT be called.
     */
    @Test
    fun `AudioChunk while LISTENING is ignored and does not start playback`() =
        runTest(testDispatcher) {
            val (vm, flow, audio) = buildVmFlowAndAudio()
            seedTranscripts(vm)

            flow.emit(OpenAiRealtimeClient.Event.SetupComplete)
            advanceTimeBy(100)
            assertEquals(AgentState.LISTENING, vm.state.value)

            // Chunk arrives while we are listening (should be ignored)
            flow.emit(OpenAiRealtimeClient.Event.AudioChunk("AAEC"))
            advanceTimeBy(100)

            assertEquals(
                "startStreamingPlayback must NOT be called when state is LISTENING",
                0, audio.startStreamingCalls
            )
        }

    // -------------------------------------------------------------------------
    // Test 8 — drainAndStopStreaming is called before second phrase
    // -------------------------------------------------------------------------

    /**
     * When advance_book fires, the ViewModel must drain the previous audio before
     * starting the new phrase. Without draining, the tail of "Отлично!" would be
     * cut off (issue 1) and the new phrase might start before the old one finishes.
     */
    @Test
    fun `advance_book drains previous audio before starting new phrase`() =
        runTest(testDispatcher) {
            val (vm, flow, audio) = buildVmFlowAndAudio()
            seedTranscripts(vm)

            flow.emit(OpenAiRealtimeClient.Event.SetupComplete)
            advanceTimeBy(100)

            // advance_book fires — drain must happen regardless of whether
            // audio was actively playing (FakeAudioSink always records the call)
            flow.emit(OpenAiRealtimeClient.Event.FunctionCall("advance_book", "call_2"))
            advanceTimeBy(100)

            assertEquals(
                "drainAndStopStreaming must be called once when advance_book fires " +
                    "(ensures previous audio finishes before starting next phrase)",
                1, audio.drainCalls
            )
            assertEquals(
                "State must be PROCESSING after drain, ready to accept new audio chunks",
                AgentState.PROCESSING, vm.state.value
            )
        }
}
