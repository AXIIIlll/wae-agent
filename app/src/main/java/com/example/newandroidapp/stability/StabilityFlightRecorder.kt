package com.example.newandroidapp.stability

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 【第 9 項】每一步結構化執行軌跡黑盒子日誌 (StabilityFlightRecorder)
 *
 * 完整記錄每一步的：時間、當前 App、畫面指紋、視覺耗時、AI 決策、動作後畫面是否變化、重試/降級次數與記憶體水位，
 * 支援即時回放檢視與持久化存檔。
 */
object StabilityFlightRecorder {

    private const val LOG_FILE_NAME = "stability_flight_logs.txt"
    private const val MAX_IN_MEMORY_LOGS = 250

    data class FlightStepRecord(
        val timestamp: String,
        val stepIndex: Int,
        val appLabel: String,
        val packageName: String,
        val screenFingerprint: String,
        val visibleNodesCount: Int,
        val visionLatencyMs: Long,
        val aiOpcode: String,
        val aiConfidence: Float,
        val aiLatencyMs: Long,
        val postActionVerified: Boolean,
        val retryOrFallbackInfo: String,
        val usedMemoryMb: Int,
        val outcomeNote: String
    ) {
        fun toCompactLine(): String {
            val verifyIcon = if (postActionVerified) "[變動有效]" else "[無變化]"
            return "[$timestamp] #$stepIndex [$appLabel] 節點:$visibleNodesCount(${visionLatencyMs}ms) -> $aiOpcode(信:${(aiConfidence * 100).toInt()}%, ${aiLatencyMs}ms) | $verifyIcon | $retryOrFallbackInfo | RAM:${usedMemoryMb}MB | $outcomeNote"
        }
    }

    private val records = CopyOnWriteArrayList<FlightStepRecord>()

    fun recordStep(
        context: Context?,
        stepIndex: Int,
        appLabel: String,
        packageName: String,
        screenFingerprint: String,
        visibleNodesCount: Int,
        visionLatencyMs: Long,
        aiOpcode: String,
        aiConfidence: Float,
        aiLatencyMs: Long,
        postActionVerified: Boolean,
        retryOrFallbackInfo: String,
        outcomeNote: String
    ) {
        val nowStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val ramMb = CrashGuardManager.checkMemoryPressureAndGc()
        val rec = FlightStepRecord(
            timestamp = nowStr,
            stepIndex = stepIndex,
            appLabel = appLabel,
            packageName = packageName,
            screenFingerprint = screenFingerprint,
            visibleNodesCount = visibleNodesCount,
            visionLatencyMs = visionLatencyMs,
            aiOpcode = aiOpcode,
            aiConfidence = aiConfidence,
            aiLatencyMs = aiLatencyMs,
            postActionVerified = postActionVerified,
            retryOrFallbackInfo = retryOrFallbackInfo,
            usedMemoryMb = ramMb,
            outcomeNote = outcomeNote
        )
        records.add(rec)
        while (records.size > MAX_IN_MEMORY_LOGS) {
            records.removeAt(0)
        }

        // 同步輸出到 logcat，方便以 `adb logcat -s AiFlight` 直接讀取每一步決策與驗證結果
        runCatching { android.util.Log.i("AiFlight", rec.toCompactLine()) }

        if (context != null) {
            runCatching {
                val f = File(context.filesDir, LOG_FILE_NAME)
                if (f.exists() && f.length() > 512 * 1024) {
                    val tail = f.readText().takeLast(200 * 1024)
                    f.writeText(tail + rec.toCompactLine() + "\n")
                } else {
                    f.appendText(rec.toCompactLine() + "\n")
                }
            }
        }
    }

    fun getRecentLogsText(maxCount: Int = 40): String {
        val snapshot = records.takeLast(maxCount).reversed()
        if (snapshot.isEmpty()) {
            return "尚無步驟執行紀錄。啟動自動任務或 5 分鐘壓力測試後，每一步的視覺指紋、AI 決策與驗證結果將實時記錄於此。"
        }
        return snapshot.joinToString("\n") { it.toCompactLine() }
    }

    fun clearLogs(context: Context?) {
        records.clear()
        if (context != null) {
            runCatching {
                val f = File(context.filesDir, LOG_FILE_NAME)
                if (f.exists()) f.delete()
            }
        }
    }
}
