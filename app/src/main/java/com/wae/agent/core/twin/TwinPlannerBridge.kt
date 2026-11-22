package com.wae.agent.core.twin

import com.wae.agent.core.decision.TwinGraph
import com.wae.agent.core.decision.TwinPlanner

/**
 * M5b：把 Twin 軌跡轉成世界圖，並用 MCTS 在圖上規劃下一步。
 *
 * 與 M3a 快路徑的差別：
 *  - 快路徑：**當前狀態完全相同**時直接重放（無搜尋）
 *  - MCTS：當前狀態不在圖上（或重放失敗）時，**搜尋**到達目標意圖的最短路徑
 *
 * 圖的建法：每個成功步驟是一條邊
 *   from(spineBefore) --action/target--> to(spineAfter)
 *   成功率先驗 = 同邊成功次數 / 總次數（此處只用成功步驟，故為 1.0，之後接遥測）
 */
object TwinPlannerBridge {

    /** 用歷史軌跡建圖（只含成功步驟） */
    fun buildGraph(history: List<TwinStep>): TwinGraph {
        val edges = HashMap<String, MutableList<TwinGraph.TwinEdge>>()
        for (s in history) {
            if (!s.ok || s.spineBefore == 0L || s.spineAfter == 0L) continue
            val key = s.spineBefore.toString()
            val actionId = s.action + "|" + selectorKeyOf(s)
            val list = edges.getOrPut(key) { ArrayList() }
            if (list.none { it.actionId == actionId && it.toSpine == s.spineAfter.toString() }) {
                list.add(
                    TwinGraph.TwinEdge(
                        actionId = actionId,
                        toSpine = s.spineAfter.toString(),
                        successRate = 1.0f,
                        costMs = s.ms.coerceAtLeast(50)
                    )
                )
            }
        }
        return TwinGraph(edges)
    }

    /**
     * 在圖上規劃：目標是「任何一個由 goalAction 產生的狀態」。
     * 找不到圖或沒計畫 → null（呼叫端回退既有恢復流程）。
     */
    fun plan(
        history: List<TwinStep>,
        currentSpine: Long,
        goalAction: String,
        iterations: Int = 120
    ): TwinPlanner.Plan? {
        if (history.isEmpty() || currentSpine == 0L) return null
        val goalSpines = history
            .filter { it.ok && it.action == goalAction }
            .map { it.spineAfter.toString() }
            .toSet()
        if (goalSpines.isEmpty()) return null
        val graph = buildGraph(history)
        if (!graph.hasNode(currentSpine.toString())) return null
        return TwinPlanner.plan(
            graph = graph,
            startSpine = currentSpine.toString(),
            isGoal = { it in goalSpines },
            iterations = iterations,
            maxDepth = 6
        )
    }

    /** 在歷史中找出「該動作 + 該目標狀態」的原始步驟，用來定位元素 */
    fun stepFor(history: List<TwinStep>, plan: TwinPlanner.Plan): TwinStep? {
        return history.lastOrNull { s ->
            s.ok &&
                s.action + "|" + selectorKeyOf(s) == plan.firstAction &&
                s.spineAfter.toString() == plan.firstTargetState
        } ?: history.lastOrNull { s ->
            s.ok && s.spineAfter.toString() == plan.firstTargetState
        }
    }

    fun selectorKeyOf(s: TwinStep): String = when {
        s.viewId.isNotEmpty() -> "id:" + s.viewId.substringAfterLast('/')
        s.roleTag.isNotEmpty() && s.target.isNotEmpty() -> "role:" + s.roleTag + ":" + s.target.take(16)
        s.target.isNotEmpty() -> "text:" + s.target.take(16)
        else -> "grid:" + s.gridX + "," + s.gridY
    }
}
