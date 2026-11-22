package com.wae.agent.core.grounding

import android.content.Context

/**
 * Grounding 2.0 裝置端自動標註（I5 資料飛輪；Phase 3）。
 *
 * 目的：**不需要人工標註**也能持續產生 grounding 訓練資料。
 * 每次 grounding 決策後，用「動作後畫面有沒有變 / 後置條件是否成立」當弱標籤：
 *
 * | 觀測 | label | reason |
 * |---|---|---|
 * | 動作後畫面有變化 | 1（正向） | `screen_changed` |
 * | 畫面沒變，但後置條件（PostCondition）成立 | 1（正向） | `postcondition` |
 * | 兩者皆無（預期有變化卻沒變） | 0（負向） | `no_change` |
 *
 * 輸出樣本寫到 `filesDir/grounding_samples.jsonl`（**只有 debug + debuggable 才寫**），
 * 離線用 `tools/grounding/train_grounding.py` 訓練成 `ui_grounding_weights.tsv`。
 *
 * 這支檔案刻意**不依賴** GroundingCandidate / GroundingTrace（G0/G1 的檔案）：
 * 特徵抽取由服務層（Lead 的 G3 整合）從候選元素抽成 `Map<String, Float>` 後傳進來，
 * 本物件只做「標註 + 序列化 + 寫檔」，因此是純 Kotlin（除 `record(Context?)`），JVM 可測。
 *
 * 隱私：樣本只留在裝置的 app 私有目錄或 E 槽，不上傳、不進 APK。
 */
data class GroundingSample(
    val features: Map<String, Float>,
    val label: Int,
    val reason: String
)

object OutcomeLabeler {

    /** 樣本檔名（測試會暫時改寫；正式值 = grounding_samples.jsonl） */
    const val DEFAULT_SAMPLES_FILE = "grounding_samples.jsonl"

    /** 當前樣本檔名。`var` 只為了單元測試與開發期除錯，正式路徑不要改。 */
    @Volatile
    var SAMPLES_FILE: String = DEFAULT_SAMPLES_FILE

    /** 三種自動標註理由（寫進 JSONL 的 reason 欄位） */
    const val REASON_SCREEN_CHANGED = "screen_changed"
    const val REASON_POSTCONDITION = "postcondition"
    const val REASON_NO_CHANGE = "no_change"

    /**
     * 一步 grounding 結果 → 一個弱標註樣本（純函式，不碰 Android API）。
     *
     * 規則與優先序：
     *  1. `changed == true`            → label=1, reason=`screen_changed`（畫面有反應，最泛用的正訊號）
     *  2. 否則 `postconditionVerified` → label=1, reason=`postcondition`（畫面沒變但條件成立，例如開關已開）
     *  3. 否則                         → label=0, reason=`no_change`（預期有變化卻沒變，負向）
     *
     * 「changed」優先於「postcondition」：畫面有變化是最直接證據；`postcondition` 只補
     * 「畫面無變化但仍算成功」的情境（例如勾選後 UI 不重繪、只是內部狀態改變）。
     *
     * @return null 代表「這步沒有可用的特徵訊號」（features 為空或全 0）→ 不產生樣本，
     *         避免 JSONL 出現沒有特徵的垃圾列。
     */
    fun labelStep(
        features: Map<String, Float>,
        changed: Boolean,
        postconditionVerified: Boolean
    ): GroundingSample? {
        if (features.isEmpty()) return null
        if (features.values.none { it != 0f && it.isFinite() }) return null
        val (label, reason) = when {
            changed -> 1 to REASON_SCREEN_CHANGED
            postconditionVerified -> 1 to REASON_POSTCONDITION
            else -> 0 to REASON_NO_CHANGE
        }
        return GroundingSample(features.toMap(), label, reason)
    }

    /**
     * 手寫 JSON（不引入任何 JSON 依賴）。輸出格式固定為：
     * ```
     * {"features":{"flag:clickable":1.000,"num:score":0.830},"label":1,"reason":"postcondition"}
     * ```
     *
     * - 欄位順序固定：features → label → reason（與 `train_grounding.py` 及 G0 trace 對齊）
     * - 值為 0 或非有限值（NaN/Inf）的特徵會被省略，避免權重訓練被污染
     * - 特徵名稱依字母排序，輸出穩定好 diff
     * - 數字固定 3 位小數並強制 `Locale.ROOT`（某些語系小數點是逗號，會毀掉 JSON）
     * - label 一律正規化成 0/1
     */
    fun toJson(sample: GroundingSample): String {
        val body = StringBuilder()
        val entries = sample.features.entries
            .filter { it.value != 0f && it.value.isFinite() }
            .sortedBy { it.key }
        for ((i, e) in entries.withIndex()) {
            if (i > 0) body.append(',')
            body.append('"').append(escapeJson(e.key)).append("\":")
            body.append(java.lang.String.format(java.util.Locale.ROOT, "%.3f", e.value))
        }
        val label = if (sample.label > 0) 1 else 0
        return buildString {
            append("{\"features\":{").append(body).append("},\"label\":").append(label)
            append(",\"reason\":\"").append(escapeJson(sample.reason)).append("\"}")
        }
    }

    /**
     * 把樣本寫進 `filesDir/grounding_samples.jsonl`（一行一個 JSON）。
     *
     * 安全/隱私防線（全部用 [runCatching]，任何失敗都吞掉，絕不影響主流程）：
     *  1. `context == null` → 直接 return（開發卡片/測試可以在沒有 Context 時安全呼叫）
     *  2. App 不是 `FLAG_DEBUGGABLE` → 不寫（release 版一個位元組都不留）
     *  3. 只寫到 app 私有 `filesDir`，不上傳、不寫外部儲存
     *
     * 拉檔（debug 版）：`adb shell run-as com.wae.agent cat files/grounding_samples.jsonl`
     */
    fun record(context: Context?, sample: GroundingSample) {
        val ctx = context ?: return
        runCatching {
            // 用完整類名避免 import android.content.pm.*；本檔只 import android.content.Context
            val flags = ctx.applicationInfo.flags
            if ((flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
            val file = java.io.File(ctx.filesDir, SAMPLES_FILE)
            // Kotlin 的 appendText 預設 UTF-8 且不寫 BOM；每筆樣本一行，符合 JSONL
            file.appendText(toJson(sample) + "\n")
        }
    }

    /** 清空樣本檔（開發期 adb/開發卡片用；回傳是否真的刪到東西） */
    fun clearSamples(context: Context?): Boolean {
        val ctx = context ?: return false
        return runCatching {
            val file = java.io.File(ctx.filesDir, SAMPLES_FILE)
            if (file.exists()) file.delete() else false
        }.getOrDefault(false)
    }

    /** JSON 字串轉義（只處理 JSON 必需的字元；樣本 key/reason 都是 ASCII 標籤） */
    internal fun escapeJson(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') {
                    sb.append(java.lang.String.format(java.util.Locale.ROOT, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}
