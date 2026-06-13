package com.example

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    val allowedVoices = listOf("marin", "cedar", "alloy", "echo", "shimmer", "ash", "ballad", "coral", "sage", "verse")

    private val voiceRecorder = VoiceRecorder(context)
    private val audioPlayer = AudioPlayer(context)
    private val realtimeClient = OpenAiRealtimeClient(viewModelScope)

    val liveAmplitude: StateFlow<Int> = voiceRecorder.currentAmplitude

    private val accumulatedText = StringBuilder()

    val tomSawyerPhrases = listOf(
        "Tom!", "No answer.", "Tom!", "No answer.",
        "What's gone with that boy,", "I wonder?", "You Tom!", "No answer.",
        "The old lady pulled", "her spectacles down", "and looked over them",
        "into the room;", "then she put them up", "and looked out under them.",
        "She seldom or never", "looked through them", "for so small a thing",
        "as a boy;", "they were her state spectacles,", "the pride of her heart,",
        "and were built for style,", "not service—", "she could have seen through",
        "a pair of stove-lids", "just as well.", "She looked perplexed",
        "for a moment,", "and then said,", "not loudly,", "but still loud enough",
        "for the furniture to hear:", "Well,", "I lay,", "if I get hold of you",
        "I'll—", "She did not finish,", "for by this time", "she was bending down",
        "and punching under the bed", "with the broom,", "and so she needed breath",
        "to punctuate the punches with.", "She resurrected nothing", "but the cat.",
        "I never did see", "the beat of that boy!"
    )

    val tomSawyerTranslations = listOf(
        "Том!", "Никакого ответа.", "Том!", "Никакого ответа.",
        "Что же стряслось с этим мальчишкой,", "я хотела бы знать?", "А ну-ка, Том!", "Никакого ответа.",
        "Старая леди приспустила", "свои очки пониже", "и посмотрела поверх них",
        "в комнату;", "затем она водрузила их наверх", "и выглянула из-под них.",
        "Она редко или никогда", "не смотрела сквозь них", "в поисках такой малости,",
        "как мальчишка;", "это были ее парадные очки,", "гордость ее сердца,",
        "и были созданы для стиля,", "а не для практической пользы —", "она могла бы видеть сквозь",
        "пару чугунных заслонок", "точно так же хорошо.", "Она выглядела озадаченной",
        "в течение мгновения,", "а затем произнесла,", "не то чтобы громко,", "но все же достаточно громко",
        "для того, чтобы мебель услышала:", "Ну уж,", "я клянусь,", "если я доберусь до тебя,",
        "я тебе задам—", "Она не закончила,", "потому что к этому времени", "она уже нагибалась,",
        "тыча под кроватью", "шваброй,", "и поэтому ей нужна была передышка,",
        "чтобы пунктировать удары.", "Она откопала из-под кровати", "только кота.",
        "В жизни своей не видывала", "такого проказника!"
    )

    init {
        _bookIndex.value = prefs.getInt("current_book_index", 0)
        _threshold.value = prefs.getInt("voice_threshold", 18) * 100
        if (_threshold.value == 0) _threshold.value = 1800
        _silenceDurationMs.value = prefs.getLong("silence_duration", 1800L)
        val savedVoice = prefs.getString("selected_voice", "ballad") ?: "ballad"
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
                            is OpenAiRealtimeClient.Event.TurnComplete -> {
                                handleTurnComplete()
                            }
                            is OpenAiRealtimeClient.Event.FunctionCall -> {
                                handleFunctionCall(event.name)
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
            addTranscriptBubble("Дональд", fullText)
        } else {
            Log.w(TAG, "handleTurnComplete: no text accumulated")
        }

        audioPlayer.drainAndStopStreaming()

        if (_state.value != AgentState.PAUSED) {
            Log.d(TAG, "handleTurnComplete: resuming → startListening")
            startListening()
        } else {
            Log.d(TAG, "handleTurnComplete: state=PAUSED, not restarting")
        }
    }

    private suspend fun handleFunctionCall(name: String) {
        val fullText = accumulatedText.toString().trim()
        accumulatedText.clear()
        Log.d(TAG, "handleFunctionCall: $name textLen=${fullText.length}")

        if (fullText.isNotEmpty()) {
            addTranscriptBubble("Дональд", fullText)
        }
        audioPlayer.drainAndStopStreaming()

        if (_state.value == AgentState.PAUSED) return

        when (name) {
            "start_book_reading" -> {
                _mode.value = AgentMode.BOOK_READING
                speakBookPhrase()
            }
            "advance_book" -> {
                setBookIndex((_bookIndex.value + 1) % tomSawyerPhrases.size)
                speakBookPhrase()
            }
            "repeat_phrase" -> {
                speakBookPhrase()
            }
            "end_book_reading" -> {
                _mode.value = AgentMode.CONVERSATION
                startListening()
            }
        }
    }

    private fun speakBookPhrase() {
        Log.d(TAG, "speakBookPhrase: phrase ${_bookIndex.value + 1}/${tomSawyerPhrases.size}")
        updateSessionContext()
        _state.value = AgentState.PROCESSING
        realtimeClient.requestGreeting()
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
        realtimeClient.connect(apiKey, _selectedVoice.value, buildFullInstructions())
    }

    fun stopCycle() {
        Log.d(TAG, "Stopping session")
        _state.value = AgentState.PAUSED
        _errorMessage.value = null
        voiceRecorder.stopRecording()
        audioPlayer.stopAll()
        realtimeClient.disconnect()
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
        realtimeClient.connect(apiKey, _selectedVoice.value, buildFullInstructions())
    }

    private fun startListening() {
        Log.d(TAG, "startListening: threshold=${_threshold.value} silence=${_silenceDurationMs.value}ms")
        updateSessionContext()
        _state.value = AgentState.LISTENING
        _errorMessage.value = null

        var chunksSent = 0
        voiceRecorder.startRecording(
            threshold = _threshold.value,
            silenceDurationMs = _silenceDurationMs.value,
            onAudioChunk = { pcm ->
                chunksSent++
                if (chunksSent == 1) Log.d(TAG, "first audio chunk sent (${pcm.size} bytes)")
                realtimeClient.sendAudioChunk(pcm)
            },
            onSilenceDetected = {
                if (chunksSent > 0) {
                    Log.d(TAG, "silence detected after $chunksSent chunks — committing buffer")
                    _state.value = AgentState.PROCESSING
                    realtimeClient.commitAndRespond()
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
            addTranscriptBubble("Дональд", "[Переход в режим книги 'Том Сойер']")
        } else {
            _mode.value = AgentMode.CONVERSATION
            addTranscriptBubble("Дональд", "[Прекращено чтение. Переход к беседе]")
        }
    }

    private fun buildContextText(): String {
        return when (_mode.value) {
            AgentMode.BOOK_READING ->
                "Режим: ЧТЕНИЕ КНИГИ. Текущая фраза №${_bookIndex.value + 1}: " +
                "\"${tomSawyerPhrases[_bookIndex.value]}\" (по-русски: \"${tomSawyerTranslations[_bookIndex.value]}\")."
            AgentMode.CONVERSATION ->
                "Режим: БЕСЕДА."
        }
    }

    private fun buildFullInstructions(): String = buildSystemPrompt() + "\n\n" + buildContextText()

    private fun updateSessionContext() {
        realtimeClient.updateInstructions(buildFullInstructions())
    }


    private fun addTranscriptBubble(sender: String, messageText: String) {
        val bubble = BubbleMessage(id = System.nanoTime(), sender = sender, text = messageText)
        _transcripts.value = _transcripts.value + bubble
    }

    private fun buildSystemPrompt(): String {
        return """
        Ты — Дональд, дружелюбный голосовой помощник и преподаватель английского языка.

        ПРАВИЛА:
        1. Пользователь говорит на русском или иврите. Отвечай только на русском языке.
        2. Будь тёплым и кратким — 1-3 предложения, не больше.
        3. Если нет предыдущих сообщений в разговоре — поприветствуй коротко и скажи, что готов помочь с английским.

        РЕЖИМ ЧТЕНИЯ "ТОМ СОЙЕР":
        У тебя есть 4 функции для управления чтением — вызывай их молча, не произноси их названия вслух:
        - start_book_reading() — когда пользователь просит читать книгу. Скажи "Хорошо, начинаем!" и вызови.
        - advance_book() — когда пользователь правильно повторил фразу. Похвали коротко (каждый раз по-разному: "Отлично!", "Верно!", "Молодец!", "Хорошо!", "Правильно!", "Супер!" и т.п.) и вызови. Следующую фразу НЕ называй — система прочитает сама.
        - repeat_phrase() — вызови в этих случаях: (1) пользователь ошибся в словах — объясни ошибку кратко; (2) пользователь не расслышал или просит повторить — просто вызови без комментариев; (3) после перевода фразы — чтобы пользователь попробовал снова. Фразу НЕ произноси — система прочитает сама.
        - end_book_reading() — когда пользователь хочет выйти из режима чтения.

        Когда система запрашивает произнести фразу (ты получаешь пустой запрос в режиме ЧТЕНИЯ КНИГИ):
        - Произнеси ТОЛЬКО текущую английскую фразу из контекста, без предисловий.

        Если пользователь просит перевод ("что это значит?", "переведи" и т.п.):
        - Дай перевод на русском (он есть в контексте), затем вызови repeat_phrase() чтобы система повторила фразу.

        ОЦЕНКА ПОВТОРА: только ПРАВИЛЬНОСТЬ СЛОВ, не произношение и не акцент.
        Акцент — это нормально. Пользователь НЕ обязан говорить как носитель языка.
        """.trimIndent()
    }

    override fun onCleared() {
        super.onCleared()
        AppState.toggleSession = null
        voiceRecorder.stopRecording()
        audioPlayer.stopAll()
        realtimeClient.disconnect()
    }
}
