package com.wae.agent.core.twin

/**
 * M5a：序列重放（把整條已錄製的鏈一次跑完，而不是逐步決策）。
 *
 * 為什麼先做序列重放而不是直接 MCTS：
 *  - MCTS 需要「圖夠密 + 邊上有成功率」，而重放是把已有的鏈變成真正的加速
 *  - 重放失敗時可以安全回退到原本的逐步決策，風險可控
 *
 * 選鏈規則（純邏輯，可單測）：
 *  1. 同一個 App
 *  2. 起始步驟的前置狀態與當前相似（或選擇器一致）
 *  3. 從該步開始**連續相連**（上一步後態 = 下一步前態）
 *  4. 全部成功、且都在 [maxAgeMs] 內
 *  5. 長度 ≥ [minSteps]，上限 [maxSteps]
 */
object TwinReplayPlan {

    data class Plan(
        val steps: List<TwinStep>,
        val similarity: Float,
        val startIndex: Int
    ) {
        fun summary(): String = "plan steps=${steps.size} sim=${"%.2f".format(similarity)}"
    }

    fun find(
        pkg: String,
        action: String,
        sigsStart: List<Long>,
        history: List<TwinStep>,
        commandKey: String = "",
        nowMs: Long = System.currentTimeMillis(),
        minSteps: Int = 2,
        maxSteps: Int = 8,
        minSimilarity: Float = 0.4f,
        maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000
    ): Plan? {
        if (history.isEmpty()) return null
        val usable = history
            .filter {
                it.pkg == pkg && it.ok && nowMs - it.ts <= maxAgeMs &&
                    TwinTaskKey.compatible(it.commandKey, commandKey)
            }
            .sortedBy { it.ts }
        if (usable.isEmpty()) return null

        // 找起點：動作相同，且前置狀態相似
        var bestPlan: Plan? = null
        for (i in usable.indices) {
            val s = usable[i]
            if (s.action != action) continue
            val sim = TwinStore.stateSimilarity(s.sigsBefore, sigsStart)
            val sameSelector = s.sigsBefore.isNotEmpty() && sim >= minSimilarity
            val noSigs = s.sigsBefore.isEmpty() && sigsStart.isEmpty()
            if (!sameSelector && !noSigs) continue

            // 從起點向後收集連續相連的成功步驟
            val run = ArrayList<TwinStep>(maxSteps)
            run.add(s)
            var cursor = s
            for (j in i + 1 until usable.size) {
                if (run.size >= maxSteps) break
                val next = usable[j]
                if (!next.ok) break
                if (next.spineBefore != cursor.spineAfter) break
                run.add(next)
                cursor = next
            }
            if (run.size < minSteps) continue
            if (bestPlan == null || sim > bestPlan.similarity || run.size > bestPlan.steps.size) {
                bestPlan = Plan(run, sim, i)
            }
        }
        return bestPlan
    }
}

/** 供序列重放的選擇器定位（與 TwinFastPath 同語意） */
fun locateStepIn(step: TwinStep, options: List<com.wae.agent.core.CandidateOption>):
    com.wae.agent.core.CandidateOption? {
    return com.wae.agent.core.action.SelectorResolver.resolve(step, options)?.candidate
}
