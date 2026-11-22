package com.wae.agent.core.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5b：Twin 圖建構 + MCTS 規劃橋接測試。 */
class TwinPlannerBridgeTest {

    private fun step(
        before: Long,
        after: Long,
        action: String = "TAP",
        target: String = "x",
        viewId: String = "com.a:id/x",
        ok: Boolean = true
    ) = TwinStep(
        pkg = "com.a", spineBefore = before, spineAfter = after, action = action,
        target = target, posture = "", ok = ok, ms = 400, ts = 0,
        viewId = viewId
    )

    @Test
    fun `graph is built from successful steps only`() {
        val g = TwinPlannerBridge.buildGraph(
            listOf(step(1, 2), step(2, 3, ok = false), step(3, 4))
        )
        assertEquals(2, g.nodeCount())
        assertEquals(2, g.edgeCount())
    }

    @Test
    fun `plans a path to a goal action`() {
        val history = listOf(step(1, 2), step(2, 3, action = "TYPE", viewId = "com.a:id/input"))
        val plan = TwinPlannerBridge.plan(history, currentSpine = 1L, goalAction = "TYPE")
        assertNotNull("1→2→3 應可規劃到 TYPE 的結果狀態", plan)
        assertTrue(plan!!.expectedSuccess > 0.0)
    }

    @Test
    fun `no plan when current spine is unknown`() {
        val history = listOf(step(1, 2, action = "TYPE"))
        assertNull(TwinPlannerBridge.plan(history, currentSpine = 99L, goalAction = "TYPE"))
    }

    @Test
    fun `no plan when goal action absent`() {
        val history = listOf(step(1, 2))
        assertNull(TwinPlannerBridge.plan(history, currentSpine = 1L, goalAction = "TYPE"))
    }

    @Test
    fun `step lookup finds the planned edge source`() {
        val history = listOf(step(1, 2, action = "TYPE", viewId = "com.a:id/input"))
        val plan = TwinPlannerBridge.plan(history, currentSpine = 1L, goalAction = "TYPE")!!
        val s = TwinPlannerBridge.stepFor(history, plan)
        assertNotNull(s)
        assertEquals("id:input", TwinPlannerBridge.selectorKeyOf(s!!))
    }
}