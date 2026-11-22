package com.wae.agent.core.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S25 測試：增量感知的判定、髒區幾何、候選合併。
 *
 * 這些測試的價值在於：增量感知一旦判斷錯，會「看不到應該看到的元素」，
 * 屬於最危險的一類優化，因此邊界條件必須全部固定下來。
 */
class IncrementalPerceptionTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = IncrementalPerception.Box(l, t, r, b)

    // ── 幾何 ─────────────────────────────────────────────────────────

    @Test
    fun `intersection is correct at edges`() {
        val a = box(0, 0, 100, 100)
        assertTrue(IncrementalPerception.intersects(a, box(50, 50, 150, 150)))
        assertTrue(IncrementalPerception.intersects(a, box(99, 99, 200, 200)))
        assertFalse("只在邊上相接不算交集", IncrementalPerception.intersects(a, box(100, 0, 200, 100)))
        assertFalse(IncrementalPerception.intersects(a, box(200, 200, 300, 300)))
        assertFalse("空框不算交集", IncrementalPerception.intersects(a, box(50, 50, 50, 60)))
    }

    @Test
    fun `dirty area ratio uses a conservative bounding box`() {
        val ratio = IncrementalPerception.dirtyAreaRatio(
            listOf(box(0, 0, 100, 100), box(900, 1800, 1000, 1900)), 1000, 2000
        )
        // 外接框會覆蓋兩個角落之間的全部區域 → 保守高估
        assertTrue("應高估而非低估（實際=$ratio）", ratio >= 0.1f)
        assertEquals(1f, IncrementalPerception.dirtyAreaRatio(emptyList(), 1000, 2000), 0.001f)
    }

    // ── 判定 ─────────────────────────────────────────────────────────

    @Test
    fun `incremental requires same page and small dirty area`() {
        val base = IncrementalPerception.canGoIncremental(
            samePackage = true, sameWindow = true, sameAmbient = true,
            dirtyCount = 1, dirtyAreaRatio = 0.1f, msSinceFull = 300
        )
        assertTrue(base)
        assertFalse(
            "不同頁面不可增量",
            IncrementalPerception.canGoIncremental(true, true, false, 1, 0.1f, 300)
        )
        assertFalse(
            "髒區太大就全量",
            IncrementalPerception.canGoIncremental(true, true, true, 1, 0.9f, 300)
        )
        assertFalse(
            "沒有髒區（缺事件）就全量",
            IncrementalPerception.canGoIncremental(true, true, true, 0, 0f, 300)
        )
        assertFalse(
            "髒區太多就全量",
            IncrementalPerception.canGoIncremental(
                true, true, true, IncrementalPerception.MAX_DIRTY_RECTS + 1, 0.1f, 300
            )
        )
        assertFalse(
            "距上次全量太久就全量（安全網）",
            IncrementalPerception.canGoIncremental(
                true, true, true, 1, 0.1f, IncrementalPerception.MAX_MS_SINCE_FULL + 1
            )
        )
    }

    // ── 合併 ─────────────────────────────────────────────────────────

    private data class C(val key: Long, val box: IncrementalPerception.Box)

    private fun merge(
        kept: List<C>,
        fresh: List<C>,
        dirty: List<IncrementalPerception.Box>
    ): List<C> = IncrementalPerception.merge(
        kept = kept,
        fresh = fresh,
        keyOf = { it.key },
        boundsOf = { it.box },
        dirty = dirty
    )

    @Test
    fun `kept candidates outside the dirty area survive`() {
        val kept = listOf(
            C(1, box(0, 0, 100, 100)),      // 髒區外
            C(2, box(500, 500, 600, 600))   // 髒區外
        )
        val fresh = listOf(C(3, box(50, 50, 150, 150)))
        val out = merge(kept, fresh, listOf(box(0, 0, 120, 120)))
        assertEquals(2, out.size)
        assertTrue(out.any { it.key == 3L })
        assertTrue("髒區外的舊候選要保留", out.any { it.key == 2L })
        assertFalse("被髒區覆蓋的舊候選要丟棄", out.any { it.key == 1L })
    }

    @Test
    fun `fresh wins over duplicate key`() {
        val kept = listOf(C(1, box(0, 0, 100, 100)))
        val fresh = listOf(C(1, box(0, 0, 110, 110)))
        val out = merge(kept, fresh, listOf(box(0, 0, 50, 50)))
        assertEquals(1, out.size)
        assertEquals("重複時以新鮮資料為準", 110, out[0].box.r)
    }

    @Test
    fun `merge keeps deterministic order`() {
        val kept = listOf(C(1, box(0, 0, 10, 10)), C(2, box(20, 20, 30, 30)))
        val fresh = listOf(C(3, box(40, 40, 50, 50)))
        val a = merge(kept, fresh, emptyList())
        val b = merge(kept, fresh, emptyList())
        assertEquals("同一輸入必須得到同一順序", a.map { it.key }, b.map { it.key })
        assertEquals(listOf(3L, 1L, 2L), a.map { it.key })
    }
}
