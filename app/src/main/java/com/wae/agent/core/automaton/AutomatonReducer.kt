package com.wae.agent.core.automaton

/**
 * M4：疊化（bisimulation partition refinement）+ 橋邊保護。
 *
 * ### 為什麼要做疊化
 * 編譯出來的圖是「觀測到的頁面」，同一個邏輯頁面會被拆成很多節點
 * （內容不同、結構相同）。疊化把「出邊標籤集合相同、且後繼等價」的節點合併，
 * 通常可降 5–20×，規劃與比對都變快，統計也更集中（同一條邊的樣本變多）。
 *
 * ### 為什麼要橋邊保護
 * 預算淘汰（§3.7.4）會砍掉「重建成本低」的邊，但可能把圖切成好幾塊，
 * 規劃器於是回報「無解」。這裡在淘汰後檢查最大連通元件覆蓋率，
 * 不足就用「候選邊」中最便宜的一條把孤島接回來。
 *
 * 兩個函式都是**純函式、確定性**（同樣輸入 → 同樣輸出），可重現。
 */
object AutomatonReducer {

    /** 最大連通元件的覆蓋率下限；低於此值就補橋邊 */
    const val MIN_COMPONENT_COVERAGE = 0.80f

    /** 少於此節點數的圖，疊化省下的成本不足以抵銷計算成本 → 直接跳過 */
    const val MIN_STATES_TO_REDUCE = 48

    private fun edgeLabel(e: AutomatonEdge): String = e.op + "\u0001" + e.selector

    /**
     * 疊化：partition refinement。
     *
     * s ≡ t  iff  出邊標籤集合相同，且所有對應後繼都在同一等價類。
     */
    fun minimize(a: Automaton, maxRounds: Int = 20): Automaton {
        if (a.states.size <= 1 || a.edges.isEmpty()) return a

        val outEdges: Map<String, List<AutomatonEdge>> =
            a.edges.groupBy { it.from }

        // 初始分區：只看「出邊標籤集合」（含 sink）
        fun initialClass(s: AutomatonState): String {
            val labels = outEdges[s.id].orEmpty().map { edgeLabel(it) }.sorted()
            return labels.joinToString(";")
        }

        var classOf: Map<String, String> = a.states.associate { it.id to initialClass(it) }
        repeat(maxRounds) {
            val raw = HashMap<String, String>(a.states.size)
            for (s in a.states) {
                val sb = StringBuilder()
                sb.append(classOf[s.id]).append('|')
                for (e in outEdges[s.id].orEmpty().sortedWith(
                    compareBy<AutomatonEdge> { edgeLabel(it) }.thenBy { it.to }
                )) {
                    sb.append(edgeLabel(e)).append("->").append(classOf[e.to] ?: "?").append(';')
                }
                raw[s.id] = sb.toString()
            }
            // 正規化成穩定的小編號（排序 → 決定性）
            val order = raw.values.distinct().sorted()
            val next = raw.mapValues { order.indexOf(it.value).toString() }
            if (next == classOf) return@repeat
            classOf = next
        }

        // 每類挑一個代表（id 最小者，確保決定性）
        val members: Map<String, List<AutomatonState>> =
            a.states.groupBy { classOf[it.id] ?: it.id }
        val newStates = ArrayList<AutomatonState>(members.size)
        val classToId = HashMap<String, String>()
        for ((cls, list) in members.entries.sortedBy { it.key }) {
            val rep = list.minBy { it.id }
            classToId[cls] = rep.id
            // 指紋取「聯集」（MinHash 逐通道取最小值）：合併後的節點與任一成員都相似
            val merged = LongArray(rep.fingerprint.size) { i ->
                list.minOf { st -> st.fingerprint.getOrElse(i) { Long.MAX_VALUE } }
            }
            newStates.add(
                rep.copy(
                    fingerprint = merged,
                    visits = list.sumOf { it.visits },
                    lastTs = list.maxOf { it.lastTs },
                    posture = list.map { it.posture }.firstOrNull { it.isNotEmpty() } ?: rep.posture
                )
            )
        }

        // 邊：端點換成代表 id，重複邊合併統計
        val mergedEdges = LinkedHashMap<String, AutomatonEdge>()
        for (e in a.edges) {
            val from = classToId[classOf[e.from] ?: e.from] ?: e.from
            val to = classToId[classOf[e.to] ?: e.to] ?: e.to
            val key = "$from\u0001${e.op}\u0001${e.selector}\u0001$to"
            val old = mergedEdges[key]
            mergedEdges[key] = if (old == null) {
                e.copy(from = from, to = to)
            } else {
                old.copy(
                    ok = old.ok + e.ok,
                    fail = old.fail + e.fail,
                    p95Ms = maxOf(old.p95Ms, e.p95Ms),
                    firstTs = minOf(old.firstTs, e.firstTs),
                    lastTs = maxOf(old.lastTs, e.lastTs),
                    lat = old.lat.indices.map { i -> old.lat[i] + e.lat.getOrElse(i) { 0 } }
                )
            }
        }

        return a.copy(states = newStates, edges = mergedEdges.values.toList())
    }

    /**
     * 橋邊保護：若最大連通元件覆蓋率不足，從候選邊補回最便宜的連接邊。
     *
     * @param candidates 淘汰前看得到的邊（含被砍掉的），用來接回孤島
     */
    fun ensureConnectivity(
        a: Automaton,
        candidates: List<AutomatonEdge> = emptyList(),
        minCoverage: Float = MIN_COMPONENT_COVERAGE
    ): Automaton {
        if (a.states.size <= 1) return a
        val ids = a.states.map { it.id }.toHashSet()
        val edges = a.edges.toMutableList()
        val present = a.edges.map { it.from + "\u0001" + it.op + "\u0001" + it.selector + "\u0001" + it.to }.toHashSet()

        var guard = 0
        while (guard++ < 8) {
            val comp = components(ids, edges)
            if (comp.isEmpty()) return a
            val largest = comp.maxByOrNull { it.size } ?: return a
            val coverage = largest.size.toFloat() / ids.size.toFloat()
            if (coverage >= minCoverage) break

            val largestSet = largest.toHashSet()
            val others = comp.filter { it !== largest && it.any { id -> id !in largestSet } }
            if (others.isEmpty()) break

            // 在候選邊中找「一端在最大元件、另一端在孤島」且成本最低者
            val bridge = candidates
                .filter { e ->
                    (e.from in largestSet && e.to !in largestSet) ||
                        (e.to in largestSet && e.from !in largestSet)
                }
                .filter { e -> present.add(e.from + "\u0001" + e.op + "\u0001" + e.selector + "\u0001" + e.to) }
                .minByOrNull { costOf(it) }
                ?: break
            edges.add(bridge)
        }
        return if (edges.size == a.edges.size) a else a.copy(edges = edges)
    }

    private fun costOf(e: AutomatonEdge): Double =
        (e.p95Estimate().coerceAtLeast(1) / 1000.0) + 3.0 * (1.0 - e.successRate)

    /** 無向連通元件（狀態 id 集合） */
    private fun components(ids: Set<String>, edges: List<AutomatonEdge>): List<List<String>> {
        val parent = HashMap<String, String>()
        fun find(x: String): String {
            var p = parent.getOrPut(x) { x }
            while (p != parent.getOrPut(p) { p }) p = parent.getOrPut(p) { p }
            return p
        }
        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
        for (id in ids) parent[id] = id
        for (e in edges) {
            if (e.from in ids && e.to in ids && e.from != e.to) union(e.from, e.to)
        }
        return ids.groupBy { find(it) }.values.map { it.toList() }.filter { it.isNotEmpty() }
    }
}
