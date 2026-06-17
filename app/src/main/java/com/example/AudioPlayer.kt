package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AudioPlayer(private val context: Context) : AudioSink {

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private var framesWritten = 0
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Max sample amplitude of the most recently written chunk — drives the
    // talking-avatar mouth animation. Purely local, no extra API cost.
    private val _outputAmplitude = MutableStateFlow(0)
    override val outputAmplitude: StateFlow<Int> = _outputAmplitude

    private var audioFocusRequest: AudioFocusRequest? = null

    // --- Streaming PCM playback via AudioTrack (Live API) ---

    override fun startStreamingPlayback(sampleRate: Int) {
        stopStreaming()
        framesWritten = 0

        // Keep audio in NORMAL mode so output routes to A2DP (car music speakers).
        // Android Auto manages SCO itself — we only stop it if it was left on by
        // a previous call, but we don't toggle the deprecated isBluetoothScoOn setter.
        audioManager.mode = AudioManager.MODE_NORMAL
        if (audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // Request audio focus before playback — required for Android Auto to route
        // audio to car speakers instead of silently blocking the AudioTrack output.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 8)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            CostTracker.logAudioError("AudioTrack не инициализирован (state=${audioTrack?.state}) — звук не будет слышен")
        }
        audioTrack?.play()
        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
            CostTracker.logAudioError("AudioTrack.play() не запустил воспроизведение (playState=${audioTrack?.playState})")
        }
    }

    override fun writePcmChunk(base64Pcm: String) {
        val bytes = Base64.decode(base64Pcm, Base64.DEFAULT)
        val written = audioTrack?.write(bytes, 0, bytes.size) ?: AudioTrack.ERROR_INVALID_OPERATION
        if (written < 0) {
            CostTracker.logAudioError("AudioTrack.write() вернул ошибку $written (${bytes.size} байт не воспроизведено)")
        }
        framesWritten += bytes.size / 2  // 16-bit PCM = 2 bytes per frame
        _outputAmplitude.value = computeMaxAmplitude(bytes)
    }

    private fun computeMaxAmplitude(bytes: ByteArray): Int {
        var max = 0
        var i = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > max) max = abs
            i += 2
        }
        return max
    }

    override fun stopStreaming() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        }
        try {
            audioTrack?.apply { stop(); release() }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error stopping AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
            framesWritten = 0
            _outputAmplitude.value = 0
        }
    }

    // Waits for buffered audio to finish playing before releasing.
    // Previously used playState == STOPPED which returns true immediately after
    // stop() even while audio is still draining — causing the tail to be cut off.
    // Now tracks framesWritten and waits for playbackHeadPosition to catch up.
    override suspend fun drainAndStopStreaming() {
        val track = audioTrack ?: return
        try {
            val target = framesWritten
            var checks = 0
            while (checks < 100 && track.playbackHeadPosition < target) {
                kotlinx.coroutines.delay(50)
                checks++
            }
            Log.d("AudioPlayer", "drain done: head=${track.playbackHeadPosition} target=$target checks=$checks")
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error draining AudioTrack: ${e.message}")
        } finally {
            try { audioTrack?.stop() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
            framesWritten = 0
            _outputAmplitude.value = 0
        }
    }

    // --- File-based playback via MediaPlayer (kept for compatibility) ---

    fun playBase64(
        base64Audio: String,
        mimeType: String,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        stopPlaying()
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val tempFile: File
            val dataToWrite: ByteArray

            when {
                mimeType.contains("pcm") -> {
                    tempFile = File(context.cacheDir, "response_playing.wav")
                    dataToWrite = addWavHeader(audioBytes, sampleRate = 24000, channels = 1, bitsPerSample = 16)
                }
                mimeType.contains("wav") -> {
                    tempFile = File(context.cacheDir, "response_playing.wav")
                    dataToWrite = audioBytes
                }
                else -> {
                    tempFile = File(context.cacheDir, "response_playing.aac")
                    dataToWrite = audioBytes
                }
            }

            if (tempFile.exists()) tempFile.delete()
            FileOutputStream(tempFile).use { it.write(dataToWrite) }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    Log.d("AudioPlayer", "Playback completed")
                    stopPlaying()
                    onFinished()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("AudioPlayer", "MediaPlayer error: what=$what, extra=$extra")
                    stopPlaying()
                    onError("Ошибка воспроизведения: what=$what")
                    true
                }
                start()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Exception: ${e.message}")
            onError(e.message ?: "Не удалось воспроизвести аудио")
            stopPlaying()
        }
    }

    fun stopPlaying() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error releasing MediaPlayer: ${e.message}")
        } finally {
            mediaPlayer = null
        }
    }

    override fun stopAll() {
        stopPlaying()
        stopStreaming()
    }

    private fun addWavHeader(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val dataSize = pcmData.size
        val totalSize = dataSize + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalSize and 0xff).toByte()
        header[5] = ((totalSize shr 8) and 0xff).toByte()
        header[6] = ((totalSize shr 16) and 0xff).toByte()
        header[7] = ((totalSize shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (blockAlign and 0xff).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()
        return header + pcmData
    }
}
