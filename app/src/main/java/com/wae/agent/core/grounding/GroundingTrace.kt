package com.wae.agent.core.grounding

import android.content.Context

/**
 * 單一候選元素的 grounding 快照（G0 量測用）。
 *
 * 欄位刻意與 a11y 候選的最小可判定特徵對齊：幾何 + 文字 + 旗標 + 分數。
 * 這個類別是純資料，不含任何 Android API 呼叫，JVM 單元測試可直接建構。
 *
 * @param slot 候選在本次盤點中的序號（0 起算，對應畫面上的標記編號）
 * @param text 候選的可讀文字（fixture 版本會被 token 化成 T1/T2；原始 trace 保留原字）
 * @param className 節點類名（例如 Button / EditText；只保留型別語意，不保留資源 id）
 * @param viewId 節點資源 id（fixture 版本會被 token 化成 V1/V2）
 * @param bounds 螢幕座標 [left, top, right, bottom]；長度固定 4
 * @param score 融合後的信心分數（0..1 為主，允許超出以保留原始訊號）
 */
data class TraceCandidate(
    val slot: Int,
    val text: String,
    val className: String,
    val viewId: String,
    val bounds: IntArray,
    val clickable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val score: Float
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TraceCandidate) return false
        return slot == other.slot &&
            text == other.text &&
            className == other.className &&
            viewId == other.viewId &&
            bounds.contentEquals(other.bounds) &&
            clickable == other.clickable &&
            editable == other.editable &&
            checkable == other.checkable &&
            checked == other.checked &&
            enabled == other.enabled &&
            score.toRawBits() == other.score.toRawBits()
    }

    override fun hashCode(): Int {
        var result = slot
        result = 31 * result + text.hashCode()
        result = 31 * result + className.hashCode()
        result = 31 * result + viewId.hashCode()
        result = 31 * result + bounds.contentHashCode()
        result = 31 * result + clickable.hashCode()
        result = 31 * result + editable.hashCode()
        result = 31 * result + checkable.hashCode()
        result = 31 * result + checked.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + score.toRawBits()
        return result
    }

    override fun toString(): String {
        return "TraceCandidate(slot=$slot, text='$text', className='$className', " +
            "viewId='$viewId', bounds=${bounds.contentToString()}, clickable=$clickable, " +
            "editable=$editable, checkable=$checkable, checked=$checked, " +
            "enabled=$enabled, score=$score)"
    }
}

/**
 * 一步 grounding 決策的完整 trace（G0 / I6 可重播基準的資料單位）。
 *
 * 對應 [GroundingTraceRecorder] 落盤的 JSONL 一行；也是 fixture 的步資料來源
 * （fixture = trace 欄位 + labelIndex，見 [GroundingReplay]）。
 *
 * 設計原則：**只記錄，不影響控制流**。`validate()` 回傳錯誤清單而不是在
 * 建構子丟例外，避免觀測程式碼把主流程弄掛。
 *
 * @param pkg 目前前景套件（fixture 版本會被 token 化成 P1）
 * @param posture 頁面姿態（例如 FORM / LIST；字串化以方便跨層傳遞）
 * @param stateId 自動機狀態 id（來自既有 automaton；沒有則空字串）
 * @param goal 這一步的目標文字（fixture 版本會被 token 化成 T1）
 * @param candidates 本次 grounding 的候選清單（依決策前的順序，rank 由 `score` 決定）
 * @param chosenIndex 實際選中的候選索引；-1 表示 abstain（不動作）
 * @param changed 動作後畫面是否有變化
 * @param postconditionVerified 動作後後置條件是否成立
 * @param elapsedMs 這步 grounding 額外花費的毫秒數（驗收門檻 <30ms）
 */
data class GroundingTrace(
    val pkg: String,
    val posture: String,
    val stateId: String,
    val goal: String,
    val candidates: List<TraceCandidate>,
    val chosenIndex: Int,
    val changed: Boolean,
    val postconditionVerified: Boolean,
    val elapsedMs: Long
) {

    /** 純函式欄位驗證；回傳空清單代表合法。觀測資料不合法時只記錄、不丟例外。 */
    fun validate(): List<String> {
        val errors = ArrayList<String>(2)
        if (chosenIndex < -1 || chosenIndex >= candidates.size) {
            errors.add("chosenIndex=$chosenIndex 超出合法範圍 -1..${candidates.size - 1}")
        }
        if (elapsedMs < 0) errors.add("elapsedMs=$elapsedMs 不得為負")
        candidates.forEachIndexed { index, candidate ->
            if (candidate.bounds.size != 4) {
                errors.add("candidates[$index].bounds.size=${candidate.bounds.size}，應為 4")
            }
            if (!candidate.score.isFinite()) {
                errors.add("candidates[$index].score=${candidate.score} 不是有限值")
            }
        }
        return errors
    }
}

/**
 * Grounding trace 緩衝與落盤（G0 量測入口）。
 *
 * 流程：
 *  1. 每次 grounding 決策後呼叫 [record]（純記憶體，不碰 Android API）
 *  2. 任務結束（或每 N 步）由服務層呼叫 [flushToFile] 批次寫入
 *     `filesDir/grounding_trace.jsonl`（一行一個 [GroundingTrace]）
 *  3. 開發機用 `adb exec-out run-as com.wae.agent cat files/grounding_trace.jsonl`
 *     拉出來，交給 `tools/grounding/build_fixtures.py` 轉 fixture
 *
 * 安全防線：
 *  - 只有 **debug 版 + debuggable** 才寫檔；本專案 Gradle 沒啟用 BuildConfig，
 *    debug build 的 `ApplicationInfo.FLAG_DEBUGGABLE` 一定為 1、release 一定為 0，
 *    因此用它同時代表「debug 且可除錯」。
 *  - 所有檔案操作都包在 `runCatching`，任何失敗都吞掉，不影響主流程。
 *  - 只寫 app 私有目錄，不上傳、不進 APK、不寫外部儲存。
 *
 * 執行緒模型：以 `synchronized` 保護緩衝清單；假設 [flushToFile] 由單一執行緒呼叫
 * （服務層主流程），併發 flush 只會造成重複寫入，不會丟資料。
 */
object GroundingTraceRecorder {

    /** 正式落盤檔名 */
    const val DEFAULT_TRACE_FILE = "grounding_trace.jsonl"

    /** 緩衝上限；滿了就丟最舊的（trace 是觀測資料，不能無限吃記憶體） */
    const val DEFAULT_MAX_BUFFER = 512

    /** 當前檔名；`var` 只為了單元測試與開發期除錯 */
    @Volatile
    var TRACE_FILE: String = DEFAULT_TRACE_FILE

    /** 總開關；關閉後 [record] 直接丟棄，不佔記憶體 */
    @Volatile
    var ENABLED: Boolean = true

    /** 緩衝上限（可調低以驗證丟舊行為） */
    @Volatile
    var MAX_BUFFER: Int = DEFAULT_MAX_BUFFER

    private val buffer = ArrayList<GroundingTrace>()

    /** 因緩衝滿而被丟棄的步數（自我監控用） */
    @Volatile
    var droppedCount: Long = 0L
        private set

    /** 記錄一步（純記憶體）。關閉或已滿時安全丟棄，不丟例外。 */
    fun record(trace: GroundingTrace) {
        if (!ENABLED) return
        synchronized(buffer) {
            val cap = if (MAX_BUFFER > 0) MAX_BUFFER else DEFAULT_MAX_BUFFER
            while (buffer.size >= cap) {
                buffer.removeAt(0)
                droppedCount += 1
            }
            buffer.add(trace)
        }
    }

    /** 目前緩衝的步數（測試/監控用） */
    fun bufferedCount(): Int = synchronized(buffer) { buffer.size }

    /** 目前緩衝內容的唯讀快照（測試/監控用） */
    fun snapshot(): List<GroundingTrace> = synchronized(buffer) { buffer.toList() }

    /**
     * 把緩衝中的 trace 轉成 JSONL 文字（不含結尾換行）。
     *
     * 給測試與工具預覽用；正式落盤請走 [flushToFile]。
     */
    fun toJson(): String = synchronized(buffer) { buffer.joinToString("\n") { toJson(it) } }

    /**
     * 單步 trace → 一行 JSON（手寫，不引入 JSON 依賴）。
     *
     * 格式（欄位順序固定，方便 diff 與 fixture 解析）：
     * ```
     * {"pkg":"...","posture":"...","stateId":"...","goal":"...","chosenIndex":0,
     *  "changed":true,"postconditionVerified":false,"elapsedMs":7,
     *  "candidates":[{"slot":0,"text":"...","className":"...","viewId":"...",
     *  "bounds":[0,0,100,50],"clickable":true,"editable":false,"checkable":false,
     *  "checked":false,"enabled":true,"score":0.910}]}
     * ```
     * - 分數固定 3 位小數並強制 `Locale.ROOT`（避免某些語系輸出逗號小數點）
     * - 非有限分數（NaN/Inf）正規化成 0.000，確保輸出永遠是合法 JSON
     */
    fun toJson(trace: GroundingTrace): String {
        val sb = StringBuilder(256 + trace.candidates.size * 160)
        sb.append("{\"pkg\":\"").append(escapeJson(trace.pkg)).append('"')
        sb.append(",\"posture\":\"").append(escapeJson(trace.posture)).append('"')
        sb.append(",\"stateId\":\"").append(escapeJson(trace.stateId)).append('"')
        sb.append(",\"goal\":\"").append(escapeJson(trace.goal)).append('"')
        sb.append(",\"chosenIndex\":").append(trace.chosenIndex)
        sb.append(",\"changed\":").append(trace.changed)
        sb.append(",\"postconditionVerified\":").append(trace.postconditionVerified)
        sb.append(",\"elapsedMs\":").append(trace.elapsedMs)
        sb.append(",\"candidates\":[")
        for ((index, candidate) in trace.candidates.withIndex()) {
            if (index > 0) sb.append(',')
            appendCandidateJson(sb, candidate)
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 把緩衝寫進 `filesDir/grounding_trace.jsonl`（append）。
     *
     * @return true = 確實寫入至少一行；false = 沒寫（context 為 null / release 版 /
     *         緩衝為空 / IO 失敗）。任何情況都不丟例外。
     */
    fun flushToFile(context: Context?): Boolean {
        val ctx = context ?: return false
        return runCatching {
            if (!ENABLED) return false
            if (!isDebuggable(ctx)) return false
            flushToDirectory(ctx.filesDir)
        }.getOrDefault(false)
    }

    /**
     * [flushToFile] 的純 JVM 版本：直接寫到指定目錄（測試用）。
     *
     * 注意：這裡**不**檢查 debuggable，只負責序列化與寫檔；debug 版判定在
     * [flushToFile] 的 Context 版本。命名刻意不同，避免多載歧義。
     */
    fun flushToDirectory(dir: java.io.File?): Boolean {
        val targetDir = dir ?: return false
        val pending = synchronized(buffer) {
            if (buffer.isEmpty()) return false
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        val text = pending.joinToString("\n") { toJson(it) } + "\n"
        val ok = runCatching {
            if (!targetDir.exists() && !targetDir.mkdirs()) return false
            // Kotlin appendText 預設 UTF-8 且不寫 BOM，符合專案檔案規範
            java.io.File(targetDir, TRACE_FILE).appendText(text, Charsets.UTF_8)
            true
        }.getOrDefault(false)
        if (!ok) {
            // 寫失敗就把資料放回緩衝，下一輪再試
            synchronized(buffer) { buffer.addAll(0, pending) }
        }
        return ok
    }

    /**
     * 測試專用重置：清空緩衝、還原開關/檔名/上限/丟棄計數。
     * （正式流程不要呼叫；避免測試之間互相污染）
     */
    fun resetForTest() {
        synchronized(buffer) { buffer.clear() }
        TRACE_FILE = DEFAULT_TRACE_FILE
        ENABLED = true
        MAX_BUFFER = DEFAULT_MAX_BUFFER
        droppedCount = 0L
    }

    private fun isDebuggable(context: Context): Boolean {
        // 用完整類名避免 import android.content.pm.*；本檔只 import android.content.Context
        val flags = context.applicationInfo.flags
        return (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun appendCandidateJson(sb: StringBuilder, candidate: TraceCandidate) {
        sb.append("{\"slot\":").append(candidate.slot)
        sb.append(",\"text\":\"").append(escapeJson(candidate.text)).append('"')
        sb.append(",\"className\":\"").append(escapeJson(candidate.className)).append('"')
        sb.append(",\"viewId\":\"").append(escapeJson(candidate.viewId)).append('"')
        sb.append(",\"bounds\":[")
        for ((index, value) in candidate.bounds.withIndex()) {
            if (index > 0) sb.append(',')
            sb.append(value)
        }
        sb.append(']')
        sb.append(",\"clickable\":").append(candidate.clickable)
        sb.append(",\"editable\":").append(candidate.editable)
        sb.append(",\"checkable\":").append(candidate.checkable)
        sb.append(",\"checked\":").append(candidate.checked)
        sb.append(",\"enabled\":").append(candidate.enabled)
        val score = if (candidate.score.isFinite()) candidate.score else 0f
        sb.append(",\"score\":")
        sb.append(java.lang.String.format(java.util.Locale.ROOT, "%.3f", score))
        sb.append('}')
    }

    /** JSON 字串轉義（只處理 JSON 必需字元；其餘（含 UTF-8 中文）原樣輸出） */
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
