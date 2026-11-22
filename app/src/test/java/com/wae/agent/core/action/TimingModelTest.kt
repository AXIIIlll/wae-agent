package com.wae.agent.core.action

import com.wae.agent.core.twin.TwinStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P4 學習式等待測試：百分位、樣本門檻、夾制區間、Twin 播種。
 */
class TimingModelTest {

    private fun step(
        pkg: String = "com.demo.mail",
        action: String = "TAP",
        viewId: String = "com.demo.mail:id/row",
        ms: Long = 900,
        ok: Boolean = true
    ) = TwinStep(
        pkg = pkg, spineBefore = 1, spineAfter = 2, action = action,
        target = "x", posture = "", ok = ok, ms = ms, ts = 0,
        viewId = viewId
    )

    @Before
    fun reset() {
        TimingModel.reset()
    }

    @Test
    fun `percentile is interpolated`() {
        assertEquals(0L, TimingModel.percentile(emptyList(), 0.95))
        assertEquals(5L, TimingModel.percentile(listOf(5L), 0.95))
        // [10,20,30,40] p50 = 25
        assertEquals(25L, TimingModel.percentile(listOf(10L, 20L, 30L, 40L), 0.5))
        // p95 接近最大值
        assertTrue(TimingModel.percentile((1L..100L).toList(), 0.95) >= 95L)
    }

    @Test
    fun `insufficient samples fall back to default`() {
        TimingModel.record(TimingModel.keyOf("com.a", "id:x", "TAP"), 800)
        assertEquals(
            TimingModel.DEFAULT_WAIT_MS,
            TimingModel.waitFor("com.a", "id:x", "TAP")
        )
    }

    @Test
    fun `enough samples produce learned cap`() {
        val pkg = "com.slow.app"
        val key = TimingModel.keyOf(pkg, "id:btn", "TAP")
        repeat(10) { TimingModel.record(key, 1_500) }
        val cap = TimingModel.waitFor(pkg, "id:btn", "TAP")
        assertEquals(1_500L, cap)
        assertTrue(cap <= TimingModel.MAX_WAIT_MS)
    }

    @Test
    fun `learned cap is clamped`() {
        val pkg = "com.very.slow"
        val key = TimingModel.keyOf(pkg, "id:x", "TAP")
        repeat(5) { TimingModel.record(key, 9_000) }
        assertEquals(TimingModel.MAX_WAIT_MS, TimingModel.waitFor(pkg, "id:x", "TAP"))

        val fastPkg = "com.fast"
        val fastKey = TimingModel.keyOf(fastPkg, "id:y", "TAP")
        repeat(5) { TimingModel.record(fastKey, 50) }
        assertEquals(TimingModel.MIN_WAIT_MS, TimingModel.waitFor(fastPkg, "id:y", "TAP"))
    }

    @Test
    fun `seed from twin history builds samples`() {
        TimingModel.seedFrom(listOf(step(ms = 700), step(ms = 800), step(ms = 900)))
        assertEquals(3, TimingModel.sampleCount("com.demo.mail", "id:row", "TAP"))
        val cap = TimingModel.waitFor("com.demo.mail", "id:row", "TAP")
        assertTrue("播種後應得到學習值，實際=$cap", cap in TimingModel.MIN_WAIT_MS..TimingModel.MAX_WAIT_MS)
    }

    @Test
    fun `failed steps are not seeded`() {
        TimingModel.seedFrom(listOf(step(ok = false), step(ok = false)))
        assertEquals(0, TimingModel.sampleCount("com.demo.mail", "id:row", "TAP"))
    }
}
