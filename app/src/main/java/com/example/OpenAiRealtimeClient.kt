package com.example

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiRealtimeClient(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "OpenAiRealtimeClient"
        private const val WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-realtime-2"
        private const val SETUP_TIMEOUT_MS = 15_000L
    }

    sealed class Event {
        object SetupComplete : Event()
        data class AudioChunk(val pcmBase64: String) : Event()
        data class TextChunk(val text: String) : Event()
        object TurnComplete : Event()
        data class FunctionCall(val name: String, val callId: String) : Event()
        data class Error(val message: String) : Event()
        data class Info(val message: String) : Event()
        object Disconnected : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var setupTimeoutJob: Job? = null

    private var lastApiKey = ""
    private var lastVoiceName = ""
    private var lastSystemPrompt = ""

    @Volatile private var ready = false
    val isReady: Boolean get() = ready

    @Volatile private var setupCompletedEmitted = false

    @Volatile private var pendingFunctionCallId: String? = null
    @Volatile private var pendingFunctionCallName: String? = null

    // Counts intentional closes (our reconnect logic) so onClosed doesn't
    // mistake them for unexpected disconnects and trigger a second reconnect.
    @Volatile private var pendingIntentionalCloses = 0

    fun connect(apiKey: String, voiceName: String, systemPrompt: String) {
        lastApiKey = apiKey
        lastVoiceName = voiceName
        lastSystemPrompt = systemPrompt
        connectInternal()
    }

    private fun connectInternal() {
        disconnectInternal()
        ready = false
        setupCompletedEmitted = false
        pendingFunctionCallId = null
        pendingFunctionCallName = null

        Log.d(TAG, "Connecting to OpenAI Realtime API voice=$lastVoiceName")

        setupTimeoutJob = scope.launch {
            delay(SETUP_TIMEOUT_MS)
            if (!ready) {
                _events.emit(Event.Error(
                    "Тайм-аут подключения к OpenAI.\n" +
                    "Проверь API ключ в настройках ⚙️"
                ))
            }
        }

        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("Authorization", "Bearer $lastApiKey")
            .build()

        webSocket = httpClient.newWebSocket(request, ConnectionListener())
    }

    private inner class ConnectionListener : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            try {
                val config = buildSessionConfig()
                Log.d(TAG, "WS open (HTTP ${response.code}), sending session config")
                Log.d(TAG, "Session config: $config")
                ws.send(config)
            } catch (e: Exception) {
                Log.e(TAG, "onOpen error: ${e.message}", e)
                scope.launch { _events.emit(Event.Error("Ошибка настройки сессии: ${e.message}")) }
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                val t = try { org.json.JSONObject(text).optString("type") } catch (e: Exception) { "?" }
                if (t != "response.output_audio.delta") {
                    Log.d(TAG, "← [$t] ${text.take(300)}")
                }
                parseMessage(text)
            } catch (e: Exception) {
                Log.e(TAG, "onMessage error: ${e.message}", e)
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            try {
                ready = false
                setupTimeoutJob?.cancel()
                val code = response?.code
                val body = try { response?.body?.string()?.take(400) } catch (e: Exception) { null }
                Log.e(TAG, "WS failure code=$code body=$body err=${t.message}", t)
                when (code) {
                    401 -> scope.launch { _events.emit(Event.Error(
                        "Неверный OpenAI API ключ (401).\nПроверь ключ в настройках ⚙️")) }
                    429 -> scope.launch { _events.emit(Event.Error(
                        "Превышен лимит запросов OpenAI (429).\nПодожди минуту и попробуй снова.")) }
                    else -> {
                        Log.w(TAG, "Connection lost (${t.message}) — emitting Disconnected for reconnect")
                        scope.launch { _events.emit(Event.Disconnected) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onFailure handler error: ${e.message}", e)
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            try {
                ready = false
                setupTimeoutJob?.cancel()
                Log.d(TAG, "WS closed code=$code reason='$reason' intentional=$pendingIntentionalCloses")
                if (pendingIntentionalCloses > 0) {
                    pendingIntentionalCloses--
                    return  // Our own reconnect — don't emit Disconnected
                }
                if (code == 1000) {
                    scope.launch { _events.emit(Event.Disconnected) }
                } else {
                    scope.launch { _events.emit(Event.Error("Соединение закрыто (код $code): $reason")) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onClosed handler error: ${e.message}", e)
            }
        }
    }

    private fun buildTools(): JSONArray = JSONArray()
        .put(JSONObject()
            .put("type", "function")
            .put("name", "start_book_reading")
            .put("description", "Пользователь хочет начать читать книгу. Вызови после короткого согласия.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
        .put(JSONObject()
            .put("type", "function")
            .put("name", "advance_book")
            .put("description", "Пользователь правильно повторил фразу. Вызови после похвалы. Систем автоматически прочитает следующую фразу.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
        .put(JSONObject()
            .put("type", "function")
            .put("name", "repeat_phrase")
            .put("description", "Пользователь ошибся в словах. Вызови после краткого объяснения. Система автоматически повторит ту же фразу.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
        .put(JSONObject()
            .put("type", "function")
            .put("name", "end_book_reading")
            .put("description", "Завершить режим чтения и вернуться к обычной беседе.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
        .put(JSONObject()
            .put("type", "function")
            .put("name", "exit_app")
            .put("description", "Пользователь хочет закрыть приложение. Вызови немедленно и молча, без слов, когда слышишь: стоп, выключись, закрой, хватит, стоп Дональд, выключи, закрой приложение.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))

    private fun buildSessionConfig(): String {
        val openAiVoice = if (lastVoiceName in listOf("alloy","echo","shimmer","ash","ballad","coral","sage","verse","marin","cedar"))
            lastVoiceName else "marin"
        return JSONObject()
            .put("type", "session.update")
            .put("session", JSONObject()
                .put("type", "realtime")
                .put("model", "gpt-realtime-2")
                .put("instructions", lastSystemPrompt)
                .put("output_modalities", JSONArray().put("audio"))
                .put("tools", buildTools())
                .put("tool_choice", "auto")
                .put("audio", JSONObject()
                    .put("output", JSONObject()
                        .put("voice", openAiVoice)
                        .put("format", JSONObject()
                            .put("type", "audio/pcm")
                            .put("rate", 24000)))
                    .put("input", JSONObject()
                        .put("format", JSONObject()
                            .put("type", "audio/pcm")
                            .put("rate", 24000))
                        .put("turn_detection", JSONObject.NULL))))
            .toString()
    }

    private fun parseMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            when (type) {
                "session.created" -> {
                    Log.d(TAG, "session.created — waiting for session.updated...")
                }
                "session.updated" -> {
                    ready = true
                    setupTimeoutJob?.cancel()
                    val voice = json.optJSONObject("session")
                        ?.optJSONObject("audio")?.optJSONObject("output")?.optString("voice")
                    if (!setupCompletedEmitted) {
                        setupCompletedEmitted = true
                        Log.d(TAG, "session.updated (initial) — ready! voice=$voice")
                        scope.launch { _events.emit(Event.SetupComplete) }
                    } else {
                        Log.d(TAG, "session.updated (context refresh) — no event emitted")
                    }
                }
                "response.output_audio.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        scope.launch { _events.emit(Event.AudioChunk(delta)) }
                    }
                }
                "response.output_audio_transcript.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        scope.launch { _events.emit(Event.TextChunk(delta)) }
                    }
                }
                "response.output_item.added" -> {
                    val item = json.optJSONObject("item")
                    val itemType = item?.optString("type")
                    if (itemType == "function_call") {
                        pendingFunctionCallId = item.optString("call_id")
                        pendingFunctionCallName = item.optString("name")
                        Log.d(TAG, "function_call started: name=${pendingFunctionCallName} id=${pendingFunctionCallId}")
                    } else {
                        Log.d(TAG, "output_item.added type=$itemType")
                    }
                }
                "response.function_call_arguments.delta" -> {
                    Log.d(TAG, "function_call_arguments.delta (ignored)")
                }
                "response.function_call_arguments.done" -> {
                    Log.d(TAG, "function_call_arguments.done name=${pendingFunctionCallName}")
                }
                "response.done" -> {
                    val status = json.optJSONObject("response")?.optString("status")
                    val usage = json.optJSONObject("response")?.optJSONObject("usage")
                    Log.d(TAG, "response.done status=$status usage=$usage fnPending=${pendingFunctionCallName}")
                    val fnName = pendingFunctionCallName
                    val fnCallId = pendingFunctionCallId
                    if (fnName != null && fnCallId != null) {
                        pendingFunctionCallId = null
                        pendingFunctionCallName = null
                        // ViewModel sends function_call_output with phrase context
                        Log.d(TAG, "function call done: $fnName callId=$fnCallId — emitting FunctionCall")
                        scope.launch { _events.emit(Event.FunctionCall(fnName, fnCallId)) }
                    } else {
                        scope.launch { _events.emit(Event.TurnComplete) }
                    }
                }
                "response.created" ->
                    Log.d(TAG, "response.created id=${json.optJSONObject("response")?.optString("id")}")
                "response.output_item.done" ->
                    Log.d(TAG, "output_item.done type=${json.optJSONObject("item")?.optString("type")}")
                "input_audio_buffer.committed" ->
                    Log.d(TAG, "audio buffer committed item=${json.optString("item_id")}")
                "input_audio_buffer.cleared" ->
                    Log.d(TAG, "audio buffer cleared")
                "conversation.item.created" ->
                    Log.d(TAG, "conversation.item.created id=${json.optJSONObject("item")?.optString("id")}")
                "error" -> {
                    val err = json.optJSONObject("error")
                    val code = err?.optString("code") ?: ""
                    val msg = err?.optString("message") ?: "Ошибка OpenAI"
                    Log.e(TAG, "API error code=$code message=$msg")
                    if (code == "input_audio_buffer_commit_empty") {
                        Log.w(TAG, "Empty buffer commit ignored (no audio recorded)")
                    } else {
                        scope.launch { _events.emit(Event.Error(msg)) }
                    }
                }
                else -> {
                    if (type.isNotEmpty()) {
                        Log.d(TAG, "unhandled event: $type")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message} raw=${text.take(100)}")
        }
    }

    fun sendAudioChunk(pcmBytes: ByteArray) {
        if (!ready) return
        val data = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        webSocket?.send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", data)
                .toString()
        )
    }

    fun sendText(text: String) {
        if (!ready) return
        webSocket?.send(
            JSONObject()
                .put("type", "conversation.item.create")
                .put("item", JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put("content", JSONArray()
                        .put(JSONObject()
                            .put("type", "input_text")
                            .put("text", text))))
                .toString()
        )
        webSocket?.send(JSONObject().put("type", "response.create").toString())
    }

    fun commitAndRespond() {
        if (!ready) return
        Log.d(TAG, "commitAndRespond: committing buffer → response.create")
        webSocket?.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
        webSocket?.send(JSONObject().put("type", "response.create").toString())
    }

    fun requestGreeting() {
        if (!ready) return
        Log.d(TAG, "requestGreeting: sending response.create (session audio config applies)")
        webSocket?.send(JSONObject().put("type", "response.create").toString())
    }

    fun updateInstructions(newInstructions: String) {
        if (!ready) return
        val openAiVoice = if (lastVoiceName in listOf("alloy","echo","shimmer","ash","ballad","coral","sage","verse","marin","cedar"))
            lastVoiceName else "marin"
        Log.d(TAG, "updateInstructions: ${newInstructions.length} chars voice=$openAiVoice")
        webSocket?.send(
            JSONObject().put("type", "session.update")
                .put("session", JSONObject()
                    .put("type", "realtime")
                    .put("output_modalities", JSONArray().put("audio"))
                    .put("tools", buildTools())
                    .put("tool_choice", "auto")
                    .put("instructions", newInstructions)
                    .put("audio", JSONObject()
                        .put("output", JSONObject()
                            .put("voice", openAiVoice)
                            .put("format", JSONObject()
                                .put("type", "audio/pcm")
                                .put("rate", 24000)))))
                .toString()
        )
    }

    // Sends function_call_output with the phrase instruction, then triggers response.create.
    // No session.update is sent here — original session audio config (PCM 24000Hz) is preserved,
    // ensuring the AI's response is audio not text.
    fun respondToFunctionAndSpeak(callId: String, phraseInstruction: String) {
        if (!ready) return
        Log.d(TAG, "respondToFunctionAndSpeak callId=$callId '${phraseInstruction.take(60)}'")
        webSocket?.send(
            JSONObject()
                .put("type", "conversation.item.create")
                .put("item", JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", callId)
                    .put("output", phraseInstruction))
                .toString()
        )
        webSocket?.send(JSONObject().put("type", "response.create").toString())
    }

    fun disconnect() {
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        disconnectInternal()
    }

    private fun disconnectInternal() {
        ready = false
        if (webSocket != null) {
            pendingIntentionalCloses++
            webSocket?.close(1000, "Session ended")
            webSocket = null
        }
    }
}
