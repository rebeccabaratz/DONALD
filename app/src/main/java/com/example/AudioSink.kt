package com.example

interface AudioSink {
    fun startStreamingPlayback(sampleRate: Int = 24000)
    fun writePcmChunk(base64Pcm: String)
    suspend fun drainAndStopStreaming()
    fun stopStreaming()
    fun stopAll()
}
