package com.wae.agent.core.taskos

import android.content.Context

/**
 * Task OS 對外門面（TaskOsEngine）：App 只要跟這一個物件打交道。
 *
 * 對 App 的契約（全部旗標關閉時**完全不改變行為**）：
 *  1. `ensureLoaded(context)`：載入模板/槽位/checkpoint/技能
 *  2. `start(command, extraVars)`：嘗試把指令變成 TaskSession
 *     - `Planned` → 用 session 取代既有 goals 清單來跑
 *     - `NeedVars` → 反問使用者（不要硬猜）
 *     - `NoMatch` → fallback 到既有 `decomposeCommandIntoLocalGoals`
 *  3. `resume(command)`：有未完成 checkpoint 就接續
 *  4. `onSessionCompleted(session)`：編譯技能、清 checkpoint
 *
 * 旗標：`ExtremeRuntimeHooks.TASK_OS_ENABLED`（預設 false）。
 */
object TaskOsEngine {

    sealed class Start {
        data class Planned(val session: TaskSession) : Start()
        data class NeedVars(val templateId: String, val missing: Set<String>) : Start()
        data object NoMatch : Start()
    }

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context?) {
        if (loaded) return
        loaded = true
        TaskTemplates.ensureLoaded(context)
        SlotFiller.ensureLoaded(context)
        CheckpointStore.ensureLoaded(context)
        SkillCompiler.ensureLoaded(context)
    }

    fun resetForTest() {
        loaded = false
        TaskTemplates.resetForTest()
        SlotFiller.resetForTest()
        CheckpointStore.resetForTest()
        SkillCompiler.resetForTest()
    }

    fun enabled(): Boolean =
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED

    /**
     * 開始一個任務。
     *
     * 順序：先看有沒有同指令的未完成 checkpoint（續跑）→ 再看技能庫 → 最後才規劃。
     * 這個順序讓「做過的任務」越來越快（技能複利）。
     */
    fun start(
        command: String,
        extraVars: Map<String, String> = emptyMap(),
        nowMs: Long = 0L
    ): Start {
        if (!enabled() || command.isBlank()) return Start.NoMatch

        // 0) 續跑：同一指令有未完成 checkpoint
        resume(command)?.let { return Start.Planned(it) }

        // 1) 技能庫命中：直接展開，不用重新規劃
        val skill = SkillCompiler.findForCommand(command)
        if (skill != null) {
            val ctx = TaskContext(command, extraVars)
            val missing = skill.params.filter { !ctx.has(it) }.toSet()
            if (missing.isEmpty()) {
                val planId = "tp-skill-" + skill.id
                val steps = skill.instantiate(planId)
                if (steps.isNotEmpty()) {
                    val plan = TaskPlan(
                        planId = planId,
                        command = command,
                        templateId = skill.templateId.ifEmpty { "skill:" + skill.id },
                        steps = steps,
                        requiredVars = skill.params,
                        createdAtMs = if (nowMs > 0L) nowMs else System.currentTimeMillis()
                    )
                    return Start.Planned(TaskSession(plan, ctx))
                }
            }
        }

        // 2) 模板規劃
        val ctx = TaskContext(command, extraVars)
        return when (val outcome = TaskPlanner.plan(command, ctx, nowMs)) {
            is TaskPlanner.Outcome.Planned -> Start.Planned(TaskSession(outcome.plan, ctx))
            is TaskPlanner.Outcome.NeedVars -> Start.NeedVars(outcome.planTemplateId, outcome.missing)
            TaskPlanner.Outcome.NoMatch -> Start.NoMatch
        }
    }

    /** 讀回未完成的 checkpoint（過期/壞檔回 null） */
    fun resume(command: String): TaskSession? {
        if (!enabled()) return null
        val text = CheckpointStore.load(CheckpointStore.keyOf(command)) ?: return null
        val session = TaskSession.fromCheckpoint(text) ?: run {
            CheckpointStore.clear(CheckpointStore.keyOf(command))
            return null
        }
        if (session.isDone) {
            CheckpointStore.clear(CheckpointStore.keyOf(command))
            return null
        }
        return session
    }

    /** 任務完成時呼叫：編譯技能 + 清 checkpoint（旗標關閉時什麼都不做） */
    fun onSessionCompleted(session: TaskSession, nowMs: Long = 0L): SkillCompiler.Skill? {
        if (!enabled()) return null
        val skill = SkillCompiler.compile(session, nowMs)
        if (skill != null) {
            runCatching { SkillCompiler.save(skill) }
        }
        runCatching { CheckpointStore.clear(CheckpointStore.keyOf(session.plan.command)) }
        return skill
    }

    /** 放棄任務：清 checkpoint（避免下次誤續跑） */
    fun abandon(command: String) {
        runCatching { CheckpointStore.clear(CheckpointStore.keyOf(command)) }
    }
}
