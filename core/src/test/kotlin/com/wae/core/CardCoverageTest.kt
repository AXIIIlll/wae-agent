package com.wae.core

import com.wae.core.config.Reason
import com.wae.core.model.Option
import com.wae.core.model.Outcome
import com.wae.core.ui.CardFactory
import com.wae.core.ui.CardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片覆蓋（H2）：**每一個棄權理由都要有專屬的人話**。
 *
 * 為什麼要有這個測試：`CardFactory` 有一個 `else -> "我停在這裡"` 的保底分支。
 * 保底很方便，但它會讓「新加的理由」安靜地掉進一句沒用的話——
 * 使用者看到的永遠是「我停在這裡」，而我們以為自己有解釋。
 */
class CardCoverageTest {

    private val reasons = listOf(
        Reason.SENSITIVE_OPERATION, Reason.LOGIN_WALL, Reason.PANEL_OPENED,
        Reason.PERMISSION_MISSING, Reason.CANCELLED, Reason.MISSING_SLOT,
        Reason.CAPTURE_UNVERIFIED, Reason.POPUP_SUSPECTED, Reason.UNKNOWN_VERB,
        Reason.NO_CANDIDATE, Reason.REPLANS_EXHAUSTED, Reason.BUDGET_EXCEEDED,
        Reason.FORBIDDEN_EFFECT, Reason.NOT_IMPLEMENTED, Reason.LOW_CONFIDENCE,
        Reason.TIMEOUT, Reason.BLOCKED_BY_CONSTRAINT, Reason.ALL_MEANS_BANNED,
        Reason.MEANS_NOT_SUPPORTED, Reason.ROUTE_LAUNCH_FAILED
    )

    @Test
    fun `every abstain reason has a specific card`() {
        val generic = "我停在這裡"
        val leaks = reasons.filter { reason ->
            val card = CardFactory.from(Outcome.Abstain(reason, listOf(Option("retry", "再試"))))
            card == null || card.title == generic
        }
        assertEquals("這些理由掉進保底分支（沒有專屬說明）", emptyList<String>(), leaks)
    }

    @Test
    fun `every card has title body and at least one option`() {
        for (reason in reasons) {
            val card = CardFactory.from(Outcome.Abstain(reason, emptyList()))
            assertNotNull("$reason 沒有卡片", card)
            val c = card!!
            assertTrue("$reason 標題是空的", c.title.isNotBlank())
            assertTrue("$reason 說明是空的", c.body.isNotBlank())
            assertTrue("$reason 沒有下一步", c.options.isNotEmpty())
            assertEquals("$reason 應是棄權卡", CardKind.ABSTAIN, c.kind)
        }
    }

    @Test
    fun `success and blocked have cards too`() {
        assertNotNull(CardFactory.from(Outcome.Success))
        val blocked = CardFactory.from(Outcome.Blocked)
        assertEquals(CardKind.FAILED, blocked?.kind)
        assertTrue("被擋下也要有下一步", blocked!!.options.isNotEmpty())
    }
}
