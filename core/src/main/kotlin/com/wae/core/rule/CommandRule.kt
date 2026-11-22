package com.wae.core.rule

/**
 * 句型 → 目的（`ui/command_purposes.tsv`）。
 *
 * 模板語法：`$slot` 是變數，其餘是字面。例：
 *   `打開$app`、`在$app裡搜尋$query`、`調大聲`
 *
 * 匹配規則：
 *  1. 字面必須依序出現（前綴／中綴／後綴）。
 *  2. 變數抓「最短且非空」的字串（避免吃掉後面）。
 *  3. 分數 = 字面總長（更具體的句型贏），同分則比 `priority`。
 */
data class CommandRule(
    val pattern: String,
    val purpose: String,
    val priority: Int = 0,
    /** 這個句型固定提供的值（例 `volume`）。 */
    val fixedSlots: Map<String, String> = emptyMap()
) {
    val literals: List<String> = splitLiterals(pattern)
    val slotNames: List<String> = splitSlots(pattern)
    val literalLength: Int = literals.sumOf { it.length }

    companion object {
        private fun splitLiterals(pattern: String): List<String> =
            pattern.split('$').mapIndexed { index, part ->
                // 只吃掉 ASCII 的槽位名；中文字（例「裡搜尋」）不能被當成槽位名吃掉
                if (index == 0) part else part.dropWhile { it.isAsciiSlotChar() }
            }

        private fun Char.isAsciiSlotChar(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' 

        private fun splitSlots(pattern: String): List<String> =
            Regex("\\$([A-Za-z_][A-Za-z0-9_]*)").findAll(pattern).map { it.groupValues[1] }.toList()
    }
}

object CommandRuleTable {

    fun parse(lines: Sequence<String>): List<CommandRule> {
        val out = ArrayList<CommandRule>()
        for (raw in lines) {
            val line = raw.trimStart('\uFEFF').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val c = line.split('\t')
            if (c.size < 2) continue
            val pattern = c[0].trim()
            val purpose = c[1].trim()
            if (pattern.isEmpty() || purpose.isEmpty()) continue
            val priority = c.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            val fixed = c.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }?.split('|')
                ?.mapNotNull { pair ->
                    val kv = pair.split('=')
                    if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
                }?.toMap().orEmpty()
            out.add(CommandRule(pattern, purpose, priority, Slots.canonical(fixed)))
        }
        return out
    }
}

/** 匹配結果：命中的規則＋抓到的槽位。 */
data class Match(
    val rule: CommandRule,
    val slots: Map<String, String>,
    val score: Int
)

object TemplateMatcher {

    /** 回傳所有命中，依分數（字面長度 × 100 + priority）排序。 */
    fun matchAll(command: String, rules: List<CommandRule>): List<Match> {
        val text = command.trim()
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (rule in rules) {
            val bound = bind(text, rule) ?: continue
            out.add(Match(rule, bound, rule.literalLength * 100 + rule.priority))
        }
        return out.sortedByDescending { it.score }
    }

    fun best(command: String, rules: List<CommandRule>): Match? = matchAll(command, rules).firstOrNull()

    /**
     * 把指令對到模板。
     *
     * 策略：遞迴比對「字面→槽位→字面…」，槽位抓最短非空字串。
     */
    private fun bind(text: String, rule: CommandRule): Map<String, String>? {
        val literals = rule.literals
        val slots = rule.slotNames
        val bound = LinkedHashMap<String, String>()
        return if (walk(text, 0, 0, literals, slots, bound)) bound else null
    }

    private fun walk(
        text: String,
        textPos: Int,
        segment: Int,
        literals: List<String>,
        slots: List<String>,
        bound: MutableMap<String, String>
    ): Boolean {
        // 最後一段字面之後必須剛好吃完（或完全相符）
        if (segment == literals.size) {
            return textPos == text.length
        }
        val literal = literals[segment]
        // 第一段字面必須從頭開始（否則「搜尋X」會匹配到「在設定裡搜尋X」的中段）
        val at = when {
            literal.isEmpty() -> textPos
            segment == 0 -> if (text.startsWith(literal, textPos)) textPos else -1
            else -> text.indexOf(literal, textPos)
        }
        if (at < 0) return false
        val slotIndex = segment
        val hasSlot = slotIndex < slots.size && slotIndex < literals.size - 1
        if (!hasSlot) {
            // 沒有槽位：字面要接上，且後面還有字面或剛好結束
            return walk(text, at + literal.length, segment + 1, literals, slots, bound)
        }
        // 槽位：在「目前字面之後」到「下一個字面之前」抓最短非空
        val afterPrefix = at + literal.length
        val nextLiteral = literals[segment + 1]
        val end = if (nextLiteral.isEmpty()) text.length else text.indexOf(nextLiteral, afterPrefix)
        if (end <= afterPrefix) return false
        val value = text.substring(afterPrefix, end).trim()
        if (value.isEmpty()) return false
        val previous = bound[slots[slotIndex]]
        if (previous != null && previous != value) return false
        bound[slots[slotIndex]] = value
        if (walk(text, end, segment + 1, literals, slots, bound)) return true
        if (previous == null) bound.remove(slots[slotIndex])
        return false
    }
}
