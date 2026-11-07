package com.example.newandroidapp.vision

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.example.newandroidapp.stability.PlayStoreComplianceHelper

/**
 * 【優化 #5 & #9：浮動球 (Floating HUD Orb) 狀態持久化與重裝自動恢復管理員】
 *
 * 解決 APK 更新重裝 (`adb install -r`) 或無障礙服務重連後浮動球消失、或位置重置的問題：
 * 1. 持久化記錄使用者是否開啟浮動球 (`KEY_HUD_ENABLED`，預設一旦通過合規同意即為 `true`)。
 * 2. 持久化記憶浮動球最後拖曳停靠的螢幕座標 (`KEY_HUD_X`, `KEY_HUD_Y`)。
 * 3. 在 `onServiceConnected` 及無障礙事件喚醒時自動檢查並恢復掛載（採用 `TYPE_ACCESSIBILITY_OVERLAY`，無需 `canDrawOverlays` 權限）。
 * 4. 提供 `isAccessibilityServiceEnabledInSettings` 精準區分「系統尚未開啟無障礙權限」與「服務已在系統啟用、僅正在重連或切換懸浮球顯示」。
 */
object HudPersistentStateManager {

    private const val PREFS_NAME = "hud_persistent_state_prefs"
    private const val KEY_HUD_ENABLED = "hud_orb_enabled_v1"
    private const val KEY_HUD_X = "hud_orb_pos_x_v1"
    private const val KEY_HUD_Y = "hud_orb_pos_y_v1"

    fun isHudPreferredEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_HUD_ENABLED, true)
    }

    fun setHudPreferredEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_HUD_ENABLED, enabled).apply()
    }

    fun saveOrbPosition(context: Context, x: Int, y: Int) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putInt(KEY_HUD_X, x)
            .putInt(KEY_HUD_Y, y)
            .apply()
    }

    fun getSavedOrbPosition(context: Context, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val x = sp.getInt(KEY_HUD_X, defaultX)
        val y = sp.getInt(KEY_HUD_Y, defaultY)
        return x to y
    }

    /**
     * 檢查系統 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 中是否已開啟本無障礙服務。
     */
    fun isAccessibilityServiceEnabledInSettings(context: Context): Boolean {
        return runCatching {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            if (enabledServices.isBlank()) return false
            val expectedFull = ComponentName(context.packageName, VisionAccessibilityService::class.java.name).flattenToString()
            val expectedShort = ComponentName(context.packageName, VisionAccessibilityService::class.java.name).flattenToShortString()
            enabledServices.split(':').any { entry ->
                entry.equals(expectedFull, ignoreCase = true) ||
                    entry.equals(expectedShort, ignoreCase = true) ||
                    entry.endsWith("/${VisionAccessibilityService::class.java.name}", ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    fun shouldAutoAttachHud(context: Context): Boolean {
        return PlayStoreComplianceHelper.hasAcceptedProminentDisclosure(context) &&
            isHudPreferredEnabled(context)
    }
}
