package com.wae.core

import com.wae.core.config.MapConfig
import com.wae.core.effect.Planner
import com.wae.core.means.MeansSolver
import com.wae.core.model.Constraints
import com.wae.core.model.Effect
import com.wae.core.model.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D10：系統設定（亮度／面板）必須能變成計畫，不能掉到「找不到」分支。 */
class PlannerSystemTargetsTest {

    private val planner = Planner(MeansSolver(MapConfig(emptyMap())))
    private val frame = TestFrames.toggleFrame()

    @Test
    fun `brightness produces a system api step`() {
        val effect = Effect("brightness:screen", Op.SET, "up")
        val plan = planner.plan(listOf(effect), frame, emptyList(), Constraints.NONE)
        assertEquals(1, plan.steps.size)
        assertEquals(com.wae.core.model.Means.Kind.SYSTEM_API, plan.steps[0].means.kind)
        assertTrue(plan.steps[0].means.describe().contains("brightness"))
    }

    @Test
    fun `wifi panel produces a system api step`() {
        val effect = Effect("panel:wifi", Op.NAVIGATE, "on")
        val plan = planner.plan(listOf(effect), frame, emptyList(), Constraints.NONE)
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps[0].means.describe().contains("panel:wifi"))
    }
}
