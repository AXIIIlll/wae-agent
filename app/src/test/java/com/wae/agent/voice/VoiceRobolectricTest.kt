package com.wae.agent.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 無需真機的 Android 行為測試（Robolectric + 真實 assets）。
 *
 * 覆蓋 P0/P1 新程式碼：
 *  - 語音歷史（落盤、最近一筆可復原、清除）
 *  - 真實 TSV 誤認修正表
 *  - 真實 TSV 風險分級（BLOCK/CONFIRM/SAFE）
 *  - 真實 TSV 指令閘門（接受指令、拒絕噪音）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceRobolectricTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SessionHistory.clear(ctx)
    }

    @Test
    fun `歷史紀錄：落盤、結果、復原、清除`() {
        SessionHistory.append(ctx, "打開設定", voice = true)
        assertEquals(1, SessionHistory.recent(ctx, 5).size)
        val latest = SessionHistory.recent(ctx, 5).first()
        assertEquals("打開設定", latest.text)
        assertTrue(latest.voice)
        assertTrue(SessionHistory.lastUndoable(ctx) != null)

        SessionHistory.markLastResult(ctx, true)
        assertEquals(true, SessionHistory.recent(ctx, 5).first().success)

        SessionHistory.markLastUndone(ctx)
        assertEquals(null, SessionHistory.lastUndoable(ctx))

        SessionHistory.clear(ctx)
        assertTrue(SessionHistory.recent(ctx, 5).isEmpty())
    }

    @Test
    fun `歷史紀錄：保留上限 20 筆`() {
        repeat(25) { i -> SessionHistory.append(ctx, "指令$i", voice = false) }
        assertEquals(20, SessionHistory.recent(ctx, 100).size)
        assertEquals("指令24", SessionHistory.recent(ctx, 1).first().text)
    }

    @Test
    fun `誤認修正：讀真實 TSV`() {
        assertEquals("打開設定", AsrCorrector.correct(ctx, "大開設定"))
        assertEquals("返回上一頁", AsrCorrector.correct(ctx, "返回上一夜"))
        assertEquals("導航到台北車站", AsrCorrector.correct(ctx, "導航到台北車展"))
        assertEquals("調高音量", AsrCorrector.correct(ctx, "調高音量"))
        // 不可連鎖誤替換
        assertEquals("調高音量", AsrCorrector.correct(ctx, "高高音量"))
    }

    @Test
    fun `風險分級：讀真實 TSV`() {
        assertEquals(VoiceRiskPolicy.Risk.BLOCK, VoiceRiskPolicy.classify(ctx, "幫我轉帳 1000 元"))
        assertEquals(VoiceRiskPolicy.Risk.BLOCK, VoiceRiskPolicy.classify(ctx, "恢復原廠設定"))
        assertEquals(VoiceRiskPolicy.Risk.CONFIRM, VoiceRiskPolicy.classify(ctx, "傳送訊息給媽媽"))
        assertEquals(VoiceRiskPolicy.Risk.CONFIRM, VoiceRiskPolicy.classify(ctx, "刪除這張照片"))
        assertEquals(VoiceRiskPolicy.Risk.SAFE, VoiceRiskPolicy.classify(ctx, "打開設定"))
        assertEquals(VoiceRiskPolicy.Risk.SAFE, VoiceRiskPolicy.classify(ctx, "播放音樂"))
    }

    @Test
    fun `指令閘門：讀真實詞庫`() {
        assertTrue(VoiceCommandGate.isCommandLike(ctx, "打開設定"))
        assertTrue(VoiceCommandGate.isCommandLike(ctx, "搜尋台北一零一"))
        assertFalse(VoiceCommandGate.isCommandLike(ctx, "素人 动用 如果 这个 这个"))
        assertFalse(VoiceCommandGate.isCommandLike(ctx, "hello world"))
    }

    @Test
    fun `離線語音模型 zip 存在於 assets`() {
        val zip = ctx.assets.open("sherpa/zh-14m-int8.zip")
        assertNotNull(zip)
        assertTrue(zip.available() > 1_000_000)
        zip.close()
        val kws = ctx.assets.open("sherpa/kws-zh.zip")
        assertTrue(kws.available() > 1_000_000)
        kws.close()
    }

    @Test
    fun `喚醒詞清單可用`() {
        val text = ctx.assets.open("sherpa/kws-keywords.txt").bufferedReader().readText()
        assertTrue(text.contains("@"))
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `訓練後的 linear reranker 可載入且排序合理`() {
        val ctx2 = ApplicationProvider.getApplicationContext<android.content.Context>()
        val loaded = com.wae.agent.agent.LinearReranker.ensureLoaded(ctx2)
        org.junit.Assert.assertTrue("reranker_linear.json 未載入", loaded)
        val el = com.wae.agent.agent.ObservedElement(0, "藍牙", clickable = true)
        val el2 = com.wae.agent.agent.ObservedElement(1, "網路與網際網路", clickable = true)
        val s1 = com.wae.agent.agent.LinearReranker.score("藍牙", el) ?: 0f
        val s2 = com.wae.agent.agent.LinearReranker.score("藍牙", el2) ?: 0f
        org.junit.Assert.assertTrue("相等元素應該分數較高（$s1 vs $s2）", s1 > s2)
    }
}
