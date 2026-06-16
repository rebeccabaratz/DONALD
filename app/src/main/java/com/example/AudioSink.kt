package com.example

import kotlinx.coroutines.flow.StateFlow

interface AudioSink {
    val outputAmplitude: StateFlow<Int>
    fun startStreamingPlayback(sampleRate: Int = 24000)
    fun writePcmChunk(base64Pcm: String)
    suspend fun drainAndStopStreaming()
    fun stopStreaming()
    fun stopAll()
}
