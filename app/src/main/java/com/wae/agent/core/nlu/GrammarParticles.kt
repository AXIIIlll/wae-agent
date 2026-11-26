package com.wae.agent.core.nlu

import android.content.Context
import com.wae.agent.core.data.AssetTable

/**
 * 語法助詞表（介詞與方位助詞）：**資料驅動，不含任何 App 資料**。
 *
 * 為什麼需要它（B1 的根因修法）：
 * 舊的拆解是「字串手術」——找到 App 別名，切掉別名之後的所有文字。
 * 但真正的「App 片語」是：
 *
 * ```
 *   [在|到|進|從|打開|進入] + <App 別名> + [中|裡|內|上|的|之後]
 * ```
 *
 * 只切別名，前後綴就會殘留（「在設定中搜尋藍牙」→ `中搜尋藍牙`）。
 * 這裡把整個片語辨識成一個 span，一次吃乾淨。
 *
 * 資料檔：`assets/open_source_ai_databases/ui_grammar_particles.tsv`
 */
object GrammarParticles {

    private const val DB_PATH = "open_source_ai_databases/ui_grammar_particles.tsv"

    /**
     * 內建預設（bootstrap）：只在資料表載入失敗時使用。
     * TSV 仍是唯一真相來源；這裡只是避免「沒有 context 的呼叫路徑」直接退回舊行為。
     */
    private val DEFAULT_PREFIXES = listOf(
        "進入", "打開", "開啟", "啟動", "前往", "从", "從", "在", "到", "進", "去", "於"
    ).sortedByDescending { it.length }

    private val DEFAULT_SUFFIXES = listOf(
        "裡面", "裏面", "里边", "之後", "之后", "以後", "以后",
        "中", "裡", "裏", "内", "內", "上", "下", "的"
    ).sortedByDescending { it.length }

    /** 受詞標記（把字句）：把/將 之後才是受詞 */
    private val OBJECT_MARKERS = listOf("幫我把", "帮忙把", "把", "將", "将")

    @Volatile
    private var prefixes: List<String> = emptyList()

    @Volatile
    private var suffixes: List<String> = emptyList()

    private val table = AssetTable(DB_PATH)

    fun ensureLoaded(context: Context?) {
        val lines = table.linesOrNull(context)
        if (lines == null) {
            // 保持空表：stripAppPhrase 會退化成「只切別名」，行為與舊版相同（安全失敗）
            return
        }
        loadFromLines(lines)
    }

    fun loadFromLines(lines: List<String>) {
        val pre = ArrayList<String>()
        val suf = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("\t")
            if (parts.size < 2) continue
            val term = parts[0].trim()
            if (term.isEmpty()) continue
            when (parts[1].trim().uppercase()) {
                "PREFIX" -> pre.add(term)
                "SUFFIX" -> suf.add(term)
            }
        }
        // 長的優先，避免「進入」被「進」先吃掉
        prefixes = pre.sortedByDescending { it.length }
        suffixes = suf.sortedByDescending { it.length }
    }

    fun effectivePrefixes(): List<String> = if (prefixes.isEmpty()) DEFAULT_PREFIXES else prefixes

    fun effectiveSuffixes(): List<String> = if (suffixes.isEmpty()) DEFAULT_SUFFIXES else suffixes

    /**
     * 抽出「受詞」：移除動詞與受詞標記（把/將）。
     *
     * 為什麼需要：中文有兩種語序
     *  - 動詞在前：「搜尋 invoice」→ 受詞在動詞之後
     *  - 把字句：  「把深色模式打開」→ 受詞在動詞**之前**
     * 舊版一律取「動詞之後的文字」，所以把字句會得到空字串，
     * 進而被誤判成「沒有受詞 → 啟動 App」。
     */
    fun extractObject(segment: String, verbTerm: String): String {
        var s = segment.trim()
        if (verbTerm.isNotEmpty()) {
            val idx = s.lowercase().indexOf(verbTerm.lowercase())
            if (idx >= 0) {
                s = (s.substring(0, idx) + " " + s.substring((idx + verbTerm.length).coerceAtMost(s.length))).trim()
            }
        }
        for (m in OBJECT_MARKERS) {
            if (s.startsWith(m)) {
                s = s.substring(m.length).trim()
                break
            }
        }
        return s.trim()
    }

    /** 導覽方向詞（不屬於「要調整的東西」） */
    private val DIRECTION_WORDS = listOf("進入", "打開", "前往", "去到", "回到", "滑到", "滑動到", "到", "在", "從")

    /**
     * 去掉開頭的導覽方向詞。
     *
     * 例：「進入顯示 亮度」→「顯示 亮度」（而不是把「進入顯示」也當成要調的目標）
     */
    fun stripLeadingDirections(text: String): String {
        var s = text.trim()
        var guard = 0
        while (guard++ < 4) {
            val hit = DIRECTION_WORDS.firstOrNull { s.startsWith(it) } ?: break
            s = s.substring(hit.length).trim()
        }
        return s.trim()
    }

    fun resetForTest() {
        prefixes = emptyList()
        suffixes = emptyList()
        table.reset()
    }

    fun prefixCount(): Int = prefixes.size
    fun suffixCount(): Int = suffixes.size

    /**
     * 把「App 片語」整段移除，回傳剩下的指令本文。
     *
     * 例：
     *  - 「在設定中搜尋藍牙」+ alias=設定 → 「搜尋藍牙」
     *  - 「在gmail搜尋invoice」+ alias=gmail → 「搜尋invoice」
     *  - 「打開設定」+ alias=設定 → 「」
     *  - 「進入顯示設定把亮度調高」+ alias=設定 → 「把亮度調高」（「顯示」不是助詞，不會被吃掉）
     */
    fun stripAppPhrase(segment: String, alias: String): String {
        if (segment.isEmpty() || alias.isEmpty()) return segment
        val lower = segment.lowercase()
        val aliasLower = alias.lowercase()
        val idx = lower.indexOf(aliasLower)
        if (idx < 0) return segment

        var start = idx
        // 往左吃掉介詞（可連續，例如「在」+「打開」）
        var guard = 0
        while (guard++ < 4) {
            var matched = false
            for (p in effectivePrefixes()) {
                val ps = start - p.length
                if (ps >= 0 && lower.substring(ps, start) == p.lowercase()) {
                    start = ps
                    matched = true
                    break
                }
            }
            if (!matched) break
        }
        // 跳過左側空白
        while (start > 0 && segment[start - 1].isWhitespace()) start--

        var end = idx + alias.length
        guard = 0
        while (guard++ < 4) {
            var matched = false
            for (s in effectiveSuffixes()) {
                val pe = end + s.length
                if (pe <= segment.length && lower.substring(end, pe) == s.lowercase()) {
                    end = pe
                    matched = true
                    break
                }
            }
            if (!matched) break
        }
        // 跳過右側空白
        while (end < segment.length && segment[end].isWhitespace()) end++

        return (segment.substring(0, start) + segment.substring(end)).trim()
    }
}
