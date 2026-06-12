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
        val savedVoice = prefs.getString("selected_voice", "marin") ?: "marin"
        _selectedVoice.value = if (savedVoice in allowedVoices) savedVoice else "marin"
        _customApiKey.value = prefs.getString("custom_openai_api_key", "") ?: ""

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
        Log.d(TAG, "speakFirstIntro: sending greeting request")
        _state.value = AgentState.PROCESSING
        realtimeClient.sendText("Поприветствуй меня коротко и скажи что готов помочь с английским.")
    }

    private suspend fun handleTurnComplete() {
        val fullText = accumulatedText.toString().trim()
        accumulatedText.clear()

        val hasAdvance = fullText.contains("[ADVANCE_BOOK]")
        val hasRepeat = fullText.contains("[REPEAT_BOOK]")
        val hasSwitch = fullText.contains("[SWITCH_TO_CONVERSATION]") || fullText.contains("[SWITCH_TO_READING]")
        Log.d(TAG, "handleTurnComplete: textLen=${fullText.length} state=${_state.value} " +
              "ADVANCE=$hasAdvance REPEAT=$hasRepeat SWITCH=$hasSwitch")

        if (fullText.isNotEmpty()) {
            val userTranscript = extractUserTranscript(fullText)
            val cleanReply = removeTranscriptHeader(fullText)

            if (userTranscript != null) {
                Log.d(TAG, "user transcript: \"${userTranscript.take(80)}\"")
                addTranscriptBubble("Вы", userTranscript)
            }

            processTextTags(cleanReply)
            val displayReply = removeCommandTags(cleanReply)
            if (displayReply.isNotEmpty()) {
                addTranscriptBubble("Дональд", displayReply)
            }
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
        realtimeClient.connect(apiKey, _selectedVoice.value, buildSystemPrompt())
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
        realtimeClient.connect(apiKey, _selectedVoice.value, buildSystemPrompt())
    }

    private fun startListening() {
        Log.d(TAG, "startListening: threshold=${_threshold.value} silence=${_silenceDurationMs.value}ms")
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
                Log.d(TAG, "silence detected after $chunksSent chunks — committing buffer")
                _state.value = AgentState.PROCESSING
                realtimeClient.commitAndRespond(buildContextText())
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
                "[Context: Mode=TOM_SAWYER_READING. Phrase index ${_bookIndex.value}: " +
                "'${tomSawyerPhrases[_bookIndex.value]}' = '${tomSawyerTranslations[_bookIndex.value]}'. " +
                "Check if user repeated correctly, expressed confusion, or wants to stop.]"
            AgentMode.CONVERSATION ->
                "[Context: Mode=CONVERSATION. Current book phrase index ${_bookIndex.value}: " +
                "'${tomSawyerPhrases[_bookIndex.value]}' = '${tomSawyerTranslations[_bookIndex.value]}'.]"
        }
    }

    private fun processTextTags(rawText: String) {
        if (rawText.contains("[SWITCH_TO_CONVERSATION]")) _mode.value = AgentMode.CONVERSATION
        if (rawText.contains("[SWITCH_TO_READING]")) _mode.value = AgentMode.BOOK_READING
        if (rawText.contains("[ADVANCE_BOOK]")) {
            setBookIndex((_bookIndex.value + 1) % tomSawyerPhrases.size)
        }
    }

    private fun removeCommandTags(rawText: String): String {
        return rawText
            .replace("[SWITCH_TO_CONVERSATION]", "")
            .replace("[SWITCH_TO_READING]", "")
            .replace("[ADVANCE_BOOK]", "")
            .replace("[REPEAT_BOOK]", "")
            .trim()
    }

    private fun extractUserTranscript(text: String): String? {
        val match = Regex("\\[User:\\s*(.*?)\\]").find(text)
        return match?.groupValues?.getOrNull(1)
    }

    private fun removeTranscriptHeader(text: String): String {
        return text.replace(Regex("\\[User:\\s*.*?\\]\\s*"), "").trim()
    }

    private fun addTranscriptBubble(sender: String, messageText: String) {
        val bubble = BubbleMessage(id = System.nanoTime(), sender = sender, text = messageText)
        _transcripts.value = _transcripts.value + bubble
    }

    private fun buildSystemPrompt(): String {
        return """
        Вы — Donald (Дональд), дружелюбный личный голосовой собеседник и опытный преподаватель английского языка.

        ОСНОВНЫЕ ПРАВИЛА ОБЩЕНИЯ:
        1. Пользователь может говорить с вами НА РУССКОМ языке или НА ИВРИТЕ. Вы прекрасно понимаете оба этих языка.
        2. Вы отвечаете ИСКЛЮЧИТЕЛЬНО на русском языке. Говорите чистым русским языком, без какого-либо акцента, мужским голосом.
        3. Будьте теплым, поддерживающим и чутким преподавателем. Если пользователь делает ошибки в английском во время разговора, тепло исправляйте его ошибки и просто объясняйте правила на русском языке.

        РЕЖИМ ЧТЕНИЯ КНИГИ "ТOM SAWYER":
        Когда пользователь явно просит почитать книгу про Тома Сойера вы отвечаете по-русски, что вы рады почитать, переходите в режим чтения и озвучиваете текущую английскую фразу. Обязательно вставьте в конце ответа тег [SWITCH_TO_READING].

        В РЕЖИМЕ ЧТЕНИЯ КНИГИ:
        - Вы читаете книгу строго по одной фразе за раз вслух на английском языке.
        - Когда пользователь повторил фразу правильно: похвалите кратко на русском, прочитайте СЛЕДУЮЩУЮ фразу, добавьте [ADVANCE_BOOK].
        - Когда пользователь повторил с ошибкой: укажите на ошибку, повторите ТУ ЖЕ фразу, добавьте [REPEAT_BOOK].
        - Если пользователь выражает непонимание: переведите фразу, объясните, добавьте [SWITCH_TO_CONVERSATION].
        - Если пользователь хочет продолжить после объяснения: вернитесь в режим чтения, добавьте [SWITCH_TO_READING].
        - Если пользователь устал: добавьте [SWITCH_TO_CONVERSATION].

        ФОРМАТ ОТВЕТА:
        Начните ответ строго с: [User: <транскрипция слов пользователя>]
        Затем с новой строки — ваш ответ.
        В конце (если применимо) — один из тегов: [ADVANCE_BOOK], [REPEAT_BOOK], [SWITCH_TO_CONVERSATION], [SWITCH_TO_READING].
        """.trimIndent()
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecorder.stopRecording()
        audioPlayer.stopAll()
        realtimeClient.disconnect()
    }
}
