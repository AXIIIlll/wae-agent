package com.wae.agent.gen5

import android.content.Context
import android.util.Log
import com.wae.agent.llm.LlmPlanner
import com.wae.core.step.LegacyStepMapping
import com.wae.core.step.PlannerBudget
import com.wae.core.step.RuleTaskPlanner
import com.wae.core.step.StepGoal
import com.wae.core.step.TaskMemory
import com.wae.core.step.TaskPlanner

/**
 * 大模型版任務計畫（三層兩憶計畫 §3）：**只碰任務邊界**。
 *
 * 三條紀律：
 *  1. 每任務 ≤ 2 次呼叫（`PlannerBudget`）。
 *  2. 模型不可用（lite 版／載入失敗）→ 回 null，上層自動退回 `RuleTaskPlanner`。
 *  3. 輸出轉成封閉詞彙（`StepGoal`）；認不出來的步驟一律變 DONE，不硬塞動作。
 */
class QwenTaskPlanner(
    private val context: Context,
    private val budget: PlannerBudget = PlannerBudget(limit = 2)
) : TaskPlanner {

    private val llm by lazy { LlmPlanner(context) }
    private val rules = RuleTaskPlanner()

    @Volatile
    private var available: Boolean? = null

    override fun plan(memory: TaskMemory): List<StepGoal>? {
        if (!budget.canCall()) {
            Log.i(TAG, "big planner budget exhausted -> rules")
            return rules.plan(memory)
        }
        val usable = available ?: runCatching { llm.ensureLoaded() }.getOrDefault(false).also {
            available = it
        }
        if (!usable) {
            Log.i(TAG, "big planner unavailable -> rules")
            return rules.plan(memory)
        }
        budget.noteCall()
        val plan = runCatching { llm.plan(memory.command) }.getOrNull() ?: return rules.plan(memory)
        val pairs = plan.steps.map { it.doText to it.target }
        val goals = LegacyStepMapping.toPlan(pairs)
        if (goals.isEmpty()) return rules.plan(memory)
        Log.i(TAG, "big planner steps=" + goals.size + " calls=" + budget.used())
        return goals
    }

    fun callsUsed(): Int = budget.used()

    private companion object {
        const val TAG = "WAE_TASKPLAN"
    }
}
