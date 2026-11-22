package com.wae.agent.gen5

import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * debug 期觀察 `agent_goal.txt`，有內容就交給舊的 LLM Agent 跑一次，結果寫回 `agent_result.txt`。
 *
 * 從 4,000 多行的無障礙服務抽出來（服務瘦身第二塊）：它的依賴只有
 * 「服務實例」與「服務是否已銷毀」，其餘全是檔案讀寫，適合獨立。
 *
 * 邊界：**只在 debuggable 或 `agent_enabled_flag` 存在時啟動**，release 預設關閉。
 */
class AgentDebugWatcher(
    private val service: com.wae.agent.core.WaeVisionAccessibilityService,
    private val dir: File,
    private val enabled: Boolean,
    private val isDestroyed: () -> Boolean
) {

    fun start() {
        if (!enabled) return
        thread(name = "wae-agent-debug-watch", isDaemon = true) {
            var lastGoal = ""
            while (!isDestroyed()) {
                runCatching {
                    val goalFile = File(dir, GOAL_FILE)
                    if (!goalFile.isFile) return@runCatching
                    val goal = goalFile.readText(Charsets.UTF_8).trim()
                    if (goal.isEmpty() || goal == lastGoal) return@runCatching

                    lastGoal = goal
                    goalFile.delete()
                    Log.i(TAG, "goal=$goal")
                    val out = com.wae.agent.core.AgentRunner(service).run(goal)
                    File(dir, RESULT_FILE).writeText(
                        "result=" + out.result + " steps=" + out.stepsExecuted +
                            " llmCalls=" + out.llmCalls + " msg=" + out.message,
                        Charsets.UTF_8
                    )
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val TAG = "WAE_AGENT"
        const val GOAL_FILE = "agent_goal.txt"
        const val RESULT_FILE = "agent_result.txt"
        const val POLL_INTERVAL_MS = 3_000L
    }
}
