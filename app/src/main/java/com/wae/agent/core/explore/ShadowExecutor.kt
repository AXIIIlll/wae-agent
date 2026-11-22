package com.wae.agent.core.explore

import com.wae.agent.core.automaton.Automaton
import com.wae.agent.core.automaton.AutomatonEdge
import com.wae.agent.core.automaton.AutomatonPlanner

/**
 * S5：影子執行（架構書 §7.4）。
 *
 * 對已規劃出的路徑，先在「記憶體中的圖」上模擬，檢查守衛/選擇器是否可能成立；
 * 只有全部通過才允許落到真機。
 *
 * 這一層**完全不碰裝置**：不點擊、不注入事件、不改狀態。
 * 它的價值是把「圖與現實不同步」的代價提前暴露成一個布林值。
 */
object ShadowExecutor {

    data class StepSim(
        val edge: AutomatonEdge,
        val locatable: Boolean,
        val arrivalSimilar: Float,
        val ok: Boolean,
        val note: String
    )

    data class Verdict(
        val steps: List<StepSim>,
        val ok: Boolean,
        val blockedAt: Int,
        val reason: String
    )

    /**
     * 在圖上模擬一條路徑。
     *
     * @param locatable 目前畫面上「可定位到該選擇器」的判斷（由呼叫端提供，通常是候選集合）
     * @param arrivalThreshold 抵達驗證門檻（沿用 M3 的 0.50）
     */
    fun simulate(
        automaton: Automaton,
        plan: AutomatonPlanner.Plan,
        locatable: (String) -> Boolean,
        arrivalThreshold: Float = 0.50f
    ): Verdict {
        val sims = ArrayList<StepSim>(plan.edges.size)
        for ((i, e) in plan.edges.withIndex()) {
            val target = automaton.byId(e.to)
            val sim = if (target == null) 0f else 1f
            val canLocate = locatable(e.selector)
            val ok = canLocate && target != null
            sims.add(
                StepSim(
                    edge = e,
                    locatable = canLocate,
                    arrivalSimilar = sim,
                    ok = ok,
                    note = when {
                        target == null -> "target state missing"
                        !canLocate -> "selector not locatable"
                        else -> "ok"
                    }
                )
            )
            if (!ok) {
                return Verdict(sims, false, i, sims.last().note)
            }
        }
        val allOk = sims.all { it.ok }
        return Verdict(sims, allOk, -1, if (allOk) "all steps pass" else "step failed")
    }

    /** 檢查點驗證：只驗前 1–2 步（架構書 §7.4 的補救措施） */
    fun checkpointVerdict(verdict: Verdict, checkpoints: Int = 2): Boolean =
        verdict.steps.take(checkpoints.coerceAtLeast(1)).all { it.ok }

    /** 影子執行是否值得落到真機（全部通過 + 至少一步） */
    fun shouldCommit(verdict: Verdict): Boolean = verdict.ok && verdict.steps.isNotEmpty()
}
