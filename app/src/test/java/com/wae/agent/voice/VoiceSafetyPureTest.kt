package com.wae.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 語音安全／修正的純函式測試（不需要 Android Context）。
 */
class VoiceSafetyPureTest {

    private val block = listOf("轉帳", "匯款", "付款", "格式化", "解除安裝")
    private val confirm = listOf("傳送", "分享", "刪除", "撥打", "發布")

    @Test
    fun `BLOCK 指令一律攔截`() {
        assertEquals(VoiceRiskPolicy.Risk.BLOCK, VoiceRiskPolicy.classifyWith("幫我轉帳 1000 元", block, confirm))
        assertEquals(VoiceRiskPolicy.Risk.BLOCK, VoiceRiskPolicy.classifyWith("格式化手機", block, confirm))
        assertEquals(VoiceRiskPolicy.Risk.BLOCK, VoiceRiskPolicy.classifyWith("解除安裝 銀行 App", block, confirm))
    }

    @Test
    fun `CONFIRM 指令需要確認`() {
        assertEquals(VoiceRiskPolicy.Risk.CONFIRM, VoiceRiskPolicy.classifyWith("傳送訊息給媽媽", block, confirm))
        assertEquals(VoiceRiskPolicy.Risk.CONFIRM, VoiceRiskPolicy.classifyWith("刪除這張照片", block, confirm))
        assertEquals(VoiceRiskPolicy.Risk.CONFIRM, VoiceRiskPolicy.classifyWith("分享這篇文章", block, confirm))
    }

    @Test
    fun `一般指令為 SAFE`() {
        assertEquals(VoiceRiskPolicy.Risk.SAFE, VoiceRiskPolicy.classifyWith("打開設定", block, confirm))
        assertEquals(VoiceRiskPolicy.Risk.SAFE, VoiceRiskPolicy.classifyWith("播放音樂", block, confirm))
    }

    @Test
    fun `空字串為 SAFE`() {
        assertEquals(VoiceRiskPolicy.Risk.SAFE, VoiceRiskPolicy.classifyWith("   ", block, confirm))
    }

    @Test
    fun `空白不影響比對`() {
        assertEquals(VoiceRiskPolicy.Risk.BLOCK, VoiceRiskPolicy.classifyWith("幫 我 轉 帳", block, confirm))
    }

    @Test
    fun `指令閘門：像指令的中文通過`() {
        val words = listOf("打開", "設定", "搜尋", "播放", "關閉")
        assertTrue(VoiceCommandGate.isCommandLikeWith("打開設定", words))
        assertTrue(VoiceCommandGate.isCommandLikeWith("搜尋台北一零一", words))
        assertTrue(VoiceCommandGate.isCommandLikeWith("播放音樂", words))
    }

    @Test
    fun `指令閘門：噪音、英文、太短不通過`() {
        val words = listOf("打開", "設定")
        assertFalse(VoiceCommandGate.isCommandLikeWith("哈囉哈囉哈囉", words))
        assertFalse(VoiceCommandGate.isCommandLikeWith("hello world", words))
        assertFalse(VoiceCommandGate.isCommandLikeWith("啊", words))
    }

    @Test
    fun `誤認修正：命令詞`() {
        val rules = listOf("大開" to "打開", "上一夜" to "上一頁", "車展" to "車站")
        assertEquals("打開設定", AsrCorrector.correctWith("大開設定", rules))
        assertEquals("返回上一頁", AsrCorrector.correctWith("返回上一夜", rules))
        assertEquals("導航到台北車站", AsrCorrector.correctWith("導航到台北車展", rules))
    }

    @Test
    fun `誤認修正：長條目優先替換不亂序`() {
        val rules = listOf(
            "高音量" to "調高音量",
            "高高音量" to "調高音量"
        ).sortedByDescending { it.first.length }
        assertEquals("調高音量", AsrCorrector.correctWith("高高音量", rules))
    }
}
