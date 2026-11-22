package com.wae.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.wae.agent.core.WaeVisionAccessibilityService
import com.wae.agent.ui.ghost.GhostView

/**
 * WAE 懸浮窗（HTML/WebView 版）。
 *
 * 為什麼用 WebView + HTML：
 *  - UI 迭代速度：改 CSS 就生效，不用每次重畫原生 layout
 *  - 可直接採用 MIT 開源玻璃 UI（Farvist）當基底，外觀接近 iOS 質感的玻璃面板
 *  - 與原生溝通只透過一條窄介面（WaeBridge），不把能力擴散進 WebView
 *
 * 視窗型別：TYPE_ACCESSIBILITY_OVERLAY（無障礙服務已啟用時可用，不需額外權限）。
 * 內容：assets/ui/overlay/index.html（離線、blockNetworkLoads=true，不連網）。
 */
class FloatingOverlay(private val service: WaeVisionAccessibilityService) {

    companion object {
        private const val TAG = "WAE_OVERLAY"
        private const val PANEL_WIDTH_DP = 376f
        private const val MAX_WIDTH_DP = 420f
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var webView: WebView? = null
    private var container: FrameLayout? = null
    private var ghost: GhostView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var collapsed = false

    // 拖曳狀態（由 JS 的 touch 事件驅動）
    private var dragStartX = 0
    private var dragStartY = 0

    fun isShowing(): Boolean = attached

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        if (attached) return
        val ctx: Context = service
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val density = ctx.resources.displayMetrics.density
        val screenW = ctx.resources.displayMetrics.widthPixels
        val maxW = (MAX_WIDTH_DP * density).toInt()
        val width = minOf((PANEL_WIDTH_DP * density).toInt(), maxW, screenW - (12 * density).toInt())

        val view = WebView(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // WebView 在硬體層常常把頁面底畫成半透明白，這裡強制軟體層才真的透
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true   // 隱私保證：UI 頁面不連網
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                textZoom = 100
            }
            addJavascriptInterface(Bridge(), "WaeBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "overlay page loaded: $url")
                }
            }
            loadUrl("file:///android_asset/ui/overlay/index.html")
        }

        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            accessibilityOverlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - width - (10 * density).toInt()).coerceAtLeast(0)
            y = (86 * density).toInt()
            // 讓 IME 能與懸浮窗共存（避免輸入時整個視窗被推走）
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val box = FrameLayout(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val bubbleGhost = GhostView(ctx).apply {
            animated = true
            bloom = 0.8f
            visibility = View.GONE
            setOnClickListener { expandPanel() }
            setOnLongClickListener { expandPanel(); true }
        }
        box.addView(bubbleGhost, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        container = box
        ghost = bubbleGhost
        val container = box

        try {
            wm.addView(container, lp)
            windowManager = wm
            webView = view
            params = lp
            attached = true
            Log.i(TAG, "overlay shown width=${lp.width} x=${lp.x} y=${lp.y}")
        } catch (t: Throwable) {
            Log.e(TAG, "overlay addView failed: ${t.message}")
            runCatching { wm.removeView(container) }
        }
    }

    /** TYPE_ACCESSIBILITY_OVERLAY（API 22+；minSdk 30 → 一定有） */
    private fun accessibilityOverlayType(): Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    fun hide() {
        if (!attached) return
        attached = false
        val wm = windowManager
        val view = webView?.parent as? View
        runCatching {
            if (view != null) wm?.removeView(view)
        }
        runCatching { webView?.destroy() }
        webView = null
        windowManager = null
        params = null
        Log.i(TAG, "overlay hidden")
    }

    fun destroy() {
        hide()
    }

    /** 收合成小幽靈時把視窗一起縮小，避免透明區域擋住底下的 App。 */
    private fun applyCollapsedLayout(collapsed: Boolean) {
        val lp = params ?: return
        val wm = windowManager ?: return
        val box = container ?: return
        val d = service.resources.displayMetrics.density
        mainHandler.post {
            runCatching {
                // 收合時 WebView 整個隱藏，只畫原生的半透明小幽靈（不會有網頁底色方框）
                webView?.visibility = if (collapsed) View.GONE else View.VISIBLE
                ghost?.visibility = if (collapsed) View.VISIBLE else View.GONE
                lp.width = ((if (collapsed) 104f else PANEL_WIDTH_DP) * d).toInt()
                lp.height = if (collapsed) (104f * d).toInt() else WindowManager.LayoutParams.WRAP_CONTENT
                if (collapsed) {
                    val screenW = service.resources.displayMetrics.widthPixels
                    lp.x = (screenW - lp.width - (10 * d).toInt()).coerceAtLeast(0)
                }
                wm.updateViewLayout(box, lp)
                params = lp
            }
        }
    }

    /** 從泡泡展開面板（長按或點一下泡泡）。 */
    private fun expandPanel() {
        applyCollapsedLayout(false)
        mainHandler.postDelayed({
            runCatching {
                webView?.evaluateJavascript("window.__waeExpand && window.__waeExpand();", null)
            }
        }, 120L)
    }

    /** 由 JS 拖曳觸發：把視窗位移 (dx, dy) */
    fun moveBy(dx: Float, dy: Float) {
        val lp = params ?: return
        val wm = windowManager ?: return
        val view = webView?.parent as? View ?: return
        mainHandler.post {
            runCatching {
                lp.x = (lp.x + dx.toInt()).coerceAtLeast(0)
                lp.y = (lp.y + dy.toInt()).coerceAtLeast(0)
                wm.updateViewLayout(view, lp)
            }
        }
    }

    // ── JS ↔ 原生橋接（刻意保持窄介面）──────────────────────────
    inner class Bridge {
        @JavascriptInterface
        fun execute(command: String?) {
            val cmd = command?.trim().orEmpty()
            if (cmd.isEmpty()) return
            Log.i(TAG, "execute: $cmd")
            service.overlayExecuteCommand(cmd)
        }

        @JavascriptInterface
        fun cancel() {
            service.requestCancel()
        }

        @JavascriptInterface
        fun refresh() {
            service.capturePerceptionSnapshotAsync(null)
        }

        @JavascriptInterface
        fun close() {
            mainHandler.post { hide() }
        }

        @JavascriptInterface
        fun setCollapsed(value: Boolean) {
            collapsed = value
            Log.i(TAG, "collapsed=$value")
            applyCollapsedLayout(value)
        }

        @JavascriptInterface
        fun dragStart() {
            val lp = params ?: return
            dragStartX = lp.x
            dragStartY = lp.y
        }

        @JavascriptInterface
        fun dragBy(dxTotal: Float, dyTotal: Float) {
            val lp = params ?: return
            val wm = windowManager ?: return
            val view = webView?.parent as? View ?: return
            mainHandler.post {
                runCatching {
                    lp.x = (dragStartX + dxTotal.toInt()).coerceAtLeast(0)
                    lp.y = (dragStartY + dyTotal.toInt()).coerceAtLeast(0)
                    wm.updateViewLayout(view, lp)
                }
            }
        }

        @JavascriptInterface
        fun stateJson(): String = service.overlayStateJson()

        @JavascriptInterface
        fun log(message: String?) {
            Log.i(TAG, "js: ${message.orEmpty().take(200)}")
        }

        @JavascriptInterface
        fun version(): String = "wae-overlay-1"
    }
}
