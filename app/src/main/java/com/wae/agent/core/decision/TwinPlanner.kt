package com.wae.agent.core.decision

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Twin 世界圖：節點是頁面簽名（spineHash），邊是「動作 → 目標狀態 + 成功率 + 耗時」。
 * 這是 MCTS 的搜尋空間，也是「反事實模擬」的基礎。
 */
class TwinGraph(
    edges: Map<String, List<TwinEdge>> = emptyMap()
) {
    data class TwinEdge(
        val actionId: String,
        val toSpine: String,
        /** 歷史成功率（0..1），來自技能庫／觀察 */
        val successRate: Float,
        val costMs: Long = 300
    )

    private val adjacency: Map<String, List<TwinEdge>> = edges

    fun successors(spine: String): List<TwinEdge> = adjacency[spine].orEmpty()

    fun hasNode(spine: String): Boolean = adjacency.containsKey(spine)

    fun nodeCount(): Int = adjacency.size

    fun edgeCount(): Int = adjacency.values.sumOf { it.size }

    companion object {
        fun of(vararg pairs: Pair<String, List<TwinEdge>>) = TwinGraph(pairs.toMap())
    }
}

/**
 * Twin 上的蒙地卡羅規劃（UCT / MCTS）。
 *
 * 與雲端 Agent 的根本差別：他們無法模擬「你的設備狀態」；我們可以在本地的 Twin 圖上
 * 跑上百次 rollout，選出期望成功率最高的第一步，再動真機一次。
 */
object TwinPlanner {

    data class Plan(
        val firstAction: String,
        val firstTargetState: String,
        val expectedSuccess: Double,
        val rollouts: Int,
        /** 同一層其他候選的期望值（用於反事實說明） */
        val alternatives: List<Pair<String, Double>> = emptyList()
    )

    private class Node(val spine: String) {
        var visits: Int = 0
        var value: Double = 0.0
        var expanded: Boolean = false
        val children: MutableList<Child> = mutableListOf()
    }

    private class Child(val edge: TwinGraph.TwinEdge, val node: Node) {
        var visits: Int = 0
        var value: Double = 0.0
    }

    /**
     * @param isGoal 目標狀態謂詞（例如 spine ∈ SEARCH_RESULTS）
     * @param iterations rollout 次數（預算；每次微秒級）
     */
    fun plan(
        graph: TwinGraph,
        startSpine: String,
        isGoal: (String) -> Boolean,
        iterations: Int = 200,
        maxDepth: Int = 8,
        exploration: Double = 1.4,
        rng: Random = Random(42)
    ): Plan? {
        if (isGoal(startSpine)) return null
        val root = Node(startSpine)

        repeat(iterations) {
            val path = ArrayList<Child>(maxDepth)
            var node = root
            var depth = 0

            // ── Selection + Expansion ─────────────────────────────
            while (depth < maxDepth) {
                if (isGoal(node.spine) || node.children.isEmpty() && node.expanded) break
                if (!node.expanded) {
                    val succ = graph.successors(node.spine)
                    if (succ.isEmpty()) break
                    for (e in succ) node.children.add(Child(e, Node(e.toSpine)))
                    node.expanded = true
                    // 展開後立刻選一個子節點，保證每次 rollout 至少推進一步
                }
                val child = selectUct(node, exploration, rng) ?: break
                path.add(child)
                node = child.node
                depth++
            }

            // ── Rollout（以邊成功率做加權蒙地卡羅估計）─────────────
            val value = rollout(graph, node.spine, isGoal, maxDepth - depth, rng)

            // ── Backup（沿實際走過的路徑回填，標準 MCTS）──────────
            node.visits++
            node.value += value
            for (c in path) {
                c.visits++
                c.value += value
            }
            root.visits++
        }

        if (root.children.isEmpty()) return null
        val ranked = root.children
            .map { it.edge.actionId to (if (it.visits > 0) it.value / it.visits else 0.0) }
            .sortedByDescending { it.second }
        val best = root.children.maxByOrNull { it.visits } ?: return null
        val expected = if (best.visits > 0) best.value / best.visits else 0.0
        return Plan(
            firstAction = best.edge.actionId,
            firstTargetState = best.edge.toSpine,
            expectedSuccess = expected,
            rollouts = iterations,
            alternatives = ranked.drop(1)
        )
    }

    /**
     * 反事實比較：給定多個「第一步候選」，分別模擬到達目標的機率。
     * 回答「如果我先按返回會怎樣？」「點搜尋還是點推薦？」這類問題。
     */
    fun compareFirstActions(
        graph: TwinGraph,
        startSpine: String,
        candidateActions: List<String>,
        isGoal: (String) -> Boolean,
        maxDepth: Int = 8,
        rng: Random = Random(7)
    ): List<Pair<String, Double>> {
        val succ = graph.successors(startSpine)
        val results = candidateActions.map { actionId ->
            val edge = succ.firstOrNull { it.actionId == actionId }
            if (edge == null) {
                actionId to 0.0
            } else {
                actionId to rollout(graph, edge.toSpine, isGoal, maxDepth - 1, rng)
            }
        }
        return results.sortedByDescending { it.second }
    }

    private fun selectUct(node: Node, exploration: Double, rng: Random): Child? {
        if (node.children.isEmpty()) return null
        // 未訪問的子節點優先（等機率挑一個），保證廣度
        val unvisited = node.children.filter { it.visits == 0 }
        if (unvisited.isNotEmpty()) return unvisited[rng.nextInt(unvisited.size)]

        var best: Child? = null
        var bestScore = -Double.MAX_VALUE
        for (c in node.children) {
            val q = c.value / c.visits
            val u = exploration * sqrt(ln(node.visits.toDouble() + 1.0) / c.visits)
            val score = q + u
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    /** 以邊的成功率抽樣走 maxDepth 步，統計到達目標的比率 */
    private fun rollout(
        graph: TwinGraph,
        from: String,
        isGoal: (String) -> Boolean,
        depth: Int,
        rng: Random,
        samples: Int = 24
    ): Double {
        if (isGoal(from)) return 1.0
        if (depth <= 0) return 0.0
        if (graph.successors(from).isEmpty()) return 0.0

        var success = 0
        repeat(samples) {
            var cur = from
            var d = depth
            var reached = false
            while (d > 0) {
                if (isGoal(cur)) { reached = true; break }
                val next = graph.successors(cur)
                if (next.isEmpty()) break
                val picked = weightedPick(next, rng)
                if (rng.nextDouble() > picked.successRate.toDouble()) break
                cur = picked.toSpine
                d--
            }
            if (reached || isGoal(cur)) success++
        }
        return success.toDouble() / samples
    }

    private fun weightedPick(edges: List<TwinGraph.TwinEdge>, rng: Random): TwinGraph.TwinEdge {
        val total = edges.sumOf { it.successRate.toDouble() }.coerceAtLeast(1e-6)
        var r = rng.nextDouble() * total
        for (e in edges) {
            r -= e.successRate
            if (r <= 0) return e
        }
        return edges.last()
    }
}
