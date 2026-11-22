package com.wae.core

import com.wae.core.chat.ConversationMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 對話記憶：只記最近幾輪、只給對話層用，不能混進執行細節。
 */
class ConversationMemoryTest {

    @Test
    fun `keeps only the most recent turns`() {
        val memory = ConversationMemory(capacity = 4)
        repeat(6) { memory.addUser("第 $it 句") }
        assertEquals(4, memory.size())
        assertEquals("第 5 句", memory.lastUserText())
    }

    @Test
    fun `prompt contains dialogue but no execution markers`() {
        val memory = ConversationMemory()
        memory.addUser("打開設定")
        memory.addAssistant("好，幫你打開了。")
        val prompt = memory.toPrompt()
        assertTrue(prompt.contains("使用者：打開設定"))
        assertTrue(prompt.contains("小助手：好，幫你打開了。"))
        for (marker in ConversationMemory.EXECUTION_MARKERS) {
            assertTrue("對話 prompt 不該含執行細節：$marker", !prompt.contains(marker))
        }
    }

    @Test
    fun `empty memory renders as placeholder`() {
        assertEquals("（無）", ConversationMemory().toPrompt())
    }

    @Test
    fun `clear empties the memory`() {
        val memory = ConversationMemory()
        memory.addUser("hello")
        memory.clear()
        assertEquals(0, memory.size())
    }
}
