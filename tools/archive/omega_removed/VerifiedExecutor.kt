package com.wae.agent.core.omega

/**
 * WAE-Ω 第 6 層：驗證式執行（VerifiedExecutor）。
 *
 * 與「動作有沒有發出去」不同，這裡每一步都要留下**契約與證據**：
 *  - 前置（pre）：這一步需要的變數/型別/狀態
 *  - 後置（post）：執行後應成立的事實（由 bridge 回報或由畫面讀回）
 *  - 證據（evidence）：一行可審計的紀錄（步驟、解析後的值、結果）
 *
 * 失敗時產生「反例」：哪一步、哪個契約不成立、當時畫面文字是什麼，
 * 交給上層決定修復（重試/改路徑/問使用者），而不是盲目重跑。
 *
 * 執行本身透過 [ExecutorBridge] 抽象，讓單元測試可以完整覆蓋（不需要 Android）。
 *
 * 純 Kotlin，JVM 可測。
 */
object VerifiedExecutor {

    /** 執行層能力（正式路徑由無障礙服務實作） */
    interface ExecutorBridge {
        /** 執行一個 IR 步驟；READ 時 [readValue] 是先前讀到的內容（可為 null） */
        fun executeStep(step: TaskIrStep, resolvedValue: String?): Boolean

        /** READ 實作：從畫面讀出欄位（抽不到回 null） */
        fun readField(field: FieldSchema?): String?

        /** COMMIT 前的確認（回傳 false＝使用者不同意/沒有確認管道） */
        fun confirmCommit(step: TaskIrStep): Boolean = false

        /** 目前畫面文字（證據用） */
        fun screenTexts(): List<String> = emptyList()

        fun isCancelled(): Boolean = false

        fun onProgress(text: String) {}
    }

    data class Contract(
        val pre: List<String> = emptyList(),
        val post: List<String> = emptyList()
    )

    data class StepEvidence(
        val index: Int,
        val intent: TaskIrStep.Intent,
        val contract: Contract,
        val success: Boolean,
        val evidence: String,
        /** 失敗時的反例（畫面/變數狀態摘要） */
        val counterExample: String = ""
    )

    data class ExecutionReport(
        val ok: Boolean,
        val steps: List<StepEvidence>,
        val failedStep: StepEvidence?,
        val message: String,
        val evidenceHash: String
    ) {
        fun summary(): String =
            "ok=$ok steps=${steps.size} message=$message"
    }

    private const val MAX_STEPS = 60

    /**
     * 執行一條已驗證的計畫。
     *
     * @param suppliedVars 呼叫端已提供的變數（READ 會覆寫同名變數）
     */
    fun run(
        plan: TaskIrPlan,
        bridge: ExecutorBridge,
        suppliedVars: MutableMap<String, String> = HashMap()
    ): ExecutionReport {
        val evidences = ArrayList<StepEvidence>()
        if (plan.steps.size > MAX_STEPS) {
            return buildReport(false, evidences, "plan_too_long", evidences)
        }

        for ((idx, step) in plan.steps.withIndex()) {
            if (bridge.isCancelled()) {
                return buildReport(false, evidences, "cancelled", evidences)
            }
            val pre = ArrayList<String>()
            val post = ArrayList<String>()
            when (step.intent) {
                TaskIrStep.Intent.READ -> {
                    pre.add("target_type=${step.target?.type?.name}")
                    post.add("var=${step.value?.name}")
                }
                TaskIrStep.Intent.WRITE -> {
                    pre.add("var=${step.value?.name}")
                    post.add("field=${step.target?.fieldName}")
                }
                TaskIrStep.Intent.COMPUTE -> {
                    pre.add("expr=${step.value?.expr}")
                    post.add("var=${step.value?.name}")
                }
                TaskIrStep.Intent.COMMIT -> {
                    pre.add("all_previous_ok")
                    post.add("committed")
                }
                TaskIrStep.Intent.NAVIGATE -> {
                    pre.add("target=${step.target?.type?.name}")
                    post.add("navigated")
                }
                TaskIrStep.Intent.BRANCH -> {
                    pre.add("guard=${step.guard}")
                    post.add("branch_taken")
                }
            }
            val contract = Contract(pre, post)

            // 1) 解析值（VAR/LITERAL/COMPUTE）。READ 的 VAR 是「產出」而非「消耗」，
            //    所以不能在這裡判斷缺變數（否則每個 READ 都會失敗）。
            var resolved: String? = null
            if (step.value != null) {
                resolved = resolveValue(step.value, suppliedVars)
                if (step.value.kind == ValueRef.Kind.VAR && resolved == null &&
                    step.intent != TaskIrStep.Intent.READ
                ) {
                    val ev = StepEvidence(
                        idx, step.intent, contract, false,
                        "missing_var:${step.value.name}",
                        "vars=${suppliedVars.keys}"
                    )
                    evidences.add(ev)
                    return buildReport(false, evidences, "missing_var", evidences)
                }
            }

            // 2) READ 特別處理：先從畫面讀值
            if (step.intent == TaskIrStep.Intent.READ) {
                val schema = step.target?.let {
                    FieldSchema(it.fieldName.ifBlank { it.textHint }, it.type)
                }
                val read = runCatching { bridge.readField(schema) }.getOrNull()
                if (!read.isNullOrBlank() && step.value?.name?.isNotBlank() == true) {
                    suppliedVars[step.value.name] = read
                }
                val ok = !read.isNullOrBlank()
                val ev = StepEvidence(
                    idx, step.intent, contract, ok,
                    if (ok) "read:${step.value?.name}=$read" else "read_failed:${step.target}",
                    if (ok) "" else "screen=${bridge.screenTexts().take(6)}"
                )
                evidences.add(ev)
                if (!ok) return buildReport(false, evidences, "read_failed", evidences)
                bridge.onProgress("✓ ${step.goalHint}")
                continue
            }

            // 3) COMMIT 需要確認
            if (step.intent == TaskIrStep.Intent.COMMIT) {
                val confirmed = runCatching { bridge.confirmCommit(step) }.getOrDefault(false)
                if (!confirmed) {
                    val ev = StepEvidence(
                        idx, step.intent, contract, false,
                        "confirmation_required",
                        "commit:${step.target}"
                    )
                    evidences.add(ev)
                    return buildReport(false, evidences, "confirmation_required", evidences)
                }
            }

            // 4) 一般執行（含 COMPUTE 的本地計算）
            if (step.intent == TaskIrStep.Intent.COMPUTE) {
                val name = step.value?.name.orEmpty()
                val computed = ComputeEval.eval(step.value?.expr.orEmpty())
                if (computed == null) {
                    val ev = StepEvidence(
                        idx, step.intent, contract, false,
                        "compute_failed:${step.value?.expr}",
                        ""
                    )
                    evidences.add(ev)
                    return buildReport(false, evidences, "compute_failed", evidences)
                }
                if (name.isNotBlank()) suppliedVars[name] = computed
                evidences.add(
                    StepEvidence(idx, step.intent, contract, true, "computed:$name=$computed")
                )
                bridge.onProgress("✓ ${step.goalHint}")
                continue
            }

            val ok = runCatching { bridge.executeStep(step, resolved) }.getOrDefault(false)
            val ev = StepEvidence(
                idx, step.intent, contract, ok,
                if (ok) "executed:${step.intent} target=${step.target}" else "execute_failed:${step.target}",
                if (ok) "" else "screen=${bridge.screenTexts().take(6)}"
            )
            evidences.add(ev)
            if (!ok) return buildReport(false, evidences, "execute_failed", evidences)
            bridge.onProgress("✓ ${step.goalHint}")
        }

        return buildReport(true, evidences, "completed", evidences)
    }

    private fun resolveValue(value: ValueRef, vars: Map<String, String>): String? = when (value.kind) {
        ValueRef.Kind.LITERAL -> value.literal
        ValueRef.Kind.VAR -> vars[value.name]
        ValueRef.Kind.COMPUTE -> ComputeEval.eval(value.expr)
    }

    private fun buildReport(
        ok: Boolean,
        evidences: List<StepEvidence>,
        message: String,
        forHash: List<StepEvidence>
    ): ExecutionReport {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(forHash.joinToString("\n") { "${it.index}|${it.intent}|${it.success}|${it.evidence}" }
                .toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return ExecutionReport(ok, evidences, evidences.lastOrNull { !it.success }, message, hash)
    }
}
