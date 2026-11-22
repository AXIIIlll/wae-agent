package com.wae.agent.agent

/** 規劃器（M1：Qwen3-0.6B；M0：可用假實作測試）。 */
interface Planner {
    fun plan(goal: String): AgentPlan?
    fun replan(goal: String, failedStep: AgentStep?, reason: String): AgentPlan?
}

interface Observer {
    fun observe(): Observation
}

interface AgentExecutor {
    fun execute(decision: AgentDecision): Boolean
}

interface UserAsker {
    fun ask(question: String, options: List<String>): String?
}

interface SafetyCheck {
    fun allows(decision: AgentDecision, observation: Observation): Boolean
}

data class AgentRunOutcome(
    val result: AgentResult,
    val message: String = "",
    val stepsExecuted: Int = 0,
    val llmCalls: Int = 0
)

/**
 * Agent 主迴圈：PLAN → OBSERVE → DECIDE → SAFETY → ACT → VERIFY → LOOP。
 * 所有上限（步數/重試/重規劃/時間）都在 AgentState。
 */
class AgentLoop(
    private val planner: Planner,
    private val observer: Observer,
    private val resolver: DecisionResolver,
    private val verifier: VerifyRules,
    private val executor: AgentExecutor,
    private val asker: UserAsker,
    private val safety: SafetyCheck,
    private val limits: AgentLimits = AgentLimits(),
    private val onProgress: (String) -> Unit = {}
) {
    fun run(goal: String, onPlan: (AgentPlan) -> Unit = {}): AgentRunOutcome {
        var llmCalls = 0
        val plan = planner.plan(goal) ?: return AgentRunOutcome(AgentResult.FAIL, "plan failed")
        onPlan(plan)
        llmCalls += 1
        val state = AgentState(plan, limits)
        resolver.resetScrollCounter()
        var executed = 0
        var stalls = 0
        var lastIndex = -1

        while (state.stepsLeft()) {
            // 防止「沒有前進」的無限迴圈（例如反問後又反問）
            if (state.stepIndex == lastIndex) stalls++ else { stalls = 0; lastIndex = state.stepIndex }
            if (stalls > 3) {
                return AgentRunOutcome(AgentResult.FAIL, "no progress", executed, llmCalls)
            }
            if (state.deadlineExceeded()) {
                return AgentRunOutcome(AgentResult.ABORTED, "timeout", executed, llmCalls)
            }
            val step = state.currentStep() ?: break
            onProgress(state.progressText())
            val before = observer.observe()
            val decision = resolver.decide(step, before, state.vars)

            when (decision.action) {
                AgentAction.DONE -> return AgentRunOutcome(AgentResult.SUCCESS, "done", executed, llmCalls)
                AgentAction.ASK_USER -> {
                    val answer = asker.ask(decision.question ?: step.doText, decision.options)
                    if (answer.isNullOrBlank()) {
                        return AgentRunOutcome(AgentResult.ASKING, "user did not answer", executed, llmCalls)
                    }
                    state.vars["choice"] = answer
                    // 步驟本身要求反問 → 使用者回答即視為此步完成
                    if (!step.ask.isNullOrBlank() && decision.question == step.ask) {
                        state.advance()
                    }
                    continue
                }
                AgentAction.REPLAN -> {
                    val newPlan = if (state.allowReplan()) planner.replan(goal, step, decision.reason) else null
                    if (newPlan != null) {
                        llmCalls += 1
                        state.noteReplan(newPlan)
                        continue
                    }
                    return AgentRunOutcome(AgentResult.FAIL, "replan limit", executed, llmCalls)
                }
                else -> {
                    if (!safety.allows(decision, before)) {
                        return AgentRunOutcome(AgentResult.ABORTED, "unsafe", executed, llmCalls)
                    }
                    val ok = executor.execute(decision)
                    executed += 1
                    val after = observer.observe()
                    val verify = verifier.check(step, before, after, decision)
                    val verified = ok && verify.outcome != VerifyOutcome.FAIL
                    if (!verified) {
                        if (state.noteRetry()) continue
                        val newPlan = if (state.allowReplan()) planner.replan(goal, step, verify.reason) else null
                        if (newPlan != null) {
                            llmCalls += 1
                            state.noteReplan(newPlan)
                            continue
                        }
                        return AgentRunOutcome(AgentResult.FAIL, "step failed: " + verify.reason, executed, llmCalls)
                    }
                    state.advance()
                }
            }
        }
        return if (!state.stepsLeft()) {
            AgentRunOutcome(AgentResult.SUCCESS, "all steps done", executed, llmCalls)
        } else {
            AgentRunOutcome(AgentResult.FAIL, "steps exhausted", executed, llmCalls)
        }
    }
}
