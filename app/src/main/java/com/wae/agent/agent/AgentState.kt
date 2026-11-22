package com.wae.agent.agent

/** 一次任務的計畫（LLM 產出，符合 plan.schema.json）。 */
data class AgentPlan(
    val version: Int = 1,
    val goalKind: String = "open_ended",
    val summary: String = "",
    val steps: List<AgentStep> = emptyList()
)

data class AgentStep(
    val id: Int,
    val doText: String,
    val target: String? = null,
    val verify: String? = null,
    val ask: String? = null,
    val options: List<String> = emptyList(),
    val optional: Boolean = false
)

/** 通用動作（不含座標、不含 App 流程）。 */
enum class AgentAction {
    TAP, TYPE, SCROLL, BACK, OPEN_APP, WAIT, ASK_USER, REPLAN, DONE
}

data class AgentDecision(
    val action: AgentAction,
    val index: Int = -1,
    val text: String? = null,
    val appName: String? = null,
    /** 直接用 package 開 App（比用 label 解析可靠；`$pkg:` target 會填這裡）。 */
    val appPackage: String? = null,
    val direction: String? = null,
    val confidence: Float = 0f,
    val reason: String = "",
    val question: String? = null,
    val options: List<String> = emptyList()
)

enum class VerifyOutcome { OK, FAIL, UNCERTAIN }

data class VerifyResult(
    val outcome: VerifyOutcome,
    val reason: String = "",
    val evidence: String = "",
    val screenChanged: Boolean = false
)

enum class AgentResult { SUCCESS, FAIL, ABORTED, ASKING }

data class AgentLimits(
    val maxSteps: Int = 8,
    val maxRetriesPerStep: Int = 2,
    val maxReplans: Int = 1,
    val deadlineMs: Long = 120_000L
)

/** 任務執行狀態（步數、重試、重規劃、截止時間）。 */
class AgentState(
    var plan: AgentPlan,
    val limits: AgentLimits = AgentLimits()
) {
    /** 執行期變數（$choice 等） */
    val vars: MutableMap<String, String> = mutableMapOf()

    var stepIndex: Int = 0
        private set
    var retries: Int = 0
        private set
    var replans: Int = 0
        private set
    val startedAt: Long = System.currentTimeMillis()

    fun currentStep(): AgentStep? = plan.steps.getOrNull(stepIndex)

    fun advance() {
        stepIndex += 1
        retries = 0
    }

    fun stepsLeft(): Boolean = stepIndex < plan.steps.size && stepIndex < limits.maxSteps

    fun allowRetry(): Boolean = retries < limits.maxRetriesPerStep

    fun noteRetry(): Boolean {
        if (!allowRetry()) return false
        retries += 1
        return true
    }

    fun allowReplan(): Boolean = replans < limits.maxReplans

    fun noteReplan(newPlan: AgentPlan): Boolean {
        if (!allowReplan()) return false
        replans += 1
        plan = newPlan
        retries = 0
        return true
    }

    fun deadlineExceeded(): Boolean = System.currentTimeMillis() - startedAt > limits.deadlineMs

    fun progressText(): String {
        val total = plan.steps.size
        val cur = currentStep()
        return "第 " + (stepIndex + 1) + "/" + total + " 步：" + (cur?.doText ?: "完成")
    }
}
