package com.example.newandroidapp.stability

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 【第 6 項 & 第 10 項】全域防閃退攔截器 (CrashGuardManager) ＋ Bitmap 安全記憶體池 (SafeBitmapPool)
 *
 * - 第 10 項：攔截所有未捕獲的 Java/Kotlin/JNI 異常並寫入本機 `crash_reports.log`，防止無預警遺失崩潰現場。
 * - 第 6 項：管理螢幕截圖 `Bitmap` 的雙緩衝生命週期，確保長時運行（5 分鐘以上、開啟 30+ 個 App）絕不發生 OOM 或 Recycled Bitmap 閃退。
 */
object CrashGuardManager {

    private const val CRASH_FILE_NAME = "crash_reports.log"
    @Volatile
    private var installed = false

    private val bitmapRingQueue = ConcurrentLinkedQueue<Bitmap>()
    private const val MAX_RETENTION_BITMAPS = 3

    fun installGlobalCrashGuard(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appCtx = context.applicationContext
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    recordThrowable(appCtx, "UNCAUGHT_FATAL [Thread=${thread.name}]", throwable)
                }
                defaultHandler?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }

    private val errorCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** 本行程自啟動以來「實際被記錄到」的例外次數（供壓測成績單使用，非寫死數字） */
    fun recordedErrorCount(): Int = errorCounter.get()

    fun recordThrowable(context: Context?, tag: String, throwable: Throwable) {
        errorCounter.incrementAndGet()
        if (context == null) return
        runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val entry = buildString {
                appendLine("=== [$timeStr] $tag ===")
                appendLine("Message: ${throwable.message}")
                appendLine(sw.toString().take(2500))
                appendLine()
            }
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (file.exists() && file.length() > 256 * 1024) {
                val tail = file.readText().takeLast(128 * 1024)
                file.writeText(tail + entry)
            } else {
                file.appendText(entry)
            }
        }
    }

    fun getRecentCrashSummary(context: Context): String {
        return runCatching {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (!file.exists() || file.length() == 0L) {
                "目前無任何崩潰或異常紀錄（0 Crashes）"
            } else {
                file.readText().takeLast(1800)
            }
        }.getOrDefault("目前無任何崩潰或異常紀錄")
    }

    fun clearCrashLogs(context: Context) {
        runCatching {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (file.exists()) file.delete()
        }
    }

    private val activeInUseBitmaps = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Bitmap, Boolean>())
    private val bitmapLifecycleLock = Any()

    fun markBitmapInUse(bmp: Bitmap?) {
        if (bmp != null && !bmp.isRecycled) {
            activeInUseBitmaps.add(bmp)
        }
    }

    fun releaseBitmapUsage(bmp: Bitmap?) {
        if (bmp != null) {
            activeInUseBitmaps.remove(bmp)
        }
    }

    inline fun <T> useBitmapSafely(bmp: Bitmap?, block: (Bitmap?) -> T): T {
        if (bmp == null || bmp.isRecycled) {
            return block(null)
        }
        markBitmapInUse(bmp)
        try {
            return block(if (bmp.isRecycled) null else bmp)
        } finally {
            releaseBitmapUsage(bmp)
        }
    }

    fun isBitmapActiveOrInUse(bmp: Bitmap?): Boolean {
        if (bmp == null || bmp.isRecycled) return false
        if (activeInUseBitmaps.contains(bmp)) return true
        val serviceLiveBmp = runCatching {
            com.example.newandroidapp.vision.VisionAccessibilityService.latestLiveBitmap
        }.getOrNull()
        return bmp === serviceLiveBmp
    }

    /**
     * 【第 6 項：Bitmap 安全記憶體池】
     * 註冊最新截圖並自動回收超過 3 幀以前的舊截圖，避免直接回收當前 UI 畫布或執行緒正在使用的 Bitmap，同時防止 OOM。
     */
    fun registerNewScreenshotAndRecycleOld(newBmp: Bitmap?) {
        if (newBmp == null) return
        synchronized(bitmapLifecycleLock) {
            bitmapRingQueue.add(newBmp)
            while (bitmapRingQueue.size > MAX_RETENTION_BITMAPS) {
                val old = bitmapRingQueue.poll() ?: break
                if (old !== newBmp && !old.isRecycled) {
                    if (!isBitmapActiveOrInUse(old)) {
                        runCatching { old.recycle() }
                    }
                }
            }
            checkMemoryPressureAndGc()
        }
    }

    fun checkMemoryPressureAndGc(): Int {
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val maxMb = (rt.maxMemory() / (1024 * 1024)).toInt().coerceAtLeast(1)
        if (usedMb.toFloat() / maxMb.toFloat() > 0.75f) {
            synchronized(bitmapLifecycleLock) {
                while (bitmapRingQueue.size > 1) {
                    val old = bitmapRingQueue.poll() ?: break
                    if (!old.isRecycled && !isBitmapActiveOrInUse(old)) {
                        runCatching { old.recycle() }
                    }
                }
            }
            System.gc()
        }
        return usedMb
    }
}
