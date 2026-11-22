package com.wae.agent.core.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5a 序列重放測試：只挑「同 App + 前置相似 + 連續相連 + 近期成功」的鏈。
 */
class TwinReplayPlanTest {

    private val now = 1_700_000_000_000L

    private fun step(
        before: Long,
        after: Long,
        pkg: String = "com.demo.mail",
        action: String = "TAP",
        ok: Boolean = true,
        ts: Long = now - 1000,
        sigsBefore: List<Long> = (1L..10L).toList()
    ) = TwinStep(
        pkg = pkg, spineBefore = before, spineAfter = after, action = action,
        target = "x", posture = "", ok = ok, ms = 500, ts = ts,
        sigsBefore = sigsBefore, sigsAfter = (20L..30L).toList(),
        primitive = "INTERACT"
    )

    @Test
    fun `finds contiguous chain from matching start`() {
        val history = listOf(
            step(1, 2, action = "TYPE"),
            step(2, 3, action = "TAP"),
            step(3, 4, action = "TAP")
        )
        val plan = TwinReplayPlan.find(
            pkg = "com.demo.mail", action = "TYPE",
            sigsStart = (1L..10L).toList(), history = history, nowMs = now
        )
        assertNotNull("應找到鏈", plan)
        assertEquals(3, plan!!.steps.size)
        assertEquals(1L, plan.steps.first().spineBefore)
        assertEquals(4L, plan.steps.last().spineAfter)
    }

    @Test
    fun `breaks at discontinuity`() {
        val history = listOf(
            step(1, 2, action = "TYPE"),
            step(9, 10, action = "TAP") // 不連續
        )
        val plan = TwinReplayPlan.find(
            pkg = "com.demo.mail", action = "TYPE",
            sigsStart = (1L..10L).toList(), history = history, nowMs = now
        )
        assertNull("斷點後不足 minSteps，不應回傳計畫", plan)
    }

    @Test
    fun `rejects other package`() {
        val history = listOf(step(1, 2, pkg = "com.other"), step(2, 3, pkg = "com.other"))
        assertNull(
            TwinReplayPlan.find(
                pkg = "com.demo.mail", action = "TAP",
                sigsStart = (1L..10L).toList(), history = history, nowMs = now
            )
        )
    }

    @Test
    fun `rejects stale chain`() {
        val old = now - 8L * 24 * 60 * 60 * 1000
        val history = listOf(step(1, 2, ts = old), step(2, 3, ts = old))
        assertNull(
            TwinReplayPlan.find(
                pkg = "com.demo.mail", action = "TAP",
                sigsStart = (1L..10L).toList(), history = history, nowMs = now
            )
        )
    }

    @Test
    fun `caps chain length`() {
        val history = (1..12).map { step(it.toLong(), (it + 1).toLong()) }
        val plan = TwinReplayPlan.find(
            pkg = "com.demo.mail", action = "TAP",
            sigsStart = (1L..10L).toList(), history = history, nowMs = now, maxSteps = 5
        )
        assertNotNull(plan)
        assertEquals(5, plan!!.steps.size)
    }

    @Test
    fun `requires minimum length`() {
        val history = listOf(step(1, 2))
        assertNull(
            TwinReplayPlan.find(
                pkg = "com.demo.mail", action = "TAP",
                sigsStart = (1L..10L).toList(), history = history, nowMs = now, minSteps = 2
            )
        )
        assertNotNull(
            TwinReplayPlan.find(
                pkg = "com.demo.mail", action = "TAP",
                sigsStart = (1L..10L).toList(), history = history, nowMs = now, minSteps = 1
            )
        )
    }

    @Test
    fun `skips failed steps`() {
        val history = listOf(step(1, 2, ok = false), step(2, 3, ok = false))
        assertNull(
            TwinReplayPlan.find(
                pkg = "com.demo.mail", action = "TAP",
                sigsStart = (1L..10L).toList(), history = history, nowMs = now, minSteps = 1
            )
        )
    }

    @Test
    fun `summary is compact`() {
        val plan = TwinReplayPlan.Plan(listOf(step(1, 2), step(2, 3)), 0.92f, 0)
        assertTrue(plan.summary().contains("steps=2"))
    }
}
