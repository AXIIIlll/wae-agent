package com.wae.agent.core.automaton

/**
 * S12：學習型前向／反向模型（架構書 §16 S12）——純 Kotlin，無神經網路。
 *
 * | 模型 | 形式 | 用途 |
 * |---|---|---|
 * | 前向 P(s' | s, a) | 稀疏計數表 | 規劃時預測後繼、支援 S9 預取 |
 * | 反向 a = f(s, s') | 轉移索引（深度受限） | 「我想從這裡到那裡，該按什麼」 |
 *
 * 反向模型是關鍵：它把「目標達成」變成「指定下一個狀態」，讓決策脫離語意比對。
 */
object ForwardModel {

    data class Prediction(val nextState: String, val probability: Float)

    /** 由圖上的 Beta 統計建立稀疏機率表 */
    fun predict(automaton: Automaton, stateId: String, op: String? = null): List<Prediction> {
        val edges = automaton.edges.filter {
            it.from == stateId && (op == null || it.op.equals(op, ignoreCase = true))
        }
        if (edges.isEmpty()) return emptyList()
        val total = edges.sumOf { (it.successRate * it.total).toDouble() }.toFloat()
        if (total <= 0f) return emptyList()
        return edges
            .map { Prediction(it.to, (it.successRate * it.total) / total) }
            .groupBy { it.nextState }
            .map { (next, list) -> Prediction(next, list.sumOf { it.probability.toDouble() }.toFloat()) }
            .sortedWith(compareByDescending<Prediction> { it.probability }.thenBy { it.nextState })
    }

    /** 最可能的後繼（S9 預取用） */
    fun bestNext(automaton: Automaton, stateId: String, op: String? = null): String? =
        predict(automaton, stateId, op).firstOrNull()?.nextState
}

object ReverseModel {

    data class Action(val op: String, val selector: String, val fromState: String)

    /** 最多探索幾層（架構書：決策樹深度 ≤ 6） */
    const val MAX_DEPTH = 6

    /** 由歷史轉移建立的直接映射 (from → to) → 動作 */
    fun learnedActions(automaton: Automaton): Map<String, Action> {
        val out = LinkedHashMap<String, Action>()
        for (e in automaton.edges.sortedWith(
            compareByDescending<AutomatonEdge> { it.successRate }.thenBy { it.selector }
        )) {
            val key = e.from + "\u0001" + e.to
            if (!out.containsKey(key)) out[key] = Action(e.op, e.selector, e.from)
        }
        return out
    }

    /**
     * 「我想從這裡到那裡，該按什麼」：先查學到的直接轉移，沒有才做廣度搜尋（≤ MAX_DEPTH）。
     */
    fun actionToward(automaton: Automaton, from: String, target: String): Action? {
        if (from == target) return null
        learnedActions(automaton)["$from\u0001$target"]?.let { return it }

        val adjacency: Map<String, List<AutomatonEdge>> = automaton.edges
            .sortedWith(compareByDescending<AutomatonEdge> { it.successRate })
            .groupBy { it.from }
        val visited = HashSet<String>()
        // 佇列元素 = (目前狀態, 從起點到這裡的**第一步**動作)
        val queue = ArrayDeque<Pair<String, Action?>>()
        visited.add(from)
        queue.add(from to null)
        var depth = 0
        while (queue.isNotEmpty() && depth <= MAX_DEPTH) {
            val levelSize = queue.size
            repeat(levelSize) {
                val (cur, firstAction) = queue.removeFirst()
                for (e in adjacency[cur].orEmpty()) {
                    if (!visited.add(e.to)) continue
                    // 回傳的是「從起點踏出的第一步」，不是最後一步
                    val firstStep = firstAction ?: Action(e.op, e.selector, e.from)
                    if (e.to == target) return firstStep
                    queue.add(e.to to firstStep)
                }
            }
            depth++
        }
        return null
    }
}
