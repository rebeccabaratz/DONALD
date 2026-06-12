package com.example

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<Content>,
    @Json(name = "generation_config") val generationConfig: GenerationConfig? = null,
    @Json(name = "system_instruction") val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val role: String? = null,
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null,
    @Json(name = "inline_data") val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    @Json(name = "mime_type") val mimeType: String,
    val data: String // base64 encoded media file
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    @Json(name = "response_modalities") val responseModalities: List<String>? = null,
    @Json(name = "speech_config") val speechConfig: SpeechConfig? = null,
    val temperature: Float? = null
)

@JsonClass(generateAdapter = true)
data class SpeechConfig(
    @Json(name = "voice_config") val voiceConfig: VoiceConfig
)

@JsonClass(generateAdapter = true)
data class VoiceConfig(
    @Json(name = "prebuilt_voice_config") val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

@JsonClass(generateAdapter = true)
data class PrebuiltVoiceConfig(
    @Json(name = "voice_name") val voiceName: String
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>?
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content,
    @Json(name = "finish_reason") val finishReason: String? = null
)
