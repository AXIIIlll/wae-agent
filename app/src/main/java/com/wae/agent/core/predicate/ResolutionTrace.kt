package com.wae.agent.core.predicate

import android.content.Context
import java.io.File

/**
 * 解析軌跡（P0）：把「這一刻的畫面 → 選了什麼」寫成一行 TSV。
 *
 * 為什麼要落盤：沒有軌跡就沒有「怎麼量」，而這個專案已經吃過四次
 * 「合成資料看起來很好、真機一測就崩」的虧。這裡記錄的是**真實解析**，
 * 之後可以直接用執行結果當標籤，不需要人工標註、也不需要合成資料。
 *
 * 紀律：
 *  - 只寫不讀，不影響任何決策；
 *  - 內容只有畫面指紋、目標文字、動作與分數，不含截圖；
 *  - 檔案超過上限就重置，避免長期使用把手機塞滿。
 */
object ResolutionTrace {

    private const val DIR = "eval"
    private const val FILE_NAME = "resolution_trace.tsv"
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val QUERY_LIMIT = 120
    private const val ACTION_LIMIT = 80

    const val KEY_ENABLED = "match.trace_enabled"

    fun enabled(context: Context?): Boolean {
        if (context == null) return true
        return com.wae.agent.core.runtime.Tunables.getBool(context, KEY_ENABLED, true)
    }

    /** 一行軌跡（純函式，JVM 可測；不含 IO）。 */
    fun formatLine(
        tsMs: Long,
        pkg: String,
        frameFp: String,
        query: String,
        step: Int,
        action: String,
        candidates: Int,
        score: Float,
        floor: Float,
        note: String
    ): String = listOf(
        tsMs.toString(),
        clean(pkg, 80),
        clean(frameFp, 16),
        clean(query, QUERY_LIMIT),
        step.toString(),
        clean(action, ACTION_LIMIT),
        candidates.toString(),
        fmt(score),
        fmt(floor),
        clean(note, 40)
    ).joinToString("\t")

    fun header(): String = "ts\tpkg\tframe\tquery\tstep\taction\tcandidates\tscore\tfloor\tnote"

    private fun fmt(v: Float): String = if (v < 0f) "" else String.format(java.util.Locale.US, "%.3f", v)

    /** 欄位分隔與換行會弄壞 TSV，一律壓成空白並截斷。 */
    private fun clean(s: String, limit: Int): String =
        s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim().take(limit)

    @Synchronized
    fun record(
        context: Context?,
        pkg: String,
        frameFp: String,
        query: String,
        step: Int,
        action: String,
        candidates: Int,
        score: Float = -1f,
        floor: Float = -1f,
        note: String = ""
    ) {
        if (context == null || !enabled(context)) return
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val f = File(dir, FILE_NAME)
            if (f.exists() && f.length() > MAX_BYTES) f.delete()
            if (!f.exists()) f.writeText(header() + "\n", Charsets.UTF_8)
            f.appendText(
                formatLine(
                    System.currentTimeMillis(), pkg, frameFp, query, step, action, candidates, score, floor, note
                ) + "\n",
                Charsets.UTF_8
            )
        }
    }
}
