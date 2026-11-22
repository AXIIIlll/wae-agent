package com.wae.agent.core.decision

import android.content.Context
import com.wae.agent.core.CandidateOption

/**
 * 內容項證據（B4 的根因修法）。
 *
 * 問題：CLASS 級目標（「點開第一封信」「隨機點一個影片」）無法用語意相似度挑選，
 * 因為列表項的文字本來就不會跟指令同名。舊版只靠「容器型別 + 相似度 + 面積」，
 * 於是把**導覽型列**（標籤/垃圾桶/寄件備份/設定）當成內容項點下去。
 *
 * 根因：CLASS 級選擇沒有「內容項長什麼樣」的模型。
 * 解法：用**結構證據**給分，並排除導覽型列：
 *
 * | 證據 | 分數 |
 * |---|---|
 * | 文字長度（內容項通常較長） | 0–2.0 |
 * | 多段文字（寄件者/主旨/摘要） | +1.5 |
 * | 含時間/日期樣式 | +1.0 |
 * | 位於清單容器（LIST_ITEM / CARD） | +1.0 |
 * | 導覽型關鍵詞（資料驅動） | −3.0 |
 */
object ContentItemEvidence {

    private const val DB_PATH = "open_source_ai_databases/ui_navigation_row_keywords.tsv"

    private val DEFAULT_NAV = listOf(
        "標籤", "寄件備份", "垃圾桶", "所有郵件", "草稿", "已寄出", "促銷內容",
        "社交", "更新", "待辦", "設定", "說明", "登出", "帳戶", "更多",
        "資料夾", "分類", "返回", "向上瀏覽"
    )

    @Volatile
    private var navKeywords: List<String> = emptyList()

    @Volatile
    private var loadAttempted = false

    private val TIME_HINT = Regex("(\\d{1,2}:\\d{2})|(\\d{1,2}月\\d{1,2}日)|(昨天)|(星期[一二三四五六日])|(\\d{4}/\\d{1,2}/\\d{1,2})")

    fun ensureLoaded(context: Context?) {
        if (loadAttempted || context == null) return
        loadAttempted = true
        try {
            val lines = context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            loadFromLines(lines)
        } catch (_: Throwable) {
            navKeywords = DEFAULT_NAV
        }
    }

    fun loadFromLines(lines: List<String>) {
        val out = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.isEmpty() || p[0].isBlank()) continue
            out.add(p[0].trim())
        }
        navKeywords = if (out.isEmpty()) DEFAULT_NAV else out
    }

    fun resetForTest() {
        navKeywords = emptyList()
        loadAttempted = false
    }

    fun keywords(): List<String> = if (navKeywords.isEmpty()) DEFAULT_NAV else navKeywords

    /** 是否為導覽型列（資料夾/分類/設定入口）——這類不可被當成「內容項」 */
    fun isNavigationRow(c: CandidateOption): Boolean {
        val core = c.coreText.trim()
        val full = (c.coreText + " " + c.weldedDescription).trim()
        if (full.isEmpty()) return false
        // 1) 標籤本身很短（資料夾/分類/設定入口）→ 用包含比對
        if (core.isNotEmpty() && core.length <= 8) {
            if (keywords().any { core.equals(it, ignoreCase = true) || core.contains(it, ignoreCase = true) }) {
                return true
            }
        }
        // 2) 完整文字必須「完全等於」導覽詞才算（避免誤殺含該詞的正常內容）
        return keywords().any { full.equals(it, ignoreCase = true) }
    }

    /** 內容項證據分數（0–5.5）；導覽型列直接大幅扣分 */
    fun score(c: CandidateOption): Float {
        var s = 0f
        val text = c.weldedDescription.ifEmpty { c.coreText }
        // 1) 文字長度
        s += when {
            text.length >= 40 -> 2.0f
            text.length >= 20 -> 1.5f
            text.length >= 10 -> 0.8f
            else -> 0f
        }
        // 2) 多段文字（寄件者、主旨、摘要被焊接在一起）
        val fragments = text.split("、", "｜", "\n", "  ").count { it.trim().length >= 2 }
        if (fragments >= 3) s += 1.5f
        else if (fragments == 2) s += 0.8f
        // 3) 時間/日期樣式
        if (TIME_HINT.containsMatchIn(text)) s += 1.0f
        // 4) 位於清單容器
        if (c.parentContainerType == "LIST_ITEM" || c.parentContainerType == "CARD") s += 1.0f
        // 5) 導覽型列扣分
        if (isNavigationRow(c)) s -= 3.0f
        return s
    }

    /** 是否值得當成「內容項」候選 */
    fun isContentItem(c: CandidateOption, minScore: Float = 1.5f): Boolean =
        !isNavigationRow(c) && score(c) >= minScore
}
