package com.wae.agent.core

import com.wae.agent.core.verify.GoalVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 效率與準確度測試：
 *  1. 感知幀重用策略（省下重複採集）
 *  2. 目標完成驗證（避免「打了字就當成功」的假成功）
 */
class PerfAndVerifyTest {

    // ── 1. 幀重用 ────────────────────────────────────────────────────

    @Test
    fun `same frame without events can be reused`() {
        assertTrue(
            FrameReusePolicy.canReuse(
                samePackage = true, sameWindow = true, sameAmbient = true,
                msSince = 500, contentEventsSince = 0
            )
        )
    }

    @Test
    fun `any content event forces a fresh perception`() {
        assertFalse(
            "有內容變化事件就代表畫面可能不同，必須重採集",
            FrameReusePolicy.canReuse(
                samePackage = true, sameWindow = true, sameAmbient = true,
                msSince = 200, contentEventsSince = 1
            )
        )
    }

    @Test
    fun `different app page or window is never reused`() {
        assertFalse(FrameReusePolicy.canReuse(false, true, true, 100, 0))
        assertFalse(FrameReusePolicy.canReuse(true, false, true, 100, 0))
        assertFalse(FrameReusePolicy.canReuse(true, true, false, 100, 0))
    }

    @Test
    fun `stale frames are not reused`() {
        assertFalse(
            "超過上限（事件窗口內）就不該重用",
            FrameReusePolicy.canReuse(true, true, true, FrameReusePolicy.MAX_AGE_MS + 1, 0)
        )
        assertFalse("時間不可能為負", FrameReusePolicy.canReuse(true, true, true, -1, 0))
    }

    // ── 2. 目標完成驗證 ──────────────────────────────────────────────

    @Test
    fun `launch verifies the package actually changed`() {
        val ok = GoalVerifier.verify(
            ActionKind.LAUNCH_APP, "com.android.settings", "",
            "com.sec.android.app.launcher", "com.android.settings", true, emptyList()
        )
        assertTrue(ok.ok)
        assertEquals("PACKAGE_MATCH", ok.reason)

        val bad = GoalVerifier.verify(
            ActionKind.LAUNCH_APP, "com.android.settings", "",
            "launcher", "com.android.chrome", true, emptyList()
        )
        assertFalse("開錯 App 不能算成功", bad.ok)
    }

    @Test
    fun `sub package counts as the same app`() {
        // 設定頁面的搜尋結果由 com.android.settings.intelligence 提供
        assertTrue(
            "同一個 App 的子套件不可判為失敗",
            GoalVerifier.verify(
                ActionKind.LAUNCH_APP, "com.android.settings", "",
                "launcher", "com.android.settings.intelligence", true, emptyList()
            ).ok
        )
        assertTrue(GoalVerifier.sameApp("com.android.settings", "com.android.settings.intelligence"))
        assertTrue("反向也要成立", GoalVerifier.sameApp("com.android.settings.intelligence", "com.android.settings"))
        assertFalse("不同 App 不可誤判", GoalVerifier.sameApp("com.android.settings", "com.android.chrome"))
        assertFalse("前綴相同但不是子套件也不可誤判",
            GoalVerifier.sameApp("com.android.set", "com.android.settings"))
    }

    @Test
    fun `type succeeds when the text really lands on the page`() {
        val r = GoalVerifier.verify(
            ActionKind.TYPE, "com.example", "invoice",
            "com.example", "com.example", true,
            listOf("搜尋結果", "Invoice #1234", "更多")
        )
        assertTrue(r.ok)
        assertEquals("TEXT_PRESENT", r.reason)
    }

    @Test
    fun `type tolerates case punctuation and spaces`() {
        val r = GoalVerifier.verify(
            ActionKind.TYPE, "p", "  InvOiCe ，",
            "p", "p", true, listOf("invoice")
        )
        assertTrue("正規化後應命中", r.ok)
        assertEquals("TEXT_PRESENT", r.reason)
    }

    @Test
    fun `type with no text and no change is a failure`() {
        val r = GoalVerifier.verify(
            ActionKind.TYPE, "p", "invoice",
            "p", "p", false, listOf("首頁", "設定")
        )
        assertFalse("打了字但畫面上沒有、也沒任何變化 → 不能算成功", r.ok)
        assertEquals("TEXT_ABSENT_AND_NO_CHANGE", r.reason)
    }

    @Test
    fun `type with page change but text in next layer is accepted with weak evidence`() {
        val r = GoalVerifier.verify(
            ActionKind.TYPE, "p", "invoice",
            "p", "p", true, listOf("載入中")
        )
        assertTrue(r.ok)
        assertEquals("SPINE_CHANGED_ONLY", r.reason)
    }

    @Test
    fun `back succeeds on package change even without spine change`() {
        val r = GoalVerifier.verify(
            ActionKind.BACK, "", "",
            "com.a", "com.b", false, emptyList()
        )
        assertTrue(r.ok)
        assertEquals("PACKAGE_CHANGED", r.reason)
    }

    @Test
    fun `adjust value and wait are dispatch verified`() {
        assertTrue(
            GoalVerifier.verify(ActionKind.ADJUST_VALUE, "p", "亮度", "p", "p", false, emptyList()).ok
        )
        assertTrue(GoalVerifier.verify(ActionKind.WAIT, "p", "", "p", "p", false, emptyList()).ok)
    }

    @Test
    fun `tap without any change is a failure`() {
        val r = GoalVerifier.verify(
            ActionKind.TAP, "p", "", "p", "p", false, listOf("首頁")
        )
        assertFalse(r.ok)
        assertEquals("NO_CHANGE", r.reason)
    }

    @Test
    fun `clear text needs a visible change`() {
        assertFalse(GoalVerifier.verify(ActionKind.CLEAR_TEXT, "p", "", "p", "p", false, emptyList()).ok)
        assertTrue(GoalVerifier.verify(ActionKind.CLEAR_TEXT, "p", "", "p", "p", true, emptyList()).ok)
    }
}
