package com.wae.core.routing

import com.wae.core.config.Config
import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.config.Reason
import com.wae.core.model.Constraints
import java.util.Locale

/** 一條路：用哪個 App、預估幾步、多可靠、分數多少、為什麼。 */
data class Route(
    val appId: String,
    val goal: Goal,
    val steps: Int,
    val reliability: Float,
    val score: Float,
    val banned: Boolean = false,
    val lowered: Boolean = false
) {

    /** 給人看的一行理由（UI 直接顯示；也是 log 的內容）。 */
    fun explain(): String = String.format(
        Locale.ROOT,
        "app=%s steps=%d p=%.2f score=%.3f%s%s",
        appId,
        steps,
        reliability,
        score,
        if (lowered) " (降權)" else "",
        if (banned) " (禁用)" else ""
    )
}

/**
 * 效果路由器（旗艦創新的第一版）。
 *
 * 做的事只有一件：**在「結果」層面比較多條路**，而不是在「單一 App 內」比較多個手段。
 *
 * ```
 * p     = (successes + prior) / (attempts + 2·prior)   拉普拉斯平滑：沒資料不吹牛
 * score = p / (steps + 1)                              每多一步就扣分
 * ```
 *
 * 沒有資料的 App 不會被判死（p = 0.5），但也不會贏過有實績的路——這就是「越用越準」。
 */
class Router(private val config: Config) {

    fun rank(goal: Goal, index: CapabilityIndex, constraints: Constraints = Constraints.NONE): List<Route> {
        val candidates = index.candidates(goal.kind)
        if (candidates.isEmpty()) return emptyList()

        val routes = candidates.map { capability ->
            val banned = isBanned(goal, capability.appId, constraints)
            val lowered = isLowered(goal, capability.appId, constraints)
            val p = reliabilityOf(capability)
            val penalty = if (lowered) lowerPenalty() else 1f
            val steps = if (capability.avgSteps > 0) capability.avgSteps else fallbackSteps()
            Route(
                appId = capability.appId,
                goal = goal,
                steps = steps,
                reliability = p,
                score = if (banned) 0f else (p / (steps + 1)) * penalty,
                banned = banned,
                lowered = lowered
            )
        }

        val pinned = goal.pinnedApp
        return routes
            .filter { !it.banned }
            .filter { pinned == null || it.appId == pinned }
            .sortedWith(compareByDescending<Route> { it.score }.thenBy { it.steps }.thenBy { it.appId })
    }

    /** 選一條；選不到回 null（呼叫端要 `ABSTAIN`／反問，不是猜）。 */
    fun choose(goal: Goal, index: CapabilityIndex, constraints: Constraints = Constraints.NONE): Route? =
        rank(goal, index, constraints).firstOrNull()

    private fun reliabilityOf(capability: Capability): Float {
        val prior = priorSuccess()
        val numerator = capability.successes + prior
        val denominator = capability.attempts + 2f * prior
        return if (denominator <= 0f) 0.5f else numerator / denominator
    }

    private fun isBanned(goal: Goal, appId: String, constraints: Constraints): Boolean =
        constraints.isBanned(goal.key(), BAN_PREFIX + appId)

    private fun isLowered(goal: Goal, appId: String, constraints: Constraints): Boolean =
        constraints.needsConfirm(goal.key(), BAN_PREFIX + appId)

    private fun priorSuccess(): Float =
        config.float(Key.ROUTE_PRIOR_SUCCESS, Default.ROUTE_PRIOR_SUCCESS)

    private fun lowerPenalty(): Float =
        config.float(Key.ROUTE_LOWER_PENALTY, Default.ROUTE_LOWER_PENALTY)

    private fun fallbackSteps(): Int =
        config.int(Key.ROUTE_FALLBACK_STEPS, Default.ROUTE_FALLBACK_STEPS)

    companion object {
        /** 反例庫用這個前綴記「這個 App 對這個結果不行」。 */
        const val BAN_PREFIX = "route:"

        fun banKey(goal: Goal, appId: String): Pair<String, String> = goal.key() to (BAN_PREFIX + appId)

        /** 給上層產生「選不到路」時的說明。 */
        fun noRouteReason(): String = Reason.NO_CANDIDATE
    }
}
