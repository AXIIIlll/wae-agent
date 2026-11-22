package com.wae.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放驗證的雙訊號：畫面文字（暫停）或系統音訊（isMusicActive）任一成立就算在播。
 * 為什麼需要第二訊號：有些播放器只有圖示、沒有「暫停」文字，單靠文字會誤判失敗。
 */
class VerifyRulesPlaybackTest {

    private fun obs(vararg texts: String) = Observation(
        appPackage = "com.example.video",
        title = texts.firstOrNull() ?: "",
        elements = texts.mapIndexed { i, t -> ObservedElement(index = i, text = t, clickable = true) }
    )

    private fun step(verify: String) = AgentStep(
        id = 1, doText = "按下播放", target = "播放", verify = verify
    )

    @Test
    fun `audio active counts as playing even without pause text`() {
        val rules = VerifyRules(isPlaybackActive = { true })
        val r = rules.check(step("暫停"), obs("首頁", "影片標題"), obs("首頁", "影片標題", "進度"))
        assertEquals(VerifyOutcome.OK, r.outcome)
        assertEquals("audio active", r.evidence)
    }

    @Test
    fun `pause text still works without audio signal`() {
        val rules = VerifyRules(isPlaybackActive = { false })
        val r = rules.check(step("暫停"), obs("首頁"), obs("首頁", "暫停"))
        assertEquals(VerifyOutcome.OK, r.outcome)
        assertEquals("found: 暫停", r.evidence)
    }

    @Test
    fun `no text and no audio is a failure`() {
        val rules = VerifyRules(isPlaybackActive = { false })
        val r = rules.check(step("暫停"), obs("首頁"), obs("首頁"))
        assertEquals(VerifyOutcome.FAIL, r.outcome)
    }

    @Test
    fun `playback signal only applies to playback-ish conditions`() {
        val rules = VerifyRules(isPlaybackActive = { true })
        // 條件是「設定」而不是播放 → 不應該被 audio 訊號誤判成功
        val r = rules.check(
            AgentStep(id = 2, doText = "點進設定", target = "設定", verify = "設定"),
            obs("首頁"), obs("首頁")
        )
        assertTrue(r.outcome != VerifyOutcome.OK)
    }
}
