package com.wae.agent.core.omega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WAE-Ω 第 7 層：反統一能力合成（自我擴張 DSL）。
 */
class OperatorSynthesizerTest {

    @Before
    fun setUp() {
        OperatorRegistry.resetForTest()
    }

    @Test
    fun `two similar traces produce parameterized operator`() {
        val ops = OperatorSynthesizer.synthesize(
            listOf(
                listOf("APP:設定", "TAP:關於", "TAP:電池"),
                listOf("APP:設定", "TAP:關於", "TAP:儲存空間")
            )
        )
        assertEquals(1, ops.size)
        val op = ops.first()
        assertEquals(3, op.pattern.size)
        assertTrue(op.pattern[0] is OperatorSynthesizer.PatternToken.Literal)
        assertTrue(op.pattern[2] is OperatorSynthesizer.PatternToken.Param)
        assertEquals(2, op.support)
        assertTrue(op.toPatternSpec().contains("\$p2"))
    }

    @Test
    fun `single trace does not meet min support`() {
        val ops = OperatorSynthesizer.synthesize(listOf(listOf("A", "B", "C")))
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `different lengths are separated`() {
        val ops = OperatorSynthesizer.synthesize(
            listOf(
                listOf("A", "B"),
                listOf("A", "B"),
                listOf("A", "B", "C"),
                listOf("A", "B", "D")
            )
        )
        assertEquals(2, ops.size)
        assertTrue(ops.all { it.pattern.size in setOf(2, 3) })
    }

    @Test
    fun `spec based synthesis`() {
        val ops = OperatorSynthesizer.synthesizeSpecs(
            listOf(
                "LAUNCH_APP:設定|TAP:關於|TYPE:\$v",
                "LAUNCH_APP:設定|TAP:關於|TYPE:\$x"
            )
        )
        assertEquals(1, ops.size)
        assertEquals(2, ops.first().support)
    }

    @Test
    fun `instantiate fills params`() {
        val ops = OperatorSynthesizer.synthesize(
            listOf(
                listOf("APP:設定", "TAP:關於", "TAP:電池"),
                listOf("APP:設定", "TAP:關於", "TAP:螢幕")
            )
        )
        val op = ops.first()
        val trace = OperatorSynthesizer.instantiate(op, mapOf("p2" to "藍牙"))
        assertNotNull(trace)
        assertEquals(listOf("APP:設定", "TAP:關於", "TAP:藍牙"), trace)
        assertNull(OperatorSynthesizer.instantiate(op, emptyMap()))
    }

    @Test
    fun `matches respects literals and params`() {
        val ops = OperatorSynthesizer.synthesize(
            listOf(
                listOf("APP:設定", "TAP:關於", "TAP:電池"),
                listOf("APP:設定", "TAP:關於", "TAP:螢幕")
            )
        )
        val op = ops.first()
        assertTrue(op.matches(listOf("APP:設定", "TAP:關於", "TAP:任何")))
        assertFalse(op.matches(listOf("APP:其他", "TAP:關於", "TAP:任何")))
        assertFalse(op.matches(listOf("APP:設定")))
    }

    @Test
    fun `empty input is safe`() {
        assertTrue(OperatorSynthesizer.synthesize(emptyList()).isEmpty())
        assertTrue(OperatorSynthesizer.synthesize(listOf(emptyList())).isEmpty())
    }

    @Test
    fun `registry save find and usage`() {
        val op = OperatorSynthesizer.synthesize(
            listOf(
                listOf("APP:設定", "TAP:關於", "TAP:電池"),
                listOf("APP:設定", "TAP:關於", "TAP:螢幕")
            )
        ).first()
        assertTrue(OperatorRegistry.save(op))
        assertEquals(1, OperatorRegistry.count())
        assertNotNull(OperatorRegistry.findApplicable(listOf("APP:設定", "TAP:關於", "TAP:任意")))
        assertNull(OperatorRegistry.findApplicable(listOf("回傳", "首頁")))
        assertTrue(OperatorRegistry.recordUsage(op.id))
    }
}
