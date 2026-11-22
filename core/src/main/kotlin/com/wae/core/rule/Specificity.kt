package com.wae.core.rule

/**
 * 具體性判斷器（你不希望每句話都驚動大模型，就是靠這一層）。
 *
 * 不對稱原則：**不確定時一律判「模糊」**。
 *  - 具體誤判成模糊 → 多花 1–2 秒叫模型，結果通常還是對的。
 *  - 模糊誤判成具體 → 規則引擎會直接做錯事。
 *
 * 純規則、零模型、可解釋（回傳 evidence / missing）。
 */
enum class Verdict { SPECIFIC, VAGUE, UNKNOWN }

data class Specificity(
    val verdict: Verdict,
    val score: Float,
    val evidence: List<String> = emptyList(),
    val missing: List<String> = emptyList()
) {
    val isSpecific: Boolean get() = verdict == Verdict.SPECIFIC
}

/**
 * 判斷規則（`ui/specificity.tsv`）。
 *
 * 格式：`kind<TAB>value<TAB>weight`
 *  kind = vague_marker（出現就扣分）／goal_only（只有目的就扣分）／
 *         explicit_marker（出現就加分）／slot_hint（該槽位有填就加分）
 */
class SpecificityTable(
    private val vagueMarkers: List<String> = emptyList(),
    private val goalOnlyPatterns: List<String> = emptyList(),
    private val explicitMarkers: List<String> = emptyList()
) {

    fun judge(
        command: String,
        match: Match?,
        purpose: Purpose?,
        extraSlots: Map<String, String> = emptyMap()
    ): Specificity {
        val text = command.trim()
        val evidence = ArrayList<String>()
        val missing = ArrayList<String>()
        var score = 0.5f

        for (marker in vagueMarkers) {
            if (text.contains(marker)) {
                score -= 0.25f
                missing.add("含指代／模糊詞：$marker")
            }
        }
        if (goalOnlyPatterns.any { Regex(it).containsMatchIn(text) }) {
            score -= 0.2f
            missing.add("只有目的、沒有手段")
        }
        for (marker in explicitMarkers) {
            if (text.contains(marker)) {
                score += 0.1f
                evidence.add("明確詞：$marker")
            }
        }

        if (match == null) {
            return Specificity(Verdict.UNKNOWN, score.coerceIn(0f, 1f), evidence, missing + "沒有命中任何句型")
        }
        evidence.add("命中句型：${match.rule.pattern}")
        score += 0.25f

        if (purpose != null) {
            for (slot in purpose.requiredSlots) {
                val value = extraSlots[slot] ?: match.slots[slot]
                    ?: extraSlots.entries.firstOrNull { Slots.compatible(it.key, slot) }?.value
                    ?: match.slots.entries.firstOrNull { Slots.compatible(it.key, slot) }?.value
                if (value.isNullOrBlank()) {
                    missing.add("缺槽位：$slot")
                    score -= 0.2f
                } else {
                    evidence.add("槽位 $slot=$value")
                }
            }
        }

        val verdict = when {
            missing.isNotEmpty() && score < 0.75f -> Verdict.VAGUE
            score >= 0.75f -> Verdict.SPECIFIC
            else -> Verdict.VAGUE
        }
        return Specificity(verdict, score.coerceIn(0f, 1f), evidence, missing)
    }

    companion object {
        fun parse(lines: Sequence<String>): SpecificityTable {
            val vague = ArrayList<String>()
            val goalOnly = ArrayList<String>()
            val explicit = ArrayList<String>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 2) continue
                when (c[0].trim()) {
                    "vague_marker" -> vague.add(c[1].trim())
                    "goal_only" -> goalOnly.add(c[1].trim())
                    "explicit_marker" -> explicit.add(c[1].trim())
                }
            }
            return SpecificityTable(vague, goalOnly, explicit)
        }
    }
}
