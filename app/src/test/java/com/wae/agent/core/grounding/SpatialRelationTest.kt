package com.wae.agent.core.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpatialRelation] 純幾何測試（G1 / I2+I3 的共同底層）。
 *
 * 覆蓋重點：
 *  1. 距離/方向/對齊/重疊的基本定義（含退化輸入：空 bounds、零面積、反向座標）
 *  2. `sameRow` 的兩種判準（垂直中心差、垂直重疊比例）與行高自動推估
 *  3. `nearestClickable` 的排序/tie-break/過濾（disabled、無效幾何）
 *  4. `rowOwner` 的典型三種佈局（整列可點、右側開關、整列不可點）
 *
 * 全部是純算術，不需要 Robolectric/Android runtime。
 */
class SpatialRelationTest {

    private fun rect(l: Int, t: Int, r: Int, b: Int) = groundingRect(l, t, r, b)

    private fun cand(
        id: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        clickable: Boolean = true,
        text: String = "",
        enabled: Boolean = true,
        score: Float = 0.5f,
        channel: GroundingChannel = GroundingChannel.A11Y
    ) = GroundingCandidate(
        id = id,
        channel = channel,
        text = text,
        bounds = rect(l, t, r, b),
        clickable = clickable,
        enabled = enabled,
        score = score
    )

    // ── 有效性與基本量測 ──────────────────────────────────────────

    @Test
    fun invalidRectsAreRejectedWithoutThrowing() {
        assertFalse(SpatialRelation.isValid(null as IntArray?))
        assertFalse(SpatialRelation.isValid(IntArray(0)))
        assertFalse(SpatialRelation.isValid(intArrayOf(0, 0, 0, 0)))
        assertFalse(SpatialRelation.isValid(intArrayOf(100, 100, 50, 200))) // right < left
        assertFalse(SpatialRelation.isValid(intArrayOf(100, 100, 200))) // 只有 3 個值
        assertTrue(SpatialRelation.isValid(rect(10, 20, 110, 80)))
    }

    @Test
    fun basicMetricsAreZeroForInvalidInput() {
        assertEquals(0, SpatialRelation.width(null))
        assertEquals(0, SpatialRelation.height(IntArray(0)))
        assertEquals(0L, SpatialRelation.area(intArrayOf(0, 0, 0, 0)))
        assertEquals(0, SpatialRelation.centerX(rect(100, 100, 50, 200)))
        assertFalse(SpatialRelation.isValid(GroundingCandidate("x", GroundingChannel.OCR)))
    }

    @Test
    fun metricsMatchRectDefinition() {
        val r = rect(100, 200, 300, 260)
        assertEquals(200, SpatialRelation.width(r))
        assertEquals(60, SpatialRelation.height(r))
        assertEquals(12_000L, SpatialRelation.area(r))
        assertEquals(200, SpatialRelation.centerX(r))
        assertEquals(230, SpatialRelation.centerY(r))
    }

    // ── 距離 ──────────────────────────────────────────────────────

    @Test
    fun edgeDistanceIsZeroWhenTouchingOrOverlapping() {
        val a = rect(0, 0, 100, 100)
        assertEquals(0f, SpatialRelation.edgeDistance(a, rect(50, 50, 150, 150)), 0.0001f)
        assertEquals(0f, SpatialRelation.edgeDistance(a, rect(100, 0, 200, 100)), 0.0001f) // 邊緣相接
    }

    @Test
    fun edgeDistanceMeasuresGapNotCenters() {
        val a = rect(0, 0, 100, 100)
        // 水平距離 10、垂直重疊 → 邊距 10（大面積列狀元件不會因中心差被高估）
        assertEquals(10f, SpatialRelation.edgeDistance(a, rect(110, 0, 500, 100)), 0.0001f)
        // 右下方斜向：dx=40, dy=30 → 50
        assertEquals(50f, SpatialRelation.edgeDistance(a, rect(140, 130, 300, 300)), 0.0001f)
    }

    @Test
    fun distancesAreInfiniteForInvalidGeometry() {
        assertTrue(SpatialRelation.edgeDistance(null, rect(0, 0, 10, 10)).isInfinite())
        assertTrue(SpatialRelation.centerDistance(IntArray(0), rect(0, 0, 10, 10)).isInfinite())
    }

    @Test
    fun centerDistanceUsesCenters() {
        assertEquals(0f, SpatialRelation.centerDistance(rect(0, 0, 100, 100), rect(0, 0, 100, 100)), 0.0001f)
        assertEquals(100f, SpatialRelation.centerDistance(rect(0, 0, 100, 100), rect(100, 0, 200, 100)), 0.0001f)
    }

    // ── 重疊 / 包含 ───────────────────────────────────────────────

    @Test
    fun intersectionAndIou() {
        val a = rect(0, 0, 100, 100)
        val b = rect(50, 50, 150, 150)
        assertEquals(2_500L, SpatialRelation.intersectionArea(a, b))
        assertEquals(0.142857f, SpatialRelation.iou(a, b), 0.001f)
        assertEquals(0L, SpatialRelation.intersectionArea(a, rect(200, 200, 300, 300)))
        assertEquals(0f, SpatialRelation.iou(a, rect(200, 200, 300, 300)), 0.0001f)
    }

    @Test
    fun overlapRatiosUseSmallerSide() {
        val row = rect(0, 100, 1000, 160)
        val switchRect = rect(900, 95, 1040, 165) // 與列幾乎同高
        assertEquals(1f, SpatialRelation.verticalOverlapRatio(row, switchRect), 0.0001f)
        assertEquals(0f, SpatialRelation.verticalOverlapRatio(row, rect(0, 500, 1000, 560)), 0.0001f)
        assertEquals(
            0.1f,
            SpatialRelation.horizontalOverlapRatio(rect(0, 0, 100, 100), rect(90, 0, 200, 100)),
            0.0001f
        )
    }

    @Test
    fun containsHonoursTolerance() {
        val outer = rect(100, 100, 500, 200)
        assertTrue(SpatialRelation.contains(outer, rect(120, 120, 300, 180)))
        assertFalse(SpatialRelation.contains(outer, rect(90, 100, 300, 180)))
        assertTrue(SpatialRelation.contains(outer, rect(90, 100, 300, 180), tolerancePx = 12))
        assertFalse(SpatialRelation.contains(null, rect(0, 0, 10, 10)))
    }

    // ── 方向 / 對齊 / 同列 ────────────────────────────────────────

    @Test
    fun directionOfNeighbouringRects() {
        val center = rect(400, 400, 600, 600)
        assertEquals(
            SpatialRelation.Direction.RIGHT,
            SpatialRelation.direction(center, rect(700, 400, 800, 600))
        )
        assertEquals(
            SpatialRelation.Direction.LEFT,
            SpatialRelation.direction(center, rect(100, 400, 200, 600))
        )
        assertEquals(
            SpatialRelation.Direction.ABOVE,
            SpatialRelation.direction(center, rect(400, 100, 600, 300))
        )
        assertEquals(
            SpatialRelation.Direction.BELOW,
            SpatialRelation.direction(center, rect(400, 700, 600, 900))
        )
        assertEquals(SpatialRelation.Direction.OVERLAP, SpatialRelation.direction(center, center))
        assertEquals(SpatialRelation.Direction.UNKNOWN, SpatialRelation.direction(null, center))
    }

    @Test
    fun alignmentChecks() {
        val a = rect(100, 100, 400, 160)
        assertTrue(SpatialRelation.isTopAligned(a, rect(120, 104, 500, 164)))
        assertFalse(SpatialRelation.isTopAligned(a, rect(120, 300, 500, 360)))
        assertTrue(SpatialRelation.isLeftAligned(a, rect(104, 300, 400, 360)))
        assertTrue(SpatialRelation.isRightAligned(a, rect(0, 300, 396, 360)))
        assertFalse(SpatialRelation.isRightAligned(a, null))
    }

    @Test
    fun sameRowUsesCenterToleranceOrOverlapRatio() {
        val label = rect(40, 100, 500, 140)
        // 中心差 10 <= 24 → 同列
        assertTrue(SpatialRelation.sameRow(label, rect(900, 95, 1040, 145)))
        // 中心差大，但垂直重疊比例 >= 0.5 → 同列（小字 + 高輸入框）
        assertTrue(SpatialRelation.sameRow(rect(40, 120, 200, 140), rect(40, 80, 400, 200)))
        // 完全不同的列 → 不同列
        assertFalse(SpatialRelation.sameRow(label, rect(40, 400, 500, 440)))
        assertFalse(SpatialRelation.sameRow(null, rect(0, 0, 10, 10)))
    }

    @Test
    fun medianRowHeightAndToleranceAreBounded() {
        val nodes = listOf(
            cand("a", 0, 0, 100, 50),
            cand("b", 0, 100, 100, 160),
            cand("c", 0, 200, 100, 260)
        )
        assertEquals(60, SpatialRelation.medianRowHeight(nodes))
        assertTrue(SpatialRelation.rowTolerance(nodes) >= SpatialRelation.DEFAULT_ROW_TOLERANCE_PX)
        assertEquals(
            SpatialRelation.DEFAULT_ROW_TOLERANCE_PX,
            SpatialRelation.rowTolerance(emptyList())
        )
        // 巨大圖卡不會把容差拉到超過上限
        val giant = listOf(cand("img", 0, 0, 1000, 2000))
        assertTrue(SpatialRelation.rowTolerance(giant) <= SpatialRelation.MAX_ROW_TOLERANCE_PX)
    }

    @Test
    fun rowsGroupNodesByVerticalPosition() {
        val nodes = listOf(
            cand("a1", 40, 100, 200, 140, clickable = false, text = "主旨"),
            cand("a2", 900, 95, 1040, 145, text = "開關"),
            cand("b1", 40, 300, 200, 340, clickable = false, text = "第二列")
        )
        val rows = SpatialRelation.rows(nodes)
        assertEquals(2, rows.size)
        assertEquals(listOf("a1", "a2"), rows[0].map { it.id })
        assertEquals(listOf("b1"), rows[1].map { it.id })
    }

    // ── nearestClickable ──────────────────────────────────────────

    @Test
    fun nearestClickableOrdersByDistance() {
        val textRect = rect(100, 100, 400, 140)
        val nodes = listOf(
            cand("far", 900, 900, 960, 960),
            cand("near", 420, 100, 500, 140),
            cand("toolbar", 100, 10, 160, 70)
        )
        val result = SpatialRelation.nearestClickable(textRect, nodes)
        assertEquals(listOf("near", "toolbar", "far"), result.map { it.id })
    }

    @Test
    fun nearestClickableFiltersDisabledAndInvalidGeometry() {
        val textRect = rect(100, 100, 400, 140)
        val nodes = listOf(
            cand("disabled", 420, 100, 500, 140, enabled = false),
            cand("notClickable", 430, 100, 510, 140, clickable = false),
            cand("noBounds", 0, 0, 0, 0),
            cand("ok", 420, 100, 500, 140)
        )
        val result = SpatialRelation.nearestClickable(textRect, nodes)
        assertEquals(listOf("ok"), result.map { it.id })
    }

    @Test
    fun nearestClickableHonoursLimitAndInvalidTextRect() {
        val textRect = rect(100, 100, 400, 140)
        val nodes = (1..5).map { cand("n$it", 400 + it * 100, 100, 460 + it * 100, 140) }
        assertEquals(2, SpatialRelation.nearestClickable(textRect, nodes, limit = 2).size)
        assertEquals(0, SpatialRelation.nearestClickable(textRect, nodes, limit = 0).size)
        // 無效 textRect → 退化為按分數排序（不猜距離），仍受 limit 限制
        val byScore = listOf(
            cand("low", 0, 0, 100, 100, score = 0.1f),
            cand("high", 500, 500, 600, 600, score = 0.9f)
        )
        assertEquals(listOf("high", "low"), SpatialRelation.nearestClickable(null, byScore).map { it.id })
    }

    // ── rowOwner（純幾何） ────────────────────────────────────────

    @Test
    fun rowOwnerPrefersWholeClickableRow() {
        val textRect = rect(200, 520, 600, 560)
        val nodes = listOf(
            cand("row", 40, 500, 1040, 580, text = ""),
            cand("chevron", 980, 515, 1020, 565)
        )
        assertEquals("row", SpatialRelation.rowOwner(textRect, nodes, 1080)?.id)
    }

    @Test
    fun rowOwnerPicksSideControlWhenRowNotClickable() {
        val textRect = rect(100, 200, 500, 240)
        val nodes = listOf(
            cand("label", 100, 200, 500, 240, clickable = false, text = "Wi-Fi"),
            cand("switch", 900, 195, 1040, 245),
            cand("otherRow", 900, 800, 1040, 850)
        )
        assertEquals("switch", SpatialRelation.rowOwner(textRect, nodes, 1080)?.id)
    }

    @Test
    fun rowOwnerReturnsNullWithoutSameRowClickable() {
        val textRect = rect(100, 200, 500, 240)
        val nodes = listOf(cand("far", 900, 800, 1040, 850))
        assertNull(SpatialRelation.rowOwner(textRect, nodes, 1080))
        assertNull(SpatialRelation.rowOwner(null, nodes, 1080))
    }
}
