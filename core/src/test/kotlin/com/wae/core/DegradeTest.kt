package com.wae.core

import com.wae.core.util.Degrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可降級的失敗機制（第 5 項：靜默失敗治理）。
 *
 * 規則：要降級就明說「哪裡失敗」，而且一定留痕；
 * 不能降級的地方不要用這個，讓例外往上丟。
 */
class DegradeTest {

    private val messages = ArrayList<String>()

    private fun install() {
        messages.clear()
        Degrade.logger = { messages.add(it) }
    }

    @Test
    fun `orNull returns null and leaves a trace`() {
        install()
        val v = Degrade.orNull<Int>("warmup:model") { error("載不到模型") }
        assertNull(v)
        assertEquals(1, messages.size)
        assertTrue("訊息要說明哪裡失敗：$messages", messages[0].contains("warmup:model"))
        assertTrue("訊息要帶原因：$messages", messages[0].contains("載不到模型"))
    }

    @Test
    fun `orNull passes value through on success and stays quiet`() {
        install()
        val v = Degrade.orNull("x") { 42 }
        assertEquals(42, v)
        assertTrue("成功時不該留痕：$messages", messages.isEmpty())
    }

    @Test
    fun `orElse returns the fallback and leaves a trace`() {
        install()
        val v = Degrade.orElse("isInstalled(com.x)", false) { error("boom") }
        assertEquals(false, v)
        assertEquals(1, messages.size)
    }

    @Test
    fun `orEmpty returns an empty list on failure`() {
        install()
        val v = Degrade.orEmpty<String>("contacts") { error("no permission") }
        assertTrue(v.isEmpty())
        assertEquals(1, messages.size)
    }

    @Test
    fun `exception without message still produces a readable trace`() {
        install()
        Degrade.orNull("weird") { throw IllegalStateException() }
        assertEquals(1, messages.size)
        assertTrue("沒有 message 也要有例外型別：$messages", messages[0].contains("IllegalStateException"))
    }

    @Test
    fun `broken logger cannot break the caller`() {
        Degrade.logger = { throw RuntimeException("logger 壞了") }
        // 記錄失敗不該把原本的降級流程再炸一次
        val v = Degrade.orElse("x", 7) { error("原始錯誤") }
        assertEquals(7, v)
    }
}
