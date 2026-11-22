package com.wae.agent.agent

import android.content.Context

/**
 * 對外入口（M0 骨架）：把目標交給 AgentLoop。
 * M1 起會注入 Qwen3 Planner；M2 起注入 cross-encoder 決策層。
 */
class AgentController(
    private val context: Context,
    private val loopFactory: (onProgress: (String) -> Unit) -> AgentLoop
) {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun run(goal: String, onProgress: (String) -> Unit = {}): AgentRunOutcome {
        cancelled = false
        val loop = loopFactory(onProgress)
        val outcome = loop.run(goal)
        runCatching { AgentHistory.save(context, goal, null, outcome) }
        return outcome
    }
}
