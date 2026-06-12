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
                val msg = when (code) {
                    401 -> "Неверный OpenAI API ключ (401).\nПроверь ключ в настройках ⚙️"
                    429 -> "Превышен лимит запросов OpenAI (429).\nПодожди минуту и попробуй снова."
                    else -> body ?: "HTTP $code: ${t.message ?: "Ошибка соединения"}"
                }
                scope.launch { _events.emit(Event.Error(msg)) }
            } catch (e: Exception) {
                Log.e(TAG, "onFailure handler error: ${e.message}", e)
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            try {
                ready = false
                setupTimeoutJob?.cancel()
                Log.d(TAG, "WS closed code=$code reason='$reason'")
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
                        .put("turn_detection", JSONObject.NULL))))  // manual commit, no server VAD
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
                    Log.d(TAG, "session.updated — ready! voice=$voice")
                    scope.launch { _events.emit(Event.SetupComplete) }
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
                "response.done" -> {
                    val status = json.optJSONObject("response")?.optString("status")
                    val usage = json.optJSONObject("response")?.optJSONObject("usage")
                    Log.d(TAG, "response.done status=$status usage=$usage")
                    scope.launch { _events.emit(Event.TurnComplete) }
                }
                "response.created" ->
                    Log.d(TAG, "response.created id=${json.optJSONObject("response")?.optString("id")}")
                "response.output_item.added" ->
                    Log.d(TAG, "output_item.added type=${json.optJSONObject("item")?.optString("type")}")
                "input_audio_buffer.committed" ->
                    Log.d(TAG, "audio buffer committed item=${json.optString("item_id")}")
                "input_audio_buffer.cleared" ->
                    Log.d(TAG, "audio buffer cleared")
                "conversation.item.created" ->
                    Log.d(TAG, "conversation.item.created id=${json.optJSONObject("item")?.optString("id")}")
                else -> {
                    if (type == "error") {
                        val err = json.optJSONObject("error")
                        val msg = err?.optString("message") ?: "Ошибка OpenAI"
                        val code = err?.optString("code") ?: ""
                        Log.e(TAG, "API error code=$code message=$msg")
                        scope.launch { _events.emit(Event.Error(msg)) }
                    } else if (type.isNotEmpty()) {
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
