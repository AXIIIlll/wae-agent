package com.wae.agent.core.explore

import android.content.Context
import android.util.Log
import com.wae.agent.core.automaton.AutomatonPlanner
import com.wae.agent.core.automaton.AutomatonStore
import com.wae.agent.core.twin.TwinStep

/**
 * 探索排程（服務瘦身第八塊）：挑一個「安全且值得」的動作，**影子執行**驗證可達性。
 *
 * 從 4,000 多行的無障礙服務抽出：它只依賴 context、Twin 軌跡與「是否充電」，
 * 其餘全是 core 的靜態元件（AutomatonStore／ExplorationGuard／CoverageBandit／ShadowExecutor）。
 *
 * 邊界：**只模擬、不碰裝置**。真正的探索開關仍由 `EXPLORER_ENABLED` 控制。
 */
object ExplorationScheduler {

    fun run(context: Context, steps: List<TwinStep>, charging: () -> Boolean) {
        val pkgs = steps.map { it.pkg }.filter { it.isNotEmpty() }.distinct()
        val bandit = CoverageBandit
        for (pkg in pkgs.take(MAX_PACKAGES)) {
            val auto = AutomatonStore.load(context, pkg) ?: continue
            val allowed = ExplorationGuard.canExploreNow(
                charging = charging(),
                screenOn = true,
                actionsThisRound = 0,
                minutesThisRound = 0f,
                actionsToday = 0,
                packageWhitelisted = true
            )
            if (!allowed) {
                Log.i(TAG, "skip pkg=$pkg guard=environment")
                continue
            }
            val candidates = auto.edges.filter { e ->
                ExplorationGuard.isSafeAction(e.op) &&
                    !ExplorationGuard.isForbidden(e.selector, e.selector)
            }
            val pick = bandit.pick(pkg, candidates, candidates.size) ?: continue
            val plan = AutomatonPlanner.Plan(
                states = listOf(pick.from, pick.to),
                edges = listOf(pick),
                expectedCostMs = pick.p95Estimate(),
                expectedSuccess = pick.successRate
            )
            val verdict = ShadowExecutor.simulate(
                auto, plan, locatable = { sel -> auto.edges.any { it.selector == sel } }
            )
            Log.i(
                TAG,
                "shadow pkg=$pkg pick=${pick.selector} verdict=${verdict.ok} " +
                    "commit=${ShadowExecutor.shouldCommit(verdict)} (device untouched)"
            )
        }
        CoverageBandit.persist(context)
    }

    private const val TAG = "WAE_EXPLORE"
    private const val MAX_PACKAGES = 2
}
