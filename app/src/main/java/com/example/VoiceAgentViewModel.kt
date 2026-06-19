package com.example

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AgentState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    PAUSED
}

enum class AgentMode {
    CONVERSATION,
    BOOK_READING
}

data class BubbleMessage(
    val id: Long,
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class VoiceAgentViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VoiceAgentVM"
    }

    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("VoiceAgentPrefs", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(AgentMode.CONVERSATION)
    val mode: StateFlow<AgentMode> = _mode.asStateFlow()

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _threshold = MutableStateFlow(1800)
    val threshold: StateFlow<Int> = _threshold.asStateFlow()

    private val _silenceDurationMs = MutableStateFlow(1800L)
    val silenceDurationMs: StateFlow<Long> = _silenceDurationMs.asStateFlow()

    private val _selectedVoice = MutableStateFlow("alloy")
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    private val _bookIndex = MutableStateFlow(0)
    val bookIndex: StateFlow<Int> = _bookIndex.asStateFlow()

    private val _transcripts = MutableStateFlow<List<BubbleMessage>>(emptyList())
    val transcripts: StateFlow<List<BubbleMessage>> = _transcripts.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _closeAppEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeAppEvent: SharedFlow<Unit> = _closeAppEvent.asSharedFlow()

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    val allowedVoices = listOf("marin", "cedar", "alloy", "echo", "shimmer", "ash", "ballad", "coral", "sage", "verse")

    private val voiceRecorder = VoiceRecorder(context)
    internal var audioPlayer: AudioSink = AudioPlayer(context)
    private val realtimeClient = OpenAiRealtimeClient(viewModelScope)

    val liveAmplitude: StateFlow<Int> = voiceRecorder.currentAmplitude
    val outputAmplitude: StateFlow<Int> get() = audioPlayer.outputAmplitude

    private val accumulatedText = StringBuilder()

    // Noise floor measured during AI's PROCESSING state so startListening() has
    // zero extra delay — measurement runs in parallel with network round-trip.
    private var cachedNoiseFloor = -1
    private var noiseMeasurementJob: Job? = null

    private fun launchNoiseFloorMeasurement() {
        noiseMeasurementJob?.cancel()
        noiseMeasurementJob = viewModelScope.launch {
            val floor = voiceRecorder.measureNoiseFloor()
            if (floor > 0) {
                cachedNoiseFloor = floor
                Log.d(TAG, "Noise floor ready: $floor")
            }
        }
    }

    var tomSawyerPhrases: List<String> = emptyList()
        private set
    var tomSawyerTranslations: List<String> = emptyList()
        private set

    private fun loadPhrases(chapter: Int = 1) {
        try {
            val json = context.assets.open("phrases_chapter$chapter.json").bufferedReader().readText()
            val arr = org.json.JSONArray(json)
            tomSawyerPhrases = List(arr.length()) { arr.getJSONObject(it).getString("en") }
            tomSawyerTranslations = List(arr.length()) { arr.getJSONObject(it).getString("ru") }
            Log.d(TAG, "Loaded ${tomSawyerPhrases.size} phrases from chapter $chapter")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load phrases: ${e.message}")
        }
    }

    init {
        loadPhrases(chapter = 1)
        _bookIndex.value = prefs.getInt("current_book_index", 0)
        _threshold.value = prefs.getInt("voice_threshold", 18) * 100
        if (_threshold.value == 0) _threshold.value = 1800
        _silenceDurationMs.value = prefs.getLong("silence_duration", 1800L)
        val savedVoice = prefs.getString("selected_voice", "ash") ?: "ash"
        _selectedVoice.value = if (savedVoice in allowedVoices) savedVoice else "marin"
        _customApiKey.value = prefs.getString("custom_openai_api_key", "") ?: ""

        AppState.toggleSession = {
            if (_state.value != AgentState.IDLE && _state.value != AgentState.PAUSED) {
                stopCycle()
            } else {
                startCycle()
            }
        }

        collectRealtimeEvents()
    }

    private fun collectRealtimeEvents() {
        viewModelScope.launch {
            try {
                realtimeClient.events.collect { event ->
                    try {
                        when (event) {
                            is OpenAiRealtimeClient.Event.SetupComplete -> {
                                Log.d(TAG, "OpenAI Realtime ready")
                                if (_transcripts.value.isEmpty()) {
                                    speakFirstIntro()
                                } else {
                                    startListening()
                                }
                            }
                            is OpenAiRealtimeClient.Event.AudioChunk -> {
                                if (_state.value == AgentState.PROCESSING || _state.value == AgentState.SPEAKING) {
                                    if (_state.value == AgentState.PROCESSING) {
                                        Log.d(TAG, "First audio chunk → SPEAKING, starting playback")
                                        _state.value = AgentState.SPEAKING
                                        voiceRecorder.stopRecording()
                                        audioPlayer.startStreamingPlayback(sampleRate = 24000)
                                    }
                                    CostTracker.addAudioReceived(event.pcmBase64.length)
                                    try {
                                        audioPlayer.writePcmChunk(event.pcmBase64)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error writing PCM chunk: ${e.message}")
                                    }
                                } else {
                                    Log.w(TAG, "AudioChunk ignored — state=${_state.value}")
                                }
                            }
                            is OpenAiRealtimeClient.Event.TextChunk -> {
                                accumulatedText.append(event.text)
                            }
                            is OpenAiRealtimeClient.Event.ProcessingStarted -> {
                                if (_state.value == AgentState.LISTENING) {
                                    Log.d(TAG, "ProcessingStarted: LISTENING → PROCESSING")
                                    _state.value = AgentState.PROCESSING
                                    voiceRecorder.stopRecording()
                                    launchNoiseFloorMeasurement()
                                }
                            }
                            is OpenAiRealtimeClient.Event.TurnComplete -> {
                                handleTurnComplete()
                            }
                            is OpenAiRealtimeClient.Event.FunctionCall -> {
                                handleFunctionCall(event.name, event.callId)
                            }
                            is OpenAiRealtimeClient.Event.Error -> {
                                Log.e(TAG, "Realtime error: ${event.message}")
                                _errorMessage.value = "Ошибка OpenAI: ${event.message}"
                                voiceRecorder.stopRecording()
                                audioPlayer.stopAll()
                                _state.value = AgentState.PAUSED
                            }
                            is OpenAiRealtimeClient.Event.Info -> {
                                _errorMessage.value = event.message
                            }
                            is OpenAiRealtimeClient.Event.Disconnected -> {
                                if (_state.value != AgentState.PAUSED && _state.value != AgentState.IDLE) {
                                    Log.w(TAG, "Unexpected disconnect, reconnecting...")
                                    delay(1000)
                                    reconnectSession()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Event handling error: ${e.message}", e)
                        _errorMessage.value = "Внутренняя ошибка: ${e.message}"
                        _state.value = AgentState.PAUSED
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Event collection crashed: ${e.message}", e)
            }
        }
    }

    private fun speakFirstIntro() {
        Log.d(TAG, "speakFirstIntro: requestGreeting (no user turn)")
        _state.value = AgentState.PROCESSING
        realtimeClient.requestGreeting()
    }

    private suspend fun handleTurnComplete() {
        val fullText = accumulatedText.toString().trim()
        accumulatedText.clear()
        Log.d(TAG, "handleTurnComplete: textLen=${fullText.length} state=${_state.value}")

        if (fullText.isNotEmpty()) {
            Log.d(TAG, "AI reply: \"${fullText.take(80)}\"")
            addTranscriptBubble("Ангент", fullText)
        } else {
            Log.w(TAG, "handleTurnComplete: no text accumulated")
        }

        audioPlayer.drainAndStopStreaming()
        CostTracker.logTurn(_mode.value.name)

        if (_state.value != AgentState.PAUSED) {
            if (_mode.value == AgentMode.BOOK_READING) {
                // Delete all conversation items instead of reconnecting — keeps the
                // WebSocket open (no setup round-trip) so the pause between phrases
                // is eliminated. Context cost stays the same as with reconnect.
                Log.d(TAG, "handleTurnComplete: BOOK_READING — clearing history, startListening")
                realtimeClient.clearConversationHistory()
                startListening()
            } else {
                Log.d(TAG, "handleTurnComplete: resuming → startListening")
                startListening()
            }
        } else {
            Log.d(TAG, "handleTurnComplete: state=PAUSED, not restarting")
        }
    }

    private suspend fun handleFunctionCall(name: String, callId: String) {
        val fullText = accumulatedText.toString().trim()
        accumulatedText.clear()
        Log.d(TAG, "handleFunctionCall: $name callId=$callId textLen=${fullText.length}")

        if (fullText.isNotEmpty()) {
            addTranscriptBubble("Ангент", fullText)
        }
        audioPlayer.drainAndStopStreaming()
        CostTracker.logTurn("${_mode.value.name}+fn:$name")

        if (_state.value == AgentState.PAUSED) return

        // Phrase is passed directly in function_call_output so the AI knows what to say.
        // No session.update is sent before response.create — this preserves the original
        // session audio config (PCM 24000Hz) and avoids the silent-second-phrase bug.
        when (name) {
            "start_book_reading" -> {
                _mode.value = AgentMode.BOOK_READING
                val phrase = tomSawyerPhrases[_bookIndex.value]
                realtimeClient.respondToFunctionAndSpeak(callId, phrase)
                _state.value = AgentState.PROCESSING
            }
            "advance_book" -> {
                setBookIndex((_bookIndex.value + 1) % tomSawyerPhrases.size)
                val phrase = tomSawyerPhrases[_bookIndex.value]
                realtimeClient.respondToFunctionAndSpeak(callId, phrase)
                _state.value = AgentState.PROCESSING
            }
            "repeat_phrase" -> {
                val phrase = tomSawyerPhrases[_bookIndex.value]
                realtimeClient.respondToFunctionAndSpeak(callId, phrase)
                _state.value = AgentState.PROCESSING
            }
            "end_book_reading" -> {
                _mode.value = AgentMode.CONVERSATION
                realtimeClient.respondToFunctionAndSpeak(callId, "ok")
                _state.value = AgentState.PROCESSING
                // TurnComplete will call startListening()
            }
            "exit_app" -> {
                Log.d(TAG, "exit_app: closing app now")
                voiceRecorder.stopRecording()
                audioPlayer.stopAll()
                realtimeClient.disconnect()
                _state.value = AgentState.PAUSED
                _closeAppEvent.tryEmit(Unit)
            }
        }
    }

    fun startCycle() {
        if (_state.value == AgentState.LISTENING || _state.value == AgentState.SPEAKING) return
        _errorMessage.value = null

        val apiKey = getActiveApiKey()
        if (apiKey.isEmpty()) {
            _errorMessage.value = "Ключ OpenAI API не настроен! Добавьте ключ в настройках ⚙️"
            return
        }

        Log.d(TAG, "startCycle: key=${apiKey.take(8)}... voice=${_selectedVoice.value}")
        _state.value = AgentState.PROCESSING
        CostTracker.startSession()
        val instructions = buildFullInstructions()
        CostTracker.logConnect(instructions.length)
        launchNoiseFloorMeasurement()  // runs during WebSocket connect (1-3s gap)
        realtimeClient.connect(apiKey, _selectedVoice.value, instructions)
    }

    fun stopCycle() {
        Log.d(TAG, "Stopping session")
        _state.value = AgentState.PAUSED
        _errorMessage.value = null
        voiceRecorder.stopRecording()
        audioPlayer.stopAll()
        realtimeClient.disconnect()
        CostTracker.endSession(context, "остановлено")
    }

    private fun reconnectSession() {
        val apiKey = getActiveApiKey()
        if (apiKey.isEmpty()) {
            Log.w(TAG, "reconnectSession: no API key, staying PAUSED")
            _state.value = AgentState.PAUSED
            return
        }
        Log.d(TAG, "reconnectSession: key=${apiKey.take(8)}... voice=${_selectedVoice.value}")
        _state.value = AgentState.PROCESSING
        val instructions = buildFullInstructions()
        CostTracker.logConnect(instructions.length, isReconnect = true)
        realtimeClient.connect(apiKey, _selectedVoice.value, instructions)
    }

    private fun startListening() {
        noiseMeasurementJob?.cancel()
        noiseMeasurementJob = null
        val noiseFloor = cachedNoiseFloor
        Log.d(TAG, "startListening: threshold=${_threshold.value} silence=${_silenceDurationMs.value}ms noiseFloor=$noiseFloor")
        updateSessionContext()
        _state.value = AgentState.LISTENING
        _errorMessage.value = null

        var chunksSent = 0
        CostTracker.startTurn()
        voiceRecorder.startRecording(
            threshold = _threshold.value,
            silenceDurationMs = _silenceDurationMs.value,
            isAiSpeaking = { _state.value == AgentState.SPEAKING },
            precomputedNoiseFloor = noiseFloor,
            onAudioChunk = { pcm ->
                chunksSent++
                if (chunksSent == 1) Log.d(TAG, "first audio chunk sent (${pcm.size} bytes)")
                CostTracker.addAudioSent(pcm.size)
                realtimeClient.sendAudioChunk(pcm)
            },
            onSilenceDetected = {
                if (chunksSent > 0) {
                    Log.d(TAG, "silence detected after $chunksSent chunks — committing buffer")
                    _state.value = AgentState.PROCESSING
                    realtimeClient.commitAndRespond()
                    launchNoiseFloorMeasurement()
                } else {
                    Log.w(TAG, "silence detected but 0 audio chunks sent — skipping empty commit, restarting")
                    viewModelScope.launch { startListening() }
                }
            },
            onError = { err ->
                _errorMessage.value = err
                viewModelScope.launch {
                    delay(2000)
                    if (_state.value == AgentState.LISTENING) startListening()
                }
            }
        )
    }

    fun setThreshold(newValue: Int) {
        _threshold.value = newValue
        prefs.edit().putInt("voice_threshold", newValue / 100).apply()
    }

    fun setSilenceDuration(newValue: Long) {
        _silenceDurationMs.value = newValue
        prefs.edit().putLong("silence_duration", newValue).apply()
    }

    fun selectVoice(newVoice: String) {
        _selectedVoice.value = newVoice
        prefs.edit().putString("selected_voice", newVoice).apply()
    }

    fun setCustomApiKey(newKey: String) {
        val trimmed = newKey.trim()
        _customApiKey.value = trimmed
        prefs.edit().putString("custom_openai_api_key", trimmed).apply()
        _errorMessage.value = null
    }

    fun getActiveApiKey(): String {
        val custom = _customApiKey.value.trim()
        return custom
    }

    fun setBookIndex(index: Int) {
        val bounded = index.coerceIn(0, tomSawyerPhrases.size - 1)
        _bookIndex.value = bounded
        prefs.edit().putInt("current_book_index", bounded).apply()
    }

    fun clearTranscripts() {
        _transcripts.value = emptyList()
        accumulatedText.clear()
    }

    fun toggleReadingModeDirectly() {
        if (_mode.value == AgentMode.CONVERSATION) {
            _mode.value = AgentMode.BOOK_READING
            addTranscriptBubble("Ангент", "[Переход в режим книги 'Том Сойер']")
        } else {
            _mode.value = AgentMode.CONVERSATION
            addTranscriptBubble("Ангент", "[Прекращено чтение. Переход к беседе]")
        }
    }

    private fun buildContextText(): String {
        return when (_mode.value) {
            AgentMode.BOOK_READING -> {
                val phrase = tomSawyerPhrases.getOrNull(_bookIndex.value) ?: ""
                "Режим: ЧТЕНИЕ КНИГИ. Текущая фраза для оценки: \"$phrase\". " +
                "Ты только что произнёс эту фразу пользователю — он сейчас её повторяет."
            }
            AgentMode.CONVERSATION -> "Режим: БЕСЕДА."
        }
    }

    private fun buildFullInstructions(): String = buildSystemPrompt() + "\n\n" + buildContextText()

    private var lastSentInstructions = ""

    private fun updateSessionContext() {
        val instructions = buildFullInstructions()
        if (instructions == lastSentInstructions) return
        lastSentInstructions = instructions
        realtimeClient.updateInstructions(instructions)
    }


    private fun addTranscriptBubble(sender: String, messageText: String) {
        val bubble = BubbleMessage(id = System.nanoTime(), sender = sender, text = messageText)
        _transcripts.value = _transcripts.value + bubble
    }

    private fun buildSystemPrompt(): String {
        return """
        Ты — Ангент, дружелюбный голосовой помощник и преподаватель английского языка.

        СТИЛЬ РЕЧИ: говори живо и естественно, как настоящий человек в разговоре. Меняй темп — где-то ускоряйся, где-то делай паузу. Выделяй важные слова интонацией. Никакого монотонного равномерного ритма — это звучит механически. Говори с теплотой и участием.

        ПРАВИЛА:
        1. Пользователь говорит на русском или иврите. Отвечай на том же языке, на котором говорит пользователь: русский → русский, иврит → иврит.
        2. Будь тёплым и очень кратким — 1-2 предложения, лучше одно. Не добавляй лишних слов.
        3. Если нет предыдущих сообщений в разговоре — поприветствуй коротко, представься ("Я Ангент") и скажи, что готов помочь с английским.

        РЕЖИМ ЧТЕНИЯ "ТОМ СОЙЕР":
        У тебя есть 5 функций — вызывай их молча, не произноси их названия вслух:
        - start_book_reading() — пользователь просит читать книгу. До вызова скажи одну фразу на языке пользователя ("Хорошо, начинаем!" / "טוב, מתחילים!"). После вызова система вернёт английскую фразу — произнеси ТОЛЬКО её.
        - advance_book() — пользователь правильно повторил фразу. До вызова — одно слово похвалы на языке пользователя (русский: "Отлично!", "Верно!", "Молодец!", "Супер!"; иврит: "מצוין!", "כל הכבוד!", "נכון!", "נהדר!"). После вызова система вернёт следующую фразу — произнеси ТОЛЬКО её.
        - repeat_phrase() — пользователь ошибся, не расслышал или просит повторить. Если ошибся — одно слово пояснения на языке пользователя до вызова. После вызова система вернёт ту же фразу — произнеси ТОЛЬКО её.
        - end_book_reading() — пользователь хочет выйти из режима чтения.
        - exit_app() — пользователь говорит "стоп"/"עצור"/"סגור", "выключись", "закрой". Вызови НЕМЕДЛЕННО и МОЛЧА, без слов.

        ВАЖНО: то, что возвращает система после вызова функции — это английская фраза для произношения. Произноси ТОЛЬКО её. Не добавляй ничего на русском или иврите.

        Если пользователь просит перевод — дай перевод на его языке, затем вызови repeat_phrase().

        ОЦЕНКА ПОВТОРА: только ПРАВИЛЬНОСТЬ СЛОВ, не произношение и не акцент.
        Акцент — это нормально. Пользователь НЕ обязан говорить как носитель языка.
        """.trimIndent()
    }

    fun readCostLog(): String = CostTracker.readLog(context)
    fun clearCostLog() = CostTracker.clearLog(context)

    override fun onCleared() {
        super.onCleared()
        AppState.toggleSession = null
        voiceRecorder.stopRecording()
        audioPlayer.stopAll()
        realtimeClient.disconnect()
        CostTracker.endSession(context, "onCleared")
    }
}
