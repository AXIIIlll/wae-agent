package com.wae.agent.gen5

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.wae.agent.vision.OmniParserIconDetector
import com.wae.core.model.Channel
import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.RectI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 圖示通道（A4）：沒有文字、沒有無障礙標籤的按鈕，用端側 YOLO 找出來。
 *
 * 存在的理由：相機快門、工具列、社群 App 的圖示按鈕，a11y 樹裡什麼都沒有。
 * 產出的 Control 沒有 label（本來就沒字），只有 bounds 與 kind=BUTTON；
 * 上層用「位置 + 型別」挑，而不是用文字比對。
 */
class IconChannel(private val service: AccessibilityService) {


    private val executor = Executors.newSingleThreadExecutor()

    // Android 對「截圖」有速率限制（連續呼叫會直接失敗）。
    // 同一幀內連續抓兩次沒有意義，所以短期快取結果，省下截圖配額。
    private var cachedAt = 0L
    private var cached: List<Control> = emptyList()

    /**
     * 能不能用圖示通道。
     *
     * 注意：`isReady` 只有 `ensureLoaded` 會被設起來——之前沒有人呼叫它，
     * 所以這條通道永遠是 0 個候選（相機快門也就永遠找不到）。
     * 這裡做成延遲載入：第一次要用時才把模型載進記憶體（之後就快取）。
     */
    fun isAvailable(): Boolean {
        if (!enabled) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!OmniParserIconDetector.isReady) {
            runCatching { OmniParserIconDetector.ensureLoaded(service.applicationContext) }
        }
        return OmniParserIconDetector.isReady
    }

    fun capture(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<Control> {
        if (!isAvailable()) return emptyList()

        val now = android.os.SystemClock.elapsedRealtime()
        if (cached.isNotEmpty() && now - cachedAt <= CACHE_TTL_MS) return cached

        val fresh = captureFresh(timeoutMs)
        if (fresh.isNotEmpty()) {
            cached = fresh
            cachedAt = now
            return fresh
        }
        // 截圖被限流時，回上一次的結果（比回空清單誠實：畫面通常還沒變）
        return if (cached.isNotEmpty() && now - cachedAt <= CACHE_STALE_MS) cached else emptyList()
    }

    private fun captureFresh(timeoutMs: Long): List<Control> {
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        runCatching {
                            bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            screenshot.hardwareBuffer.close()
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
        }.onFailure { latch.countDown() }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return emptyList()
        val shot = bitmap ?: return emptyList()
        return toControls(shot).also { runCatching { shot.recycle() } }
    }

    private fun toControls(shot: Bitmap): List<Control> {
        val boxes = runCatching {
            OmniParserIconDetector.detect(shot, shot.width, shot.height)
        }.getOrDefault(emptyList())
        android.util.Log.i(
            "WAE_VISION",
            "icon scan ${shot.width}x${shot.height} boxes=${boxes.size} " +
                boxes.take(6).joinToString(" ") { b ->
                    "(${b.x1.toInt()},${b.y1.toInt()},${b.x2.toInt()},${b.y2.toInt()}:${"%.2f".format(b.score)})"
                }
        )
        return boxes.take(MAX_ICONS).mapIndexed { index, box ->
            val left = box.x1.toInt().coerceAtLeast(0)
            val top = box.y1.toInt().coerceAtLeast(0)
            val right = box.x2.toInt().coerceAtLeast(left + 1)
            val bottom = box.y2.toInt().coerceAtLeast(top + 1)
            Control(
                key = "icon:$index:$left,$top,$right,$bottom",
                kind = ControlKind.BUTTON,
                label = "",
                textHash = "",
                bounds = RectI(left, top, right, bottom),
                enabled = true,
                source = Channel.ICON
            )
        }
    }

    companion object {
        /**
         * 圖示偵測是「小模型」而且**可以關**（計畫 §12）。
         * 關掉之後只認有文字的元件——誠實退化，不是壞掉。
         */
        @Volatile
        var enabled: Boolean = true

        const val DEFAULT_TIMEOUT_MS = 1_500L
        const val MAX_ICONS = 20
        /** 短時間內重複抓同一幀沒有意義（也省截圖配額）。 */
        const val CACHE_TTL_MS = 800L
        /** 截圖被限流時，這個時間內還可以用上一次的結果。 */
        const val CACHE_STALE_MS = 3_000L
    }
}
