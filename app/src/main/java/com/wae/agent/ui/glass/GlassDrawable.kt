package com.wae.agent.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** 玻璃卡片底：半透明漸層＋亮邊＋頂部高光，四個角自己畫。 */
class GlassDrawable(
    private val radius: Float,
    private val fillTop: Int = Color.argb(34, 255, 255, 255),
    private val fillBottom: Int = Color.argb(14, 255, 255, 255),
    private val stroke: Int = Color.argb(44, 255, 255, 255),
    private val highlight: Boolean = true
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        rect.set(bounds)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        fill.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.bottom,
            fillTop, fillBottom, Shader.TileMode.CLAMP
        )
        line.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            Color.argb(90, 255, 255, 255), Color.argb(18, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        sheen.shader = LinearGradient(
            rect.left, rect.top, rect.left + rect.width() * 0.45f, rect.top + rect.height() * 0.9f,
            Color.argb(40, 255, 255, 255), Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, fill)
        if (highlight) canvas.drawPath(path, sheen)
        canvas.drawPath(path, line)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        line.alpha = alpha
        sheen.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fill.colorFilter = colorFilter
        line.colorFilter = colorFilter
        sheen.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** 整頁背景：深色底＋四顆柔光，讓玻璃有東西可以透。 */
class WaeBackgroundDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private var w = 0f
    private var h = 0f
    private var built = false

    private fun build() {
        if (built || w <= 0 || h <= 0) return
        built = true
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        w = bounds.width().toFloat()
        h = bounds.height().toFloat()
        built = false
        build()
    }

    override fun draw(canvas: Canvas) {
        if (w <= 0f) return
        canvas.drawColor(Color.parseColor("#050409"))

        // 上左：紫
        paint.shader = RadialGradient(
            0.08f * w, 0.02f * h, 0.72f * w,
            intArrayOf(Color.argb(120, 139, 92, 246), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // 右上：粉
        paint.shader = RadialGradient(
            w, 0.10f * h, 0.60f * w,
            intArrayOf(Color.argb(80, 236, 72, 153), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // 右下：青
        paint.shader = RadialGradient(
            w, h, 0.66f * w,
            intArrayOf(Color.argb(66, 34, 211, 238), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // 左下：靛
        paint.shader = RadialGradient(
            0f, h, 0.62f * w,
            intArrayOf(Color.argb(80, 99, 102, 241), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // 中央壓暗（玻璃需要對比）
        paint.shader = RadialGradient(
            w / 2f, h / 2f, 0.62f * maxOf(w, h),
            intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(90, 0, 0, 0)),
            floatArrayOf(0.1f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}
