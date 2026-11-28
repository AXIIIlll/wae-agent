package com.wae.agent.core.automaton

/**
 * 在自動機上做 A* 規劃。
 *
 * 目標有兩種：
 *  1. [planToState]：走到指定的狀態（已知目標頁）
 *  2. [planToSelectorHint]：走到「某個有機會操作目標元素的狀態」
 *     （該狀態有出邊的選擇器含目標關鍵詞，例如「搜尋」）
 *
 * 成本模型（學習型）：
 * ```
 * c(edge) = p95Ms/1000 + λ·(1 − 成功率)，λ = 3.0
 * ```
 * 失敗率高的邊自然變貴，規劃器會繞路；負邊（M1）在呼叫端以 [isBlocked] 排除。
 *
 * 啟發式：以 MinHash 相似度估計「離目標還多遠」，乘上樂觀單步成本；
 * 對 planToSelectorHint（目標是一組狀態）時 h = 0（退化為 Dijkstra，圖很小仍很快）。
 */
object AutomatonPlanner {

    /** 失敗的代價權重（秒） */
    private const val LAMBDA = 3.0

    /** 單步的樂觀成本（毫秒）；注意成本以「秒」計算，套用時必須 /1000 */
    private const val OPTIMISTIC_STEP_MS = 300

    data class Plan(
        val states: List<String>,
        val edges: List<AutomatonEdge>,
        val expectedCostMs: Int,
        val expectedSuccess: Float
    ) {
        val steps: Int get() = edges.size
        fun first(): AutomatonEdge? = edges.firstOrNull()
        fun summary(): String =
            "plan steps=${edges.size} cost=${expectedCostMs}ms success=${"%.2f".format(expectedSuccess)}"
    }

    /** 邊成本下限（秒），避免決策頭的 bonus 把成本扣成 0 或負值 */
    private const val MIN_EDGE_COST_SEC = 0.01

    private fun edgeCostMs(e: AutomatonEdge): Double =
        (e.p95Estimate().coerceAtLeast(1) / 1000.0) + LAMBDA * (1.0 - e.successRate)

    /**
     * 加上可選的「決策頭」重排序（S29）。
     *
     * [edgeBonus] 回傳扣減量（秒）；預設恆為 0.0 → 成本模型與舊版完全一致，
     * 既有測試與行為不受影響。bonus 由呼叫端決定來源（目前是
     * `com.wae.agent.core.decision.DecisionHead.edgeBonus`，旗標關閉時為 0）。
     */
    private fun effectiveCostMs(e: AutomatonEdge, edgeBonus: (AutomatonEdge) -> Double): Double =
        (edgeCostMs(e) - edgeBonus(e)).coerceAtLeast(MIN_EDGE_COST_SEC)

    /** 走到指定狀態 */
    fun planToState(
        automaton: Automaton,
        from: String,
        to: String,
        isBlocked: (AutomatonEdge) -> Boolean = { false },
        maxExpansions: Int = 512,
        edgeBonus: (AutomatonEdge) -> Double = { 0.0 }
    ): Plan? {
        if (from == to) return Plan(listOf(from), emptyList(), 0, 1f)
        val goal = automaton.byId(to) ?: return null
        return search(automaton, from, listOf(to), isBlocked, maxExpansions, edgeBonus) { s ->
            // h：與目標狀態的相似度越低，估計越貴
            val sim = MinHash.similarity(s.fingerprint, goal.fingerprint)
            ((1f - sim).coerceIn(0f, 1f) * OPTIMISTIC_STEP_MS / 1000.0)
        }
    }

    /**
     * 走到「最近且能執行某種動作、或能操作含 hint 元素」的狀態。
     *
     * 為什麼需要 goalOp：實測「搜尋 invoice」的 token 對不上 `id:search_omnibox`，
     * 但自動機裡「打字」這件事記為 `op = TYPE`，用動作型別當提示命中率高得多。
     *
     * @param hint  目標文字（可空）
     * @param goalOp 目標動作（`ActionKind.name`，可空）
     */
    fun planToGoalHint(
        automaton: Automaton,
        from: String,
        hint: String,
        goalOp: String,
        isBlocked: (AutomatonEdge) -> Boolean = { false },
        maxExpansions: Int = 512,
        edgeBonus: (AutomatonEdge) -> Double = { 0.0 }
    ): Plan? {
        val tokens = tokenize(hint)
        val goals = automaton.states.filter { st ->
            automaton.edges.any { e ->
                if (e.from != st.id || isBlocked(e)) return@any false
                (goalOp.isNotEmpty() && e.op == goalOp) ||
                    tokens.any { t -> e.selector.contains(t, ignoreCase = true) }
            }
        }.map { it.id }
        if (goals.isEmpty()) return null
        return search(automaton, from, goals, isBlocked, maxExpansions, edgeBonus) { 0.0 }
    }

    /**
     * 走到「最近且能操作含 hint 的元素」的狀態。
     *
     * 命中條件：該狀態有出邊，其選擇器（id/role/text）包含 hint 的任一 token。
     */
    fun planToSelectorHint(
        automaton: Automaton,
        from: String,
        hint: String,
        isBlocked: (AutomatonEdge) -> Boolean = { false },
        maxExpansions: Int = 512,
        edgeBonus: (AutomatonEdge) -> Double = { 0.0 }
    ): Plan? {
        // selector 內的文字是雜湊（隱私），所以除了原文 token 之外，
        // 也要用「整句雜湊」比對，否則 role:x:<hash> 永遠對不上。
        val tokens = (tokenize(hint) + com.wae.agent.core.action.SelectorKeys.hashOf(hint))
        if (tokens.isEmpty()) return null
        val goals = automaton.states.filter { s ->
            automaton.edges.any { e ->
                e.from == s.id &&
                    !isBlocked(e) &&   // 被負邊擋掉的路不算「可操作」
                    tokens.any { t -> e.selector.contains(t, ignoreCase = true) }
            }
        }.map { it.id }
        if (goals.isEmpty()) return null
        return search(automaton, from, goals, isBlocked, maxExpansions, edgeBonus) { 0.0 }
    }

    // ── A* / Dijkstra 核心 ────────────────────────────────────────────

    private fun search(
        automaton: Automaton,
        from: String,
        goals: List<String>,
        isBlocked: (AutomatonEdge) -> Boolean,
        maxExpansions: Int,
        edgeBonus: (AutomatonEdge) -> Double,
        heuristic: (AutomatonState) -> Double
    ): Plan? {
        val goalSet = goals.toHashSet()
        return searchBy(automaton, from, { it in goalSet }, isBlocked, maxExpansions, edgeBonus, heuristic)
    }

    /**
     * 述詞目標的 A*：**目標判定改成 `eval(Φ, world)`**，啟發改成述詞距離。
     *
     * 為什麼換：原本的目標是「到達某個 state id」，那是「技能」的思維
     * （先知道要走到哪一頁，才知道要做什麼）。述詞思維是「不管走到哪，只要 Φ 成立」。
     *
     * admissible 的選擇：**選 admissible**。
     *   `h(s) = distance(Φ, facts(s)) × MIN_EDGE_COST_SEC`
     *   因為每個未成立的原子至少要一步才可能被消掉，而每一步的成本 ≥ `MIN_EDGE_COST_SEC`，
     *   所以 h 不會高估。代價是展開節點變多（原本 `OPTIMISTIC_STEP_MS = 300` 那版不是 admissible）。
     *   這裡刻意選「保證最優」，因為述詞目標常用在「一次就要對」的系統設定上。
     *
     * @param factsOf 給定 state id，回傳該狀態的特徵快照（沒有就回 null＝未知，視為不成立）
     */
    fun planToPhi(
        automaton: Automaton,
        from: String,
        phi: com.wae.core.predicate.Phi,
        factsOf: (String) -> com.wae.core.predicate.PredicateWorld?,
        isBlocked: (AutomatonEdge) -> Boolean = { false },
        maxExpansions: Int = 512,
        edgeBonus: (AutomatonEdge) -> Double = { 0.0 }
    ): Plan? {
        val isGoal: (String) -> Boolean = { id ->
            factsOf(id)?.let { w -> com.wae.core.predicate.PredicateEval.eval(phi, w) } == true
        }
        val heuristic: (AutomatonState) -> Double = { st ->
            val d = factsOf(st.id)?.let { w -> com.wae.core.predicate.PredicateEval.distance(phi, w) } ?: 0
            d * MIN_EDGE_COST_SEC
        }
        return searchBy(automaton, from, isGoal, isBlocked, maxExpansions, edgeBonus, heuristic)
    }

    private fun searchBy(
        automaton: Automaton,
        from: String,
        isGoal: (String) -> Boolean,
        isBlocked: (AutomatonEdge) -> Boolean,
        maxExpansions: Int,
        edgeBonus: (AutomatonEdge) -> Double,
        heuristic: (AutomatonState) -> Double
    ): Plan? {
        val byFrom = HashMap<String, MutableList<AutomatonEdge>>()
        for (e in automaton.edges) {
            if (isBlocked(e)) continue
            byFrom.getOrPut(e.from) { ArrayList() }.add(e)
        }
        if (!byFrom.containsKey(from) && !isGoal(from)) return null

        data class Node(val id: String, val g: Double, val f: Double, val prev: Node?, val via: AutomatonEdge?)

        val best = HashMap<String, Double>()
        val open = java.util.PriorityQueue<Node>(compareBy { it.f })
        val startState = automaton.byId(from) ?: return null
        open.add(Node(from, 0.0, heuristic(startState), null, null))
        var expansions = 0

        while (open.isNotEmpty() && expansions < maxExpansions) {
            val cur = open.poll()
            if (cur.g > (best[cur.id] ?: Double.MAX_VALUE)) continue
            if (isGoal(cur.id)) {
                val edges = ArrayList<AutomatonEdge>()
                val states = ArrayList<String>()
                var n: Node? = cur
                while (n != null) {
                    states.add(0, n.id)
                    n.via?.let { edges.add(0, it) }
                    n = n.prev
                }
                val costMs = cur.g.times(1000).toInt()
                var success = 1f
                for (e in edges) success *= e.successRate
                return Plan(states, edges, costMs, success)
            }
            expansions++
            for (e in byFrom[cur.id].orEmpty()) {
                val ng = cur.g + effectiveCostMs(e, edgeBonus)
                if (ng >= (best[e.to] ?: Double.MAX_VALUE)) continue
                best[e.to] = ng
                val st = automaton.byId(e.to) ?: continue
                open.add(Node(e.to, ng, ng + heuristic(st), cur, e))
            }
        }
        return null
    }

    /** 把目標文字切成可用的比對 token（中文 2 字以上、英文 3 字以上） */
    fun tokenize(hint: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in hint) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            } else {
                if (sb.length >= 2) out.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.length >= 2) out.add(sb.toString())
        return out.filter { it.length >= 2 || it.any { c -> c.code < 128 } }.distinct()
    }
}
