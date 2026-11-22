package com.wae.core.match

import com.wae.core.predicate.PredicateControl
import com.wae.core.predicate.TextFold

/** 給「目標字串 + 一個控制項」打分（0..1）。core 不認識 Android，也不認識模型。 */
fun interface ControlScorer {
    fun score(goal: String, control: PredicateControl): Float
}

/**
 * 多定義比對器（P1）：**加權最大**，不是平均。
 *
 * 為什麼是 max：同一個目標有很多說法，平均會被「你又多寫了幾個同義詞」拉低分數，
 * 越補越糟。取最大值才符合直覺——只要有一條說法對上，就該算對上。
 *
 * 為什麼要權重：`specificity`（由 IDF 產生）讓籠統詞自動變小。
 * 「介面圖標」這種被上千個 entity 共用的詞，權重只有 0.07，永遠壓不過「路線」。
 *
 * 視圖（view）怎麼比：
 *  - TEXT：標籤包含該說法（折疊全形／大小寫後）。
 *  - ICON：把控制項的身份字串（`class|viewId`）當 token 比對，**用原字串**
 *    而不是折疊字串，因為折疊會把 `/`、`_` 吃掉，`add` 就會誤中 `address`。
 *  - DESC／ROLE／STATE／ID：先留空（資料還沒有，P3 再補）。
 *
 * 效率：查詢 → 候選 entity 的對應只算一次（快取上一句），
 * 之後每個控制項只檢查那幾個 entity 的定義，不會整張表掃 3000 次。
 */
class DefinitionMatcher(
    private val table: DefinitionTable,
    viewWeights: Map<Definition.View, Float> = emptyMap()
) : ControlScorer {

    /** 正規化後的說法 → entity 們（TEXT 定義的倒排索引）。 */
    private val textTerms: List<Pair<String, List<String>>> = run {
        val acc = LinkedHashMap<String, MutableSet<String>>()
        for (entity in table.entities) {
            for (d in table.definitionsOf(entity)) {
                if (d.view != Definition.View.TEXT) continue
                val term = TextFold.fold(d.value)
                if (term.isEmpty()) continue
                acc.getOrPut(term) { LinkedHashSet() }.add(entity)
            }
        }
        acc.map { (k, v) -> k to v.toList() }
    }

    /** 視圖的相對強度，除以最強者歸一到 0..1（沒有給就視為同等強）。 */
    private val viewRel: Map<Definition.View, Float> = run {
        if (viewWeights.isEmpty()) {
            emptyMap()
        } else {
            val top = viewWeights.values.maxOrNull() ?: 1f
            if (top <= 0f) emptyMap() else viewWeights.mapValues { it.value / top }
        }
    }

    private var cachedQuery: String? = null
    private var cachedEntities: List<String> = emptyList()

    /** 這句話可能指到的 entity（用「說法出現在句子裡」判定）。 */
    fun entitiesFor(goal: String, limit: Int = 64): List<String> {
        if (goal == cachedQuery) return cachedEntities
        val folded = TextFold.fold(goal)
        if (folded.isEmpty()) {
            cachedQuery = goal
            cachedEntities = emptyList()
            return emptyList()
        }
        val hit = LinkedHashSet<String>()
        for ((term, entities) in textTerms) {
            if (term.isEmpty() || !folded.contains(term)) continue
            hit.addAll(entities)
            if (hit.size >= limit) break
        }
        cachedQuery = goal
        cachedEntities = hit.toList()
        return cachedEntities
    }

    override fun score(goal: String, control: PredicateControl): Float {
        val entities = entitiesFor(goal)
        if (entities.isEmpty()) return 0f

        val labelFolded = TextFold.fold(control.label)
        val identity = control.key.lowercase()

        var best = 0f
        for (entity in entities) {
            for (d in table.definitionsOf(entity)) {
                val sim = when (d.view) {
                    Definition.View.TEXT ->
                        if (labelFolded.isNotEmpty() && labelFolded.contains(TextFold.fold(d.value))) 1f else 0f

                    Definition.View.ICON ->
                        if (containsToken(identity, d.value.lowercase())) 1f else 0f

                    else -> 0f
                }
                if (sim <= 0f) continue
                val rel = viewRel[d.view] ?: 1f
                val s = d.specificity * sim * rel
                if (s > best) best = s
            }
        }
        return best.coerceIn(0f, 1f)
    }

    companion object {
        /**
         * token 比對：前後必須是「非英數字」或字串邊界。
         *
         * 為什麼不用 `contains`：`add` 會誤中 `address`、`map` 會誤中 `maps`，
         * 這種誤配比找不到更糟（會點錯東西）。
         */
        fun containsToken(haystack: String, token: String): Boolean {
            if (token.isEmpty() || haystack.isEmpty()) return false
            var from = 0
            while (true) {
                val idx = haystack.indexOf(token, from)
                if (idx < 0) return false
                val beforeOk = idx == 0 || !haystack[idx - 1].isLetterOrDigit()
                val end = idx + token.length
                val afterOk = end >= haystack.length || !haystack[end].isLetterOrDigit()
                if (beforeOk && afterOk) return true
                from = idx + 1
            }
        }
    }
}
