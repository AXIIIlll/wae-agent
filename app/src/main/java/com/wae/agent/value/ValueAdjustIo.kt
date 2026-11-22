package com.wae.agent.value

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.wae.agent.gen5.DeviceIO

/**
 * 數值元件的低階 I/O（服務瘦身第九塊）。
 *
 * 兩個很小的動作，但都被「調數值」路徑共用：
 *  - `dispatchStroke`：把 ValueAdjustPlanner 算出的拖曳路徑送出去
 *  - `readValue`：先 refresh 再讀取，避免拿到快取舊值（讀舊值會誤判「拖曳沒效」）
 *
 * 抽出來的理由不是行數，是**讓「送出拖曳」只有一個地方**（I1 的延伸）。
 */
object ValueAdjustIo {

    fun dispatchStroke(service: AccessibilityService, stroke: com.wae.agent.core.runtime.GesturePaths.Stroke): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply {
                moveTo(stroke.startX, stroke.startY)
                lineTo(stroke.endX, stroke.endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, stroke.durationMs))
                .build()
            DeviceIO.dispatch(service, gesture)
        } catch (_: Throwable) {
            // 忽略：手勢參數異常（負座標／NaN）時不送出手勢，回 false 讓上層換路
            false
        }
    }

    /** 讀回節點目前的數值（先 refresh，避免拿到快取舊值）。 */
    fun readValue(node: AccessibilityNodeInfo): Float? = try {
        node.refresh()
        node.rangeInfo?.current
    } catch (_: Throwable) {
        // 忽略：節點可能已失效（TOCTOU），回 null 讓上層放棄這一次
        null
    }

}
