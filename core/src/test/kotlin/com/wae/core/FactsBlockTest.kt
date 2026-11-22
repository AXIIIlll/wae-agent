package com.wae.core

import com.wae.core.chat.FactsBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事實區塊注入：有內容就包起來、空白就不加、不含執行細節。
 */
class FactsBlockTest {

    @Test
    fun `empty facts produce no block`() {
        assertEquals("", FactsBlock.wrap(""))
        assertEquals("", FactsBlock.wrap("   \n  "))
    }

    @Test
    fun `facts are wrapped with header`() {
        val block = FactsBlock.wrap("apps=83\nperms: WRITE_SETTINGS=true")
        assertTrue(block.contains(FactsBlock.HEADER))
        assertTrue(block.contains("apps=83"))
        assertTrue(block.contains("WRITE_SETTINGS=true"))
    }

    @Test
    fun `execution markers are stripped from facts`() {
        val block = FactsBlock.wrap("apps=83\nsteps=3 tx=1\ncaps=7")
        assertTrue(!block.contains("steps=3"))
        assertTrue(!block.contains("tx=1"))
        assertTrue(block.contains("caps=7"))
    }

    @Test
    fun `facts are capped per line`() {
        val long = "x".repeat(500)
        val block = FactsBlock.wrap(long)
        val line = block.lines().first { it.startsWith("x") }
        assertTrue("每行要截斷：${line.length}", line.length <= 160)
    }
}
