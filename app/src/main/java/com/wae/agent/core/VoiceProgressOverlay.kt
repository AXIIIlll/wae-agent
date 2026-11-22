package com.wae.agent.core

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.wae.agent.core.WaeVisionAccessibilityService

/**
 * 語音任務的極簡進度條（P0 #5）。
 *
 * 語音指令送出後助理 UI 會關閉，使用者看不到「它現在在做什麼」；
 * 這裡用無障礙 overlay 顯示一行狀態（點擊穿透、不干擾）。
 * 只在語音來源的任務顯示；結束後自動消失。
 */
class VoiceProgressOverlay(private val service: WaeVisionAccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    private fun dp(v: Int): Int =
        (service.resources.displayMetrics.density * v).toInt()

    private fun windowManager(): WindowManager? =
        service.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager

    fun show(text: String) {
        main.post {
            runCatching {
                val wm = windowManager() ?: return@runCatching
                val tv = view ?: TextView(service).apply {
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = GradientDrawable().apply {
                        setColor(0xD91F2937.toInt())
                        cornerRadius = dp(18).toFloat()
                    }
                    maxLines = 2
                }.also { view = it }
                tv.text = text.take(40)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(72)
                }
                if (tv.parent == null) wm.addView(tv, lp) else wm.updateViewLayout(tv, lp)
                tv.visibility = View.VISIBLE
            }
        }
    }

    fun update(text: String) {
        main.post { runCatching { view?.text = text.take(40) } }
    }

    fun hide(delayMs: Long = 600L) {
        main.postDelayed({
            runCatching {
                val tv = view ?: return@runCatching
                windowManager()?.removeView(tv)
                view = null
            }
        }, delayMs)
    }
}
