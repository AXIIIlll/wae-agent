package com.wae.agent.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 底部波浪面板（S47）。
 *
 * 更新：
 *  - **整片模糊移除**（原本 42px RenderEffect 拿掉）。
 *  - 改成「每一道波浪的邊緣做細微模糊」：填色清楚，邊線用 8px BlurMaskFilter 柔化。
 *  - 每個顏色之間仍有過渡色層、層中還有同色副層。
 *  - 全體彩度降低約 10%。
 *  - View 比可見區域高，波浪峰值自然淡出，不會有截斷線。
 *  - 顏色由下到上：灰 → 白 → 藍 → 紫 → 粉；越上面越透明、振幅越大。
 */
class WavePanelView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 波浪邊緣的細微模糊（只糊邊線，不糊整片） */
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        maskFilter = android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    /** 可見面板高度（px）；View 實際高度可以比它高，多出來的是波浪緩衝區 */
    var visiblePanelHeightPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** 一層顏色：offsetFromBottom 距底部比例、amp 振幅比例、freq 波數、color、offset 相位差 */
    private data class Layer(
        val offsetFromBottom: Float,
        val amp: Float,
        val freq: Float,
        val color: Int,
        val offset: Float
    )

    /** 原始色（高彩度） */
    private val baseLayers = listOf(
        Layer(0.68f, 0.150f, 1.15f, 0xB3FF4D8D.toInt(), 0.0f),  // 粉
        Layer(0.56f, 0.110f, 1.25f, 0xCC8B5CFF.toInt(), 1.1f),  // 紫
        Layer(0.44f, 0.080f, 1.35f, 0xE61EA7FF.toInt(), 2.2f),  // 藍
        Layer(0.32f, 0.050f, 1.45f, 0xF2FFFFFF.toInt(), 3.0f),  // 白
        Layer(0.20f, 0.020f, 1.55f, 0xB38A8A93.toInt(), 4.0f)   // 灰
    )

    /** 在每兩層之間插入過渡色層，並全體降低 10% 彩度；
     *  另外每一層顏色再複製一層「同色波形」（不同振幅/頻率/相位），做出層中有層的效果。 */
    private val layers: List<Layer> = run {
        val list = mutableListOf<Layer>()
        baseLayers.forEachIndexed { index, layer ->
            val color = desaturate(layer.color, 0.10f)
            // 主層
            list.add(layer.copy(color = color))
            // 同色副層（在同一個顏色裡再疊一層波浪）
            list.add(
                Layer(
                    offsetFromBottom = layer.offsetFromBottom - 0.018f,
                    amp = layer.amp * 0.62f,
                    freq = layer.freq * 1.27f,
                    color = color,
                    offset = layer.offset + 0.9f
                )
            )
            // 與下一色之間的過渡層
            val next = baseLayers.getOrNull(index + 1)
            if (next != null) {
                list.add(
                    Layer(
                        offsetFromBottom = (layer.offsetFromBottom + next.offsetFromBottom) / 2f,
                        amp = (layer.amp + next.amp) / 2f,
                        freq = (layer.freq + next.freq) / 2f,
                        color = desaturate(blend(layer.color, next.color, 0.5f), 0.10f),
                        offset = (layer.offset + next.offset) / 2f
                    )
                )
            }
        }
        list
    }

    // 靜態波浪：不跑無限動畫、不做 RenderEffect 模糊
    private val phase = 0f

    /** 動畫速度倍率（1＝平常，2＝輸入/聆聽中） */
    var speed: Float = 1f
        set(value) {
            field = value.coerceIn(0.2f, 3f)
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val ph = if (visiblePanelHeightPx > 0f) visiblePanelHeightPx else h
        val steps = 64
        val twoPi = 2f * PI.toFloat()

        for (layer in layers) {
            val fill = Path()
            val edge = Path()
            fill.moveTo(0f, h)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val x = t * w
                val arc = -ph * 0.22f * sin(PI.toFloat() * t)
                val angle = twoPi * (t * layer.freq) + phase + layer.offset
                val wave = ph * layer.amp * sin(angle)
                val y = h - ph * layer.offsetFromBottom + arc + wave
                if (i == 0) {
                    fill.lineTo(0f, y)
                    edge.moveTo(0f, y)
                } else {
                    fill.lineTo(x, y)
                    edge.lineTo(x, y)
                }
            }
            fill.lineTo(w, h)
            fill.close()
            paint.color = layer.color
            canvas.drawPath(fill, paint)

            // 只在波浪邊緣做細微模糊（整片不糊）
            val alpha = ((Color.alpha(layer.color) * 0.85f).toInt()).coerceIn(40, 255)
            edgePaint.color = Color.argb(alpha, Color.red(layer.color), Color.green(layer.color), Color.blue(layer.color))
            canvas.drawPath(edge, edgePaint)
        }
    }

    companion object {

        /** 兩色混合 */
        private fun blend(c1: Int, c2: Int, ratio: Float): Int {
            val a = Color.alpha(c1) + ((Color.alpha(c2) - Color.alpha(c1)) * ratio).roundToInt()
            val r = Color.red(c1) + ((Color.red(c2) - Color.red(c1)) * ratio).roundToInt()
            val g = Color.green(c1) + ((Color.green(c2) - Color.green(c1)) * ratio).roundToInt()
            val b = Color.blue(c1) + ((Color.blue(c2) - Color.blue(c1)) * ratio).roundToInt()
            return Color.argb(a, r, g, b)
        }

        /** 降低彩度：與亮度灰混合 amount（0.10＝彩度降約 10%） */
        private fun desaturate(color: Int, amount: Float): Int {
            val lum = (0.299f * Color.red(color) +
                0.587f * Color.green(color) +
                0.114f * Color.blue(color)).roundToInt()
            val r = Color.red(color) + ((lum - Color.red(color)) * amount).roundToInt()
            val g = Color.green(color) + ((lum - Color.green(color)) * amount).roundToInt()
            val b = Color.blue(color) + ((lum - Color.blue(color)) * amount).roundToInt()
            return Color.argb(Color.alpha(color), r, g, b)
        }
    }
}
