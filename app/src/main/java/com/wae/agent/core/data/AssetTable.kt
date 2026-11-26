package com.wae.agent.core.data

import android.content.Context

/**
 * assets 內的資料表（TSV／txt）讀取器：**每個檔案只讀一次並快取**。
 *
 * 為什麼要抽這個：專案裡原本有 21 個類別各自複製同一段樣板——
 *
 * ```kotlin
 * @Volatile private var loadAttempted = false
 * fun ensureLoaded(context: Context?) {
 *     if (loadAttempted) return
 *     loadAttempted = true
 *     try { lines = context?.assets?.open(DB_PATH)... } catch (_: Throwable) { ... }
 * }
 * ```
 *
 * 問題不是「重複」而已，而是**修正永遠只改到其中幾份**：
 * 例如之後要加「資料檔版本檢查」或「讀檔失敗要留痕」，得改 21 個地方。
 *
 * 現在的行為刻意保持與舊版一致：
 *  - 讀檔失敗回 null／fallback，不拋例外
 *  - 同一個 [AssetTable] 實例只嘗試讀一次（失敗也記住，不重試）
 */
class AssetTable(
    private val path: String,
    /**
     * JVM 單元測試沒有 Android Context 時，改從工作目錄找檔的相對路徑清單。
     * Gradle 測試的 cwd 是模組根，所以列「模組內」與「往上一層」兩種；不寫死絕對路徑。
     */
    private val jvmFallbackPaths: List<String> = emptyList()
) {

    @Volatile
    private var attempted = false

    @Volatile
    private var cached: List<String>? = null

    /** 讀一次並快取；讀不到回 null（呼叫端自己決定 fallback）。 */
    fun linesOrNull(context: Context?): List<String>? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            if (attempted) return null
            attempted = true
            var got = com.wae.core.util.Degrade.orNull("asset:$path") {
                context?.assets?.open(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
            }
            if (got == null && jvmFallbackPaths.isNotEmpty()) {
                // 沒有 Context（單元測試）或有 Context 但 assets 沒有這個檔 → 從工作目錄找
                got = jvmFallbackPaths.asSequence()
                    .map { java.io.File(it) }
                    .firstOrNull { it.isFile }
                    ?.let { f ->
                        com.wae.core.util.Degrade.orNull("asset:file:$path") {
                            f.readLines(Charsets.UTF_8)
                        }
                    }
            }
            cached = got
            return got
        }
    }

    /** 測試用：忘掉已讀過的內容，下次重新讀。 */
    fun reset() {
        synchronized(this) {
            attempted = false
            cached = null
        }
    }

    /** 讀一次並快取；讀不到用 [fallback]。 */
    fun lines(context: Context?, fallback: List<String>): List<String> =
        linesOrNull(context) ?: fallback

    /** 去掉空行與 `#` 註解，並依 tab 切欄（多數資料表的共同格式）。 */
    fun rows(context: Context?): List<List<String>> =
        (linesOrNull(context) ?: emptyList()).asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split('\t') }
            .toList()
}
