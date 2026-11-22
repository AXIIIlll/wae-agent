package com.wae.core.effect

import com.wae.core.model.ControlKind
import com.wae.core.model.Effect
import com.wae.core.model.Frame
import com.wae.core.pipeline.AppResolver
import com.wae.core.system.SystemTargets
import com.wae.core.util.TextHash

/**
 * 效果層驗證（E5）：**最後再看一次目標真的達成了沒**。
 *
 * 為什麼需要：多步計畫的「每一步都成功」不代表「效果成立」。
 * 回傳 `null` 代表「從畫面驗不出來」（開 App 的 pkg 由 post 管、面板與亮度由 API 管），
 * 這種情況不偽裝成功也不偽裝失敗。
 */
object EffectVerifier {

    private const val PREFIX_QUERY = "query:"
    private const val PREFIX_TOGGLE = "toggle:"
    private const val PREFIX_APP = "app:"
    private const val PREFIX_PANEL = "panel:"
    private const val PREFIX_BRIGHTNESS = "brightness:"

    fun verify(effect: Effect, frame: Frame, appResolver: AppResolver? = null): Boolean? {
        val target = effect.target
        return when {
            target.startsWith(PREFIX_QUERY) -> queryVisible(target.removePrefix(PREFIX_QUERY), frame)
            target.startsWith(PREFIX_TOGGLE) -> toggleState(effect, target.removePrefix(PREFIX_TOGGLE), frame)
            target.startsWith(PREFIX_APP) -> {
                // 用「同一個 App 家族」判定，而不是精確 pkg：
                // 實測開「設定」後前景可能是 com.google.android.settings.intelligence，
                // 精確比對會把成功的啟動判成失敗、然後無限重試。
                val label = target.removePrefix(PREFIX_APP)
                appResolver?.matches(frame.pkg, label)
            }
            // 面板／亮度由 API 或使用者負責，畫面驗不出來
            target.startsWith(PREFIX_PANEL) -> null
            target.startsWith(PREFIX_BRIGHTNESS) -> null
            else -> null
        }
    }

    /**
     * 搜尋字串真的在畫面上/欄位裡。
     *
     * 有輸入框時**只認欄位裡的文字**：
     * 否則「設定頁的建議清單剛好有這個字」就會被誤判成搜尋成功（實測踩過）。
     * 沒有輸入框（例如結果頁、聊天視窗）才退回到「畫面上看得到」。
     */
    private fun queryVisible(text: String, frame: Frame): Boolean {
        if (text.isBlank()) return true
        val needle = TextHash.normalize(text)
        val fields = frame.controls.filter { it.kind == ControlKind.FIELD }
        if (fields.isNotEmpty()) {
            return fields.any { c ->
                TextHash.normalize(c.label).contains(needle) ||
                    TextHash.normalize(c.state.text.orEmpty()).contains(needle)
            }
        }
        return frame.controls.any { c ->
            TextHash.normalize(c.label).contains(needle) ||
                TextHash.normalize(c.state.text.orEmpty()).contains(needle)
        }
    }

    /**
     * 開關狀態比對：畫面上找得到對應的開關時才驗，找不到回 `null`。
     * （Wi-Fi／藍牙這類系統開關多半不在畫面上，會落到 `null`。）
     */
    private fun toggleState(effect: Effect, theme: String, frame: Frame): Boolean? {
        if (SystemTargets.normalize(theme) != null) return null
        val wanted = effect.value.equals("on", ignoreCase = true)
        val needle = TextHash.normalize(theme)
        val control = frame.controls.firstOrNull {
            it.kind == ControlKind.TOGGLE && TextHash.normalize(it.label).contains(needle)
        } ?: return null
        val actual = control.state.on ?: control.state.checked ?: return null
        return actual == wanted
    }
}
