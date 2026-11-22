package com.wae.agent.core.omega

/**
 * WAE-Ω 第 5 層：模擬沙盒（SimSandbox）——「先在模型裡跑一遍，再決定要不要真的執行」。
 *
 * 為什麼需要：
 *  - 即時反應式 agent 的錯都在「已經點下去了」才發現；有 App Model 就能先模擬：
 *      * 每一步在模型裡找得到轉移嗎？（可達性）
 *      * 這條路徑的歷史成功率乘起來還夠高嗎？
 *      * 哪裡是「模型不確定」→ 標記成探測步驟（probe），而不是盲點
 *  - 模擬結果同時是「執行前的證明素材」：預期成功率、風險步驟、需要探測的位置。
 *
 * 純 Kotlin，JVM 可測。
 */
object SimSandbox {

    /** 低於這個預期成功率就不建議直接執行（先探測/先確認） */
    const val MIN_EXPECTED_SUCCESS = 0.55f

    data class SimStep(
        val index: Int,
        val intent: TaskIrStep.Intent,
        val stateBefore: String,
        val stateAfter: String,
        val certainty: Float,
        val note: String
    )

    /** 模型不知道怎麼做的地方 → 建議的探測步驟 */
    data class Probe(
        val fromState: String,
        val targetType: UiOntology.UiType,
        val reason: String
    )

    data class SimResult(
        val feasible: Boolean,
        val steps: List<SimStep>,
        val uncertainties: List<String>,
        val probes: List<Probe>,
        val expectedSuccess: Float,
        val needsConfirmation: Boolean
    ) {
        fun summary(): String =
            "feasible=$feasible expected=${"%.2f".format(expectedSuccess)} " +
                "uncertainties=${uncertainties.size} probes=${probes.size}"
    }

    /**
     * 模擬一條 Task IR 計畫。
     *
     * @param startStateId 起始狀態（null＝模型裡第一個狀態；模型為空則無法模擬）
     * @param minSuccess 建議執行的最低預期成功率
     */
    fun simulate(
        plan: TaskIrPlan,
        model: AppModel,
        startStateId: String? = null,
        minSuccess: Float = MIN_EXPECTED_SUCCESS
    ): SimResult {
        val steps = ArrayList<SimStep>()
        val uncertainties = ArrayList<String>()
        val probes = ArrayList<Probe>()
        var expected = 1f
        var needsConfirm = false

        var current: String = startStateId ?: model.states.firstOrNull()?.id ?: return SimResult(
            feasible = false,
            steps = emptyList(),
            uncertainties = listOf("empty_model"),
            probes = emptyList(),
            expectedSuccess = 0f,
            needsConfirmation = false
        )
        if (model.stateById(current) == null) {
            return SimResult(
                feasible = false,
                steps = emptyList(),
                uncertainties = listOf("unknown_start_state:$current"),
                probes = emptyList(),
                expectedSuccess = 0f,
                needsConfirmation = false
            )
        }

        for ((idx, step) in plan.steps.withIndex()) {
            val before = current
            var certainty = 1f
            var note = "ok"
            when (step.intent) {
                TaskIrStep.Intent.NAVIGATE -> {
                    val type = step.target?.type
                    val candidates = model.transitionsFrom(before)
                        .filter { t -> type == null || t.targetType == null || t.targetType == type }
                        .sortedByDescending { it.successRate * (it.ok + 1) }
                    val best = candidates.firstOrNull()
                    if (best == null) {
                        certainty = 0.35f
                        note = "no_transition"
                        uncertainties.add("nav:$before->${type?.name ?: "?"}")
                        if (type != null) {
                            probes.add(Probe(before, type, "模型中找不到這個轉移，需要探測"))
                        }
                    } else {
                        certainty = 0.55f + 0.45f * best.successRate
                        note = "via:${best.selector}"
                        current = best.toId
                    }
                }
                TaskIrStep.Intent.READ -> {
                    val field = step.target?.fieldName.orEmpty()
                        .ifBlank { step.target?.textHint.orEmpty() }
                    val schema = model.fields.firstOrNull {
                        it.name == field || it.readPattern.contains(field) || field.contains(it.name)
                    }
                    if (schema == null) {
                        certainty = 0.60f
                        note = "no_schema"
                        uncertainties.add("read:$field")
                    } else {
                        certainty = 0.90f
                        note = "field:${schema.name}"
                    }
                }
                TaskIrStep.Intent.WRITE -> {
                    val type = step.target?.type ?: UiOntology.UiType.TEXT_FIELD
                    if (!model.supports(type)) {
                        certainty = 0.40f
                        note = "type_unsupported"
                        uncertainties.add("write:${type.name}")
                    } else {
                        certainty = 0.85f
                        note = "supported:${type.name}"
                    }
                }
                TaskIrStep.Intent.COMPUTE -> {
                    certainty = 1.0f
                    note = "local_compute"
                }
                TaskIrStep.Intent.BRANCH -> {
                    certainty = 0.70f
                    note = "branch_guard"
                }
                TaskIrStep.Intent.COMMIT -> {
                    certainty = 0.98f
                    note = "needs_confirmation"
                    needsConfirm = true
                }
            }
            expected *= certainty.coerceAtLeast(0.05f)
            steps.add(SimStep(idx, step.intent, before, current, certainty, note))
        }

        val feasible = uncertainties.isEmpty() && expected >= minSuccess
        return SimResult(feasible, steps, uncertainties, probes, expected, needsConfirm)
    }
}
