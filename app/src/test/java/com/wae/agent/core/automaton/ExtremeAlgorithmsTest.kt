package com.wae.agent.core.automaton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §16 剩餘純演算法項目測試：S23 雙層 ID O(1) 查表、S24 可重現性。
 *
 * 2026-11-16：S19 參數自校準、S20 WL meta-node、S24 digest/regression 已從執行期移除
 * （只寫 log、無查詢點），對應測試與類別一併刪除。
 */
class ExtremeAlgorithmsTest {

    private fun fp(b: Long) = MinHash.fingerprint((b until b + 20).toList())

    private fun edge(from: String, to: String, sel: String, op: String = "TAP", ok: Int = 5, fail: Int = 0) =
        AutomatonEdge(from, to, op, sel, ok, fail, 200, 1L, 1L)

    // ── S23 雙層 ID O(1) 查表 ───────────────────────────────────────

    @Test
    fun `edge index finds every inserted edge`() {
        val edges = (0 until 200).map { edge("s$it", "t$it", "id:sel$it") }
        val index = EdgeIndex.build(edges)
        assertEquals(200, index.size())
        for (e in edges) {
            val got = index.get(stateKeyOf(e.from), e.op, e.selector)
            assertEquals("查表必須命中", e, got)
        }
    }

    @Test
    fun `edge index rejects unknown keys`() {
        val index = EdgeIndex.build(listOf(edge("A", "B", "id:x")))
        assertNull(index.get(stateKeyOf("A"), "TAP", "id:missing"))
        assertNull(index.get(stateKeyOf("Z"), "TAP", "id:x"))
    }

    @Test
    fun `edge index overwrites duplicate keys`() {
        val first = edge("A", "B", "id:x", ok = 1, fail = 1)
        val second = edge("A", "B", "id:x", ok = 9, fail = 0)
        val index = EdgeIndex.build(listOf(first, second))
        assertEquals(1, index.size())
        assertEquals(9, index.get(stateKeyOf("A"), "TAP", "id:x")?.ok)
    }

    @Test
    fun `edge index lookup is fast`() {
        val edges = (0 until 2000).map { edge("s$it", "t$it", "id:sel$it") }
        val index = EdgeIndex.build(edges)
        repeat(5000) { index.get(stateKeyOf("s1"), "TAP", "id:sel1") }
        val keys = LongArray(edges.size) { stateKeyOf("s$it") }
        val sels = Array(edges.size) { "id:sel$it" }
        val tapCode = EdgeIndex.opCodeOf("TAP")
        val selHashes = LongArray(edges.size) { EdgeIndex.hashOf(sels[it]) }
        repeat(20000) { index.getByHash(keys[1], tapCode, selHashes[1]) }
        val n = 50000
        // 微基準取 5 次最佳值：JIT 暖機與 GC 會讓單次量測有 2–3 倍雜訊
        val samples = ArrayList<Double>()
        repeat(5) {
            val t0 = System.nanoTime()
            repeat(n) { i ->
                val k = i % edges.size
                // 真正的 O(1) primitive：所有 key 都已預先算成 long
                index.getByHash(keys[k], tapCode, selHashes[k])
            }
            samples.add((System.nanoTime() - t0).toDouble() / n)
        }
        val perNs = samples.min()
        println("PERF edgeLookupNs=%.1f samples=%s".format(perNs, samples.joinToString(",") { "%.0f".format(it) }))
        assertTrue("單次查詢必須 < 150ns（最佳值=%.1fns）".format(perNs), perNs < 150.0)
    }

    private fun stateKeyOf(id: String): Long {
        var h = 0L
        for (ch in id) h = h * 31 + ch.code
        return h
    }

    // ── S24 可重現性 ────────────────────────────────────────────────

    private fun fixedSteps(): List<com.wae.agent.core.twin.TwinStep> = (0 until 40).map { i ->
        com.wae.agent.core.twin.TwinStep(
            pkg = "com.example.fixed",
            spineBefore = (0 until 20).map { it + i }.hashCode().toLong(),
            spineAfter = (0 until 20).map { it + i + 1 }.hashCode().toLong(),
            action = "TAP",
            target = "第${i}項",
            posture = "LIST_FEED",
            ok = true,
            ms = 200L,
            ts = 1_700_000_000_000L + i
        )
    }

    @Test
    fun `compilation is bit for bit reproducible`() {
        val steps = fixedSteps()
        val now = 1_700_001_000_000L
        val a = AutomatonCompiler.compile("com.example.fixed", steps, nowMs = now)
        val b = AutomatonCompiler.compile("com.example.fixed", steps, nowMs = now)
        assertEquals("同一份軌跡必須逐位元相同", a.sha256(), b.sha256())
    }
}
