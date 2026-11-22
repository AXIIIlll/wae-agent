package com.wae.agent.agent

/**
 * 把 LLM 產出的計畫「綁定」到第一段選出的 App，但**不再吃掉明確指定的其他 App**。
 *
 * 規則：
 *  - 只有**裸 `$app`**（模型不知道要開哪個 App）才覆寫成已選 App。
 *  - `$app:名稱`（模型明確指定，例如跨 App 任務的第二步）保持原樣，執行期再用名稱解析。
 *  - 完全沒有開 App 步驟 → 在最前面補一步開已選 App。
 *
 * 為什麼要改：原本只要 target 以 `$app` 開頭就一律覆寫，導致「查版本 → 貼到 LINE」
 * 這種跨 App 計畫的第二步也被改成同一個 App，跨 App 任務永遠做不出來。
 */
class AppBoundPlanner(
    private val delegate: Planner,
    private val app: String?
) : Planner {

    override fun plan(goal: String): AgentPlan? = bind(delegate.plan(goal))

    override fun replan(goal: String, failedStep: AgentStep?, reason: String): AgentPlan? =
        bind(delegate.replan(goal, failedStep, reason))

    private fun bind(p: AgentPlan?): AgentPlan? {
        if (p == null) return p
        val steps = p.steps.toMutableList()
        val openIdx = steps.indexOfFirst { (it.target ?: "") == "\$app" }
        if (openIdx >= 0) {
            if (!app.isNullOrBlank()) steps[openIdx] = steps[openIdx].copy(target = "\$app:" + app)
        } else if (steps.none { (it.target ?: "").startsWith("\$app") }) {
            // 完全沒有開 App 步驟才補；已經有 $app:名稱 的計畫不要多插一步。
            if (!app.isNullOrBlank()) {
                steps.add(
                    0,
                    AgentStep(id = 1, doText = "開啟 " + app, target = "\$app:" + app, verify = "App 首頁")
                )
            }
        }
        return p.copy(steps = steps.mapIndexed { i, s -> s.copy(id = i + 1) })
    }
}
