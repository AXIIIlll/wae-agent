package com.wae.agent.gen5

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 裝置 I/O 的唯一最低層（I1 的終點）。
 *
 * 為什麼要有這一層：
 *  - 之前有兩條路各碰裝置（Gen-5 的 `AndroidActuator`、舊 LLM 路徑的 `NodeOps`），
 *    座標保護、例外處理、記錄都各寫一份，出過「負座標讓 `GestureDescription` 崩潰」。
 *  - 現在全部收斂成 **2 個原始操作**：`perform`（節點動作）與 `dispatch`（手勢）。
 *    其他（tap／swipe／gestureAt）都建在這兩個上面，所以守門只會看到 2 個點。
 *
 * 這一層**不做決策**：不挑元素、不判斷成不成功、不重試。那是上層的事。
 */
object DeviceIO {

    fun perform(node: AccessibilityNodeInfo, action: Int, arguments: Bundle? = null): Boolean =
        runCatching { node.performAction(action, arguments) }.getOrDefault(false)

    fun dispatch(
        service: AccessibilityService,
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback? = null
    ): Boolean = runCatching { service.dispatchGesture(gesture, callback, null) }.getOrDefault(false)

    /** 點節點中心（節點本身不能點時的退路）。 */
    fun tap(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }.getOrNull() ?: return false
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return gestureAt(service, rect.exactCenterX(), rect.exactCenterY())
    }

    /** 點一個座標；負座標直接拒絕（呼叫端不必各寫一次）。 */
    fun gestureAt(service: AccessibilityService, x: Float, y: Float, durationMs: Long = TAP_MS): Boolean {
        if (x < 0f || y < 0f) return false
        val path = Path().apply { moveTo(x, y) }
        return dispatch(service, build(listOf(path), durationMs))
    }

    /** 直線拖曳（滑動、拉桿都用這個）。 */
    fun swipe(
        service: AccessibilityService,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = SWIPE_MS
    ): Boolean {
        if (fromX < 0f || fromY < 0f || toX < 0f || toY < 0f) return false
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatch(service, build(listOf(path), durationMs))
    }

    fun build(paths: List<Path>, durationMs: Long): GestureDescription {
        val builder = GestureDescription.Builder()
        for (path in paths) {
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
        }
        return builder.build()
    }

    private const val TAP_MS = 60L
    private const val SWIPE_MS = 220L
}
