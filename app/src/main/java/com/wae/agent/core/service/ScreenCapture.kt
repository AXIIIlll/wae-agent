package com.wae.agent.core.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.Display
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 畫面擷取（從 3,900 行的服務抽出來）。
 *
 * 為什麼獨立：這是「視覺通道」的唯一入口——OCR、圖示偵測、白圖守衛都靠它。
 * 它只依賴無障礙服務的 `takeScreenshot`，跟指令規劃完全無關，
 * 抽出來之後可以單獨閱讀、也比較好調參數。
 *
 * 走的是無障礙服務的 takeScreenshot（Service XML 已宣告 canTakeScreenshot），
 * 不需要 MediaProjection 授權。
 */
object ScreenCapture {

    /** 截圖回呼必須有自己的執行緒，不能佔用主執行緒（無障礙服務本來就不在主執行緒）。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wae-screenshot").apply { isDaemon = true }
    }

    /** 給 Agent 的感知用：截一張畫面給 OCR（覆蓋沒有無障礙樹的 App）。 */
    fun forPerception(service: AccessibilityService): Bitmap? =
        com.wae.core.util.Degrade.orNull("screenCapture:forPerception") { bitmap(service, 1520) }

    /**
     * 截取目前畫面並轉成可讀像素的 Bitmap。
     *
     * @param maxLongEdge 長邊上限；視覺模型的輸入是 640，超過沒有意義，先縮小可省記憶體與拷貝時間
     */
    fun bitmap(service: AccessibilityService, maxLongEdge: Int): Bitmap? {
        val hw = hardwareScreenshot(service) ?: return null
        return try {
            val wrapped = Bitmap.wrapHardwareBuffer(hw, ColorSpace.get(ColorSpace.Named.SRGB))
                ?: return null
            // HardwareBitmap 不能直接 getPixels，必須先複製成 ARGB_8888
            val argb = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val longEdge = kotlin.math.max(argb.width, argb.height)
            val out = if (longEdge <= maxLongEdge) {
                argb
            } else {
                val scale = maxLongEdge.toFloat() / longEdge.toFloat()
                val sw = (argb.width * scale).toInt().coerceAtLeast(1)
                val sh = (argb.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(argb, sw, sh, true)
                argb.recycle()
                scaled
            }
            // 空白/單色截圖守衛：某些 App（含 FLAG_SECURE、或視窗切換動畫中）會回傳整片白/黑，
            // 這種圖丟給檢測器只會得到幾十個假框（實測白圖 det=49 boxes）。直接判為無效。
            if (!hasVisualContent(out)) {
                Log.w("WAE_VISION", "capture rejected: blank/uniform screenshot")
                out.recycle()
                return null
            }
            out
        } catch (t: Throwable) {
            Log.e("WAE_VISION", "screen capture failed", t)
            null
        } finally {
            try {
                hw.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** 原始 HardwareBuffer（給原生層編譯畫面用）；呼叫端負責 close。 */
    fun hardwareScreenshot(service: AccessibilityService): HardwareBuffer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val holder = AtomicReference<HardwareBuffer?>(null)
        val latch = CountDownLatch(1)
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            holder.set(screenshot.hardwareBuffer)
                        } catch (_: Throwable) {
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
            latch.await(700L, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
        return holder.get()
    }

    /** 粗略判斷截圖是否有內容（亮度標準差過低 = 整片純色） */
    fun hasVisualContent(bitmap: Bitmap): Boolean {
        return try {
            val stepX = (bitmap.width / 32).coerceAtLeast(1)
            val stepY = (bitmap.height / 32).coerceAtLeast(1)
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val p = bitmap.getPixel(x, y)
                    val lum = (((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 +
                        (p and 0xFF) * 0.114)
                    sum += lum
                    sumSq += lum * lum
                    n++
                    x += stepX
                }
                y += stepY
            }
            if (n == 0) return false
            val mean = sum / n
            val variance = (sumSq / n) - mean * mean
            variance > 36.0   // stddev > 6
        } catch (_: Throwable) {
            true
        }
    }
}
