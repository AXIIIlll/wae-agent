package com.wae.agent.core.automaton

import com.wae.agent.core.twin.TwinFastPath
import com.wae.agent.core.twin.TwinStep

/**
 * 自動機編譯器：把「逐步軌跡」壓縮成「狀態圖」。
 *
 * 輸入：TwinStep 串（已有 sigsBefore / sigsAfter / action / viewId / roleTag / ok / ms）
 * 輸出：Automaton（節點 = 正規化頁面，邊 = 動作 + 統計）
 *
 * 三個關鍵設計：
 *  1. **確定性**：MinHash 固定種子、排序鍵固定 → 同一份軌跡必得同一份 sha256
 *  2. **增量**：可從舊圖出發合併新軌跡，不必整張重算
 *  3. **預算感知**：超過 nodeBudget / edgeBudget 時，淘汰「最容易重新發現」的部分
 *
 * 這個編譯器**不呼叫任何模型**，純計數與圖演算法。
 */
object AutomatonCompiler {

    /** 狀態 id：由指紋決定（可重現、跨裝置一致） */
    private fun stateId(fp: LongArray): String {
        var h = 0x9E3779B97F4A7C15uL.toLong()
        for (v in fp) h = h * 31 + v
        return "s" + java.lang.Long.toHexString(h)
    }

    fun compile(
        pkg: String,
        steps: List<TwinStep>,
        prev: Automaton? = null,
        lowEndDevice: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Automaton {
        if (pkg.isEmpty()) return prev ?: Automaton(pkg = pkg, builtAt = nowMs)

        // ── 1. 由舊圖出發（增量）───────────────────────────────────────
        val stateMap = LinkedHashMap<String, AutomatonState>()
        val edgeMap = LinkedHashMap<String, AutomatonEdge>()

        fun putState(fp: LongArray, posture: String, ts: Long) {
            val id = stateId(fp)
            val old = stateMap[id]
            stateMap[id] = if (old == null) {
                AutomatonState(id = id, fingerprint = fp, posture = posture, visits = 1, lastTs = ts)
            } else {
                old.copy(visits = old.visits + 1, lastTs = maxOf(old.lastTs, ts), posture = old.posture.ifEmpty { posture })
            }
        }

        prev?.states?.forEach { stateMap[it.id] = it }
        prev?.edges?.forEach { edgeMap[edgeKey(it)] = it }

        // ── 2. 併入新軌跡 ──────────────────────────────────────────────
        for (step in steps) {
            val fromFp = MinHash.fingerprint(step.sigsBefore)
            val toFp = MinHash.fingerprint(step.sigsAfter)
            putState(fromFp, step.posture, step.ts)
            putState(toFp, "", step.ts)

            val selector = TwinFastPath.selectorOf(step)
            val fromId = stateId(fromFp)
            val toId = stateId(toFp)
            val key = fromId + "\u0001" + step.action + "\u0001" + selector + "\u0001" + toId
            val old = edgeMap[key]
            edgeMap[key] = if (old == null) {
                AutomatonEdge(
                    from = fromId,
                    to = toId,
                    op = step.action,
                    selector = selector,
                    ok = if (step.ok) 1 else 0,
                    fail = if (step.ok) 0 else 1,
                    p95Ms = step.ms.coerceIn(0, 5000).toInt(),
                    firstTs = step.ts,
                    lastTs = step.ts,
                    lat = bucketOf(step.ms)
                )
            } else {
                old.copy(
                    ok = old.ok + if (step.ok) 1 else 0,
                    fail = old.fail + if (step.ok) 0 else 1,
                    p95Ms = maxOf(old.p95Ms, step.ms.coerceIn(0, 5000).toInt()),
                    lastTs = maxOf(old.lastTs, step.ts),
                    lat = old.lat.indices.map { i -> old.lat[i] + bucketOf(step.ms)[i] }
                )
            }
        }

        // ── 3. 依預算裁切（自適應）─────────────────────────────────────
        val complexity = GraphComplexity(
            states = stateMap.size,
            edges = edgeMap.size,
            skeletons = stateMap.values.map { it.posture }.filter { it.isNotEmpty() }.distinct().size,
            ops = edgeMap.values.map { it.op }.distinct().size
        )
        val decision = GraphBudget.decide(
            complexity = complexity,
            currentTier = prev?.tier ?: "SIMPLE",
            streak = prev?.upgradeStreak ?: 0,
            lowEndDevice = lowEndDevice,
            hasHistory = prev != null
        )
        val ttlMs = decision.ttlDays.toLong() * 24 * 3600 * 1000
        val keptEdges = trimEdges(edgeMap.values.toList(), decision.edgeBudget, ttlMs, nowMs)
        val keptStates = trimStates(stateMap, keptEdges, decision.nodeBudget)
        val finalEdges = keptEdges.filter { it.from in keptStates && it.to in keptStates }

        // ── M4：橋邊保護 + 疊化（bisimulation）──────────────────────────
        // 先確保淘汰後圖仍連通（用「淘汰前的全部邊」當候選），再合併等價節點。
        var built = Automaton(
            pkg = pkg,
            states = keptStates.values.sortedBy { it.id },
            edges = finalEdges.sortedWith(
                compareBy<AutomatonEdge> { it.from }.thenBy { it.op }.thenBy { it.selector }.thenBy { it.to }
            ),
            tier = decision.tier,
            upgradeStreak = decision.streak,
            builtAt = nowMs
        )
        if (REDUCTION_ENABLED) {
            // 橋邊保護：用「淘汰前的全部邊」當候選，把被砍斷的孤島接回來
            built = AutomatonReducer.ensureConnectivity(built, candidates = edgeMap.values.toList())
            // 疊化：合併「出邊標籤集合相同且後繼等價」的節點
            built = AutomatonReducer.minimize(built)
        }
        return built
    }

    /** M4 疊化開關（可獨立關閉，關閉即回到 M2 行為） */
    @Volatile
    var REDUCTION_ENABLED: Boolean = true

    /** 把一次延遲放進 4 桶直方圖 */
    private fun bucketOf(ms: Long): List<Int> {
        val m = ms.coerceIn(0, 5000)
        val idx = when {
            m < 200 -> 0
            m < 500 -> 1
            m < 1000 -> 2
            else -> 3
        }
        return List(4) { if (it == idx) 1 else 0 }
    }

    private fun edgeKey(e: AutomatonEdge): String = "${e.from}\u0001${e.op}\u0001${e.selector}\u0001${e.to}"

    /**
     * 邊的淘汰：保留「難重新發現」的邊。
     *   keepScore = 使用次數 × Beta 成功率 × 新鮮度 / (1 + 重建成本)
     *   重建成本 ≈ 從根節點（造訪最多的節點）到該邊起點的 BFS 距離
     */
    private fun trimEdges(
        all: List<AutomatonEdge>,
        edgeBudget: Int,
        ttlMs: Long,
        nowMs: Long
    ): List<AutomatonEdge> {
        val alive = all.filter { it.lastTs == 0L || nowMs - it.lastTs <= ttlMs || it.total >= 3 }
        if (alive.size <= edgeBudget) return alive
        val depth = bfsDepth(alive)
        return alive.sortedWith(
            compareByDescending<AutomatonEdge> {
                val recency = freshness(it.lastTs, nowMs)
                (it.total.coerceAtLeast(1) * it.successRate * recency) / (1f + (depth[it.from] ?: 0))
            }.thenByDescending { it.total }
                .thenBy { it.from }.thenBy { it.op }.thenBy { it.selector }
        ).take(edgeBudget)
    }

    private fun freshness(lastTs: Long, nowMs: Long): Float {
        if (lastTs <= 0L) return 1f
        val days = ((nowMs - lastTs).coerceAtLeast(0L) / (24f * 3600 * 1000)).toFloat()
        return (1f / (1f + days / 30f)).coerceIn(0.05f, 1f)
    }

    /** 從「造訪最多的節點」出發的 BFS 深度（重建成本代理） */
    private fun bfsDepth(edges: List<AutomatonEdge>): Map<String, Int> {
        if (edges.isEmpty()) return emptyMap()
        val adjacency = HashMap<String, MutableList<String>>()
        for (e in edges) adjacency.getOrPut(e.from) { ArrayList() }.add(e.to)
        val root = edges.groupingBy { it.from }.eachCount().maxByOrNull { it.value }?.key ?: edges.first().from
        val depth = HashMap<String, Int>()
        depth[root] = 0
        val queue = ArrayDeque<String>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val d = depth[cur] ?: 0
            for (nxt in adjacency[cur].orEmpty()) {
                if (!depth.containsKey(nxt)) {
                    depth[nxt] = d + 1
                    queue.add(nxt)
                }
            }
        }
        return depth
    }

    /** 節點淘汰：保留被保留邊引用到的節點；若仍超預算，依造訪次數保留 */
    private fun trimStates(
        states: LinkedHashMap<String, AutomatonState>,
        keptEdges: List<AutomatonEdge>,
        nodeBudget: Int
    ): LinkedHashMap<String, AutomatonState> {
        val referenced = HashSet<String>()
        for (e in keptEdges) {
            referenced.add(e.from)
            referenced.add(e.to)
        }
        val kept = LinkedHashMap<String, AutomatonState>()
        for ((id, s) in states) {
            if (id in referenced || referenced.isEmpty()) kept[id] = s
        }
        if (kept.size <= nodeBudget) return kept
        val trimmed = LinkedHashMap<String, AutomatonState>()
        kept.values.sortedWith(
            compareByDescending<AutomatonState> { it.visits }.thenByDescending { it.lastTs }.thenBy { it.id }
        ).take(nodeBudget).forEach { trimmed[it.id] = it }
        return trimmed
    }
}
