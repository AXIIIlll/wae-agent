package com.wae.agent.core.omega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WAE-Ω 安全計算器（COMPUTE 只允許安全子集）。
 */
class ComputeEvalTest {

    @Test
    fun `basic arithmetic`() {
        assertEquals("7", ComputeEval.eval("1+2*3"))
        assertEquals("9", ComputeEval.eval("(1+2)*3"))
        assertEquals("0.5", ComputeEval.eval("1/2"))
        assertEquals("3", ComputeEval.eval("-1+4"))
        assertEquals("2", ComputeEval.eval(" 2 "))
    }

    @Test
    fun `division by zero returns null`() {
        assertNull(ComputeEval.eval("1/0"))
    }

    @Test
    fun `string concatenation`() {
        assertEquals("訂單123", ComputeEval.eval("'訂單' + '123'"))
        assertEquals("ABC", ComputeEval.eval("\"A\" + 'B' + 'C'"))
    }

    @Test
    fun `invalid expressions return null`() {
        assertNull(ComputeEval.eval(""))
        assertNull(ComputeEval.eval("1+"))
        assertNull(ComputeEval.eval("abc"))
        assertNull(ComputeEval.eval("(1+2"))
        assertNull(ComputeEval.eval("1+2)"))
        assertNull(ComputeEval.eval("'unterminated"))
    }
}
