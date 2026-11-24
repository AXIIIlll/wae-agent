package com.wae.agent.core.system

import android.content.Context
import android.util.Log
import com.wae.agent.gen5.SettingsActuator
import com.wae.core.system.SystemTargets

/**
 * 「能用系統 API 就不要用畫面」的統一入口（值類動作）。
 *
 * 為什麼要有這一層：
 *  - 音量、亮度是**系統值**，不是某個 App 的畫面元件。用畫面找滑桿不只慢，
 *    還會因為找不到而亂點（實測「把音量調低」掃不到滑桿，最後退回桌面抽屜）。
 *  - 系統 API 可以**讀回確認**，是目前最可靠的成功判定。
 *
 * 方向的判定交給既有的資料層（`ValueIntent` 解析 `ui_value_adjust_lexicon.tsv`），
 * 這裡不另外寫一份方向詞表。
 */
object SystemValueBridge {

    /**
     * @return true＝已用系統 API 完成且讀回一致；false＝系統 API 試過但沒成功；
     *         null＝這個目標不屬於系統值，交給畫面流程。
     */
    fun tryApply(context: Context, goalText: String, payload: String): Boolean? {
        val text = goalText.ifBlank { payload }
        if (text.isBlank()) return null
        val target = SystemTargets.normalize(text) ?: return null
        if (target != SystemTargets.Target.VOLUME && target != SystemTargets.Target.BRIGHTNESS) {
            return null
        }

        // 方向用既有語彙庫（資料驅動）：ValueIntent 已把「調高／調低／最大／一半」解析成方向與比例
        val parsed = com.wae.agent.core.component.ValueIntent.parse(text)
        val direction = when {
            parsed.targetFraction != null && parsed.targetFraction!! >= 0.95f -> "max"
            parsed.targetFraction != null && parsed.targetFraction!! <= 0.05f -> "min"
            parsed.direction > 0 -> "up"
            parsed.direction < 0 -> "down"
            else -> return null
        }

        val ok = when (target) {
            SystemTargets.Target.VOLUME -> SettingsActuator.adjustVolume(context, direction)
            SystemTargets.Target.BRIGHTNESS -> SettingsActuator.adjustBrightness(context, direction)
            else -> return null
        }
        Log.i("WAE_SYSSYS", "system value target=$target dir=$direction ok=$ok text='$text'")
        return ok
    }
}
