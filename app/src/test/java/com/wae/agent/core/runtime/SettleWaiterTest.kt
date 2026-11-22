package com.wae.agent.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件驅動等待（M3c）測試：確認「畫面一穩定就提前返回」，且逾時有保底。
 */
class SettleWaiterTest {

    /** 假時鐘：sleep 會推進時間，事件由測試腳本控制 */
    private class FakeClock {
        var t = 0L
        var quietAt: Long? = null // 從此時間點起視為安靜
        fun now() = t
        fun sleep(ms: Long) {
            t += ms
        }
        fun isQuiet(): Boolean = quietAt?.let { t >= it } ?: false
    }

    @Test
    fun `returns early once screen is quiet`() {
        val c = FakeClock()
        c.quietAt = 300L
        val elapsed = SettleWaiter.waitUntilQuiet(
            now = { c.now() },
            sleep = { c.sleep(it) },
            isQuiet = { c.isQuiet() },
            timeoutMs = 1500L,
            minWaitMs = 120L,
            pollMs = 50L
        )
        assertTrue("應在畫面穩定後提前返回，實際=$elapsed", elapsed in 300..400)
        assertTrue("不得等到 timeout", elapsed < 1500L)
    }

    @Test
    fun `waits until timeout when never quiet`() {
        val c = FakeClock()
        val elapsed = SettleWaiter.waitUntilQuiet(
            now = { c.now() },
            sleep = { c.sleep(it) },
            isQuiet = { false },
            timeoutMs = 500L,
            minWaitMs = 120L,
            pollMs = 50L
        )
        assertTrue("逾時保底，實際=$elapsed", elapsed >= 500L)
        assertTrue(elapsed <= 600L)
    }

    @Test
    fun `respects minimum wait`() {
        val c = FakeClock()
        c.quietAt = 0L // 一開始就安靜
        val elapsed = SettleWaiter.waitUntilQuiet(
            now = { c.now() },
            sleep = { c.sleep(it) },
            isQuiet = { c.isQuiet() },
            timeoutMs = 1000L,
            minWaitMs = 200L,
            pollMs = 50L
        )
        assertTrue("最短等待仍需保留，實際=$elapsed", elapsed >= 200L)
        assertTrue("但不該拖到 timeout", elapsed < 400L)
    }

    @Test
    fun `zero timeout returns immediately`() {
        val c = FakeClock()
        val elapsed = SettleWaiter.waitUntilQuiet(
            now = { c.now() },
            sleep = { c.sleep(it) },
            isQuiet = { false },
            timeoutMs = 0L,
            minWaitMs = 0L
        )
        assertEquals(0L, elapsed)
    }
}
