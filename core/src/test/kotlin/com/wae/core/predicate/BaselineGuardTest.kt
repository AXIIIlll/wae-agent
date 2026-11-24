package com.wae.core.predicate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「卡在子頁面要不要返回」的決策測試。
 *
 * 這個判斷之所以要單獨測，是因為它會按 BACK——按錯一次就會把 App 關掉
 * （S31 的教訓：Maps 首頁被誤判後直接被退出）。所以規則必須窄且有上限。
 */
class BaselineGuardTest {

    @Test
    fun `launcher is a clean baseline so no back is needed`() {
        val s = BaselineGuard.Screen(
            foregroundPackage = "com.sec.android.app.launcher",
            launcherPackage = "com.sec.android.app.launcher",
            hasBackAffordance = false
        )
        assertFalse(BaselineGuard.shouldGoBack(s, backsTaken = 0, maxBacks = 3))
    }

    @Test
    fun `nested page with a visible back affordance should go back`() {
        val s = BaselineGuard.Screen(
            foregroundPackage = "com.android.settings",
            launcherPackage = "com.sec.android.app.launcher",
            hasBackAffordance = true,
            atRoot = false
        )
        assertTrue(BaselineGuard.shouldGoBack(s, backsTaken = 0, maxBacks = 3))
    }

    @Test
    fun `root page without back affordance is left alone`() {
        val s = BaselineGuard.Screen(
            foregroundPackage = "com.android.settings",
            launcherPackage = "com.sec.android.app.launcher",
            hasBackAffordance = false,
            atRoot = true
        )
        assertFalse(BaselineGuard.shouldGoBack(s, backsTaken = 0, maxBacks = 3))
    }

    @Test
    fun `back count is capped so we never退出 the app`() {
        val s = BaselineGuard.Screen(
            foregroundPackage = "com.android.settings",
            launcherPackage = "com.sec.android.app.launcher",
            hasBackAffordance = true
        )
        assertFalse(BaselineGuard.shouldGoBack(s, backsTaken = 3, maxBacks = 3))
    }

    @Test
    fun `unknown foreground is not touched`() {
        val s = BaselineGuard.Screen(foregroundPackage = "", hasBackAffordance = true)
        assertFalse(BaselineGuard.shouldGoBack(s, backsTaken = 0, maxBacks = 3))
    }

    @Test
    fun `back labels are matched case insensitively and exactly`() {
        val labels = listOf("返回", "上一頁", "back", "navigate up")
        assertTrue(BaselineGuard.isBackLabel("返回", labels))
        assertTrue(BaselineGuard.isBackLabel("Back", labels))
        assertTrue(BaselineGuard.isBackLabel("Navigate up", labels))
        // 不能因為含「回」就命中，避免誤點其他按鈕
        assertFalse(BaselineGuard.isBackLabel("回首頁", labels))
        assertFalse(BaselineGuard.isBackLabel("", labels))
    }

    @Test
    fun `view ids are matched by whole segment only`() {
        assertTrue(BaselineGuard.isBackViewId("com.android.settings:id/action_bar_back"))
        assertTrue(BaselineGuard.isBackViewId("com.google.android.apps.nexuslauncher:id/home"))
        // 專案踩過的坑：edit_compose_post 曾被 ASCII 關鍵字 post 命中
        assertFalse(BaselineGuard.isBackViewId("edit_compose_post"))
        assertFalse(BaselineGuard.isBackViewId("backup_button"))
    }
}
