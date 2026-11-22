package com.wae.agent.core.taskos

/**
 * 任務會話（TaskSession）：一份計畫 + 黑板 + 游標 + 修復狀態。
 *
 * 這是 Task OS 在執行期唯一的狀態物件：
 *  - [nextRunnable] 取得下一個要跑的步驟（已代入 `$var`、已依守衛/螢幕文字跳過）
 *  - [onSuccess] 收集變數、記錄、存 checkpoint
 *  - [onFailure] 產生最小修復步驟（[RepairPlanner]），修完再重試原步驟
 *  - [toCheckpoint]/[fromCheckpoint] 支援中斷續跑
 *
 * 純 Kotlin，JVM 可測。
 */
class TaskSession(
    val plan: TaskPlan,
    val context: TaskContext = TaskContext(plan.command)
) {
    /** 待執行佇列（含修復插入的步驟） */
    private val queue = ArrayDeque<TaskStep>()

    /** 計畫本身的 nodeId（修復步驟不計入完成數，避免進度顯示 3/2） */
    private val planNodeIds: Set<String> = plan.steps.map { it.nodeId }.toSet()

    /** 已完成步數（只計計畫步驟，不含修復步驟；診斷/進度用） */
    var completed = 0
        private set

    /** 每個 nodeId 已嘗試次數（修復上限用） */
    private val attempts = HashMap<String, Int>()

    @Volatile
    private var finished = false

    /** 是否已判定失敗（沒有可用的修復） */
    @Volatile
    private var failed = false

    init {
        plan.steps.forEach { queue.addLast(it) }
    }

    /** 已綁定變數的步驟（執行層只需要這個） */
    data class Runnable(
        val step: TaskStep,
        val goal: com.wae.agent.core.LocalGoal
    )

    val isDone: Boolean get() = finished && queue.isEmpty() && !failed

    val hasFailed: Boolean get() = failed

    fun remainingCount(): Int = queue.size

    fun progressText(): String = "步驟 ${completed}/${plan.steps.size}"

    /**
     * 取下一個可執行步驟。
     *
     * 守衛判定：
     *  - `Always` → 執行
     *  - `TextContains` → 畫面文字包含才執行，否則跳過（記為成功跳過）
     *  - `VarEquals` / `VarPresent` / `VarAbsent` → 依黑板判定
     *
     * @param screenTexts 目前畫面可見文字（沒有就傳空 list）
     */
    fun nextRunnable(screenTexts: List<String> = emptyList()): Runnable? {
        if (queue.isEmpty()) {
            finished = true
            return null
        }
        while (queue.isNotEmpty()) {
            val step = queue.removeFirst()
            if (!guardSatisfied(step.guard, screenTexts)) {
                // 守衛不成立 = 這步不該做；視為完成（避免卡住）
                if (step.nodeId in planNodeIds) completed += 1
                context.record(step, ok = true, collected = emptyMap())
                continue
            }
            val bound = Runnable(
                step = step,
                goal = step.goal.copy(
                    goalText = context.resolve(step.goal.goalText),
                    inputPayload = context.resolve(step.goal.inputPayload)
                )
            )
            return bound
        }
        finished = true
        return null
    }

    /** 成功：收集變數、存 checkpoint */
    fun onSuccess(runnable: Runnable, visibleTexts: List<String> = emptyList()) {
        val step = runnable.step
        val collected = LinkedHashMap<String, String>()
        step.collect?.let { spec ->
            val v = runCatching { spec.extract(visibleTexts) }.getOrNull()
            if (!v.isNullOrBlank()) {
                context.put(spec.name, v)
                collected[spec.name] = v
            }
        }
        if (step.nodeId in planNodeIds) completed += 1
        context.record(step, ok = true, collected = collected)
        saveCheckpoint()
        if (queue.isEmpty()) finished = true
    }

    /**
     * 失敗：產生最小修復並插入佇列（修完重試原步驟）；回傳修復說明（null＝不修）。
     */
    fun onFailure(runnable: Runnable, screenTexts: List<String> = emptyList()): RepairPlanner.Repair? {
        val step = runnable.step
        val attempt = (attempts[step.nodeId] ?: 0) + 1
        attempts[step.nodeId] = attempt
        context.record(step, ok = false, collected = emptyMap())
        val repair = RepairPlanner.plan(
            failed = step,
            attempt = attempt,
            screenTexts = screenTexts,
            planPrefix = plan.planId + "-repair"
        ) ?: run {
            // 沒有可用修復 → 這個任務確定失敗（isDone 不該為 true，否則外層會誤判成功）
            failed = true
            finished = true
            return null
        }
        // 先修復，再重試原步驟（retryOriginal）
        if (repair.retryOriginal) queue.addFirst(step)
        for (s in repair.steps.asReversed()) queue.addFirst(s)
        saveCheckpoint()
        return repair
    }

    /** 這一步是否為不可逆（送出/付款）→ 執行前必須確認 */
    fun requiresConfirmation(step: TaskStep): Boolean = step.irreversible

    private fun guardSatisfied(guard: TaskGuard, screenTexts: List<String>): Boolean = when (guard) {
        is TaskGuard.Always -> true
        is TaskGuard.TextContains -> {
            val joined = screenTexts.joinToString(" ")
            if (guard.caseSensitive) joined.contains(guard.text) else
                joined.contains(guard.text, ignoreCase = true)
        }
        is TaskGuard.VarEquals -> context.get(guard.name) == guard.value
        is TaskGuard.VarPresent -> context.has(guard.name)
        is TaskGuard.VarAbsent -> !context.has(guard.name)
    }

    // ── checkpoint ────────────────────────────────────────────────

    fun saveCheckpoint(): Boolean =
        CheckpointStore.save(CheckpointStore.keyOf(plan.command), toCheckpoint())

    fun toCheckpoint(): String {
        val sb = StringBuilder()
        sb.append(CheckpointStore.HEADER).append('\n')
        sb.append("planId=").append(esc(plan.planId)).append('\n')
        sb.append("template=").append(esc(plan.templateId)).append('\n')
        sb.append("command=").append(esc(plan.command)).append('\n')
        sb.append("created=").append(plan.createdAtMs).append('\n')
        sb.append("done=").append(if (finished) 1 else 0).append('\n')
        for (step in queue) {
            sb.append("step=")
                .append(esc(step.nodeId)).append('\t')
                .append(esc(step.goal.goalId)).append('\t')
                .append(esc(step.goal.goalText)).append('\t')
                .append(step.goal.actionKind.name).append('\t')
                .append(esc(step.goal.inputPayload)).append('\t')
                .append(esc(step.collect?.name ?: "")).append('\t')
                .append(if (step.irreversible) 1 else 0).append('\n')
        }
        for ((k, v) in context.snapshotVars()) {
            sb.append("ctxvar=").append(esc(k)).append('\t').append(esc(v)).append('\n')
        }
        return sb.toString()
    }

    companion object {
        fun fromCheckpoint(text: String): TaskSession? {
            if (!text.startsWith(CheckpointStore.HEADER)) return null
            var planId = ""
            var template = ""
            var command = ""
            var created = 0L
            var done = false
            val steps = ArrayList<TaskStep>()
            val vars = LinkedHashMap<String, String>()

            for (line in text.lineSequence()) {
                when {
                    line.startsWith("planId=") -> planId = unesc(line.removePrefix("planId="))
                    line.startsWith("template=") -> template = unesc(line.removePrefix("template="))
                    line.startsWith("command=") -> command = unesc(line.removePrefix("command="))
                    line.startsWith("created=") -> created = line.removePrefix("created=").toLongOrNull() ?: 0L
                    line.startsWith("done=") -> done = line.removePrefix("done=").trim() == "1"
                    line.startsWith("step=") -> {
                        val f = line.removePrefix("step=").split('\t')
                        if (f.size >= 7) {
                            val kind = runCatching {
                                com.wae.agent.core.ActionKind.valueOf(f[3])
                            }.getOrNull() ?: continue
                            val stepIdx = steps.size
                            val collectName = unesc(f[5])
                            val collect = if (collectName.isNotBlank()) CollectSpec(collectName) else null
                            steps.add(
                                TaskStep(
                                    nodeId = unesc(f[0]),
                                    goal = com.wae.agent.core.LocalGoal(
                                        stepIdx = stepIdx,
                                        goalId = unesc(f[1]),
                                        goalText = unesc(f[2]),
                                        canonicalIntentTag = kind.name,
                                        actionKind = kind,
                                        inputPayload = unesc(f[4])
                                    ),
                                    collect = collect,
                                    irreversible = f[6] == "1"
                                )
                            )
                        }
                    }
                    line.startsWith("ctxvar=") -> {
                        val rest = line.removePrefix("ctxvar=")
                        val tab = rest.indexOf('\t')
                        if (tab > 0) vars[unesc(rest.substring(0, tab))] = unesc(rest.substring(tab + 1))
                    }
                }
            }
            if (planId.isBlank() || command.isBlank()) return null
            val plan = TaskPlan(
                planId = planId,
                command = command,
                templateId = template,
                steps = steps,
                requiredVars = emptySet(),
                createdAtMs = created
            )
            val session = TaskSession(plan, TaskContext(command, vars))
            session.finished = done
            return session
        }

        private fun esc(raw: String): String = raw
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "")

        private fun unesc(raw: String): String {
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    when (val n = raw[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '\\' -> sb.append('\\')
                        else -> sb.append(n)
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
