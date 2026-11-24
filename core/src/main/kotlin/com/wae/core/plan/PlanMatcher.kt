package com.wae.core.plan

import com.wae.core.screen.ScreenModel
import com.wae.core.screen.ScreenOption

/**
 * 計畫與畫面比對：在「可選項」裡挑出最能讓這一步成立的選項。
 *
 * 為什麼不只用字面相似度：
 *  - 字面相似只回答「像不像」，不回答「能不能讓後置條件成立」。
 *  - 例如「打開 Wi-Fi」：字面上「Wi-Fi 與其他裝置」也很像，但它是說明列，
 *    真正能滿足的是有開關狀態的那一列。
 *
 * 這裡的評分順序（全部可調、全部可測）：
 *  1. 被負面知識擋掉的組合直接排除
 *  2. 動作相容性（PICK 需要可點、TYPE 需要可編輯、TOGGLE 需要開關）
 *  3. 標籤相似度（由呼叫端傳入的分數函式，通常是 DecisionRanker）
 *  4. 群組相似度（打折）
 *  5. 同分：完全相同 → 標籤較短
 */
object PlanMatcher {

    /** 一個候選的評分結果。 */
    data class Candidate(
        val option: ScreenOption,
        val score: Float,
        val reason: String
    )

    /** 動作相容性：不符合的回 0，符合的回 1（之後乘上權重）。 */
    fun actionCompatible(action: String, option: ScreenOption): Boolean = when (action.uppercase()) {
        "PICK", "LONG_PRESS" -> option.enabled
        "TOGGLE_ON", "TOGGLE_OFF", "TOGGLE" -> option.enabled && option.checked != null
        "TYPE", "FILL" -> option.enabled && (option.role == "FIELD" || option.role == "EDITABLE")
        // BACK／OPEN／ADJUST 不靠選項，交給呼叫端
        else -> true
    }

    /**
     * 從畫面裡挑出這一步的最佳選項。
     *
     * @param scoreOf 由呼叫端注入的相似度函式（core 不依賴 app 的 DecisionRanker）
     * @param banned 負面知識：這些 `pkg|target|action` 已被證明走不通
     */
    fun best(
        step: PlanStep,
        screen: ScreenModel,
        scoreOf: (goal: String, label: String) -> Float,
        banned: Set<String> = emptySet(),
        packageName: String = ""
    ): Candidate? {
        if (screen.options.isEmpty()) return null
        val goal = step.target.ifBlank { step.arg }
        if (goal.isBlank()) return null

        val scored = ArrayList<Candidate>(screen.options.size)
        for (o in screen.options) {
            val key = banKey(packageName.ifEmpty { screen.packageName }, o.label, step.action)
            if (key in banned) {
                scored.add(Candidate(o, 0f, "banned"))
                continue
            }
            if (!actionCompatible(step.action, o)) {
                scored.add(Candidate(o, 0f, "action-incompatible(${o.role})"))
                continue
            }
            val labelScore = scoreOf(goal, o.label)
            val groupScore = if (o.group.isNotEmpty()) scoreOf(goal, o.group) * 0.8f else 0f
            val score = maxOf(labelScore, groupScore)
            scored.add(Candidate(o, score, if (labelScore >= groupScore) "label" else "group"))
        }

        val goalFold = fold(goal)
        val best = scored
            .filter { it.score > 0f }
            .maxWithOrNull { a, b ->
                val byScore = a.score.compareTo(b.score)
                if (byScore != 0) return@maxWithOrNull byScore
                val aExact = if (fold(a.option.label) == goalFold) 1 else 0
                val bExact = if (fold(b.option.label) == goalFold) 1 else 0
                if (aExact != bExact) return@maxWithOrNull (aExact - bExact)
                b.option.label.length.compareTo(a.option.label.length)
            }
            ?: return null
        return best
    }

    /** 負面知識的鍵：套件＋標籤＋動作。 */
    fun banKey(pkg: String, label: String, action: String): String =
        "$pkg|${fold(label)}|${action.uppercase()}"

    private fun fold(s: String): String = com.wae.core.predicate.TextFold.fold(s)
}
