package com.wae.core

import com.wae.core.component.ValueAdjustDecider
import com.wae.core.component.ValueAdjustDecider.Decision
import com.wae.core.component.ValueAdjustDecider.Range
import com.wae.core.component.ValueAdjustDecider.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 數值調整的決策表（抽 `performAdjustValue` 之前先釘住行為）。
 *
 * 每一條都是真機踩過的坑：0→0 假成功、盲點擊、唯讀元件、導覽步被誤報完成。
 */
class ValueAdjustDeciderTest {

    private val confident = Target(hasOwnSemanticEvidence = true, hybridSimScore = 1f, readOnlyOnly = false)
    private val vague = Target(hasOwnSemanticEvidence = false, hybridSimScore = 0.2f, readOnlyOnly = false)
    private val range = Range(min = 0f, max = 100f, current = 10f)

    @Test
    fun `no live node only taps and is not done`() {
        val d = ValueAdjustDecider.decide(false, range, confident, intentRequested = true)
        assertEquals(Decision.TapOnlyNoNode, d)
    }

    @Test
    fun `no range and no semantic evidence refuses blind click`() {
        val d = ValueAdjustDecider.decide(true, null, vague, intentRequested = true)
        assertEquals(Decision.RefuseBlindClick, d)
    }

    @Test
    fun `no range but confident target opens deeper`() {
        val d = ValueAdjustDecider.decide(true, null, confident, intentRequested = true)
        assertEquals(Decision.OpenDeeper, d)
    }

    @Test
    fun `zero width range is refused`() {
        val flat = Range(min = 50f, max = 50f, current = 50f)
        val d = ValueAdjustDecider.decide(true, flat, confident, intentRequested = true)
        assertEquals(Decision.RefuseInvalidRange, d)
    }

    @Test
    fun `read only widget is refused`() {
        val ro = confident.copy(readOnlyOnly = true)
        val d = ValueAdjustDecider.decide(true, range, ro, intentRequested = true)
        assertEquals(Decision.RefuseReadOnly, d)
    }

    @Test
    fun `no explicit request means navigate not apply`() {
        val d = ValueAdjustDecider.decide(true, range, confident, intentRequested = false)
        assertEquals(Decision.OpenDeeper, d)
    }

    @Test
    fun `valid case applies with desired value`() {
        val d = ValueAdjustDecider.decide(
            true, range, confident, intentRequested = true, desiredValue = 80f, intentMatched = "調大聲"
        )
        assertTrue(d is Decision.Apply)
        d as Decision.Apply
        assertEquals(80f, d.desired, 0.001f)
        assertEquals("調大聲", d.intentMatched)
        assertEquals(100f, d.range.max, 0.001f)
    }
}
