package com.wae.agent.core.taskos

/**
 * 任務執行器（TaskRunner）：用 [TaskSession] 驅動整個長任務。
 *
 * 它把「執行一步」抽象成 [TaskExecutor]，因此：
 *  - 單元測試可以注入假的 executor，完整驗證長任務行為（分支/修復/續跑）
 *  - 正式執行時由無障礙服務提供真的 executor（同一份邏輯，不會有兩套行為）
 *
 * 純 Kotlin，JVM 可測。
 */
object TaskRunner {

    /** 最大值：超過就視為異常（避免無限迴圈） */
    const val MAX_STEPS_HARD_LIMIT = 120

    /** 執行一步所需的環境能力（正式路徑由服務實作） */
    interface TaskExecutor {
        /** 執行一個已綁定變數的目標；回傳是否成功 */
        fun execute(goal: com.wae.agent.core.LocalGoal): Boolean

        /** 目前畫面可見文字（收集變數/守衛判定用） */
        fun screenTexts(): List<String>

        /** 不可逆動作（送出/付款）的確認；回傳 true 才放行 */
        fun confirm(step: TaskStep, goal: com.wae.agent.core.LocalGoal): Boolean = false

        fun isCancelled(): Boolean = false

        fun onProgress(text: String) {}
    }

    data class Result(
        val completed: Int,
        val failedStep: TaskStep?,
        val cancelled: Boolean,
        val stepsRun: Int,
        val message: String
    ) {
        val ok: Boolean get() = failedStep == null && !cancelled
    }

    /**
     * 跑完整個會話（含修復/重試/續跑）。
     *
     * 呼叫端負責在開始前用 [TaskSession.fromCheckpoint] 還原（若存在）。
     */
    fun run(session: TaskSession, executor: TaskExecutor): Result {
        var stepsRun = 0
        var lastMessage = ""
        while (true) {
            if (executor.isCancelled()) {
                return Result(session.completed, null, true, stepsRun, "cancelled")
            }
            if (stepsRun >= MAX_STEPS_HARD_LIMIT) {
                return Result(session.completed, null, false, stepsRun, "hard_limit")
            }
            val runnable = session.nextRunnable(executor.screenTexts()) ?: break
            val step = runnable.step

            if (step.irreversible && !executor.confirm(step, runnable.goal)) {
                return Result(session.completed, step, false, stepsRun, "confirmation_declined")
            }

            stepsRun += 1
            val ok = runCatching { executor.execute(runnable.goal) }.getOrDefault(false)
            if (ok) {
                session.onSuccess(runnable, executor.screenTexts())
                executor.onProgress("✓ ${runnable.goal.goalText}（${session.progressText()}）")
                lastMessage = "ok"
                continue
            }

            // 失敗 → 最小修復（修完會自動重試原步驟；修不了就停）
            val repair = session.onFailure(runnable, executor.screenTexts())
            if (repair == null) {
                return Result(session.completed, step, false, stepsRun, "failed_no_repair")
            }
            executor.onProgress("⚠ ${runnable.goal.goalText} 失敗，修復：${repair.reason}")
            lastMessage = "repaired:${repair.reason}"
        }
        TaskOsEngine.onSessionCompleted(session)
        return Result(session.completed, null, false, stepsRun, "completed:$lastMessage")
    }
}
