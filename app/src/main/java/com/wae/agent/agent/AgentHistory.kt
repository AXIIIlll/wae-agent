package com.wae.agent.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/** AgentRun 落盤（診斷包/訓練資料用）。 */
object AgentHistory {

    private const val DIR = "agent/runs"
    private const val MAX_RUNS = 50

    fun save(context: Context, goal: String, plan: AgentPlan?, outcome: AgentRunOutcome): File? =
        runCatching {
            val dir = File(context.filesDir, DIR)
            dir.mkdirs()
            val file = File(dir, "run-" + System.currentTimeMillis() + ".json")
            val obj = JSONObject()
                .put("goal", goal)
                .put("result", outcome.result.name)
                .put("message", outcome.message)
                .put("steps", outcome.stepsExecuted)
                .put("llmCalls", outcome.llmCalls)
                .put("ts", System.currentTimeMillis())
            plan?.let { p ->
                obj.put("planSummary", p.summary)
                obj.put("planSteps", p.steps.size)
            }
            file.writeText(obj.toString(), Charsets.UTF_8)
            trim(dir)
            file
        }.getOrNull()

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        if (files.size <= MAX_RUNS) return
        files.take(files.size - MAX_RUNS).forEach { runCatching { it.delete() } }
    }
}
