package com.wae.core

import com.wae.core.config.Key
import com.wae.core.config.MapConfig
import com.wae.core.memory.RefutationLog
import com.wae.core.model.Attribution
import com.wae.core.model.Frame
import com.wae.core.model.Verdict
import com.wae.core.session.TaskSession
import com.wae.core.time.FixedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAndSessionTest {

    private val effectKey = "SET:toggle:藍牙"
    private val means = "a11y:click"

    private fun frame(fp: String = FP): Frame =
        Frame(fp = fp, pkg = TestFrames.PKG, appVersion = 1L, controls = listOf(TestFrames.toggleControl()))

    @Test
    fun `refutation escalates to ban at threshold`() {
        val clock = FixedClock(1_000)
        val config = MapConfig(mapOf(Key.REFUTATION_BAN_THRESHOLD to "2"))
        val log = RefutationLog(config, clock)

        val first = log.record(SCOPE, FP, effectKey, means, Attribution.LOCATION)
        assertEquals(Verdict.LOWER, first.verdict)
        assertEquals(1, first.count)

        val second = log.record(SCOPE, FP, effectKey, means, Attribution.LOCATION)
        assertEquals(Verdict.BAN, second.verdict)
        assertEquals(2, second.count)
    }

    @Test
    fun `success decays the counter and eventually removes it`() {
        val log = RefutationLog(MapConfig(emptyMap()), FixedClock(0))
        log.record(SCOPE, FP, effectKey, means, Attribution.LOCATION)
        assertEquals(1, log.all().size)

        log.noteSuccess(SCOPE, FP, effectKey, means)
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun `expired refutation stops constraining`() {
        val clock = FixedClock(0)
        val config = MapConfig(mapOf(Key.REFUTATION_TTL_MS to "1000", Key.REFUTATION_BAN_THRESHOLD to "1"))
        val log = RefutationLog(config, clock)
        log.record(SCOPE, FP, effectKey, means, Attribution.LOCATION)

        assertTrue(log.constraintsFor(frame()).isBanned(effectKey, means))

        clock.advance(5_000)
        assertFalse(log.constraintsFor(frame()).isBanned(effectKey, means))
    }

    @Test
    fun `constraint only bans the same effect plus means pair`() {
        val config = MapConfig(mapOf(Key.REFUTATION_BAN_THRESHOLD to "1"))
        val log = RefutationLog(config, FixedClock(0))
        log.record(SCOPE, FP, effectKey, means, Attribution.LOCATION)

        val constraints = log.constraintsFor(frame())
        assertTrue(constraints.isBanned(effectKey, means))
        assertFalse(constraints.isBanned("SET:toggle:Wi-Fi", means))
        assertFalse(constraints.isBanned(effectKey, "gesture:TAP"))
    }

    @Test
    fun `refutation scope does not leak to other frames`() {
        val config = MapConfig(mapOf(Key.REFUTATION_BAN_THRESHOLD to "1"))
        val log = RefutationLog(config, FixedClock(0))
        log.record(SCOPE, FP, effectKey, means, Attribution.LOCATION)

        assertFalse(log.constraintsFor(frame(fp = "fp:other")).isBanned(effectKey, means))
    }

    @Test
    fun `session rejects illegal transition`() {
        val session = TaskSession.create("打開設定", maxReplans = 1)
        assertThrows(IllegalArgumentException::class.java) {
            session.moveTo(TaskSession.State.DONE)
        }
    }

    @Test
    fun `session replan budget is enforced`() {
        val session = TaskSession.create("打開設定", maxReplans = 1)
        session.moveTo(TaskSession.State.COMPILING)
        session.moveTo(TaskSession.State.PLANNING)
        assertTrue(session.replan())
        assertFalse(session.replan())
        assertEquals(1, session.replansUsed)
    }

    private companion object {
        const val FP = "fp:toggle-1"
        const val SCOPE = "frame:fp:toggle-1"
    }
}
