package com.wae.agent.core.automaton

import com.wae.agent.core.twin.TwinStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * M2 測試：MinHash 正規化、自動機編譯、自適應預算、可重現性。
 *
 * 這裡的核心契約是「**同一份軌跡必得同一張圖**」（sha256 相同），
 * 因為後面所有規劃與除錯都建立在「圖可重現」之上。
 */
class AutomatonTest {

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        GraphBudget.resetForTest()
        val candidates = listOf(
            File("../app/src/main/assets/open_source_ai_databases/ui_graph_budget_policy.tsv"),
            File("app/src/main/assets/open_source_ai_databases/ui_graph_budget_policy.tsv"),
            File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/ui_graph_budget_policy.tsv")
        )
        candidates.firstOrNull { it.exists() }?.let {
            GraphBudget.loadFromLines(it.readLines(Charsets.UTF_8))
        }
    }

    private fun step(
        sigsBefore: List<Long>,
        sigsAfter: List<Long>,
        action: String = "TAP",
        posture: String = "LIST_FEED",
        viewId: String = "com.example:id/btn",
        ok: Boolean = true,
        ts: Long = now
    ) = TwinStep(
        pkg = "com.example",
        spineBefore = sigsBefore.hashCode().toLong(),
        spineAfter = sigsAfter.hashCode().toLong(),
        action = action,
        target = "目標",
        posture = posture,
        ok = ok,
        ms = 320,
        ts = ts,
        sigsBefore = sigsBefore,
        sigsAfter = sigsAfter,
        viewId = viewId,
        className = "android.widget.Button",
        roleTag = "button"
    )

    private val pageA = (1L..12L).toList()
    private val pageB = (1L..10L).toList() + listOf(30L, 31L)
    private val pageC = (40L..52L).toList()

    // ── MinHash ───────────────────────────────────────────────────────

    @Test
    fun `minhash is deterministic`() {
        val a = MinHash.fingerprint(pageA)
        val b = MinHash.fingerprint(pageA)
        assertTrue(a.contentEquals(b))
        assertEquals(MinHash.LANES, a.size)
    }

    @Test
    fun `minhash similarity reflects overlap`() {
        assertEquals(1f, MinHash.similarity(MinHash.fingerprint(pageA), MinHash.fingerprint(pageA)), 0.001f)
        val overlap = MinHash.similarity(MinHash.fingerprint(pageA), MinHash.fingerprint(pageB))
        val disjoint = MinHash.similarity(MinHash.fingerprint(pageA), MinHash.fingerprint(pageC))
        assertTrue("重疊頁面應較相似（overlap=$overlap）", overlap > disjoint)
        assertTrue("完全不同頁面應低於門檻", disjoint < 0.6f)
    }

    @Test
    fun `empty signature is handled`() {
        val fp = MinHash.fingerprint(emptyList())
        assertEquals(MinHash.LANES, fp.size)
        assertEquals(0f, MinHash.similarity(fp, MinHash.fingerprint(pageA)), 0.001f)
    }

    // ── 編譯器 ────────────────────────────────────────────────────────

    @Test
    fun `compile two steps builds states and edges`() {
        val steps = listOf(
            step(pageA, pageB, viewId = "com.example:id/search"),
            step(pageB, pageC, viewId = "com.example:id/row")
        )
        val a = AutomatonCompiler.compile("com.example", steps, null, false, now)
        assertEquals(3, a.stateCount())
        assertEquals(2, a.edgeCount())
        assertEquals("SIMPLE", a.tier)
        assertTrue(a.edges.all { it.ok == 1 && it.fail == 0 })
    }

    @Test
    fun `compilation is reproducible`() {
        val steps = listOf(
            step(pageA, pageB, viewId = "com.example:id/search"),
            step(pageB, pageC, viewId = "com.example:id/row", ok = false)
        )
        val a1 = AutomatonCompiler.compile("com.example", steps, null, false, now)
        val a2 = AutomatonCompiler.compile("com.example", steps, null, false, now)
        assertEquals("同一份軌跡必須得到同一張圖", a1.sha256(), a2.sha256())
    }

    @Test
    fun `same edge accumulates beta statistics`() {
        val steps = listOf(
            step(pageA, pageB),
            step(pageA, pageB),
            step(pageA, pageB, ok = false)
        )
        val a = AutomatonCompiler.compile("com.example", steps, null, false, now)
        assertEquals(1, a.edgeCount())
        val e = a.edges.first()
        assertEquals(2, e.ok)
        assertEquals(1, e.fail)
        assertTrue("成功率應在 0.5~0.8 之間（Beta 平滑）", e.successRate in 0.5f..0.8f)
    }

    @Test
    fun `incremental compile keeps old edges`() {
        val base = AutomatonCompiler.compile(
            "com.example", listOf(step(pageA, pageB)), null, false, now
        )
        val grown = AutomatonCompiler.compile(
            "com.example", listOf(step(pageB, pageC)), base, false, now
        )
        assertEquals("舊邊不能消失", 2, grown.edgeCount())
        assertEquals(3, grown.stateCount())
    }

    @Test
    fun `state matching uses fingerprint`() {
        val a = AutomatonCompiler.compile("com.example", listOf(step(pageA, pageB)), null, false, now)
        val hit = a.matchState(MinHash.fingerprint(pageA))
        assertNotNull("同一頁面應能匹配", hit)
        assertNull("陌生頁面不應匹配", a.matchState(MinHash.fingerprint(pageC)))
    }

    @Test
    fun `json round trip preserves graph and sha`() {
        val a = AutomatonCompiler.compile(
            "com.example",
            listOf(step(pageA, pageB), step(pageB, pageC, ok = false)),
            null,
            false,
            now
        )
        val restored = Automaton.fromJson(a.toJson())
        assertNotNull(restored)
        assertEquals(a.stateCount(), restored!!.stateCount())
        assertEquals(a.edgeCount(), restored.edgeCount())
        assertEquals("序列化前後 sha 必須一致", a.sha256(), restored.sha256())
    }

    // ── 自適應預算 ────────────────────────────────────────────────────

    @Test
    fun `tier classification follows the policy table`() {
        assertEquals("SIMPLE", GraphBudget.classify(GraphComplexity(0, 0, 0)).name)
        assertEquals("STANDARD", GraphBudget.classify(GraphComplexity(30, 100, 3)).name)
        assertEquals("COMPLEX", GraphBudget.classify(GraphComplexity(120, 500, 7)).name)
        assertEquals("HUGE", GraphBudget.classify(GraphComplexity(400, 1600, 12)).name)
        assertEquals("COMPLEX", GraphBudget.classify(GraphComplexity(400, 1600, 12), lowEndDevice = true).name)
        assertEquals("SIMPLE", GraphBudget.classify(GraphComplexity(30, 100, 3), lowEndDevice = true).name)
    }

    @Test
    fun `budgets grow with complexity`() {
        val simple = GraphBudget.classify(GraphComplexity(0, 0, 0))
        val huge = GraphBudget.classify(GraphComplexity(400, 1600, 12))
        assertTrue(simple.nodeBudget < huge.nodeBudget)
        assertTrue(simple.edgeBudget < huge.edgeBudget)
        assertEquals(32, simple.nodeBudget)
        assertEquals(256, huge.nodeBudget)
    }

    @Test
    fun `upgrade requires three consecutive hits`() {
        val c = GraphComplexity(30, 100, 3) // STANDARD 門檻
        val d1 = GraphBudget.decide(c, "SIMPLE", 0)
        assertEquals("SIMPLE", d1.tier)
        assertEquals(1, d1.streak)

        val d2 = GraphBudget.decide(c, "SIMPLE", 1)
        assertEquals("SIMPLE", d2.tier)
        assertEquals(2, d2.streak)

        val d3 = GraphBudget.decide(c, "SIMPLE", 2)
        assertEquals("STANDARD", d3.tier)
        assertTrue(d3.upgraded)
        assertEquals(0, d3.streak)
    }

    @Test
    fun `low end device never exceeds its budget`() {
        val d = GraphBudget.decide(GraphComplexity(400, 1600, 12), "COMPLEX", 0, lowEndDevice = true)
        assertTrue("低階機不得升到 HUGE", d.nodeBudget <= 128)
    }

    @Test
    fun `compiler trims to node budget`() {
        // 造出 40 個互不相關的頁面（SIMPLE 預算 32）
        val steps = (0 until 40).map { i ->
            val before = (i * 10L until i * 10L + 8).toList()
            val after = (i * 10L + 100 until i * 10L + 108).toList()
            step(before, after, viewId = "com.example:id/b$i")
        }
        val a = AutomatonCompiler.compile("com.example", steps, null, false, now)
        assertTrue("節點數需受預算限制，實際=${a.stateCount()}", a.stateCount() <= 32)
    }
}
