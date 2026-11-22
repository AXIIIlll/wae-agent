package com.wae.core.intent

/**
 * 開放式意圖的路由（`我想看影片` → 類別＋進入後策略）。
 *
 * 為什麼要獨立一層：`IntentCompiler` 解的是「動詞＋目標」，
 * 但「看影片」沒有動詞（或動詞是「看」）也沒有目標名；它有的是**類別**。
 * 先把句子落在類別（VIDEO_MEDIA / MAPS_NAV…），才能從已安裝 App 裡挑。
 *
 * 純資料＋純函式（JVM 可測），不碰 Android API。
 */
object IntentRouter {

    enum class Strategy {
        /** 打開就停首頁：內容選擇留給人。 */
        HOME,

        /** 打開後搜尋「剩下的字」。 */
        SEARCH
    }

    data class Route(
        val term: String,
        val categories: List<String>,
        val strategy: Strategy,
        /** SEARCH 時要搜尋的字（已去掉 term 前綴）。 */
        val query: String,
        /** 對應的目的 id（能力表要用它記成敗）；沒有就空字串。 */
        val purpose: String = ""
    )

    private val rules = ArrayList<Rule>()

    @Volatile
    var loaded: Boolean = false
        private set

    private data class Rule(
        val term: String,
        val categories: List<String>,
        val strategy: Strategy,
        val purpose: String
    )

    fun resetForTest() {
        synchronized(rules) { rules.clear() }
        loaded = false
    }

    fun loadFromLines(lines: Sequence<String>) {
        val parsed = ArrayList<Rule>()
        for (raw in lines) {
            val line = raw.trimStart('\uFEFF').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val c = line.split('\t')
            if (c.size < 3) continue
            val term = c[0].trim()
            if (term.isEmpty()) continue
            val cats = c[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (cats.isEmpty()) continue
            val strategy = when (c[2].trim().lowercase()) {
                "search" -> Strategy.SEARCH
                else -> Strategy.HOME
            }
            val purpose = c.getOrNull(3)?.trim().orEmpty()
            parsed.add(Rule(term, cats, strategy, purpose))
        }
        synchronized(rules) {
            rules.clear()
            rules.addAll(parsed.sortedByDescending { it.term.length })
        }
        loaded = parsed.isNotEmpty()
    }

    /** 這句話是不是「目的型」指令；是就回路由（含策略與搜尋字）。 */
    fun route(text: String): Route? {
        val input = text.trim()
        if (input.isEmpty()) return null
        for (rule in synchronized(rules) { rules.toList() }) {
            if (!input.startsWith(rule.term)) continue
            val rest = input.removePrefix(rule.term).trim()
            // SEARCH 但沒有要搜什麼 → 不算路由（交給反問或一般解析）
            if (rule.strategy == Strategy.SEARCH && rest.isEmpty()) continue
            return Route(rule.term, rule.categories, rule.strategy, rest, rule.purpose)
        }
        return null
    }
}
