package com.wae.core.means

import com.wae.core.config.Config
import com.wae.core.config.Key
import com.wae.core.model.Constraints
import com.wae.core.model.Means
import com.wae.core.model.Risk
import com.wae.core.model.Step
import java.util.Locale

/**
 * 手段求解（Gen-5 §4.4 / L3）。
 *
 * 不問「哪個按鈕最像」，而是問「哪個**手段**最省、且沒被反例禁用」。
 * 排序鍵：成本 → 風險 → 描述（描述讓同一輸入有穩定順序，可重現）。
 */
class MeansSolver(
    private val config: Config,
    private val costs: Map<Means.Kind, Int> = DEFAULT_COSTS
) {

    /** 過濾被 BAN 的手段，並依成本排序。回傳的順序就是嘗試順序。 */
    fun rank(candidates: List<Step>, constraints: Constraints): List<Step> =
        candidates
            .filterNot { constraints.isBanned(it.effect.key(), it.means.describe()) }
            .sortedWith(
                compareBy<Step>(
                    { costOf(it.means.kind) },
                    { riskOrder(it.risk) },
                    { it.means.describe() }
                )
            )

    fun best(candidates: List<Step>, constraints: Constraints): Step? =
        rank(candidates, constraints).firstOrNull()

    fun costOf(kind: Means.Kind): Int {
        val default = costs[kind] ?: DEFAULT_COST
        val key = Key.MEANS_COST_PREFIX + kind.name.lowercase(Locale.ROOT)
        return config.int(key, default)
    }

    companion object {
        /** 愈小愈省：系統 API < Intent < 無障礙動作 < 輸入 < 手勢 < 教學。 */
        val DEFAULT_COSTS: Map<Means.Kind, Int> = mapOf(
            Means.Kind.SYSTEM_API to 1,
            Means.Kind.INTENT to 2,
            Means.Kind.A11Y to 3,
            Means.Kind.INPUT to 4,
            Means.Kind.GESTURE to 5,
            Means.Kind.TEACH to 9
        )

        const val DEFAULT_COST = 50

        private fun riskOrder(risk: Risk): Int = when (risk) {
            Risk.LOW -> 0
            Risk.MEDIUM -> 1
            Risk.HIGH -> 2
            Risk.FORBIDDEN -> 3
        }
    }
}
