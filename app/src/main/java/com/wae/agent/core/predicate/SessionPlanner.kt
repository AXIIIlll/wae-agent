package com.wae.agent.core.predicate

import com.wae.agent.core.automaton.Automaton
import com.wae.agent.core.automaton.AutomatonEdge
import com.wae.agent.core.automaton.AutomatonPlanner
import com.wae.agent.core.automaton.AutomatonState
import com.wae.core.predicate.Phi
import com.wae.core.predicate.PredicateWorld

/**
 * **把「這次探索到的地圖」變成可規劃的圖**（缺口 3 的接線）。
 *
 * 為什麼要這樣做：`AutomatonPlanner.planToPhi` 早就寫好了（述詞目標 + 距離啟發），
 * 但它需要「每個狀態長什麼樣」才規劃得動。自動機的持久化狀態只有 MinHash 指紋，
 * **沒有事實快照**，所以一直沒接上。
 *
 * 這裡改用**本次執行期間**觀察到的畫面當節點：
 * ```
 * 節點 = frame.fp           （畫面指紋）
 * 事實 = FactsWorld(frame)  （那個畫面有哪些元素）
 * 邊   = 我按了哪個標籤，從哪一頁到哪一頁
 * ```
 * 地圖雖然只有這次的，但足以回答「我現在該往哪一頁走」。
 */
object SessionPlanner {

    /** 一次規劃的輸入。 */
    data class Graph(
        val facts: Map<String, PredicateWorld> = emptyMap(),
        val edges: List<AutomatonEdge> = emptyList()
    )

    /**
     * 從 [from] 規劃到「滿足 [goal] 的頁面」，回傳**第一步要按的標籤**。
     *
     * @return null＝規劃不出來（沒有路、或已知的頁面都不滿足目標）
     */
    fun nextSelector(graph: Graph, from: String, goal: Phi, maxExpansions: Int = 256): String? {
        if (graph.facts.size < 2 || graph.edges.isEmpty()) return null
        val states = graph.facts.keys.map { id ->
            AutomatonState(id = id, fingerprint = LongArray(0), posture = "")
        }
        val automaton = Automaton(
            pkg = "",
            states = states,
            edges = graph.edges
        )
        val plan = runCatching {
            AutomatonPlanner.planToPhi(
                automaton = automaton,
                from = from,
                phi = goal,
                factsOf = { id -> graph.facts[id] },
                maxExpansions = maxExpansions
            )
        }.getOrNull() ?: return null
        return plan.edges.firstOrNull()?.selector?.takeIf { it.isNotBlank() }
    }
}
