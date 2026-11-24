package com.wae.agent.core.debug

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 測試用的「全螢幕最上層大字」。
 *
 * 為什麼要這個：人工驗證時（一題一題看畫面）必須知道「現在跑的是第幾題」，
 * 但我們的 App 執行時會退到背景、被測 App 跑到前景，App 內的文字就看不到了。
 * 無障礙服務可以用 `TYPE_ACCESSIBILITY_OVERLAY` 畫在所有 App 之上，
 * 而且不需要額外權限。
 *
 * 用法（adb）：
 * ```
 * adb shell am broadcast -a com.wae.agent.DEBUG_HUD --es text "001 打開設定"
 * adb shell am broadcast -a com.wae.agent.DEBUG_HUD --es text ""     # 清除
 * ```
 *
 * 只有 debug 意圖：文字只來自廣播，不影響任何決策與畫面流程。
 */
object DebugHud {

    private const val TAG = "WAE_HUD"
    private const val ACTION = "com.wae.agent.DEBUG_HUD"

    private var service: AccessibilityService? = null
    private var receiver: BroadcastReceiver? = null
    private var view: LinearLayout? = null
    private var wm: WindowManager? = null

    fun attach(svc: AccessibilityService) {
        // 只在 debuggable 建置生效：release 版不掛這個廣播接收器，
        // 否則任何 App 都能送廣播把文字畫到所有 App 之上。
        val debuggable = (svc.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        service = svc
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val text = intent?.getStringExtra("text")?.trim().orEmpty()
                if (text.isEmpty()) hide() else show(text)
            }
        }
        receiver = r
        com.wae.core.util.Degrade.orNull("hud:register") {
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                svc.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                svc.registerReceiver(r, filter)
            }
        }
    }

    fun detach() {
        com.wae.core.util.Degrade.orNull("hud:unregister") { receiver?.let { service?.unregisterReceiver(it) } }
        receiver = null
        hide()
        service = null
    }

    fun show(text: String) {
        val svc = service ?: return
        wm = wm ?: (svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        val manager = wm ?: return

        val label = TextView(svc).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(28, 18, 28, 18)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        val box = view ?: LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC101018"))
            addView(label)
        }
        if (view == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 120
            }
            com.wae.core.util.Degrade.orNull("hud:addView") { manager.addView(box, params) }
            view = box
        } else {
            // 已經存在：換掉文字就好（避免每次重加視窗造成閃爍）
            val boxView = view!!
            boxView.removeAllViews()
            boxView.addView(label)
        }
    }

    fun hide() {
        val v = view ?: return
        com.wae.core.util.Degrade.orNull("hud:removeView") { wm?.removeView(v) }
        view = null
    }
}
