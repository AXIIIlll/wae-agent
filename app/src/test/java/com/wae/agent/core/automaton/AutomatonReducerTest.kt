package com.wae.agent.core.automaton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 測試：疊化（bisimulation）與橋邊保護。
 *
 * 這些測試的重點是「語意正確」而不是「數字好看」：
 * 合併後的圖必須保留原本可行的規劃路徑。
 */
class AutomatonReducerTest {

    private fun fp(base: Long) = MinHash.fingerprint((base until base + 20).toList())

    private fun state(id: String, base: Long, visits: Int = 1) =
        AutomatonState(id = id, fingerprint = fp(base), posture = "LIST_FEED", visits = visits)

    private fun edge(
        from: String,
        to: String,
        sel: String,
        op: String = "TAP",
        ok: Int = 3,
        fail: Int = 0
    ) = AutomatonEdge(from, to, op, sel, ok, fail, 300, 1L, 1L)

    @Test
    fun `identical behaviour nodes merge`() {
        // A→B, A→C 的 B、C 行為完全相同（都只有一條同標籤的邊到 D）→ B、C 應合併
        val a = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100), state("C", 200), state("D", 300)),
            edges = listOf(
                edge("A", "B", "id:tab1"),
                edge("A", "C", "id:tab2"),
                edge("B", "D", "id:next"),
                edge("C", "D", "id:next")
            )
        )
        val m = AutomatonReducer.minimize(a)
        assertEquals("B 與 C 行為等價 → 應合併", 3, m.stateCount())
        assertNotNull("合併後仍要能從 A 出發", m.byId("A"))
        // A 的兩條邊不能被合併（選擇器不同）
        assertEquals(2, m.edges.count { it.from == "A" })
    }

    @Test
    fun `distinct behaviour is not merged`() {
        // B→D（next），C→E（back）→ 行為不同，不可合併
        val a = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100), state("C", 200), state("D", 300), state("E", 400)),
            edges = listOf(
                edge("A", "B", "id:tab1"),
                edge("A", "C", "id:tab2"),
                edge("B", "D", "id:next"),
                edge("C", "E", "id:back")
            )
        )
        val m = AutomatonReducer.minimize(a)
        // B、C 的標籤不同（next vs back）→ 不可合併；但 D、E 都是無出邊的終點，
        // 依 bisimulation 它們行為等價（都沒得走），會合併 → 5 個節點變 4 個。
        assertEquals("標籤不同的節點不可合併（D/E 同為終點可合併）", 4, m.stateCount())
        assertEquals("A 的兩條出邊都要保留", 2, m.edges.count { it.from == "A" })
    }

    @Test
    fun `merged nodes accumulate statistics`() {
        val a = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100), state("C", 200), state("D", 300)),
            edges = listOf(
                edge("A", "B", "id:x"),
                edge("A", "C", "id:y"),
                edge("B", "D", "id:next", ok = 4, fail = 1),
                edge("C", "D", "id:next", ok = 6, fail = 2)
            )
        )
        val m = AutomatonReducer.minimize(a)
        val merged = m.edges.first { it.from != "A" && it.to == "D" }
        assertEquals("ok 應累加", 10, merged.ok)
        assertEquals("fail 應累加", 3, merged.fail)
    }

    @Test
    fun `minimization is deterministic`() {
        val a = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100), state("C", 200), state("D", 300)),
            edges = listOf(
                edge("A", "B", "id:tab1"),
                edge("A", "C", "id:tab2"),
                edge("B", "D", "id:next"),
                edge("C", "D", "id:next")
            )
        )
        val m1 = AutomatonReducer.minimize(a)
        val m2 = AutomatonReducer.minimize(m1)
        assertEquals("疊化必須可重現（sha 相同）", m1.sha256(), m2.sha256())
        assertEquals("再次疊化不應再變", m1.stateCount(), m2.stateCount())
    }

    @Test
    fun `bridge protection reconnects islands`() {
        // 主元件 A-B（3 個節點）＋孤島 C，覆蓋率 3/5=0.6 < 0.8 → 應補橋邊
        // 主元件 {A,B,D}（3/5 = 0.6 < 0.8），C 與 E 是孤島
        val a = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100), state("C", 200), state("D", 300), state("E", 400)),
            edges = listOf(edge("A", "B", "id:1"), edge("A", "D", "id:2"))
        )
        val candidates = listOf(edge("B", "C", "id:to-c", ok = 2, fail = 1))
        val fixed = AutomatonReducer.ensureConnectivity(a, candidates)
        assertTrue("孤島應被橋邊接回", fixed.edges.any { it.selector == "id:to-c" })
        assertEquals(5, fixed.stateCount())
        assertTrue("邊數應增加", fixed.edgeCount() > a.edgeCount())
    }

    @Test
    fun `no bridge added when graph already connected`() {
        val a = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100)),
            edges = listOf(edge("A", "B", "id:1"))
        )
        val fixed = AutomatonReducer.ensureConnectivity(a, listOf(edge("B", "A", "id:2")))
        assertEquals("已連通就不該亂加邊", a.edgeCount(), fixed.edgeCount())
    }

    @Test
    fun `planning still works after minimization`() {
        // 疊化後仍必須能在圖上規劃 A→D
        val a = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100), state("C", 200), state("D", 300)),
            edges = listOf(
                edge("A", "B", "id:tab1"),
                edge("A", "C", "id:tab2"),
                edge("B", "D", "id:next"),
                edge("C", "D", "id:next")
            )
        )
        val m = AutomatonReducer.minimize(a)
        val plan = AutomatonPlanner.planToState(m, "A", "D")
        assertNotNull("疊化不可破壞可達性", plan)
        assertEquals(2, plan!!.steps)
    }

    @Test
    fun `sinks with no outgoing edges collapse`() {
        // 三個沒有出邊的終點頁 → 行為等價（都沒得走），合併成一個
        val a = Automaton(
            pkg = "p",
            states = listOf(state("A", 0), state("B", 100), state("C", 200), state("D", 300)),
            edges = listOf(edge("A", "B", "id:1"), edge("A", "C", "id:2"), edge("A", "D", "id:3"))
        )
        val m = AutomatonReducer.minimize(a)
        assertEquals("三個 sink 應合併", 2, m.stateCount())
    }
}
