package com.wae.agent

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wae.agent.voice.SessionHistory
import com.wae.agent.voice.WakeWordService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1 #9：instrumentation 測試（需裝置或模擬器執行）。
 *
 * 只做「非 UI」的穩定檢查（UI 由 adb 手動 smoke + Robolectric 覆蓋），避免透明 Activity
 * 在 emulator 上造成 ActivityScenario 卡住：
 *  - App context / assets 可讀
 *  - 語音歷史落盤與清除
 *  - 關鍵元件（助理 Activity、喚醒詞服務）可解析
 *  - 封裝沒有 INTERNET 權限（物理離線）
 */
@RunWith(AndroidJUnit4::class)
class SmokeInstrumentedTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SessionHistory.clear(ctx)
    }

    @Test
    fun sessionHistoryRoundTrip() {
        SessionHistory.append(ctx, "打開設定", voice = true)
        SessionHistory.markLastResult(ctx, true)
        val recent = SessionHistory.recent(ctx, 5)
        assertEquals(1, recent.size)
        assertEquals("打開設定", recent[0].text)
        assertEquals(true, recent[0].success)
        val undoable = SessionHistory.lastUndoable(ctx)
        assertNotNull(undoable)
        SessionHistory.clear(ctx)
        assertTrue(SessionHistory.recent(ctx, 5).isEmpty())
    }

    @Test
    fun packageHasNoInternetPermission() {
        val info = ctx.packageManager.getPackageInfo(
            ctx.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val perms = info.requestedPermissions?.toList().orEmpty()
        assertTrue(
            "WAE 不應要求 INTERNET 權限（物理離線設計）",
            perms.none { it == android.Manifest.permission.INTERNET }
        )
    }

    @Test
    fun keyComponentsAreDeclared() {
        val assist = Intent(ctx, com.wae.agent.voice.AssistActivity::class.java)
        assertTrue(ctx.packageManager.resolveActivity(assist, 0) != null)
        assertFalse(WakeWordService.isEnabled(ctx))
        val svc = Intent(ctx, WakeWordService::class.java)
        assertTrue(ctx.packageManager.resolveService(svc, 0) != null)
    }

    @Test
    fun voiceModelAssetsAreBundled() {
        val zh = ctx.assets.open("sherpa/zh-14m-int8.zip")
        assertTrue(zh.available() > 1_000_000)
        zh.close()
        val kws = ctx.assets.open("sherpa/kws-zh.zip")
        assertTrue(kws.available() > 1_000_000)
        kws.close()
        val keywords = ctx.assets.open("sherpa/kws-keywords.txt")
            .bufferedReader().readText()
        assertTrue(keywords.contains("@"))
    }

    @Test
    fun decisionHeadOnByDefault() {
        assertTrue(com.wae.agent.core.runtime.ExtremeRuntimeHooks.DECISION_HEAD_ENABLED)
        assertFalse(com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED)
        assertFalse(com.wae.agent.core.runtime.ExtremeRuntimeHooks.WAE_OMEGA_ENABLED)
        assertFalse(com.wae.agent.core.runtime.ExtremeRuntimeHooks.GROUNDING_2_ENABLED)
    }
}
