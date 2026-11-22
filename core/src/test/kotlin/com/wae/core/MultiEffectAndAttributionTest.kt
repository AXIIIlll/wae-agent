package com.wae.core

import com.wae.core.intent.MultiEffectParser
import com.wae.core.learn.Attributor
import com.wae.core.model.Attribution
import com.wae.core.model.Effect
import com.wae.core.model.Means
import com.wae.core.model.Op
import com.wae.core.model.Outcome
import com.wae.core.model.PostCondition
import com.wae.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B7 多效果組合 ＋ 歸因校準。 */
class MultiEffectAndAttributionTest {

    @Test
    fun `composes open-app then search`() {
        val effects = MultiEffectParser.parse("在設定裡搜尋藍牙")
        assertEquals(2, effects?.size)
        assertEquals("app:設定", effects!![0].target)
        assertEquals(Op.NAVIGATE, effects[0].op)
        assertEquals("query:藍牙", effects[1].target)
    }

    @Test
    fun `composes with another verb shape`() {
        val effects = MultiEffectParser.parse("打開地圖然後搜尋台北車站")
        assertEquals("app:地圖", effects?.get(0)?.target)
        assertEquals("query:台北車站", effects?.get(1)?.target)
    }

    @Test
    fun `does not fire on a single effect sentence`() {
        assertNull(MultiEffectParser.parse("打開設定"))
        assertNull(MultiEffectParser.parse("搜尋台北101"))
    }

    @Test
    fun `applied but no visible change is attributed to location not contract`() {
        val step = Step(
            Effect("toggle:藍牙", Op.SET),
            Means.A11y("sw1", "click"),
            PostCondition { true },
            idempotencyKey = "k"
        )
        val frame = TestFrames.toggleFrame()
        val attribution = Attributor().attribute(step, Outcome.Success, frame, frame)
        assertEquals(Attribution.LOCATION, attribution)
    }

    @Test
    fun `applied on an almost empty screen is attributed to perception`() {
        val step = Step(
            Effect("toggle:藍牙", Op.SET),
            Means.A11y("sw1", "click"),
            PostCondition { true },
            idempotencyKey = "k"
        )
        val empty = com.wae.core.model.Frame.EMPTY
        val attribution = Attributor().attribute(step, Outcome.Success, empty, empty)
        assertEquals(Attribution.PERCEPTION, attribution)
    }

    @Test
    fun `no effect with changed frame is timing`() {
        val step = Step(
            Effect("toggle:藍牙", Op.SET),
            Means.A11y("sw1", "click"),
            PostCondition { true },
            idempotencyKey = "k"
        )
        val before = TestFrames.toggleFrame(on = true)
        val after = TestFrames.frame(listOf(TestFrames.rowControl()))
        assertEquals(Attribution.TIMING, Attributor().attribute(step, Outcome.NoEffect, before, after))
    }

    @Test
    fun `multi effect targets are distinct`() {
        val effects = MultiEffectParser.parse("在相簿搜尋貓")!!
        assertTrue(effects.map { it.key() }.distinct().size == 2)
    }
}
