package com.wae.agent.core.taskos

import java.util.UUID

/**
 * 任務規劃器：指令 → [TaskPlan]（模板比對 → 槽位填充 → 步驟展開 → 資料流檢查）。
 *
 * 這是 Task OS 的入口；**規劃不出計畫就回 null**，由上層 fallback 到既有拆解器，
 * 保證「Task OS 永遠不會讓原本能做的指令變差」。
 *
 * 純 Kotlin，JVM 可測。
 */
object TaskPlanner {

    /** 規劃結果 */
    sealed class Outcome {
        data class Planned(val plan: TaskPlan) : Outcome()

        /** 有命中模板但缺必要變數（例如沒說要開哪個 App）→ 應反問使用者 */
        data class NeedVars(val planTemplateId: String, val missing: Set<String>) : Outcome()

        /** 沒有可用模板 → 由上層 fallback */
        data object NoMatch : Outcome()
    }

    /**
     * 規劃。
     *
     * @param command 使用者指令
     * @param ctx 起始上下文（可帶已填好的變數，例如使用者 profile）
     * @param nowMs 現在時間（測試可注入）
     */
    fun plan(command: String, ctx: TaskContext = TaskContext(command), nowMs: Long = 0L): Outcome {
        if (command.isBlank()) return Outcome.NoMatch
        val template = TaskTemplates.match(command) ?: return Outcome.NoMatch

        // 1) 槽位填充：先抽指令裡的參數，再用呼叫端已提供的變數補
        val slots = SlotFiller.extract(command)
        for ((k, v) in slots) ctx.put(k, v)

        // 2) 展開步驟 DSL
        val planId = "tp-" + UUID.randomUUID().toString().take(8)
        val steps = TaskTemplates.parseSteps(planId, template.stepsSpec)
        if (steps.isEmpty()) return Outcome.NoMatch

        // 3) 收集必要的變數（步驟裡出現的 $var）
        val required = LinkedHashSet<String>()
        for (step in steps) {
            for (m in TASK_VAR_RE.findAll(step.goal.goalText)) required.add(m.groupValues[1])
            for (m in TASK_VAR_RE.findAll(step.goal.inputPayload)) required.add(m.groupValues[1])
            step.collect?.let { required.add(it.name) }
        }

        // 4) 缺變數 → 反問（但 collect 產生的變數不算缺）
        val produced = steps.mapNotNull { it.collect?.name }.toSet()
        val needed = required.filter { it !in produced && !ctx.has(it) }.toSet()
        if (needed.isNotEmpty()) return Outcome.NeedVars(template.id, needed)

        val plan = TaskPlan(
            planId = planId,
            command = command,
            templateId = template.id,
            steps = steps,
            requiredVars = required,
            createdAtMs = if (nowMs > 0L) nowMs else System.currentTimeMillis()
        )
        return Outcome.Planned(plan)
    }

    /**
     * 便利函式：沒有模板時用的「單步計畫」（讓 Task OS 對所有指令都能回一個計畫，
     * 但單步計畫不會改變行為——只是包一層，方便 checkpoint/續跑一致化）。
     */
    fun singleStepPlan(command: String, goal: com.wae.agent.core.LocalGoal): TaskPlan =
        TaskPlan(
            planId = "tp-single-" + goal.stepIdx,
            command = command,
            templateId = "",
            steps = listOf(TaskStep(nodeId = "0", goal = goal)),
            requiredVars = emptySet(),
            createdAtMs = System.currentTimeMillis()
        )
}
