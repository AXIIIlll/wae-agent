package com.wae.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 動作原語（3 類）測試。
 *
 * 目的：把整個動作空間收斂成 LAUNCH / INTERACT / INPUT，
 * 讓 Twin/IR 的儲存與 token 成本下降，並保證「任何操作都歸得進某一類」。
 */
class ActionOpsTest {

    @Test
    fun `every action kind maps to exactly one primitive`() {
        assertEquals(ActionPrimitive.LAUNCH, ActionOps.of(ActionKind.LAUNCH_APP).primitive)
        assertEquals(ActionPrimitive.LAUNCH, ActionOps.of(ActionKind.DEEP_LINK).primitive)
        assertEquals(ActionPrimitive.INPUT, ActionOps.of(ActionKind.TYPE, "query").primitive)
        listOf(ActionKind.TAP, ActionKind.SCROLL_DOWN, ActionKind.BACK, ActionKind.WAIT).forEach {
            assertEquals("$it 應歸為 INTERACT", ActionPrimitive.INTERACT, ActionOps.of(it).primitive)
        }
    }

    @Test
    fun `interact sub kinds are distinguished`() {
        assertEquals(InteractKind.TAP, ActionOps.of(ActionKind.TAP).kind)
        assertEquals(InteractKind.SCROLL, ActionOps.of(ActionKind.SCROLL_DOWN).kind)
        assertEquals(InteractKind.BACK, ActionOps.of(ActionKind.BACK).kind)
        assertEquals(InteractKind.WAIT, ActionOps.of(ActionKind.WAIT).kind)
    }

    @Test
    fun `input carries slot not literal value`() {
        val op = ActionOps.of(ActionKind.TYPE, "query")
        assertEquals("query", op.slot)
        assertTrue("輸入值不得寫入動作描述", op.slot != "台北101")
    }

    @Test
    fun `compact form is short`() {
        assertEquals("L:APP", ActionOps.of(ActionKind.LAUNCH_APP).compact())
        assertEquals("N:TXT(query)", ActionOps.of(ActionKind.TYPE, "query").compact())
        assertEquals("I:TAP", ActionOps.of(ActionKind.TAP).compact())
        assertEquals("I:SDN", ActionOps.of(ActionKind.SCROLL_DOWN).compact())
        assertTrue("每一步應 ≤14 bytes（省 token）", ActionOps.of(ActionKind.TAP).compact().length <= 14)
    }

    @Test
    fun `every fine op maps to one of three primitives`() {
        FineOp.values().forEach { op ->
            assertTrue(
                "\$op 必須歸入三類之一",
                op.primitive in setOf(
                    ActionPrimitive.LAUNCH,
                    ActionPrimitive.INTERACT,
                    ActionPrimitive.INPUT
                )
            )
        }
    }

    @Test
    fun `planned ops only contain notification now`() {
        val planned = ActionOps.planned().map { it.name }
        assertEquals(listOf("L_NOTIFICATION"), planned)
    }

    @Test
    fun `six newly added actions map to fine ops`() {
        assertEquals(FineOp.I_SCROLL_UP, ActionOps.of(ActionKind.SCROLL_UP).fine)
        assertEquals(FineOp.I_SWIPE_LR, ActionOps.of(ActionKind.SWIPE_HORIZONTAL).fine)
        assertEquals(FineOp.I_LONG_PRESS, ActionOps.of(ActionKind.LONG_PRESS).fine)
        assertEquals(FineOp.I_SUBMIT, ActionOps.of(ActionKind.SUBMIT).fine)
        assertEquals(FineOp.N_CLEAR, ActionOps.of(ActionKind.CLEAR_TEXT).fine)
        assertEquals(FineOp.N_REPLACE, ActionOps.of(ActionKind.REPLACE_TEXT, "query").fine)
    }

    @Test
    fun `new actions keep three primitive classification`() {
        assertEquals(ActionPrimitive.INTERACT, ActionOps.of(ActionKind.SCROLL_UP).primitive)
        assertEquals(ActionPrimitive.INTERACT, ActionOps.of(ActionKind.SWIPE_HORIZONTAL).primitive)
        assertEquals(ActionPrimitive.INTERACT, ActionOps.of(ActionKind.LONG_PRESS).primitive)
        assertEquals(ActionPrimitive.INTERACT, ActionOps.of(ActionKind.SUBMIT).primitive)
        assertEquals(ActionPrimitive.INPUT, ActionOps.of(ActionKind.CLEAR_TEXT).primitive)
        assertEquals(ActionPrimitive.INPUT, ActionOps.of(ActionKind.REPLACE_TEXT, "q").primitive)
    }

    @Test
    fun `primitive enum has exactly three members`() {
        assertEquals(3, ActionPrimitive.values().size)
    }
}
