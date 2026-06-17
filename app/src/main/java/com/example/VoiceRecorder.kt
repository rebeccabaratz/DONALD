package com.example

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 24000
        private const val CHUNK_BYTES = 4800 // 100ms at 24kHz 16-bit mono
    }

    private var audioRecord: AudioRecord? = null
    private var streamingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _currentAmplitude = MutableStateFlow(0)
    val currentAmplitude: StateFlow<Int> = _currentAmplitude

    // Opens the mic briefly, measures 300ms of ambient noise, closes the mic.
    // Called during PROCESSING state while AI is generating — mic is idle then,
    // so the measurement costs zero extra latency before the next listen cycle.
    suspend fun measureNoiseFloor(): Int = withContext(Dispatchers.Default) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return@withContext 0
        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK_BYTES * 4)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext 0
            record.startRecording()
            val buffer = ByteArray(CHUNK_BYTES)
            val chunkMs = CHUNK_BYTES * 1000L / (SAMPLE_RATE * 2)
            val noiseChunks = (300L / chunkMs).toInt().coerceAtLeast(1)
            val peaks = IntArray(noiseChunks)
            for (i in 0 until noiseChunks) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) peaks[i] = computeMaxAmplitude(buffer, read)
            }
            peaks.sorted().let { s -> s[(s.size * 0.9).toInt().coerceIn(0, s.size - 1)] }
        } catch (e: Exception) {
            Log.w(TAG, "measureNoiseFloor failed: ${e.message}")
            0
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            try { record?.release() } catch (_: Exception) {}
        }
    }

    fun startRecording(
        threshold: Int = 1800,
        silenceDurationMs: Long = 1800,
        isAiSpeaking: () -> Boolean = { false },
        precomputedNoiseFloor: Int = -1,
        onAudioChunk: (ByteArray) -> Unit,
        onSilenceDetected: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (_isRecording.value) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            onError("AudioRecord не поддерживается на этом устройстве")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK_BYTES * 4)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("Не удалось инициализировать микрофон")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            _isRecording.value = true

            streamingJob = scope.launch {
                val buffer = ByteArray(CHUNK_BYTES)
                val chunkMs = CHUNK_BYTES * 1000L / (SAMPLE_RATE * 2)

                // Use pre-measured noise floor from PROCESSING state if available,
                // otherwise fall back to a quick inline measurement (first session turn).
                val noiseFloor = if (precomputedNoiseFloor >= 0) {
                    precomputedNoiseFloor
                } else {
                    val noiseChunks = (300L / chunkMs).toInt().coerceAtLeast(1)
                    val noisePeaks = IntArray(noiseChunks)
                    for (i in 0 until noiseChunks) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                        if (read > 0) noisePeaks[i] = computeMaxAmplitude(buffer, read)
                    }
                    noisePeaks.sorted().let { s -> s[(s.size * 0.9).toInt().coerceIn(0, s.size - 1)] }
                }
                val effectiveThreshold = maxOf(threshold, (noiseFloor * 1.5).toInt())
                Log.d(TAG, "noiseFloor=$noiseFloor (precomputed=${precomputedNoiseFloor >= 0}) userThreshold=$threshold effectiveThreshold=$effectiveThreshold")

                var hasSpoken = false
                var silenceAccumMs = 0L
                var noSpeechMs = 0L

                while (_isRecording.value) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read <= 0) continue

                    val amplitude = computeMaxAmplitude(buffer, read)
                    _currentAmplitude.value = amplitude

                    // Mic-gating: while AI is speaking, discard audio entirely.
                    // Prevents car speakers from bleeding into the microphone input.
                    if (isAiSpeaking()) {
                        hasSpoken = false
                        silenceAccumMs = 0
                        continue
                    }

                    if (amplitude > effectiveThreshold) {
                        hasSpoken = true
                        silenceAccumMs = 0
                        noSpeechMs = 0
                        onAudioChunk(buffer.copyOf(read))
                    } else if (hasSpoken) {
                        onAudioChunk(buffer.copyOf(read))
                        silenceAccumMs += chunkMs
                        if (silenceAccumMs >= silenceDurationMs) {
                            Log.d(TAG, "Silence detected, signaling turn complete")
                            _isRecording.value = false
                            cleanupAudioRecord()
                            _currentAmplitude.value = 0
                            onSilenceDetected()
                            break
                        }
                    } else {
                        noSpeechMs += chunkMs
                        if (noSpeechMs >= 12000L) {
                            Log.d(TAG, "No speech in 12s, resetting timer")
                            noSpeechMs = 0
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No mic permission: ${e.message}")
            onError("Нет разрешения на использование микрофона")
            cleanupAudioRecord()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}")
            onError(e.message ?: "Ошибка микрофона")
            cleanupAudioRecord()
        }
    }

    fun stopRecording() {
        _isRecording.value = false
        streamingJob?.cancel()
        streamingJob = null
        cleanupAudioRecord()
        _currentAmplitude.value = 0
    }

    private fun cleanupAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }
    }

    private fun computeMaxAmplitude(buffer: ByteArray, size: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < size) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > max) max = abs
            i += 2
        }
        return max
    }
}
