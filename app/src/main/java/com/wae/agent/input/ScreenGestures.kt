package com.wae.agent.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import com.wae.agent.core.NodeOps
import com.wae.agent.core.runtime.GesturePaths
import com.wae.agent.value.ValueAdjustIo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 螢幕手勢原語（座標層）。
 *
 * 從 `WaeVisionAccessibilityService`（3,951 行）抽出來的第一塊「執行」邏輯：
 * 這裡只做「畫一條路徑、送出去、等結果」，**不判斷該不該點、也不驗證後果**。
 * 判斷留給 Planner／StepActor，驗證留給 EffectVerifier —— 這樣這一層永遠只有一種失敗模式。
 */
object ScreenGestures {

    /** 單點輕觸（65ms）。只有 onCompleted 才算成功；被使用者觸控打斷 = 失敗。 */
    fun tap(service: AccessibilityService, x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                lineTo(x.toFloat(), y.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val latch = CountDownLatch(1)
            val completed = AtomicBoolean(false)
            val dispatched = NodeOps.dispatch(
                service,
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        latch.countDown()
                    }
                }
            )
            if (dispatched) {
                latch.await(300L, TimeUnit.MILLISECONDS)
                completed.get()
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 往下滑半個螢幕（列表前進）。 */
    fun scrollDownHalf(service: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.72f
        val endY = dm.heightPixels * 0.28f
        return try {
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 280L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            NodeOps.dispatch(service, gesture)
        } catch (_: Throwable) {
            false
        }
    }

    /** 往上滑半個螢幕（列表回退／回頂）。 */
    fun scrollUp(service: AccessibilityService): Boolean {
        val dm = service.resources.displayMetrics
        return ValueAdjustIo.dispatchStroke(
            service,
            GesturePaths.scrollUp(dm.widthPixels, dm.heightPixels)
        )
    }

    /** 水平滑動（先右後左各一次由呼叫端決定）。 */
    fun swipeHorizontal(service: AccessibilityService, leftToRight: Boolean): Boolean {
        val dm = service.resources.displayMetrics
        return ValueAdjustIo.dispatchStroke(
            service,
            GesturePaths.swipeHorizontal(dm.widthPixels, dm.heightPixels, leftToRight = leftToRight)
        )
    }

    /** 長按指定座標（多選／拖曳／上下文菜單）。 */
    fun longPress(service: AccessibilityService, x: Int, y: Int): Boolean =
        ValueAdjustIo.dispatchStroke(service, GesturePaths.longPress(x, y))
}
