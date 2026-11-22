package com.wae.agent.core.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 動作契約測試：冪等鍵、冪等視窗、三態後置驗證。
 *
 * 目標：重複副作用 = 0；NO_EFFECT 能被辨識並允許重試；WRONG_EFFECT 立即標紅。
 */
class ActionContractTest {

    private val now = 1_700_000_000_000L

    private fun intent(
        pkg: String = "com.demo.mail",
        action: String = "TAP",
        selector: String = "id:row",
        posture: String = "LIST_FEED",
        spine: Long = 111L,
        slot: String = ""
    ) = ActionIntent(pkg, action, selector, posture, spine, slot)

    // ── 意圖鍵 ──────────────────────────────────────────────────

    @Test
    fun `same intent produces same key`() {
        assertEquals(intent().key(), intent().key())
    }

    @Test
    fun `different selector produces different key`() {
        assertTrue(intent(selector = "id:row").key() != intent(selector = "id:fab").key())
    }

    @Test
    fun `different page state produces different key`() {
        assertTrue(intent(spine = 1L).key() != intent(spine = 2L).key())
    }

    @Test
    fun `input slot distinguishes typing targets`() {
        assertTrue(
            intent(action = "TYPE", slot = "query").key() != intent(action = "TYPE", slot = "address").key()
        )
    }

    // ── 冪等帳本 ────────────────────────────────────────────────

    @Test
    fun `applied intent is skipped within window`() {
        ActionLedger.reset()
        val key = intent().key()
        ActionLedger.record(key, ActionEffect.APPLIED, now)
        assertTrue("視窗內應跳過", ActionLedger.shouldSkip(key, now + 1000))
        assertFalse("視窗過後可再執行", ActionLedger.shouldSkip(key, now + 3000))
    }

    @Test
    fun `failed intent is not skipped`() {
        ActionLedger.reset()
        val key = intent().key()
        ActionLedger.record(key, ActionEffect.NO_EFFECT, now)
        assertFalse("沒生效的動作必須允許重試", ActionLedger.shouldSkip(key, now + 100))
    }

    @Test
    fun `wrong effect is not skipped but recorded`() {
        ActionLedger.reset()
        val key = intent().key()
        ActionLedger.record(key, ActionEffect.WRONG_EFFECT, now)
        assertFalse(ActionLedger.shouldSkip(key, now + 100))
        assertTrue(ActionLedger.recentEffects(now + 100).contains(ActionEffect.WRONG_EFFECT))
    }

    @Test
    fun `ledger is capped and resettable`() {
        ActionLedger.reset()
        repeat(200) { i -> ActionLedger.record("k$i", ActionEffect.APPLIED, now) }
        assertTrue("帳本不應無限增長", ActionLedger.size() <= 128)
        ActionLedger.reset()
        assertEquals(0, ActionLedger.size())
    }

    // ── 三態後置驗證 ────────────────────────────────────────────

    @Test
    fun `page change means applied`() {
        assertEquals(
            ActionEffect.APPLIED,
            ActionContract.classifyEffect(111L, 222L, "com.a", "com.a")
        )
    }

    @Test
    fun `no change means no effect`() {
        assertEquals(
            ActionEffect.NO_EFFECT,
            ActionContract.classifyEffect(111L, 111L, "com.a", "com.a", 20, 20)
        )
    }

    @Test
    fun `node count change means applied`() {
        assertEquals(
            ActionEffect.APPLIED,
            ActionContract.classifyEffect(111L, 111L, "com.a", "com.a", 10, 15)
        )
    }

    @Test
    fun `jump to expected package is applied`() {
        assertEquals(
            ActionEffect.APPLIED,
            ActionContract.classifyEffect(
                111L, 222L, "com.a", "com.target", expectedPackage = "com.target"
            )
        )
    }

    @Test
    fun `jump to wrong package is wrong effect`() {
        assertEquals(
            ActionEffect.WRONG_EFFECT,
            ActionContract.classifyEffect(
                111L, 222L, "com.a", "com.other", expectedPackage = "com.target"
            )
        )
    }

    @Test
    fun `unknown spine values do not fake applied`() {
        assertEquals(
            ActionEffect.NO_EFFECT,
            ActionContract.classifyEffect(0L, 0L, "com.a", "com.a", 0, 0)
        )
    }
}
