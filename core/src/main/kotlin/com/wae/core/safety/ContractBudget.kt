package com.wae.core.safety

import com.wae.core.config.Config
import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.model.Step
import com.wae.core.time.Clock

/**
 * 合約的預算（C4）。
 *
 * 為什麼需要：現在的風險只有「這一步危不危險」，沒有「這個任務總共能花多少」。
 * 一個失控的 agent 會用 200 步去試一個做不到的任務 —— 那比失敗更糟。
 */
data class ContractBudget(
    val maxSteps: Int = 20,
    val maxWallClockMs: Long = 120_000L,
    val maxHighRiskOps: Int = 1,
    val forbiddenEffects: Set<String> = emptySet()
) {
    companion object {
        fun fromConfig(config: Config): ContractBudget = ContractBudget(
            maxSteps = config.int(Key.BUDGET_MAX_STEPS, Default.BUDGET_MAX_STEPS),
            maxWallClockMs = config.long(Key.BUDGET_MAX_WALLCLOCK_MS, Default.BUDGET_MAX_WALLCLOCK_MS),
            maxHighRiskOps = config.int(Key.BUDGET_MAX_HIGH_RISK, Default.BUDGET_MAX_HIGH_RISK)
        )
    }
}

enum class BudgetDecision { ALLOWED, DENIED_STEPS, DENIED_TIME, DENIED_FORBIDDEN, DENIED_RISK }

/**
 * 預算追蹤器：每一步執行前問一次。
 *
 * 純計數、不看裝置、不依賴 UI —— 因此可離線重放（I5）。
 */
class BudgetTracker(
    private val budget: ContractBudget,
    private val clock: Clock,
    private val startedAtMs: Long = clock.nowMs()
) {

    private var steps = 0
    private var highRisk = 0

    fun check(step: Step): BudgetDecision {
        if (step.effect.key() in budget.forbiddenEffects) return BudgetDecision.DENIED_FORBIDDEN
        if (steps >= budget.maxSteps) return BudgetDecision.DENIED_STEPS
        if (clock.nowMs() - startedAtMs > budget.maxWallClockMs) return BudgetDecision.DENIED_TIME
        if (step.risk == com.wae.core.model.Risk.HIGH && highRisk >= budget.maxHighRiskOps) {
            return BudgetDecision.DENIED_RISK
        }
        return BudgetDecision.ALLOWED
    }

    fun consume(step: Step) {
        steps++
        if (step.risk == com.wae.core.model.Risk.HIGH) highRisk++
    }

    fun stepsUsed(): Int = steps

    fun elapsedMs(): Long = clock.nowMs() - startedAtMs
}
