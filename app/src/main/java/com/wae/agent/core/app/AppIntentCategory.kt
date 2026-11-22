package com.wae.agent.core.app

/**
 * 開放式意圖 → App 類別（資料驅動，來源 `ui_app_intent_category.tsv`）。
 *
 * 用途：`我想看劇` 這種句子沒有明確的 App 名稱，需要先落在「類別」
 * （VIDEO_MEDIA / SHORT_DRAMA …），再從已安裝 App 中挑同類別的候選。
 * 這比直接拿目標去跟 App 名稱做 embedding 穩得多（「看劇」和「Netflix」在向量上不接近）。
 *
 * 純資料 + 純函式，JVM 可測；不碰 Android API。
 */
object AppIntentCategory {

    data class Rule(val term: String, val categories: List<String>, val weight: Float)

    private val rules = ArrayList<Rule>()

    @Volatile
    var loaded: Boolean = false
        private set

    fun resetForTest() {
        synchronized(rules) { rules.clear() }
        loaded = false
    }

    /** 解析 TSV 行；格式錯誤或權重非法就跳過（資料檔允許註解與空行）。 */
    fun loadFromLines(lines: Sequence<String>) {
        val parsed = ArrayList<Rule>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val term = parts[0].trim()
            if (term.isEmpty()) continue
            val cats = parts[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (cats.isEmpty()) continue
            val weight = parts.getOrNull(2)?.trim()?.toFloatOrNull() ?: 1.0f
            if (!weight.isFinite() || weight <= 0f) continue
            parsed.add(Rule(term, cats, weight))
        }
        synchronized(rules) {
            rules.clear()
            // 長詞優先（「看短劇」要贏過「看劇」）
            rules.addAll(parsed.sortedByDescending { it.term.length })
            loaded = true
        }
    }

    /** 所有規則的類別集合（守門測試用：必須都存在於 App metadata）。 */
    fun allCategories(): Set<String> = synchronized(rules) {
        rules.flatMap { it.categories }.toSet()
    }

    fun ruleCount(): Int = synchronized(rules) { rules.size }

    /**
     * 從目標句子取出命中的類別與權重。
     *
     * 同一類別取最高權重；結果依權重排序（同權重時長詞優先）。
     * 完全沒命中回空清單（上層應退回 embedding 或反問）。
     */
    fun match(goal: String): List<Pair<String, Float>> {
        if (goal.isBlank()) return emptyList()
        // 先正規化（簡繁＋ASR 同音），「我想看剧」也要命中「看劇」
        val text = TextNormalizer.normalize(goal)
        if (text.isEmpty()) return emptyList()
        val best = LinkedHashMap<String, Float>()
        synchronized(rules) {
            for (rule in rules) {
                if (!text.contains(TextNormalizer.normalize(rule.term))) continue
                for (cat in rule.categories) {
                    val prev = best[cat] ?: 0f
                    if (rule.weight > prev) best[cat] = rule.weight
                }
            }
        }
        return best.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
}
