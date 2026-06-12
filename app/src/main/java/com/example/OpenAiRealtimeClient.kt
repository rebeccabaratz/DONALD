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
        private const val WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview"
        private const val SETUP_TIMEOUT_MS = 15_000L
    }

    sealed class Event {
        object SetupComplete : Event()
        data class AudioChunk(val pcmBase64: String) : Event()
        data class TextChunk(val text: String) : Event()
        object TurnComplete : Event()
        data class Error(val message: String) : Event()
        data class Info(val message: String) : Event()
        object Disconnected : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var setupTimeoutJob: Job? = null

    private var lastApiKey = ""
    private var lastVoiceName = ""
    private var lastSystemPrompt = ""

    @Volatile
    private var ready = false
    val isReady: Boolean get() = ready

    fun connect(apiKey: String, voiceName: String, systemPrompt: String) {
        lastApiKey = apiKey
        lastVoiceName = voiceName
        lastSystemPrompt = systemPrompt
        connectInternal()
    }

    private fun connectInternal() {
        disconnectInternal()
        ready = false

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
                Log.d(TAG, "WS open, sending session config")
                ws.send(buildSessionConfig())
            } catch (e: Exception) {
                Log.e(TAG, "onOpen error: ${e.message}")
                scope.launch { _events.emit(Event.Error("Ошибка настройки сессии: ${e.message}")) }
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                Log.d(TAG, "Server ← ${text.take(200)}")
                parseMessage(text)
            } catch (e: Exception) {
                Log.e(TAG, "onMessage error: ${e.message}")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            try {
                ready = false
                setupTimeoutJob?.cancel()
                val code = response?.code
                val body = try { response?.body?.string()?.take(200) } catch (e: Exception) { null }
                Log.e(TAG, "WS failure code=$code body=$body err=${t.message}")
                val msg = when (code) {
                    401 -> "Неверный OpenAI API ключ (401).\nПроверь ключ в настройках ⚙️"
                    429 -> "Превышен лимит запросов OpenAI (429).\nПодожди минуту и попробуй снова."
                    else -> body ?: "HTTP $code: ${t.message ?: "Ошибка соединения"}"
                }
                scope.launch { _events.emit(Event.Error(msg)) }
            } catch (e: Exception) {
                Log.e(TAG, "onFailure handler error: ${e.message}")
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            try {
                ready = false
                setupTimeoutJob?.cancel()
                Log.d(TAG, "WS closed code=$code reason=$reason")
                if (code == 1000) {
                    scope.launch { _events.emit(Event.Disconnected) }
                } else {
                    scope.launch { _events.emit(Event.Error("Соединение закрыто (код $code): $reason")) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onClosed handler error: ${e.message}")
            }
        }
    }

    private fun buildSessionConfig(): String {
        val openAiVoice = if (lastVoiceName in listOf("alloy","echo","shimmer","ash","ballad","coral","sage","verse","marin","cedar"))
            lastVoiceName else "alloy"
        return JSONObject()
            .put("type", "session.update")
            .put("session", JSONObject()
                .put("modalities", JSONArray().put("text").put("audio"))
                .put("instructions", lastSystemPrompt)
                .put("voice", openAiVoice)
                .put("input_audio_format", "pcm16")
                .put("output_audio_format", "pcm16")
                .put("turn_detection", JSONObject.NULL))  // client-side VAD, manual commit
            .toString()
    }

    private fun parseMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            when (type) {
                "session.created" -> {
                    Log.d(TAG, "Session created, waiting for config to apply...")
                }
                "session.updated" -> {
                    ready = true
                    setupTimeoutJob?.cancel()
                    Log.d(TAG, "Session configured and ready")
                    scope.launch { _events.emit(Event.SetupComplete) }
                }
                "response.audio.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        scope.launch { _events.emit(Event.AudioChunk(delta)) }
                    }
                }
                "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        scope.launch { _events.emit(Event.TextChunk(delta)) }
                    }
                }
                "response.done" -> {
                    scope.launch { _events.emit(Event.TurnComplete) }
                }
                "input_audio_buffer.speech_started" ->
                    Log.d(TAG, "Speech started")
                "input_audio_buffer.speech_stopped" ->
                    Log.d(TAG, "Speech stopped")
                else -> {
                    if (type == "error") {
                        val msg = json.optJSONObject("error")?.optString("message") ?: "Ошибка OpenAI"
                        Log.e(TAG, "API error: $msg raw=${text.take(200)}")
                        scope.launch { _events.emit(Event.Error(msg)) }
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

    fun commitAndRespond(contextHint: String) {
        if (!ready) return
        webSocket?.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
        val responseJson = JSONObject().put("type", "response.create")
        if (contextHint.isNotEmpty()) {
            responseJson.put("response", JSONObject().put("instructions", contextHint))
        }
        webSocket?.send(responseJson.toString())
    }

    fun disconnect() {
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        disconnectInternal()
    }

    private fun disconnectInternal() {
        ready = false
        webSocket?.close(1000, "Session ended")
        webSocket = null
    }
}
