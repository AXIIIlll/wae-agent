package com.example.newandroidapp.stability

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 【狀態列與系統導覽列「真人穿透 ＋ AI 絕對禁區」四層攔截器 (SystemBarBlockerGuard)】
 *
 * 核心目標：讓真人使用者 100% 隨時自由點擊安卓最下方三顆系統導覽鍵（返回 / 首頁 / 多工）與下拉狀態列，
 * 同時確保 AI 100% 絕對看不到、選不到、也點不到底部三鍵與頂部狀態列：
 * 1. 【第 1 層：真人觸控 100% 物理穿透 (`FLAG_NOT_TOUCHABLE`)】：
 *    頂底防護邊界指示條全面加上 `WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE`，
 *    真人手指點擊底部三顆系統導覽鍵時 100% 直接穿透給系統處理，絕不阻擋真人操作。
 * 2. 【第 2 層：AI 視窗樹與無障礙節點剔除 (`ZOrderTreeCollector`)】：
 *    自動過濾 `com.android.systemui` 之 `TYPE_SYSTEM` 頂部狀態列與底部系統導覽列視窗及節點。
 * 3. 【第 3 層：全螢幕截圖 OCR / Sobel 邊緣偵測禁區剔除 (`AiVisionPipeline`)】：
 *    強制剔除任何落入頂部狀態列或底部三鍵區間之視覺補盲節點，避免 AI 從螢幕截圖將底部三顆圖示誤認成按鈕。
 * 4. 【第 4 層：AI 手勢夾限與節點點擊硬攔截 (`VisionAccessibilityService`)】：
 *    所有 AI 派發之點擊、輸入、滑動、毫秒重鎖定與 `ACTION_CLICK` 補償均強制攔截並夾限於安全區內，AI 物理上不可能觸碰底部三鍵。
 */
object SystemBarBlockerGuard {

    @Volatile
    var isBlockerEnabled: Boolean = true

    @Volatile
    var isPhysicalTouchShieldEnabled: Boolean = true

    @Volatile
    var blockedNodeCountLastScan: Int = 0

    @Volatile
    var clampedGestureCount: Int = 0

    @Volatile
    var interceptedAiActionCount: Int = 0

    private var topStatusBarShieldView: View? = null
    private var bottomNavBarShieldView: View? = null

    /**
     * 動態計算實機頂部狀態列（Status Bar）像素高度
     */
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    fun getStatusBarHeightPx(context: Context?, screenHeight: Int): Int {
        val defaultPx = (screenHeight * 0.042f).toInt().coerceIn(64, 136)
        if (context == null) return defaultPx

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val insets = wm?.currentWindowMetrics?.windowInsets
                    ?.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                    )
                if (insets != null && insets.top > 0) {
                    return insets.top.coerceIn(48, 200)
                }
            }
        }

        val resId = runCatching {
            context.resources.getIdentifier("status_bar_height", "dimen", "android")
        }.getOrDefault(0)
        if (resId > 0) {
            val h = runCatching { context.resources.getDimensionPixelSize(resId) }.getOrDefault(0)
            if (h > 0) return h.coerceIn(48, 180)
        }
        return defaultPx
    }

    /**
     * 動態計算實機底部系統導覽列（Navigation Bar / 手勢橫條）像素高度
     */
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    fun getNavigationBarHeightPx(context: Context?, screenHeight: Int): Int {
        val defaultPx = (screenHeight * 0.055f).toInt().coerceIn(72, 160)
        if (context == null) return defaultPx

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val insets = wm?.currentWindowMetrics?.windowInsets
                    ?.getInsetsIgnoringVisibility(
                        WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement()
                    )
                if (insets != null && insets.bottom > 0) {
                    return insets.bottom.coerceIn(48, 240)
                }
            }
        }

        val resId = runCatching {
            context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        }.getOrDefault(0)
        if (resId > 0) {
            val h = runCatching { context.resources.getDimensionPixelSize(resId) }.getOrDefault(0)
            if (h > 0) return h.coerceIn(48, 220)
        }
        return defaultPx
    }

    /**
     * 判斷某個 AccessibilityWindowInfo 是否屬於頂部狀態列或底部系統導覽列視窗
     */
    fun isSystemBarWindow(
        win: AccessibilityWindowInfo,
        @Suppress("UNUSED_PARAMETER") screenWidth: Int,
        screenHeight: Int,
        statusBarHeightPx: Int,
        navBarHeightPx: Int
    ): Boolean {
        if (!isBlockerEnabled) return false
        val pkg = win.root?.packageName?.toString().orEmpty()
        if (win.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
            val b = Rect()
            win.getBoundsInScreen(b)
            // 位於頂部狀態列或底部導覽列之系統視窗，或 com.android.systemui 系統列
            if (pkg == "com.android.systemui" ||
                b.bottom <= statusBarHeightPx + 24 ||
                b.top >= screenHeight - navBarHeightPx - 24
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 判斷某個節點的邊界是否落在頂部狀態列或底部系統導覽列禁區內
     */
    fun isNodeInSystemBarZone(
        bounds: Rect,
        packageName: String,
        screenHeight: Int,
        statusBarHeightPx: Int,
        navBarHeightPx: Int
    ): Boolean {
        if (!isBlockerEnabled) return false
        val cy = (bounds.top + bounds.bottom) / 2
        // 若是 SystemUI 套件，或是中心點完全落入頂部狀態列 / 底部導覽列禁區
        if (packageName == "com.android.systemui") return true
        if (bounds.bottom <= statusBarHeightPx + 6 || cy < statusBarHeightPx) return true
        if (bounds.top >= screenHeight - navBarHeightPx - 6 || cy > screenHeight - navBarHeightPx) return true
        return false
    }

    /**
     * 判斷某個 Y 軸座標是否落入頂部狀態列或底部系統導覽列（三鍵區）之 AI 絕對禁區
     */
    fun isCoordinateInForbiddenSystemBarZone(
        context: Context?,
        y: Int,
        screenHeight: Int
    ): Boolean {
        if (!isBlockerEnabled) return false
        val topLimit = getStatusBarHeightPx(context, screenHeight) + 10
        val bottomLimit = (screenHeight - getNavigationBarHeightPx(context, screenHeight) - 10)
            .coerceAtLeast(topLimit + 40)
        return y < topLimit || y > bottomLimit
    }

    /**
     * 將任意 AI 點擊或輸入座標強制夾限於「排除狀態列與導覽列三鍵區後」的安全操作區內
     */
    fun clampPointToSafeAppZone(
        context: Context?,
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Point, Boolean> {
        val ix = x.toInt().coerceIn(8, (screenWidth - 8).coerceAtLeast(8))
        val iy = y.toInt()
        if (!isBlockerEnabled) {
            return Point(ix, iy.coerceIn(8, (screenHeight - 8).coerceAtLeast(8))) to false
        }
        val topLimit = getStatusBarHeightPx(context, screenHeight) + 12
        val bottomLimit = (screenHeight - getNavigationBarHeightPx(context, screenHeight) - 12)
            .coerceAtLeast(topLimit + 40)

        val safeY = iy.coerceIn(topLimit, bottomLimit)
        val wasClamped = safeY != iy
        if (wasClamped) {
            clampedGestureCount++
            interceptedAiActionCount++
        }
        return Point(ix, safeY) to wasClamped
    }

    @Suppress("DEPRECATION")
    fun getRealScreenSizePx(context: Context?, fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
        if (context != null) {
            runCatching {
                val dmgr = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                val display = dmgr?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val realDm = android.util.DisplayMetrics()
                    display.getRealMetrics(realDm)
                    if (realDm.widthPixels > 0 && realDm.heightPixels > 0) {
                        return realDm.widthPixels to realDm.heightPixels
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    val bounds = wm?.maximumWindowMetrics?.bounds ?: wm?.currentWindowMetrics?.bounds
                    if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                        return bounds.width() to bounds.height()
                    }
                }
            }
        }
        return fallbackW to fallbackH
    }

    /**
     * 掛載或更新頂部狀態列（Status Bar）與底部導覽列（Navigation Bar）AI 禁區純白 Letterbox 遮罩層：
     * 1. 取得真實物理螢幕尺寸（例如 1080x2340，而非被扣除系統列後的 2116）
     * 2. 設定 LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS，確保頂部 89px 與底部 135px 精準貼齊物理螢幕最頂端與最底端
     * 3. 全程加上 FLAG_NOT_TOUCHABLE，讓真人手指點擊底部三鍵與頂部狀態列時 100% 穿透無阻礙！
     */
    fun syncPhysicalShieldOverlays(
        context: Context,
        wm: WindowManager?,
        screenWidth: Int,
        screenHeight: Int,
        shouldShow: Boolean
    ) {
        if (wm == null) return
        if (!shouldShow || !isBlockerEnabled || !isPhysicalTouchShieldEnabled) {
            removePhysicalShieldOverlays(wm)
            return
        }

        val (realW, realH) = getRealScreenSizePx(context, screenWidth, screenHeight)
        // 手機橫向旋轉時（realW > realH）無需掛載上下遮罩，直接移除避免方向錯位
        if (realW > realH) {
            removePhysicalShieldOverlays(wm)
            return
        }
        val statusH = getStatusBarHeightPx(context, realH)
        val navH = getNavigationBarHeightPx(context, realH)
        val passthroughFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val topLp = WindowManager.LayoutParams(
            realW,
            statusH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            passthroughFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (topStatusBarShieldView == null) {
            val topView = SystemBarShieldStripView(context, isTopBar = true)
            runCatching {
                wm.addView(topView, topLp)
                topStatusBarShieldView = topView
            }
        } else {
            topStatusBarShieldView?.visibility = View.VISIBLE
            runCatching { wm.updateViewLayout(topStatusBarShieldView, topLp) }
        }

        val bottomLp = WindowManager.LayoutParams(
            realW,
            navH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            passthroughFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (bottomNavBarShieldView == null) {
            val bottomView = SystemBarShieldStripView(context, isTopBar = false)
            runCatching {
                wm.addView(bottomView, bottomLp)
                bottomNavBarShieldView = bottomView
            }
        } else {
            bottomNavBarShieldView?.visibility = View.VISIBLE
            runCatching { wm.updateViewLayout(bottomNavBarShieldView, bottomLp) }
        }
    }

    fun setPhysicalShieldTemporaryVisibility(visible: Boolean) {
        val v = if (visible && isBlockerEnabled && isPhysicalTouchShieldEnabled) View.VISIBLE else View.INVISIBLE
        topStatusBarShieldView?.visibility = v
        bottomNavBarShieldView?.visibility = v
    }

    fun removePhysicalShieldOverlays(wm: WindowManager?) {
        if (wm == null) return
        topStatusBarShieldView?.let { v ->
            v.visibility = View.GONE
            runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
        }
        bottomNavBarShieldView?.let { v ->
            v.visibility = View.GONE
            runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
        }
        topStatusBarShieldView = null
        bottomNavBarShieldView = null
    }

    fun toggleBlocker(): Boolean {
        isBlockerEnabled = !isBlockerEnabled
        isPhysicalTouchShieldEnabled = isBlockerEnabled
        return isBlockerEnabled
    }

    fun statusSummary(context: Context?, screenHeight: Int = 2400): String {
        val topH = getStatusBarHeightPx(context, screenHeight)
        val botH = getNavigationBarHeightPx(context, screenHeight)
        val state = if (isBlockerEnabled) "已啟用（真人三鍵穿透 / AI 禁區隔離）" else "已關閉"
        return "頂底系統欄防護：$state (頂部 ${topH}px / 底部 ${botH}px | 已剔除系統列節點 $blockedNodeCountLastScan 個 | AI 手勢夾限 $clampedGestureCount 次)"
    }

    private class SystemBarShieldStripView(
        context: Context,
        @Suppress("UNUSED_PARAMETER") private val isTopBar: Boolean
    ) : View(context) {
        init {
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 完全透明（Color.TRANSPARENT），不繪製任何實心白條或邊框，
            // 僅透過底層座標與節點過濾達成 AI 防誤觸，且旋轉時不遮擋任何畫面
        }
    }
}
