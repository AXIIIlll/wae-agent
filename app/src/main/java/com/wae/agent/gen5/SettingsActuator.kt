package com.wae.agent.gen5

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 系統設定操作（D10）。
 *
 * 誠實邊界（Android 10+）：
 *  - **亮度**：有 WRITE_SETTINGS 就能改，而且可以**讀回確認**（真正的驗證）。
 *  - **Wi-Fi／藍牙／飛航**：第三方不能直接切，只能開面板／設定頁讓使用者自己切。
 *    所以 Agent 會回 `ABSTAIN(panel_opened)`，卡片上寫「幫你打開了，請你切一下」。
 */
object SettingsActuator {

    private const val BRIGHTNESS_STEP = 30
    private const val BRIGHTNESS_MIN = 1
    private const val BRIGHTNESS_MAX = 255

    /** 回傳 true 代表「真的改了而且讀回一致」。 */
    fun adjustBrightness(context: Context, direction: String): Boolean {
        if (!Settings.System.canWrite(context)) return false
        val resolver = context.contentResolver
        return runCatching {
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, DEFAULT_BRIGHTNESS)
            val delta = when (direction) {
                "up" -> BRIGHTNESS_STEP
                "down" -> -BRIGHTNESS_STEP
                else -> return false
            }
            val target = (current + delta).coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX)
            val written = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
            val readBack = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            written && readBack == target
        }.getOrDefault(false)
    }

    /** 開亮度設定頁（沒有 WRITE_SETTINGS 時的退路）。 */
    fun openDisplaySettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_DISPLAY_SETTINGS))

    /** 開系統面板（wifi／bluetooth／airplane）；成功只代表「開了」。 */
    fun openPanel(context: Context, key: String): Boolean {
        val intent = when (key) {
            "wifi" -> Intent(ACTION_WIFI_PANEL)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            else -> return false
        }
        return start(context, intent)
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false).let { ok ->
        // 有些機型沒有 WIFI panel：退回 Wi-Fi 設定頁
        if (!ok && intent.action == ACTION_WIFI_PANEL) {
            start(context, Intent(Settings.ACTION_WIFI_SETTINGS))
        } else {
            ok
        }
    }

    private const val ACTION_WIFI_PANEL = "android.settings.panel.action.WIFI"
    private const val DEFAULT_BRIGHTNESS = 128
}
