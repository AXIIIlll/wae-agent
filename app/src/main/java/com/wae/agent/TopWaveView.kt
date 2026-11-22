package com.wae.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 主畫面頂部的裝飾波浪（S51）。
 *
 * 與語音助理的波浪同一套配色（粉/紫/藍/白），但改成**從上往下**流動，
 * 底部是白色、與 App 的白色底自然融合（不會有截斷線）。
 * 顏色之間有過渡層、邊緣細微模糊 + 整片輕微模糊。
 */
class TopWaveView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /** 距頂部比例、振幅比例、波數、顏色、相位差 */
    private data class Layer(
        val offsetFromTop: Float,
        val amp: Float,
        val freq: Float,
        val color: Int,
        val offset: Float
    )

    private val baseLayers = listOf(
        Layer(0.30f, 0.070f, 1.15f, 0x99FF4D8D.toInt(), 0.0f),
        Layer(0.44f, 0.060f, 1.25f, 0x8C7B5CFF.toInt(), 1.1f),
        Layer(0.58f, 0.050f, 1.35f, 0x8012B8FF.toInt(), 2.2f),
        Layer(0.74f, 0.035f, 1.45f, -1, 3.0f) // 白（以頁面白底呈現）
    )

    private val layers: List<Layer> = run {
        val list = mutableListOf<Layer>()
        baseLayers.forEachIndexed { index, layer ->
            val color = if (layer.color == -1) Color.TRANSPARENT else desaturate(layer.color, 0.10f)
            list.add(layer.copy(color = color))
            val next = baseLayers.getOrNull(index + 1)
            if (next != null && next.color != -1) {
                list.add(
                    Layer(
                        offsetFromTop = (layer.offsetFromTop + next.offsetFromTop) / 2f,
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

    // 靜態波浪：不跑無限動畫、不做 RenderEffect 模糊（打字/捲動才不會卡）
    private val phase = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val steps = 64
        val twoPi = 2f * PI.toFloat()

        // 由下往上畫（先白、再藍、紫、粉），每一層都填滿「頂部 → 波浪線」
        for (layer in layers.asReversed()) {
            if (layer.color == Color.TRANSPARENT) continue
            val fill = Path()
            val edge = Path()
            var first = true
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val x = t * w
                val arc = h * 0.10f * sin(PI.toFloat() * t)
                val angle = twoPi * (t * layer.freq) + phase + layer.offset
                val wave = h * layer.amp * sin(angle)
                val y = h * layer.offsetFromTop + arc + wave
                if (first) {
                    fill.moveTo(x, 0f)
                    fill.lineTo(x, y)
                    edge.moveTo(x, y)
                    first = false
                } else {
                    fill.lineTo(x, y)
                    edge.lineTo(x, y)
                }
            }
            // 封回頂部形成色塊
            fill.lineTo(w, 0f)
            fill.close()
            paint.color = layer.color
            canvas.drawPath(fill, paint)

            val a = (Color.alpha(layer.color) * 0.55f).toInt().coerceIn(20, 255)
            edgePaint.color = Color.argb(a, Color.red(layer.color), Color.green(layer.color), Color.blue(layer.color))
            canvas.drawPath(edge, edgePaint)
        }
    }

    companion object {
        private fun blend(c1: Int, c2: Int, ratio: Float): Int {
            val a = Color.alpha(c1) + ((Color.alpha(c2) - Color.alpha(c1)) * ratio).roundToInt()
            val r = Color.red(c1) + ((Color.red(c2) - Color.red(c1)) * ratio).roundToInt()
            val g = Color.green(c1) + ((Color.green(c2) - Color.green(c1)) * ratio).roundToInt()
            val b = Color.blue(c1) + ((Color.blue(c2) - Color.blue(c1)) * ratio).roundToInt()
            return Color.argb(a, r, g, b)
        }

        private fun desaturate(color: Int, amount: Float): Int {
            val lum = (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)).roundToInt()
            val r = Color.red(color) + ((lum - Color.red(color)) * amount).roundToInt()
            val g = Color.green(color) + ((lum - Color.green(color)) * amount).roundToInt()
            val b = Color.blue(color) + ((lum - Color.blue(color)) * amount).roundToInt()
            return Color.argb(Color.alpha(color), r, g, b)
        }
    }
}
