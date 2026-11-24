package com.wae.core.screen

/**
 * 概念與同族比對（通用，不含任何 App 名稱）。
 *
 * 要解決的是：「通話記錄」這個概念，畫面上可能只寫「最近」。
 *  - `CONCEPT`：概念 → 它的明確說法（通話記錄、通話紀錄、來電記錄…）
 *  - `FAMILY`：同一類畫面會一起出現的字（鍵盤／撥號／聯絡人／最近／語音信箱）
 *  - `MEMBER`：同族裡的某個字，**在同族同時出現 ≥2 個時**才算那個概念
 *
 * 為什麼要「同族共現」：單獨一個「最近」在檔案管理器也常見，
 * 只有在鍵盤／聯絡人也在同一頁時，它才幾乎確定是通話記錄。
 */
object ConceptMatcher {

    data class Concept(val id: String, val domains: Set<String>, val labels: List<String>)

    data class Table(
        val concepts: Map<String, Concept> = emptyMap(),
        /** family -> members */
        val families: Map<String, Set<String>> = emptyMap(),
        /** family -> (member -> conceptId) */
        val members: Map<String, Map<String, String>> = emptyMap()
    ) {
        fun conceptOf(label: String): Concept? {
            val l = fold(label)
            return concepts.values.firstOrNull { c -> c.labels.any { fold(it) == l } }
        }
    }

    /** 同族至少要有這麼多成員同時出現，才啟用推論。 */
    const val FAMILY_MIN_COOCCURRENCE = 2

    /** 由概念推論出來的分數（低於明確說法的 1.00／0.88，但高於亂猜）。 */
    const val FAMILY_SCORE = 0.80f

    /**
     * 這些標籤可以被視為目標概念的「同族說法」。
     *
     * @param targetConcept 由提示詞解析出的概念 id（null＝解析不出，直接回空）
     * @param presentLabels 目前畫面上所有可選項的標籤
     */
    fun familyLabelsFor(
        targetConcept: String?,
        presentLabels: Collection<String>,
        table: Table
    ): Set<String> {
        if (targetConcept.isNullOrBlank()) return emptySet()
        val present = presentLabels.map { fold(it) }.toSet()
        val out = HashSet<String>()
        for ((family, memberToConcept) in table.members) {
            val members = table.families[family] ?: continue
            // 同族共現：這一頁同時出現幾個同族詞？
            val cooccurrence = members.count { present.contains(fold(it)) }
            if (cooccurrence < FAMILY_MIN_COOCCURRENCE) continue
            for ((member, conceptId) in memberToConcept) {
                if (conceptId == targetConcept && present.contains(fold(member))) out.add(member)
            }
        }
        return out
    }

    /**
     * 最近似：字元 bigram Dice。只在「原本會直接失敗」時使用。
     *
     * 要求 **≥ floor 且領先第二名 ≥ margin**，避免「差不多就點」。
     */
    fun nearest(
        target: String,
        candidates: Collection<String>,
        floor: Float = 0.45f,
        margin: Float = 0.10f
    ): Pair<String, Float>? {
        if (target.isBlank() || candidates.isEmpty()) return null
        val scored = candidates.map { it to dice(target, it) }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second < floor) return null
        val second = scored.getOrNull(1)?.second ?: 0f
        if (best.second - second < margin) return null
        return best
    }

    /** 字元 bigram Dice 係數（0..1）。 */
    fun dice(a: String, b: String): Float {
        val x = fold(a).replace(" ", "")
        val y = fold(b).replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0f
        if (x == y) return 1f
        val gx = grams(x)
        val gy = grams(y)
        if (gx.isEmpty() || gy.isEmpty()) return 0f
        var inter = 0
        val pool = gy.toMutableList()
        for (g in gx) {
            if (pool.remove(g)) inter++
        }
        return (2f * inter) / (gx.size + gy.size)
    }

    private fun grams(s: String): List<String> {
        if (s.length == 1) return listOf(s)
        return (0 until s.length - 1).map { s.substring(it, it + 2) }
    }

    private fun fold(s: String): String = com.wae.core.predicate.TextFold.fold(s)
}
