package com.example

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CostTracker {
    private const val TAG = "CostTracker"
    private const val LOG_FILE = "donald_cost_log.txt"
    private const val SAMPLE_RATE = 24000
    private const val BYTES_PER_SAMPLE = 2

    @Volatile private var sessionStart = 0L
    @Volatile private var audioBytesSent = 0L
    @Volatile private var audioBytesReceived = 0L
    @Volatile private var connectCount = 0
    @Volatile private var promptCharsSent = 0L
    @Volatile private var turnCount = 0

    @Volatile private var turnBytesSent = 0L
    @Volatile private var turnBytesReceived = 0L
    @Volatile private var turnStart = 0L

    private val logBuilder = StringBuilder()

    fun startSession() {
        sessionStart = System.currentTimeMillis()
        audioBytesSent = 0
        audioBytesReceived = 0
        connectCount = 0
        promptCharsSent = 0
        turnCount = 0
        logBuilder.clear()
        appendLine("═══════════════════════════════════════")
        appendLine("  СЕССИЯ  ${formatTime(sessionStart)}")
        appendLine("═══════════════════════════════════════")
    }

    fun logConnect(promptChars: Int, isReconnect: Boolean = false) {
        connectCount++
        promptCharsSent += promptChars
        val label = if (isReconnect) "Переподкл. #$connectCount" else "Подкл. #$connectCount"
        appendLine("[$label] промпт ${promptChars} симв (~${promptChars / 4} ток)")
    }

    fun startTurn() {
        turnCount++
        turnBytesSent = 0
        turnBytesReceived = 0
        turnStart = System.currentTimeMillis()
    }

    fun addAudioSent(bytes: Int) {
        audioBytesSent += bytes
        turnBytesSent += bytes
    }

    fun addAudioReceived(base64Length: Int) {
        val bytes = base64Length.toLong() * 3 / 4
        audioBytesReceived += bytes
        turnBytesReceived += bytes
    }

    fun logTurn(label: String) {
        val sentSec = turnBytesSent / (SAMPLE_RATE.toDouble() * BYTES_PER_SAMPLE)
        val recvSec = turnBytesReceived / (SAMPLE_RATE.toDouble() * BYTES_PER_SAMPLE)
        val durSec = (System.currentTimeMillis() - turnStart) / 1000.0
        appendLine(
            "  [Ход $turnCount | $label] " +
            "mic→AI: ${fmt1(sentSec)}с | " +
            "AI→mic: ${fmt1(recvSec)}с | " +
            "итого: ${fmt1(durSec)}с"
        )
    }

    fun endSession(context: Context, reason: String = "") {
        if (sessionStart == 0L) return
        val totalSec = (System.currentTimeMillis() - sessionStart) / 1000.0
        val sentSec = audioBytesSent / (SAMPLE_RATE.toDouble() * BYTES_PER_SAMPLE)
        val recvSec = audioBytesReceived / (SAMPLE_RATE.toDouble() * BYTES_PER_SAMPLE)
        val promptTokens = promptCharsSent / 4

        appendLine("───────────────────────────────────────")
        if (reason.isNotEmpty()) appendLine("  ИТОГ ($reason)")
        appendLine("  Время окончания     : ${formatTime(System.currentTimeMillis())}")
        appendLine("  Длительность сессии : ${fmt1(totalSec)}с = ${fmt2(totalSec / 60)}мин")
        appendLine("  Аудио mic→AI (вход) : ${fmt1(sentSec)}с = ${fmt2(sentSec / 60)}мин")
        appendLine("  Аудио AI→mic (выход): ${fmt1(recvSec)}с = ${fmt2(recvSec / 60)}мин")
        val promptTokensPerConnect = if (connectCount > 0) promptTokens / connectCount else 0
        appendLine("  Ходов разговора     : $turnCount")
        appendLine("  Подключений         : $connectCount (переподкл. ${connectCount - 1})")
        appendLine("  Токены промпта (≈)  : ~${promptTokensPerConnect}ток/подкл × $connectCount = ~${promptTokens}ток всего")
        appendLine("───────────────────────────────────────")
        appendLine("")

        Log.i(TAG, "Session ended [$reason]: mic=${fmt1(sentSec)}s ai=${fmt1(recvSec)}s connects=$connectCount turns=$turnCount")
        writeToFile(context)
        sessionStart = 0L
    }

    fun readLog(context: Context): String =
        try {
            val f = logFile(context)
            if (f.exists()) f.readText().takeLast(8000) else "(Лог пуст — запусти разговор)"
        } catch (e: Exception) { "Ошибка чтения: ${e.message}" }

    fun clearLog(context: Context) {
        try { logFile(context).delete() } catch (e: Exception) { Log.e(TAG, "Clear failed: ${e.message}") }
    }

    private fun logFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_FILE)

    private fun appendLine(line: String) { logBuilder.appendLine(line) }

    private fun writeToFile(context: Context) {
        try {
            logFile(context).appendText(logBuilder.toString())
            logBuilder.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Write failed: ${e.message}")
        }
    }

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

    private fun fmt1(d: Double) = String.format(Locale.US, "%.1f", d)
    private fun fmt2(d: Double) = String.format(Locale.US, "%.2f", d)
}
