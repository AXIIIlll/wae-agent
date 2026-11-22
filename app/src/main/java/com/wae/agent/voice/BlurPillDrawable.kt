package com.wae.agent.voice

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 真正「往外散開」的模糊膠囊背景（S43）。
 *
 * 之前只畫一層模糊白，仍看得到膠囊邊界的一條線。
 * 現在改用多層高斯疊加（半徑由大到小、透明度逐層累加），
 * 邊緣是連續的濃度梯度，沒有硬線；同時保留底下的顏色（不是整片白）。
 */
class BlurPillDrawable(
    private val blurRadiusPx: Float,
    private val insetPx: Float = 0f
) : Drawable() {

    private data class Pass(val paint: Paint)

    private val passes: List<Pass> = listOf(
        // radius 倍率, 白色 alpha
        1.55f to 0x12,
        1.25f to 0x16,
        1.00f to 0x1A,
        0.80f to 0x1E,
        0.62f to 0x22,
        0.46f to 0x27,
        0.30f to 0x2E,
        0.16f to 0x38
    ).map { (ratio, alpha) ->
        Pass(Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (alpha shl 24) or 0x00FFFFFF
            maskFilter = BlurMaskFilter(blurRadiusPx * ratio, BlurMaskFilter.Blur.NORMAL)
        })
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2E000000
        maskFilter = BlurMaskFilter(blurRadiusPx * 1.5f, BlurMaskFilter.Blur.NORMAL)
    }
    private val rect = RectF()
    private var radius = 0f

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        rect.set(
            bounds.left + insetPx,
            bounds.top + insetPx,
            bounds.right - insetPx,
            bounds.bottom - insetPx
        )
        radius = rect.height() / 2f
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, shadowPaint)
        // 多層疊加 → 平滑的濃度梯度（中心濃、邊緣淡）
        for (p in passes) {
            canvas.drawRoundRect(rect, radius, radius, p.paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        passes.forEach { it.paint.alpha = (it.paint.alpha * alpha) / 255 }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        passes.forEach { it.paint.colorFilter = colorFilter }
        shadowPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
