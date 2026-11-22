package com.wae.agent.ui.ghost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * 小幽靈（半透明版）。
 *
 * 畫法刻意「不像貼紙」：
 *  1. 身體是半透明白＋淡藍漸層，底下的漸層背景會透上來（玻璃感）
 *  2. 左上角一團柔光，像內部有光源
 *  3. 邊線用「一邊亮、一邊化開」的漸層，不是均勻一圈
 *  4. 底部一層薄霧，尾巴不硬
 *  5. 周圍飄浮光點
 *
 * 動畫（4.32 秒循環，只改 transform 與 alpha，不重算點陣圖）：
 *  漂浮 16dp、呼吸 1.028、眨眼每 2.16 秒、眼神每 4.32 秒往左瞄。
 *
 * 座標：全部在 120×120 的設計座標裡畫，onDraw 只做一次 canvas.scale，
 * 所以任何尺寸（24dp 頭像到 190dp 主視覺）比例都一致。
 */
class GhostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 身體整體透明度（頭像可以調淡）。 */
    var bodyAlpha: Float = 1f
    /** 要不要跑動畫（列表頭像可以關掉，省電）。 */
    var animated: Boolean = true
    /** 光暈強度，頭像可以調低。 */
    var bloom: Float = 1f

    private val density = resources.displayMetrics.density
    private val bodyPath = Path()
    private val eyePath = Path()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.2f
    }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B1220") }
    private val eyeLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DBEAFE") }

    private var shadersBuilt = false

    private fun buildShapes() {
        if (!bodyPath.isEmpty) return
        bodyPath.moveTo(60f, 18f)
        bodyPath.cubicTo(37f, 18f, 20f, 33f, 20f, 54f)
        bodyPath.lineTo(20f, 80f)
        bodyPath.cubicTo(20f, 88f, 25f, 94f, 32f, 94f)
        bodyPath.cubicTo(38f, 94f, 40f, 88f, 46f, 88f)
        bodyPath.cubicTo(51f, 88f, 53f, 94f, 59f, 94f)
        bodyPath.cubicTo(65f, 94f, 67f, 88f, 72f, 88f)
        bodyPath.cubicTo(78f, 88f, 80f, 94f, 86f, 94f)
        bodyPath.cubicTo(93f, 94f, 98f, 88f, 98f, 80f)
        bodyPath.lineTo(98f, 54f)
        bodyPath.cubicTo(98f, 33f, 83f, 18f, 60f, 18f)
        bodyPath.close()
    }

    private fun buildShaders() {
        if (shadersBuilt) return
        shadersBuilt = true

        bodyPaint.shader = LinearGradient(
            0f, 0f, 42f, 120f,
            intArrayOf(
                argb(0.52f, 255, 255, 255),
                argb(0.34f, 214, 230, 255),
                argb(0.20f, 169, 194, 245)
            ),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )

        sheenPaint.shader = RadialGradient(
            40f, 22f, 58f,
            intArrayOf(
                argb(0.88f, 255, 255, 255),
                argb(0.30f, 236, 245, 255),
                argb(0f, 236, 245, 255)
            ),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )

        rimPaint.shader = LinearGradient(
            4f, 10f, 104f, 104f,
            intArrayOf(
                argb(1f, 255, 255, 255),
                argb(0.80f, 220, 236, 255),
                argb(0.22f, 190, 214, 255),
                argb(0f, 190, 214, 255)
            ),
            floatArrayOf(0f, 0.34f, 0.62f, 0.88f), Shader.TileMode.CLAMP
        )

        bloomPaint.shader = RadialGradient(
            60f, 60f, 59f,
            intArrayOf(
                argb(0.30f, 147, 197, 253),
                argb(0.14f, 139, 92, 246),
                argb(0f, 139, 92, 246)
            ),
            null, Shader.TileMode.CLAMP
        )

        mistPaint.shader = RadialGradient(
            60f, 108f, 54f,
            intArrayOf(argb(0.34f, 198, 222, 255), argb(0f, 198, 222, 255)),
            null, Shader.TileMode.CLAMP
        )
    }

    private fun argb(a: Float, r: Int, g: Int, b: Int): Int =
        Color.argb((a * 255).toInt().coerceIn(0, 255), r, g, b)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShaders()
    }

    override fun onDraw(canvas: Canvas) {
        buildShapes()
        buildShaders()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis()
        val phase = if (animated) (now % 4320L) / 4320f else 0f
        val ang = phase * 2f * PI.toFloat()
        val floatY = if (animated) -sin(ang) * 6f else 0f          // 設計座標：6/120 ≈ 5%
        val breathe = if (animated) 1f + 0.028f * sin(ang + PI.toFloat() / 2f) else 1f

        // 眨眼：每 2.16 秒一次，閉 0.11 秒
        val blinkPhase = (now % 2160L) / 2160f
        val eyeScale = if (animated && blinkPhase > 0.945f && blinkPhase < 0.975f) 0.08f else 1f
        // 眼神：每 4.32 秒往左瞄一下
        val lookX = when {
            !animated -> 0f
            phase in 0.55f..0.60f -> -(phase - 0.55f) / 0.05f * 5f
            phase in 0.60f..0.72f -> -5f
            phase in 0.72f..0.78f -> -(0.78f - phase) / 0.06f * 5f
            else -> 0f
        }

        canvas.save()
        canvas.scale(w / 120f, h / 120f)
        canvas.translate(0f, floatY)
        canvas.scale(breathe, breathe, 60f, 60f)

        // 光暈
        bloomPaint.alpha = (255 * bloom * bodyAlpha).toInt().coerceIn(0, 255)
        canvas.drawCircle(60f, 60f, 59f, bloomPaint)

        // 身體（半透明，背景會透上來）
        bodyPaint.alpha = (255 * bodyAlpha).toInt().coerceIn(0, 255)
        canvas.drawPath(bodyPath, bodyPaint)

        // 內光 + 底部薄霧（裁在身體裡）
        canvas.save()
        canvas.clipPath(bodyPath)
        canvas.drawCircle(41f, 24f, 52f, sheenPaint)
        canvas.drawCircle(60f, 108f, 54f, mistPaint)
        canvas.restore()

        // 邊線：一邊亮、一邊化開
        rimPaint.alpha = (255 * bodyAlpha).toInt().coerceIn(0, 255)
        canvas.drawPath(bodyPath, rimPaint)

        // 眼睛
        eyePaint.alpha = (250 * bodyAlpha).toInt().coerceIn(0, 255)
        eyeLightPaint.alpha = (235 * bodyAlpha).toInt().coerceIn(0, 255)
        drawEye(canvas, 47f, lookX, eyeScale)
        drawEye(canvas, 73f, lookX, eyeScale)

        canvas.restore()

        // 飄浮光點（動畫才畫）
        if (animated) {
            drawMotes(canvas, now, w / 120f, h / 120f)
            postInvalidateOnAnimation()
        }
    }

    private fun drawEye(canvas: Canvas, cx: Float, dx: Float, scaleY: Float) {
        val x = cx + dx
        val y = 57f
        val rx = 6f
        val ry = 10f * scaleY
        eyePath.reset()
        eyePath.addOval(x - rx, y - ry, x + rx, y + ry, Path.Direction.CW)
        canvas.drawPath(eyePath, eyePaint)
        if (scaleY > 0.5f) {
            canvas.drawCircle(x - 1.6f, y - 3.4f, 2.2f, eyeLightPaint)
        }
    }

    private fun drawMotes(canvas: Canvas, now: Long, sx: Float, sy: Float) {
        val specs = arrayOf(
            floatArrayOf(0.16f, 0.34f, 0f, 0.9f),
            floatArrayOf(0.86f, 0.40f, 900f, 0.7f),
            floatArrayOf(0.10f, 0.74f, 1800f, 0.6f),
            floatArrayOf(0.90f, 0.80f, 2700f, 0.75f)
        )
        for (s in specs) {
            val t = ((now + s[2].toLong()) % 4320L) / 4320f
            val rise = (1f - t) * 22f
            val alpha = (sin(t * PI.toFloat()) * s[3]).coerceIn(0f, 1f) * bodyAlpha
            motePaint.alpha = (alpha * 255).toInt()
            val r = (1.5f + 1.3f * (1f - t))
            canvas.drawCircle(s[0] * 120f * sx, (s[1] * 120f - rise) * sy, r * sx, motePaint)
        }
    }
}
