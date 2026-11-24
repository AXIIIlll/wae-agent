package com.wae.agent.gen5

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.wae.core.model.Channel
import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.ControlState
import com.wae.core.model.RectI
import com.wae.core.util.TextHash
import com.wae.agent.vision.PpOcrEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * OCR 通道（B1）：沒有無障礙節點的畫面，用截圖＋OCR 補文字與座標。
 *
 * 只做「看得到字」；能不能點由座標手勢負責（B2）。
 * 截圖走無障礙服務的 `takeScreenshot`（API 30+），不需要 MediaProjection。
 */
class OcrChannel(
    private val service: AccessibilityService
) {

    private val executor = Executors.newSingleThreadExecutor()

    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && PpOcrEngine.isReady

    /** 同步取一幀（最多等 timeoutMs）。失敗回空清單，不拋。 */
    /**
     * 同步取一幀（最多等 timeoutMs）。失敗回空清單，不拋。
     *
     * @param region 只辨識這一塊（像素座標）；null＝整頁。
     *               整頁 OCR 會被背景文字干擾又慢，所以呼叫端通常先規劃小區域。
     */
    fun capture(
        region: android.graphics.Rect? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxLines: Int = DEFAULT_MAX_LINES
    ): List<Control> {
        if (!isAvailable()) return emptyList()
        val latch = CountDownLatch(1)
        val out = ArrayList<Control>()
        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        runCatching {
                            val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            screenshot.hardwareBuffer.close()
                            if (bitmap != null) {
                                val (cropBitmap, offX, offY) = cropTo(bitmap, region)
                                out.addAll(toControls(cropBitmap, maxLines, offX, offY))
                                if (cropBitmap !== bitmap) cropBitmap.recycle()
                                bitmap.recycle()
                            }
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
        }.onFailure { latch.countDown() }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    /** 裁切到指定區域（超出範圍會夾住）；回傳 bitmap 與它在原圖的位移。 */
    private fun cropTo(
        bitmap: Bitmap,
        region: android.graphics.Rect?
    ): Triple<Bitmap, Int, Int> {
        if (region == null) return Triple(bitmap, 0, 0)
        val l = region.left.coerceIn(0, bitmap.width - 1)
        val t = region.top.coerceIn(0, bitmap.height - 1)
        val r = region.right.coerceIn(l + 1, bitmap.width)
        val b = region.bottom.coerceIn(t + 1, bitmap.height)
        val cropped = com.wae.core.util.Degrade.orNull("ocr:crop") {
            Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
        } ?: return Triple(bitmap, 0, 0)
        return Triple(cropped, l, t)
    }

    private fun toControls(
        bitmap: Bitmap,
        maxLines: Int,
        offsetX: Int = 0,
        offsetY: Int = 0
    ): List<Control> {
        val lines = runCatching { PpOcrEngine.detectAndRecognize(bitmap, maxLines) }
            .getOrDefault(emptyList())
        return lines.mapIndexed { index, line ->
            val text = line.text.trim()
            Control(
                key = "ocr:$index:" + TextHash.of(text).take(KEY_HASH_LEN),
                kind = ControlKind.ROW,
                label = text,
                textHash = TextHash.of(text),
                bounds = RectI(
                    line.rect.left + offsetX, line.rect.top + offsetY,
                    line.rect.right + offsetX, line.rect.bottom + offsetY
                ),
                enabled = true,
                state = ControlState(text = text),
                source = Channel.OCR
            )
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 1_500L
        const val DEFAULT_MAX_LINES = 40
        const val KEY_HASH_LEN = 10
    }
}
