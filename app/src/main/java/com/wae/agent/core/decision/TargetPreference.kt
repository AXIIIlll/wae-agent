package com.wae.agent.core.decision

import android.content.Context
import com.wae.agent.core.ActionKind
import com.wae.agent.core.CandidateOption

/**
 * 動作目標偏好（**資料驅動**）：同一種動作下，哪個候選更適合。
 *
 * 為什麼需要它（B3／B4 的根因修法）：
 * 舊版對 TYPE 只給扁平加成（搜尋 id +1.4、可編輯 +0.6），
 * 於是「設定」頁的 `search_plate`（入口）、`語音搜尋`（按鈕）、`search_src_text`（真輸入框）
 * 全部被加到 0.97 同分 → 觸發 NEAR_TIE 歧義 → 直接放棄。
 *
 * 根因不是分數不夠高，而是**歧義用錯了維度**：
 * 歧義應該定義在「與該動作相關的特徵」上，而不是原始語意相似度。
 *
 * 這裡提供兩個能力：
 *  1. [score]：動作相關偏好分（加進決策 logits，也用於排序）
 *  2. [isEquivalent]：兩個候選在該動作下是否「真的等價」（= 該問使用者）
 *  3. [needsEntryLadder]：目標是輸入，但畫面上只有「入口」沒有輸入框 → 需要兩段式
 *
 * 資料檔：`assets/open_source_ai_databases/ui_target_preference.tsv`
 */
object TargetPreference {

    private const val DB_PATH = "open_source_ai_databases/ui_target_preference.tsv"

    /** 兩者在偏好上差距小於此值，才可能被視為等價（真正需要澄清） */
    const val EQUIVALENCE_GAP = 0.25f

    private data class Feature(val kind: String, val arg: String, val weight: Float)

    @Volatile
    private var weights: Map<String, List<Feature>> = emptyMap()

    @Volatile
    private var loadAttempted = false

    /** 內建 bootstrap（資料表載入失敗時使用，確保行為不退化） */
    private val DEFAULT_ROWS = listOf(
        "TYPE\tflag:isEditable\t2.6",
        "TYPE\ttoken:src_text\t2.2",
        "TYPE\ttoken:search_src\t2.0",
        "TYPE\ttoken:edit\t1.2",
        "TYPE\tflag:hasSearchIdHint\t0.6",
        "TYPE\tflag:isSearchAffordance\t0.4",
        "TYPE\tflag:isBottomNavigationTab\t-3.0",
        "TAP\tflag:isClickable\t1.0",
        "TAP\tflag:isEditable\t-1.5"
    )

    fun ensureLoaded(context: Context?) {
        if (loadAttempted || context == null) return
        loadAttempted = true
        try {
            val lines = context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            loadFromLines(lines)
        } catch (_: Throwable) {
            loadFromLines(DEFAULT_ROWS)
        }
    }

    fun loadFromLines(lines: List<String>) {
        val map = HashMap<String, MutableList<Feature>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 3) continue
            val action = p[0].trim().uppercase()
            val spec = p[1].trim()
            val weight = p[2].trim().toFloatOrNull() ?: continue
            val parts = spec.split(":", limit = 2)
            if (parts.size < 2) continue
            map.getOrPut(action) { ArrayList() }.add(Feature(parts[0], parts[1], weight))
        }
        weights = map
    }

    fun resetForTest() {
        weights = emptyMap()
        loadAttempted = false
    }

    fun ruleCount(): Int = weights.values.sumOf { it.size }

    private fun effective(): Map<String, List<Feature>> =
        if (weights.isEmpty()) {
            val map = HashMap<String, MutableList<Feature>>()
            for (raw in DEFAULT_ROWS) {
                val p = raw.split("\t")
                val parts = p[1].split(":", limit = 2)
                map.getOrPut(p[0].uppercase()) { ArrayList() }
                    .add(Feature(parts[0], parts[1], p[2].toFloat()))
            }
            map
        } else weights

    private fun featureValue(f: Feature, c: CandidateOption): Boolean = when (f.kind) {
        "flag" -> when (f.arg) {
            "isEditable" -> c.isEditable
            "isClickable" -> c.isClickable
            "isSearchAffordance" -> c.isSearchAffordance
            "hasSearchIdHint" -> c.hasSearchIdHint
            "isBottomNavigationTab" -> c.isBottomNavigationTab
            else -> false
        }
        "token" -> c.viewId.lowercase().contains(f.arg.lowercase())
        else -> false
    }

    /** 動作相關偏好分（可正可負；0 表示不影響） */
    fun score(actionKind: ActionKind, c: CandidateOption): Float {
        val list = effective()[actionKind.name] ?: return 0f
        var s = 0f
        for (f in list) if (featureValue(f, c)) s += f.weight
        return s
    }

    /**
     * 兩個候選在該動作下是否「真的等價」。
     * 只有等價才值得打斷使用者去澄清；偏好明顯不同就直接選。
     */
    fun isEquivalent(actionKind: ActionKind, a: CandidateOption, b: CandidateOption): Boolean {
        // TYPE 的語意是「找輸入框」：能輸入的候選 vs 不能輸入的候選，永遠不等價。
        // （實測：YouTube 首頁的「搜尋」圖示 0.86 與影片卡片 0.84 曾因此被誤判為歧義而放棄）
        if (actionKind == ActionKind.TYPE) {
            val inputA = a.isEditable || a.isSearchAffordance || a.hasSearchIdHint
            val inputB = b.isEditable || b.isSearchAffordance || b.hasSearchIdHint
            if (inputA != inputB) return false
        }
        return kotlin.math.abs(score(actionKind, a) - score(actionKind, b)) < EQUIVALENCE_GAP
    }

    /**
     * 目標是「輸入」但最佳候選只是入口（可點、非可編輯）→ 需要先點開再打字。
     */
    fun needsEntryLadder(actionKind: ActionKind, c: CandidateOption): Boolean =
        actionKind == ActionKind.TYPE &&
            !c.isEditable &&
            (c.hasSearchIdHint || c.isSearchAffordance) &&
            c.isClickable
}
