package com.wae.agent.core.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 精準滾動規劃測試：距離按目標遠近計算、方向正確、掃描模式有上限。
 */
class ScrollPlannerTest {

    private val vpTop = 200
    private val vpBottom = 2200 // 視窗高 2000

    @Test
    fun `visible target needs no scroll`() {
        val plan = ScrollPlanner.plan(
            targetTop = 800, targetBottom = 900,
            viewportTop = vpTop, viewportBottom = vpBottom
        )
        assertTrue(plan.visible)
        assertEquals(ScrollDirection.NONE, plan.direction)
        assertEquals(0, plan.distancePx)
    }

    @Test
    fun `target below viewport scrolls down by distance`() {
        val plan = ScrollPlanner.plan(
            targetTop = 2600, targetBottom = 2700,
            viewportTop = vpTop, viewportBottom = vpBottom
        )
        assertTrue(!plan.visible)
        assertEquals(ScrollDirection.DOWN, plan.direction)
        // 目標底部超出可視底界 → 距離 = 超出量（並受 80% 視窗上限保護）
        assertEquals(2700 - (vpBottom - ScrollPlanner.MARGIN_PX), plan.distancePx)
    }

    @Test
    fun `target above viewport scrolls up`() {
        val plan = ScrollPlanner.plan(
            targetTop = -500, targetBottom = -400,
            viewportTop = vpTop, viewportBottom = vpBottom
        )
        assertEquals(ScrollDirection.UP, plan.direction)
        assertTrue(plan.distancePx > 0)
    }

    @Test
    fun `target partially covered by margin is scrolled`() {
        // 貼邊（小於 MARGIN）不算完全可見，避免點擊偏移
        val plan = ScrollPlanner.plan(
            targetTop = vpBottom - 20, targetBottom = vpBottom + 100,
            viewportTop = vpTop, viewportBottom = vpBottom
        )
        assertTrue(!plan.visible)
        assertEquals(ScrollDirection.DOWN, plan.direction)
    }

    @Test
    fun `distance is capped to avoid overshoot`() {
        val plan = ScrollPlanner.plan(
            targetTop = 20_000, targetBottom = 20_100,
            viewportTop = vpTop, viewportBottom = vpBottom
        )
        assertEquals(ScrollDirection.DOWN, plan.direction)
        assertTrue(
            "單次滾動不得超過視窗的 80%",
            plan.distancePx <= ((vpBottom - vpTop) * 0.8f).toInt()
        )
    }

    @Test
    fun `unknown target enters scan mode`() {
        val plan = ScrollPlanner.plan(0, 0, vpTop, vpBottom, targetKnown = false)
        assertTrue(!plan.visible)
        assertTrue(plan.needsScan)
        assertEquals(ScrollDirection.DOWN, plan.direction)
        assertTrue(plan.distancePx > 0)
    }

    @Test
    fun `scans are bounded`() {
        assertEquals(5, ScrollPlanner.remainingScans(0))
        assertEquals(1, ScrollPlanner.remainingScans(4))
        assertEquals(0, ScrollPlanner.remainingScans(5))
        assertEquals(0, ScrollPlanner.remainingScans(99))
    }

    @Test
    fun `reached bottom requires both signals`() {
        assertTrue(ScrollPlanner.reachedBottom(true, true))
        assertTrue(!ScrollPlanner.reachedBottom(true, false))
        assertTrue(!ScrollPlanner.reachedBottom(false, true))
    }
}
