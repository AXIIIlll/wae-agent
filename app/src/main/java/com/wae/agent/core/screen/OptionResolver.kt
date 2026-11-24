package com.wae.agent.core.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.wae.agent.gen5.NodeRegistry
import com.wae.core.screen.OptionExtractor
import com.wae.core.screen.ScreenModel
import com.wae.core.screen.ScreenOption

/**
 * 把「選項」變成實際動作——簡化器的反向定位。
 *
 * 順序刻意是「節點優先、座標退路」：
 *  1. 用選項的 id 重新掃一次當前畫面，拿到對應的控制項 key
 *  2. `NodeRegistry.get(key)` 取到**活的節點** → 直接 performAction（沒有座標過期問題）
 *  3. 真的拿不到節點（例如 OCR／圖示候選）才用那一幀的像素座標做手勢點擊
 *
 * 三種失敗都回 false，呼叫端要當成「這一步沒做成」，不要假裝成功。
 */
object OptionResolver {

    /** 從目前畫面取出簡化後的模型（給除錯輸出與決策層用）。 */
    fun snapshot(
        service: AccessibilityService,
        packageName: String,
        fingerprint: String,
        controls: List<com.wae.core.model.Control>,
        screenHeightPx: Int
    ): ScreenModel = OptionExtractor.from(packageName, fingerprint, controls, screenHeightPx)

    /**
     * 點擊某個選項。
     *
     * @param optionId [ScreenModel.options] 裡的 id
     * @param controls 目前這一幀的控制項（必須與產生 optionId 的那一幀一致或高度相似）
     */
    fun click(service: AccessibilityService, controls: List<com.wae.core.model.Control>, optionId: String): Boolean {
        val control = OptionExtractor.locate(controls, optionId)
        if (control == null) {
            Log.i("WAE_OPTION", "click '$optionId' → 目前畫面上找不到這個選項")
            return false
        }
        // 1) 節點優先
        val node = com.wae.core.util.Degrade.orNull("option:node") { NodeRegistry.get(control.key) }
        if (node != null) {
            val ok = com.wae.core.util.Degrade.orElse("option:performClick", false) {
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (ok) {
                Log.i("WAE_OPTION", "click '$optionId' → node('${control.label}')")
                return true
            }
        }
        // 2) 座標退路
        val cx = ((control.bounds.left + control.bounds.right) / 2).toFloat()
        val cy = ((control.bounds.top + control.bounds.bottom) / 2).toFloat()
        val tapped = com.wae.core.util.Degrade.orElse("option:tapGesture", false) {
            val path = Path().apply { moveTo(cx, cy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            service.dispatchGesture(gesture, null, null)
        }
        Log.i(
            "WAE_OPTION",
            "click '$optionId' → " + (if (tapped) "gesture@($cx,$cy)" else "失敗（沒有節點也沒有手勢）")
        )
        return tapped
    }

    /** 只查詢、不點擊：給除錯或決策層確認用。 */
    fun find(controls: List<com.wae.core.model.Control>, optionId: String): ScreenOption? =
        OptionExtractor.scan("", "", controls, 0).model.find(optionId)
}
