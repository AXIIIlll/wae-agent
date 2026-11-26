package com.wae.agent.core.grounding

import android.content.Context
import com.wae.agent.core.data.AssetTable

/**
 * 動態 UI 辨識（S32 / 計畫「動態 UI」）：畫面不一定已經準備好可以點。
 *
 * 常見的五種「現在不該硬點」的情況：
 *  1. [DynamicUiState.LOADING]     載入中（轉圈、骨架屏、ProgressBar）
 *  2. [DynamicUiState.AD_OVERLAY]  廣告 / 蓋板 / 評分彈窗
 *  3. [DynamicUiState.PERMISSION_DIALOG] 系統權限彈窗（要使用者決定）
 *  4. [DynamicUiState.KEYBOARD_OCCLUSION] 鍵盤遮住目標區域
 *  5. [DynamicUiState.SNACKBAR]    底部 Snackbar/Toast 短暫蓋住內容
 *
 * 判定完全資料驅動（關鍵詞在 `ui_dynamic_ui_words.tsv`），幾何訊號
 * （鍵盤高度、modal overlay）由呼叫端傳入。純 Kotlin，JVM 可測。
 */
object DynamicUiClassifier {

    private const val DB_PATH = "open_source_ai_databases/ui_dynamic_ui_words.tsv"

    /** TSV 載入失敗時的 bootstrap（只放最常見、最不容易誤判的詞） */
    private val DEFAULT_ROWS = listOf(
        "loading\t載入中", "loading\t正在載入", "loading\t請稍候", "loading\tloading",
        "loading\t處理中", "loading\t請等待",
        "ad\t廣告", "ad\t贊助", "ad\t立即下載", "ad\t略過", "ad\tskip ad",
        "permission\t允許", "permission\t拒絕", "permission\t權限", "permission\tallow",
        "permission\tdeny", "permission\t僅在使用時允許",
        "snackbar\t已儲存", "snackbar\t已複製", "snackbar\t已刪除", "snackbar\t已加入",
        "snackbar\t復原", "snackbar\tundo"
    )

    /** 每種狀態的關鍵詞（lowercase） */
    private val words = HashMap<DynamicUiState, MutableList<String>>()

    private val table = AssetTable(DB_PATH)

    data class Signal(
        val state: DynamicUiState,
        val confidence: Float,
        val evidence: String
    ) {
        val isBlocking: Boolean
            get() = state == DynamicUiState.PERMISSION_DIALOG ||
                state == DynamicUiState.AD_OVERLAY ||
                state == DynamicUiState.LOADING
    }

    fun ensureLoaded(context: Context?) {
        loadFromLines(table.lines(context, DEFAULT_ROWS))
    }

    /** 解析：`state<TAB>keyword`；state ∈ loading / ad / permission / snackbar / keyboard */
    fun loadFromLines(lines: List<String>) {
        val map = HashMap<DynamicUiState, MutableList<String>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 2) continue
            val state = stateOf(p[0]) ?: continue
            val w = p[1].trim().lowercase()
            if (w.isEmpty()) continue
            map.getOrPut(state) { ArrayList() }.add(w)
        }
        synchronized(words) {
            words.clear()
            words.putAll(map)
        }
    }

    private fun stateOf(raw: String): DynamicUiState? = when (raw.trim().lowercase()) {
        "loading" -> DynamicUiState.LOADING
        "ad" -> DynamicUiState.AD_OVERLAY
        "permission" -> DynamicUiState.PERMISSION_DIALOG
        "snackbar" -> DynamicUiState.SNACKBAR
        "keyboard" -> DynamicUiState.KEYBOARD_OCCLUSION
        else -> null
    }

    fun resetForTest() {
        synchronized(words) { words.clear() }
        table.reset()
    }

    fun wordCount(): Int = synchronized(words) { words.values.sumOf { it.size } }

    /**
     * 判定目前畫面狀態。
     *
     * 優先序（越前面越該先處理）：
     *  permission > ad > loading > keyboard > snackbar > none
     * 因為權限彈窗與廣告會蓋住整個畫面，鍵盤只遮下半部。
     */
    fun classify(
        visibleTexts: List<String>,
        hasModalOverlay: Boolean = false,
        imeVisible: Boolean = false,
        imeTopPx: Int = 0,
        screenHeightPx: Int = 0,
        progressBarCount: Int = 0
    ): Signal {
        val hay = visibleTexts.joinToString(" ").lowercase()
        val snapshot = synchronized(words) { words.mapValues { it.value.toList() } }

        // 1) 權限彈窗：關鍵詞 + 模態遮罩
        hit(snapshot, DynamicUiState.PERMISSION_DIALOG, hay)?.let {
            if (hasModalOverlay) return Signal(DynamicUiState.PERMISSION_DIALOG, 0.9f, "kw=$it+modal")
            return Signal(DynamicUiState.PERMISSION_DIALOG, 0.65f, "kw=$it")
        }

        // 2) 廣告：關鍵詞 + 模態遮罩
        hit(snapshot, DynamicUiState.AD_OVERLAY, hay)?.let {
            if (hasModalOverlay) return Signal(DynamicUiState.AD_OVERLAY, 0.85f, "kw=$it+modal")
        }

        // 3) 載入中：關鍵詞或進度條
        hit(snapshot, DynamicUiState.LOADING, hay)?.let {
            return Signal(DynamicUiState.LOADING, if (progressBarCount > 0) 0.9f else 0.6f, "kw=$it")
        }
        if (progressBarCount > 0 && hasModalOverlay) {
            return Signal(DynamicUiState.LOADING, 0.55f, "progress+modal")
        }

        // 4) 鍵盤遮擋：IME 存在且頂端高於畫面 55%
        if (imeVisible && screenHeightPx > 0 && imeTopPx in 1 until (screenHeightPx * 55 / 100)) {
            return Signal(DynamicUiState.KEYBOARD_OCCLUSION, 0.8f, "imeTop=$imeTopPx/$screenHeightPx")
        }

        // 5) Snackbar：關鍵詞命中但沒有 modal（短暫、不該中止）
        hit(snapshot, DynamicUiState.SNACKBAR, hay)?.let {
            return Signal(DynamicUiState.SNACKBAR, 0.5f, "kw=$it")
        }

        return Signal(DynamicUiState.NONE, 0f, "")
    }

    private fun hit(snapshot: Map<DynamicUiState, List<String>>, state: DynamicUiState, hay: String): String? {
        val list = snapshot[state] ?: return null
        for (w in list) {
            if (w.isNotEmpty() && hay.contains(w)) return w
        }
        return null
    }

    /** 建議等待時間（毫秒）：載入中要多等一點，Snackbar 等它消失 */
    fun suggestedWaitMs(state: DynamicUiState): Long = when (state) {
        DynamicUiState.LOADING -> 600L
        DynamicUiState.AD_OVERLAY -> 400L
        DynamicUiState.PERMISSION_DIALOG -> 0L
        DynamicUiState.KEYBOARD_OCCLUSION -> 300L
        DynamicUiState.SNACKBAR -> 350L
        DynamicUiState.NONE -> 0L
    }

    enum class DynamicUiState {
        NONE, LOADING, AD_OVERLAY, PERMISSION_DIALOG, KEYBOARD_OCCLUSION, SNACKBAR
    }
}
