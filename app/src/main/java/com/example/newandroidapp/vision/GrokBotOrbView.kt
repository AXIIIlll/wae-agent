package com.example.newandroidapp.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Grok Bot 雙眼圓形 AI 懸浮球視圖（內建 88 種多彩情緒與物理動畫引擎，100% 原生 Canvas 幾何繪製，零 Emoji）
 *
 * 涵蓋 7 大動畫維度（#01 ~ #88）：
 * 1. 眨眼與呼吸（#01–#11）
 * 2. 大小眼與縮放變化（#12–#22）
 * 3. 視線方向與環視追蹤（#23–#34）
 * 4. 多彩情緒表情（#35–#50：害羞櫻花粉+腮紅、開心暖陽金、大笑珊瑚橘、好奇薄荷青、生氣赤焰紅、委屈冰川藍、震驚電光紫、感動星辰紫、暈眩萊姆綠等）
 * 5. 語音聆聽與聲波律動（#51–#61）
 * 6. AI 推理、OCR 雷射掃描與閉環執行（#62–#73）
 * 7. 拖曳慣性、左右磁吸貼邊、展開與收合轉場物理（#74–#88）
 */
class GrokBotOrbView(context: Context) : TextView(context) {

    enum class EyeArcMode {
        PILL,
        HAPPY_ARC,
        DOT,
        RING,
        LINE
    }

    data class OrbFrameState(
        val lx: Float = 0f,
        val ly: Float = 0f,
        val rx: Float = 0f,
        val ry: Float = 0f,
        val lw: Float = 1f,
        val lh: Float = 1f,
        val rw: Float = 1f,
        val rh: Float = 1f,
        val lRotRad: Float = 0f,
        val rRotRad: Float = 0f,
        val arcMode: EyeArcMode = EyeArcMode.PILL,
        val eyeColor: Int = Color.parseColor("#F4F4F5"),
        val ringColor: Int = Color.parseColor("#52525B"),
        val blushColor: Int? = null,
        val scanLineY: Float? = null,
        val bodyTiltX: Float = 0f,
        val bodyTiltY: Float = 0f,
        val bodyScaleX: Float = 1f,
        val bodyScaleY: Float = 1f
    )

    var isBusyState: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var isVoiceListeningState: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var isHandoverState: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 拖曳時附加的速度眼神偏移 (-0.6f..0.6f) */
    var dragVelocityGazeX: Float = 0f
    var dragVelocityGazeY: Float = 0f

    /** 指定播放的動畫編號 (1..88)；若為 0 則由智慧情緒排程器自動輪播 */
    var pinnedAnimationId: Int = 0

    private var temporaryAnimId: Int = 0
    private var temporaryAnimUntilMs: Long = 0L

    private val idlePlaylist = intArrayOf(
        1, 43, 35, 2, 14, 23, 24, 4, 37, 3,
        46, 5, 12, 36, 50, 27, 28, 16, 44, 7,
        40, 41, 45, 47, 17, 18, 21, 33, 34, 48
    )
    private var idlePlaylistIndex = 0
    private var lastIdleSwitchMs = SystemClock.elapsedRealtime()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val startElapsedMs = SystemClock.elapsedRealtime()

    private val outerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#18181B")
    }
    private val outerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val visorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#09090B")
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 16, 185, 129)
    }
    private val eyeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val eyeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val visorRect = RectF()
    private val tmpRect = RectF()
    private val visorClipPath = Path()

    private val frameTickRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || visibility != VISIBLE) return
            invalidate()
            // 展開或無特殊動作時以 30~60fps 驅動流暢表情動畫
            val intervalMs = if (alpha < 0.6f && !isBusyState && !isVoiceListeningState && temporaryAnimId == 0) 48L else 24L
            mainHandler.postDelayed(this, intervalMs)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mainHandler.removeCallbacks(frameTickRunnable)
        mainHandler.post(frameTickRunnable)
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(frameTickRunnable)
        super.onDetachedFromWindow()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            mainHandler.removeCallbacks(frameTickRunnable)
            mainHandler.post(frameTickRunnable)
        }
    }

    /**
     * 觸發短暫特定動畫（例如：#74 按壓蓄力、#78/#79 吸邊回彈、#81 展開、#82 收合、#43 害羞粉、#35 開心笑眼、#70 任務成功）
     */
    fun triggerAnimation(animId: Int, durationMs: Long = 1600L) {
        temporaryAnimId = animId.coerceIn(1, 88)
        temporaryAnimUntilMs = SystemClock.elapsedRealtime() + durationMs
        invalidate()
    }

    /**
     * 依據螢幕目標點擊座標 (targetX, targetY)，讓懸浮球雙眼即時看向目標所在方位（#23~#30 或 #66 鎖定聚焦）
     */
    fun triggerGazeForTargetPoint(
        targetX: Float,
        targetY: Float,
        screenW: Int,
        screenH: Int,
        durationMs: Long = 1100L
    ): Int {
        val sw = screenW.coerceAtLeast(720).toFloat()
        val sh = screenH.coerceAtLeast(1280).toFloat()
        val nx = (targetX / sw) - 0.5f // -0.5 (左) .. +0.5 (右)
        val ny = (targetY / sh) - 0.5f // -0.5 (上) .. +0.5 (下)
        val animId = when {
            nx < -0.16f && ny < -0.16f -> 27 // 左上斜視
            nx > 0.16f && ny < -0.16f -> 28  // 右上斜視
            nx < -0.16f && ny > 0.16f -> 29  // 左下斜視
            nx > 0.16f && ny > 0.16f -> 30   // 右下斜視
            ny < -0.18f -> 25                // 向上仰視
            ny > 0.18f -> 26                 // 向下俯視
            nx < -0.18f -> 23                // 極左注視
            nx > 0.18f -> 24                 // 極右注視
            else -> 66                       // 正中央目標鎖定聚焦
        }
        triggerAnimation(animId, durationMs)
        return animId
    }

    /**
     * 取得指定動畫編號對應的情緒主題代表色（供懸浮微氣泡側邊色條與光暈同步）
     */
    fun getAnimationAccentColor(animId: Int): Int {
        val state = computeAnimationFrame(animId.coerceIn(1, 88), 0.25f)
        return state.eyeColor
    }

    /**
     * 切換至下一個隨機多彩情緒動畫並返回動畫編號與名稱
     */
    fun cycleNextEmotionAnimation(): Pair<Int, String> {
        idlePlaylistIndex = (idlePlaylistIndex + 1) % idlePlaylist.size
        val id = idlePlaylist[idlePlaylistIndex]
        triggerAnimation(id, 3200L)
        return id to getAnimationTitle(id)
    }

    private fun resolveActiveAnimationId(nowMs: Long): Int {
        if (pinnedAnimationId in 1..88) return pinnedAnimationId
        if (temporaryAnimId in 1..88 && nowMs < temporaryAnimUntilMs) {
            return temporaryAnimId
        } else if (temporaryAnimId != 0 && nowMs >= temporaryAnimUntilMs) {
            temporaryAnimId = 0
        }
        if (isHandoverState) return 72
        if (isVoiceListeningState) {
            val idx = ((nowMs / 2200L) % 4L).toInt()
            return intArrayOf(51, 52, 54, 57)[idx]
        }
        if (isBusyState) {
            val idx = ((nowMs / 1800L) % 5L).toInt()
            return intArrayOf(62, 63, 64, 65, 66)[idx]
        }
        if (nowMs - lastIdleSwitchMs >= 3400L) {
            lastIdleSwitchMs = nowMs
            idlePlaylistIndex = (idlePlaylistIndex + 1) % idlePlaylist.size
        }
        return idlePlaylist[idlePlaylistIndex]
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.onDraw(canvas)
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        val tSec = ((nowMs - startElapsedMs) % 3600_000L) / 1000f
        val activeId = resolveActiveAnimationId(nowMs)
        val state = computeAnimationFrame(activeId, tSec)

        val cx = w * 0.5f
        val cy = h * 0.5f
        val density = resources.displayMetrics.density
        val strokeW = (density * 2.1f).coerceAtLeast(3f)
        val baseR = min(w, h) * 0.44f

        val tx = (state.bodyTiltX * w * 0.06f) + (dragVelocityGazeX * w * 0.05f)
        val ty = (state.bodyTiltY * h * 0.06f) + (dragVelocityGazeY * h * 0.05f)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(state.bodyScaleX, state.bodyScaleY)

        // 1. 外層圓形機身 (#18181B)
        canvas.drawCircle(0f, 0f, baseR, outerFillPaint)

        // 2. 多彩情緒狀態外環
        outerStrokePaint.strokeWidth = strokeW
        outerStrokePaint.color = state.ringColor
        canvas.drawCircle(0f, 0f, baseR, outerStrokePaint)

        // 3. 內層深邃面罩 (#09090B)
        val visorW = baseR * 1.42f
        val visorH = baseR * 1.02f
        val visorR = visorH * 0.5f
        visorRect.set(
            tx * 0.5f - visorW * 0.5f,
            ty * 0.5f - visorH * 0.5f,
            tx * 0.5f + visorW * 0.5f,
            ty * 0.5f + visorH * 0.5f
        )
        canvas.drawRoundRect(visorRect, visorR, visorR, visorPaint)

        // 3b. 雙頰腮紅微光（害羞櫻花粉 #43、開心笑眼 #35/#36、俏皮眨眼 #50、感動星辰紫 #46 等）
        if (state.blushColor != null || state.scanLineY != null) {
            canvas.save()
            visorClipPath.reset()
            visorClipPath.addRoundRect(visorRect, visorR, visorR, Path.Direction.CW)
            canvas.clipPath(visorClipPath)

            state.blushColor?.let { bCol ->
                blushPaint.color = bCol
                val blushW = baseR * 0.28f
                val blushH = baseR * 0.14f
                val blushX = baseR * 0.44f
                val blushY = baseR * 0.22f
                tmpRect.set(
                    tx * 0.4f - blushX - blushW,
                    ty * 0.4f + blushY - blushH,
                    tx * 0.4f - blushX + blushW,
                    ty * 0.4f + blushY + blushH
                )
                canvas.drawOval(tmpRect, blushPaint)
                tmpRect.set(
                    tx * 0.4f + blushX - blushW,
                    ty * 0.4f + blushY - blushH,
                    tx * 0.4f + blushX + blushW,
                    ty * 0.4f + blushY + blushH
                )
                canvas.drawOval(tmpRect, blushPaint)
            }

            state.scanLineY?.let { syNorm ->
                scanLinePaint.strokeWidth = max(2f, w * 0.022f)
                val lineY = ty * 0.5f + syNorm * (visorH * 0.36f)
                canvas.drawLine(-visorW, lineY, visorW, lineY, scanLinePaint)
            }
            canvas.restore()
        }

        // 4. 繪製兩顆 Grok Bot 多彩小眼睛
        val baseEyeW = baseR * 0.24f
        val baseEyeH = baseR * 0.50f
        val spacingX = baseR * 0.31f
        val maxShiftX = baseR * 0.24f
        val maxShiftY = baseR * 0.20f

        val leftX = -spacingX + (state.lx + dragVelocityGazeX) * maxShiftX + tx * 0.35f
        val leftY = (state.ly + dragVelocityGazeY) * maxShiftY + ty * 0.35f
        val rightX = spacingX + (state.rx + dragVelocityGazeX) * maxShiftX + tx * 0.35f
        val rightY = (state.ry + dragVelocityGazeY) * maxShiftY + ty * 0.35f

        drawSingleEye(
            canvas = canvas,
            x = leftX,
            y = leftY,
            ew = baseEyeW * state.lw,
            eh = baseEyeH * state.lh,
            rotRad = state.lRotRad,
            arcMode = state.arcMode,
            color = state.eyeColor
        )
        drawSingleEye(
            canvas = canvas,
            x = rightX,
            y = rightY,
            ew = baseEyeW * state.rw,
            eh = baseEyeH * state.rh,
            rotRad = state.rRotRad,
            arcMode = state.arcMode,
            color = state.eyeColor
        )

        canvas.restore()
    }

    private fun drawSingleEye(
        canvas: Canvas,
        x: Float,
        y: Float,
        ew: Float,
        eh: Float,
        rotRad: Float,
        arcMode: EyeArcMode,
        color: Int
    ) {
        canvas.save()
        canvas.translate(x, y)
        if (rotRad != 0f) {
            canvas.rotate((rotRad * 180f / PI.toFloat()))
        }
        eyeFillPaint.color = color
        eyeStrokePaint.color = color

        when (arcMode) {
            EyeArcMode.HAPPY_ARC -> {
                eyeStrokePaint.strokeWidth = max(3f, ew * 0.72f)
                val r = ew * 0.85f
                val cyArc = eh * 0.22f
                tmpRect.set(-r, cyArc - r, r, cyArc + r)
                canvas.drawArc(tmpRect, 205f, 130f, false, eyeStrokePaint)
            }
            EyeArcMode.DOT -> {
                val r = ew * 0.65f
                canvas.drawCircle(0f, 0f, r, eyeFillPaint)
            }
            EyeArcMode.RING -> {
                eyeStrokePaint.strokeWidth = max(2.5f, ew * 0.38f)
                canvas.drawCircle(0f, 0f, ew * 0.75f, eyeStrokePaint)
            }
            EyeArcMode.LINE -> {
                val lw = ew * 1.25f
                val lh = max(3f, ew * 0.35f)
                tmpRect.set(-lw * 0.5f, -lh * 0.5f, lw * 0.5f, lh * 0.5f)
                canvas.drawRoundRect(tmpRect, lh * 0.5f, lh * 0.5f, eyeFillPaint)
            }
            EyeArcMode.PILL -> {
                val w = max(3f, ew)
                val h = max(3f, eh)
                val r = min(w, h) * 0.5f
                tmpRect.set(-w * 0.5f, -h * 0.5f, w * 0.5f, h * 0.5f)
                canvas.drawRoundRect(tmpRect, r, r, eyeFillPaint)
            }
        }
        canvas.restore()
    }

    companion object {
        private val PI_F = PI.toFloat()

        private fun hex(hexStr: String): Int = Color.parseColor(hexStr)
        private fun rgba(r: Int, g: Int, b: Int, aFloat: Float): Int =
            Color.argb((aFloat * 255f).toInt().coerceIn(0, 255), r, g, b)

        fun getAnimationTitle(animId: Int): String = when (animId) {
            1 -> "自然單次眨眼"
            2 -> "快速連眨兩下"
            3 -> "薄荷深呼吸縮放"
            4 -> "左眼蜜桃單眨 Wink"
            5 -> "右眼蜜桃單眨 Wink"
            6 -> "左右交替機械眨眼"
            7 -> "櫻粉蝴蝶式連眨"
            8 -> "薰衣草半閉假寐"
            9 -> "晨曦金甦醒伸展"
            10 -> "深海藍入睡閉合"
            11 -> "冰晶白待機漂浮"
            12 -> "星光黃同步放大驚奇"
            13 -> "冰青色微距縮小聚焦"
            14 -> "薄荷青大小眼（左大右小）"
            15 -> "薄荷青大小眼（右大左小）"
            16 -> "糖果紫大小眼翹翹板"
            17 -> "軟糖綠縱向果凍拉長"
            18 -> "蜜桃粉橫向麻糬壓扁"
            19 -> "琥珀橘向中心聚攏鬥雞眼"
            20 -> "雲霧藍向外側擴張放空"
            21 -> "薔薇紅心跳雙連拍"
            22 -> "電光青蓄力瞬間撐大"
            23 -> "往左看"
            24 -> "往右看"
            25 -> "往上看"
            26 -> "往下看"
            27 -> "往左上偷瞄"
            28 -> "往右上偷瞄"
            29 -> "往左下俯視"
            30 -> "往右下俯視"
            31 -> "翠綠左右快速閱讀"
            32 -> "翠綠上下垂直檢視"
            33 -> "極光紫順時針 360° 環視"
            34 -> "極光青 8 字形無限巡弋"
            35 -> "開心暖陽金彎彎笑眼"
            36 -> "大笑珊瑚橘彈跳笑眼"
            37 -> "好奇薄荷青歪頭大小眼"
            38 -> "懷疑琥珀金瞇縫審視"
            39 -> "生氣赤焰紅內傾怒目"
            40 -> "委屈冰川藍下垂八字眼"
            41 -> "震驚電光紫瞳孔地震"
            42 -> "無奈霧灰藍翻白眼"
            43 -> "害羞櫻花粉閃躲腮紅眼"
            44 -> "得意翡翠綠挑眉高低眼"
            45 -> "呆滯奶油黃圓點豆豆眼"
            46 -> "感動星辰紫水汪汪閃爍"
            47 -> "暈眩萊姆綠反向旋轉眼"
            48 -> "疲憊紫羅蘭打瞌睡驚醒"
            49 -> "警覺電光青瞬間鎖定"
            50 -> "俏皮玫瑰粉眨眼歪頭"
            51 -> "語音聆聽：科技藍聲波律動"
            52 -> "語音喚醒：冰藍呼吸擴散"
            53 -> "說話播報：青綠音節跳動"
            54 -> "高音量電光藍撐大"
            55 -> "低語柔藍細微傾聽"
            56 -> "青藍空心圓環聲納脈衝"
            57 -> "紫藍左右聲道立體聲交替"
            58 -> "翠綠語音辨識收斂點頭"
            59 -> "靜音模式平直橫線"
            60 -> "翠綠語音確認雙點頭"
            61 -> "琥珀金沒聽清楚歪頭詢問"
            62 -> "AI 深度思考：智慧紫波浪"
            63 -> "畫面 OCR 翠綠雷射橫掃"
            64 -> "Z-Order 節點樹青藍對焦"
            65 -> "反向推理星雲紫迴旋"
            66 -> "準備點擊：戰術青準星收斂"
            67 -> "向下滑動手勢跟隨"
            68 -> "向上滑動手勢跟隨"
            69 -> "跨 App 跳轉極速穿梭"
            70 -> "任務完成：翡翠綠笑眼點頭"
            71 -> "攔截誤觸：珊瑚紅左右搖頭"
            72 -> "R7 安全接管：琥珀金凝視"
            73 -> "閒置半透明微光守候"
            74 -> "手指按壓蓄力壓扁 (0.92x)"
            75 -> "放手彈簧回彈超調"
            76 -> "向左高速拖曳慣性拉伸"
            77 -> "向右高速拖曳慣性拉伸"
            78 -> "左側邊緣磁吸撞擊回彈"
            79 -> "右側邊緣磁吸撞擊回彈"
            80 -> "貼邊半隱藏探頭偷看"
            81 -> "圓球展開膠囊無縫變形"
            82 -> "膠囊收合圓球彈簧回縮"
            83 -> "長按圓球 360ms 蓄力喚醒"
            84 -> "雙擊圓球驚喜彈跳笑眼"
            85 -> "快速甩動慣性搖擺恢復"
            86 -> "頂底系統擋板軟性防撞"
            87 -> "軟鍵盤彈出自動上移避讓"
            88 -> "關閉懸浮球粉紅眨眼道別"
            else -> "自然待機"
        }

        /**
         * 計算第 1..88 號動畫在時間點 t (秒) 的雙眼幾何與色彩狀態
         */
        fun computeAnimationFrame(animId: Int, t: Float): OrbFrameState {
            return when (animId) {
                // === 1. 眨眼與呼吸 (1-11) ===
                1 -> {
                    val p = t % 2.8f
                    val b = if (p < 0.14f) sin((p / 0.14f) * PI_F) else 0f
                    OrbFrameState(lh = 1f - 0.88f * b, rh = 1f - 0.88f * b, eyeColor = hex("#F4F4F5"), ringColor = hex("#52525B"))
                }
                2 -> {
                    val p = t % 3.0f
                    val b = when {
                        p < 0.12f -> sin((p / 0.12f) * PI_F)
                        p in 0.18f..0.30f -> sin(((p - 0.18f) / 0.12f) * PI_F)
                        else -> 0f
                    }
                    OrbFrameState(lh = 1f - 0.88f * b, rh = 1f - 0.88f * b, eyeColor = hex("#E0F2FE"), ringColor = hex("#38BDF8"))
                }
                3 -> {
                    val s = 1f + 0.08f * sin(t * 2.4f)
                    val bs = 1f + 0.03f * sin(t * 2.4f)
                    OrbFrameState(lw = s, lh = s, rw = s, rh = s, bodyScaleX = bs, bodyScaleY = bs, eyeColor = hex("#CCFBF1"), ringColor = hex("#2DD4BF"))
                }
                4 -> {
                    val p = t % 2.2f
                    val w = if (p < 0.35f) sin((p / 0.35f) * PI_F) else 0f
                    OrbFrameState(lh = 1f - 0.86f * w, rh = 1f + 0.12f * w, ly = 0.05f * w, eyeColor = hex("#FDA4AF"), ringColor = hex("#FB7185"), blushColor = rgba(251, 113, 133, 0.32f))
                }
                5 -> {
                    val p = t % 2.2f
                    val w = if (p < 0.35f) sin((p / 0.35f) * PI_F) else 0f
                    OrbFrameState(rh = 1f - 0.86f * w, lh = 1f + 0.12f * w, ry = 0.05f * w, eyeColor = hex("#FDA4AF"), ringColor = hex("#FB7185"), blushColor = rgba(251, 113, 133, 0.32f))
                }
                6 -> {
                    val s = sin(t * 4.5f)
                    OrbFrameState(lh = if (s > 0f) 1f - 0.8f * s else 1f, rh = if (s < 0f) 1f + 0.8f * s else 1f, eyeColor = hex("#67E8F9"), ringColor = hex("#06B6D4"))
                }
                7 -> {
                    val p = t % 2.4f
                    val b = if (p < 0.54f) abs(sin(p * PI_F * 5.5f)) else 0f
                    OrbFrameState(lh = 1f - 0.82f * b, rh = 1f - 0.82f * b, eyeColor = hex("#F9A8D4"), ringColor = hex("#EC4899"), blushColor = rgba(244, 114, 182, 0.30f))
                }
                8 -> {
                    val h = 0.32f + 0.06f * sin(t * 2.0f)
                    OrbFrameState(lh = h, rh = h, ly = 0.12f, ry = 0.12f, eyeColor = hex("#A5B4FC"), ringColor = hex("#6366F1"))
                }
                9 -> {
                    val p = (t % 2.6f) / 2.6f
                    val h = when {
                        p < 0.3f -> 0.12f
                        p < 0.65f -> 0.12f + ((p - 0.3f) / 0.35f) * 1.25f
                        else -> 1.37f - ((p - 0.65f) / 0.35f) * 0.37f
                    }
                    OrbFrameState(lh = h, rh = h, lw = 0.9f + 0.15f * h, rw = 0.9f + 0.15f * h, eyeColor = hex("#FDE68A"), ringColor = hex("#F59E0B"))
                }
                10 -> {
                    val p = (t % 2.8f) / 2.8f
                    val h = max(0.12f, 1f - p * 0.95f)
                    OrbFrameState(lh = h, rh = h, ly = p * 0.2f, ry = p * 0.2f, eyeColor = hex("#818CF8"), ringColor = hex("#4F46E5"))
                }
                11 -> {
                    OrbFrameState(
                        lx = 0.18f * sin(t * 1.3f), ly = 0.14f * cos(t * 1.9f),
                        rx = 0.18f * sin(t * 1.3f), ry = 0.14f * cos(t * 1.9f),
                        eyeColor = hex("#F4F4F5"), ringColor = hex("#52525B")
                    )
                }

                // === 2. 大小眼與縮放變化 (12-22) ===
                12 -> {
                    val s = 1.0f + 0.38f * (0.5f + 0.5f * sin(t * 3.8f))
                    OrbFrameState(lw = s, lh = s, rw = s, rh = s, eyeColor = hex("#FEF08A"), ringColor = hex("#EAB308"))
                }
                13 -> {
                    val s = 0.52f + 0.22f * (0.5f + 0.5f * cos(t * 3.5f))
                    OrbFrameState(lw = s, lh = s, rw = s, rh = s, eyeColor = hex("#67E8F9"), ringColor = hex("#0891B2"))
                }
                14 -> {
                    val w = 0.08f * sin(t * 3.2f)
                    OrbFrameState(lw = 1.32f + w, lh = 1.35f + w, rw = 0.72f - w, rh = 0.62f - w, eyeColor = hex("#5EEAD4"), ringColor = hex("#14B8A6"))
                }
                15 -> {
                    val w = 0.08f * sin(t * 3.2f)
                    OrbFrameState(lw = 0.72f - w, lh = 0.62f - w, rw = 1.32f + w, rh = 1.35f + w, eyeColor = hex("#5EEAD4"), ringColor = hex("#14B8A6"))
                }
                16 -> {
                    val s = 0.38f * sin(t * 4.2f)
                    OrbFrameState(lw = 1f + s * 0.7f, lh = 1f + s, rw = 1f - s * 0.7f, rh = 1f - s, eyeColor = hex("#D8B4FE"), ringColor = hex("#9333EA"))
                }
                17 -> {
                    val k = sin(t * 5.0f)
                    OrbFrameState(lw = 1f - 0.25f * k, lh = 1f + 0.38f * k, rw = 1f - 0.25f * k, rh = 1f + 0.38f * k, eyeColor = hex("#6EE7B7"), ringColor = hex("#10B981"))
                }
                18 -> {
                    val k = 0.5f + 0.5f * sin(t * 4.5f)
                    OrbFrameState(lw = 1f + 0.42f * k, lh = 1f - 0.45f * k, rw = 1f + 0.42f * k, rh = 1f - 0.45f * k, eyeColor = hex("#FBCFE8"), ringColor = hex("#EC4899"), blushColor = rgba(244, 114, 182, 0.28f))
                }
                19 -> {
                    val c = 0.5f + 0.5f * sin(t * 3.0f)
                    OrbFrameState(lx = 0.42f * c, rx = -0.42f * c, lw = 0.88f, rw = 0.88f, eyeColor = hex("#FDBA74"), ringColor = hex("#EA580C"))
                }
                20 -> {
                    val c = 0.5f + 0.5f * sin(t * 2.5f)
                    OrbFrameState(lx = -0.32f * c, rx = 0.32f * c, lh = 0.85f, rh = 0.85f, eyeColor = hex("#BAE6FD"), ringColor = hex("#0284C7"))
                }
                21 -> {
                    val p = t % 1.4f
                    val beat = when {
                        p < 0.16f -> sin((p / 0.16f) * PI_F) * 0.35f
                        p in 0.22f..0.38f -> sin(((p - 0.22f) / 0.16f) * PI_F) * 0.22f
                        else -> 0f
                    }
                    OrbFrameState(
                        lw = 1f + beat, lh = 1f + beat, rw = 1f + beat, rh = 1f + beat,
                        bodyScaleX = 1f + beat * 0.18f, bodyScaleY = 1f + beat * 0.18f,
                        eyeColor = hex("#FB7185"), ringColor = hex("#E11D48"), blushColor = rgba(244, 63, 94, 0.36f)
                    )
                }
                22 -> {
                    val p = (t % 2.0f) / 2.0f
                    val s = when {
                        p < 0.35f -> 1f - 0.45f * (p / 0.35f)
                        p < 0.55f -> 0.55f + 0.9f * ((p - 0.35f) / 0.2f)
                        else -> 1f + exp(-(p - 0.55f) * 8f) * 0.45f * cos((p - 0.55f) * 24f)
                    }
                    OrbFrameState(lw = s, lh = s, rw = s, rh = s, eyeColor = hex("#38BDF8"), ringColor = hex("#0284C7"))
                }

                // === 3. 視線方向與環視追蹤 (23-34) ===
                23 -> {
                    val k = 0.65f + 0.2f * sin(t * 2.5f)
                    OrbFrameState(lx = -0.55f * k, rx = -0.55f * k, lh = 1.06f, rh = 0.94f, bodyTiltX = -0.35f * k, eyeColor = hex("#E0F2FE"), ringColor = hex("#0EA5E9"))
                }
                24 -> {
                    val k = 0.65f + 0.2f * sin(t * 2.5f)
                    OrbFrameState(lx = 0.55f * k, rx = 0.55f * k, lh = 0.94f, rh = 1.06f, bodyTiltX = 0.35f * k, eyeColor = hex("#E0F2FE"), ringColor = hex("#0EA5E9"))
                }
                25 -> {
                    val k = 0.65f + 0.2f * sin(t * 2.5f)
                    OrbFrameState(ly = -0.52f * k, ry = -0.52f * k, bodyTiltY = -0.35f * k, eyeColor = hex("#E0F2FE"), ringColor = hex("#0EA5E9"))
                }
                26 -> {
                    val k = 0.65f + 0.2f * sin(t * 2.5f)
                    OrbFrameState(ly = 0.52f * k, ry = 0.52f * k, bodyTiltY = 0.35f * k, eyeColor = hex("#E0F2FE"), ringColor = hex("#0EA5E9"))
                }
                27 -> OrbFrameState(lx = -0.48f, ly = -0.42f, rx = -0.48f, ry = -0.42f, lh = 1.05f, rh = 0.92f, bodyTiltX = -0.25f, bodyTiltY = -0.25f, eyeColor = hex("#99F6E4"), ringColor = hex("#14B8A6"))
                28 -> OrbFrameState(lx = 0.48f, ly = -0.42f, rx = 0.48f, ry = -0.42f, lh = 0.92f, rh = 1.05f, bodyTiltX = 0.25f, bodyTiltY = -0.25f, eyeColor = hex("#99F6E4"), ringColor = hex("#14B8A6"))
                29 -> OrbFrameState(lx = -0.48f, ly = 0.42f, rx = -0.48f, ry = 0.42f, lh = 1.05f, rh = 0.92f, bodyTiltX = -0.25f, bodyTiltY = 0.25f, eyeColor = hex("#99F6E4"), ringColor = hex("#14B8A6"))
                30 -> OrbFrameState(lx = 0.48f, ly = 0.42f, rx = 0.48f, ry = 0.42f, lh = 0.92f, rh = 1.05f, bodyTiltX = 0.25f, bodyTiltY = 0.25f, eyeColor = hex("#99F6E4"), ringColor = hex("#14B8A6"))
                31 -> {
                    val p = (t * 0.9f) % 1.0f
                    val x = -0.55f + p * 1.1f
                    val y = -0.2f + 0.4f * floor((t * 0.9f) % 2f)
                    OrbFrameState(lx = x, rx = x, ly = y, ry = y, bodyTiltX = x * 0.4f, eyeColor = hex("#6EE7B7"), ringColor = hex("#10B981"))
                }
                32 -> {
                    val y = 0.55f * sin(t * 3.4f)
                    OrbFrameState(ly = y, ry = y, bodyTiltY = y * 0.45f, eyeColor = hex("#6EE7B7"), ringColor = hex("#10B981"))
                }
                33 -> {
                    val x = 0.5f * cos(t * 2.8f)
                    val y = 0.45f * sin(t * 2.8f)
                    OrbFrameState(lx = x, rx = x, ly = y, ry = y, bodyTiltX = x * 0.35f, bodyTiltY = y * 0.35f, eyeColor = hex("#C4B5FD"), ringColor = hex("#8B5CF6"))
                }
                34 -> {
                    val x = 0.55f * sin(t * 2.4f)
                    val y = 0.38f * sin(t * 4.8f)
                    OrbFrameState(lx = x, rx = x, ly = y, ry = y, bodyTiltX = x * 0.3f, eyeColor = hex("#67E8F9"), ringColor = hex("#06B6D4"))
                }

                // === 4. 多彩情緒與豐富表情 (35-50) ===
                35 -> {
                    val dy = -0.08f * abs(sin(t * 3f))
                    OrbFrameState(arcMode = EyeArcMode.HAPPY_ARC, ly = dy, ry = dy, eyeColor = hex("#FDE047"), ringColor = hex("#F59E0B"), blushColor = rgba(251, 113, 133, 0.35f))
                }
                36 -> {
                    val b = abs(sin(t * 6.5f)) * 0.35f
                    OrbFrameState(
                        arcMode = EyeArcMode.HAPPY_ARC, ly = -b, ry = -b,
                        bodyScaleX = 1f + b * 0.12f, bodyScaleY = 1f - b * 0.1f,
                        eyeColor = hex("#FB923C"), ringColor = hex("#F97316"), blushColor = rgba(244, 114, 182, 0.42f)
                    )
                }
                37 -> {
                    val tilt = 0.22f * sin(t * 2.5f)
                    OrbFrameState(lw = 1.25f, lh = 1.28f, rw = 0.78f, rh = 0.75f, ly = -0.15f, ry = 0.15f, lRotRad = tilt, rRotRad = tilt, eyeColor = hex("#2DD4BF"), ringColor = hex("#0D9488"))
                }
                38 -> {
                    val x = 0.28f * sin(t * 2.2f)
                    OrbFrameState(lh = 0.26f, rh = 0.26f, lw = 1.18f, rw = 1.18f, lx = x, rx = x, eyeColor = hex("#FBBF24"), ringColor = hex("#D97706"))
                }
                39 -> {
                    val p = 0.06f * sin(t * 8f)
                    OrbFrameState(lRotRad = 0.38f, rRotRad = -0.38f, lh = 0.78f + p, rh = 0.78f + p, eyeColor = hex("#F87171"), ringColor = hex("#EF4444"), blushColor = rgba(239, 68, 68, 0.28f))
                }
                40 -> {
                    val d = 0.05f * sin(t * 5f)
                    OrbFrameState(lRotRad = -0.35f, rRotRad = 0.35f, ly = 0.22f + d, ry = 0.22f + d, lh = 0.82f, rh = 0.82f, eyeColor = hex("#60A5FA"), ringColor = hex("#2563EB"))
                }
                41 -> {
                    val jx = sin(t * 45f) * 0.08f
                    val jy = cos(t * 52f) * 0.08f
                    OrbFrameState(lw = 1.3f, lh = 1.35f, rw = 1.3f, rh = 1.35f, lx = jx, rx = jx, ly = jy, ry = jy, eyeColor = hex("#C084FC"), ringColor = hex("#9333EA"))
                }
                42 -> {
                    val p = (t % 2.6f) / 2.6f
                    val y = if (p < 0.65f) -0.62f * sin((p / 0.65f) * (PI_F * 0.5f)) else -0.62f * (1f - (p - 0.65f) / 0.35f)
                    OrbFrameState(ly = y, ry = y, lh = 0.85f, rh = 0.85f, eyeColor = hex("#94A3B8"), ringColor = hex("#475569"))
                }
                43 -> {
                    // 害羞櫻花粉閃躲眼 + 粉紅雙頰腮紅
                    val x = if (sin(t * 3.8f) > 0f) 0.36f else -0.36f
                    OrbFrameState(
                        lx = x, rx = x, ly = 0.26f, ry = 0.26f, lh = 0.78f, rh = 0.78f,
                        eyeColor = hex("#F472B6"), ringColor = hex("#EC4899"), blushColor = rgba(244, 114, 182, 0.48f)
                    )
                }
                44 -> {
                    val lift = 0.28f * (0.5f + 0.5f * sin(t * 3.2f))
                    OrbFrameState(ly = 0.12f, lh = 0.65f, ry = -lift, rh = 1.22f, eyeColor = hex("#34D399"), ringColor = hex("#059669"))
                }
                45 -> OrbFrameState(arcMode = EyeArcMode.DOT, lx = 0.1f * sin(t * 2f), rx = 0.1f * sin(t * 2f), eyeColor = hex("#FDE68A"), ringColor = hex("#CA8A04"))
                46 -> {
                    val s = 1.28f + 0.1f * sin(t * 14f)
                    OrbFrameState(lw = s, lh = s, rw = s, rh = s, eyeColor = hex("#E879F9"), ringColor = hex("#C026D3"), blushColor = rgba(232, 121, 249, 0.36f))
                }
                47 -> {
                    OrbFrameState(
                        lx = 0.28f * cos(t * 5.5f), ly = 0.28f * sin(t * 5.5f),
                        rx = -0.28f * cos(t * 5.5f), ry = 0.28f * sin(t * 5.5f),
                        lh = 0.82f, rh = 0.82f, eyeColor = hex("#A3E635"), ringColor = hex("#65A30D")
                    )
                }
                48 -> {
                    val p = (t % 2.8f) / 2.8f
                    if (p < 0.78f) {
                        val k = p / 0.78f
                        OrbFrameState(lh = 1f - 0.82f * k, rh = 1f - 0.82f * k, ly = 0.45f * k, ry = 0.45f * k, bodyTiltY = 0.3f * k, eyeColor = hex("#A78BFA"), ringColor = hex("#7C3AED"))
                    } else {
                        OrbFrameState(lh = 1.25f, rh = 1.25f, ly = -0.15f, ry = -0.15f, eyeColor = hex("#FDE047"), ringColor = hex("#EAB308"))
                    }
                }
                49 -> {
                    val step = (floor(t * 1.8f).toInt().coerceAtLeast(0)) % 3
                    val px = floatArrayOf(-0.4f, 0.42f, 0f)[step]
                    val py = floatArrayOf(-0.2f, 0.15f, 0f)[step]
                    OrbFrameState(lx = px, rx = px, ly = py, ry = py, lw = 1.15f, rw = 1.15f, lh = 1.15f, rh = 1.15f, eyeColor = hex("#22D3EE"), ringColor = hex("#0891B2"))
                }
                50 -> {
                    val w = max(0f, sin(t * 3.2f))
                    OrbFrameState(
                        rh = 1f - 0.85f * w, lh = 1f + 0.1f * w, lRotRad = -0.18f * w, rRotRad = -0.18f * w,
                        bodyTiltX = 0.2f * w, eyeColor = hex("#FB7185"), ringColor = hex("#F43F5E"), blushColor = rgba(251, 113, 133, 0.40f)
                    )
                }

                // === 5. 語音聆聽與聲波互動 (51-61) ===
                51 -> OrbFrameState(
                    lh = 0.45f + 0.85f * abs(sin(t * 9.5f)),
                    rh = 0.45f + 0.85f * abs(cos(t * 8.2f)),
                    ringColor = hex("#3B82F6"), eyeColor = hex("#60A5FA")
                )
                52 -> {
                    val s = 1f + 0.18f * sin(t * 4.5f)
                    val bs = 1f + 0.06f * sin(t * 4.5f)
                    OrbFrameState(lw = s, lh = s, rw = s, rh = s, bodyScaleX = bs, bodyScaleY = bs, ringColor = hex("#3B82F6"), eyeColor = hex("#93C5FD"))
                }
                53 -> {
                    val syl = 0.7f + 0.55f * abs(sin(t * 11f) * cos(t * 3.7f))
                    val w = 1f + (syl - 1f) * 0.3f
                    OrbFrameState(lh = syl, rh = syl, lw = w, rw = w, eyeColor = hex("#5EEAD4"), ringColor = hex("#0D9488"))
                }
                54 -> {
                    val peak = (0.5f + 0.5f * sin(t * 7f)).pow(2)
                    OrbFrameState(lw = 1f + 0.45f * peak, lh = 1f + 0.5f * peak, rw = 1f + 0.45f * peak, rh = 1f + 0.5f * peak, ringColor = hex("#2563EB"), eyeColor = hex("#38BDF8"))
                }
                55 -> {
                    val m = 0.85f + 0.12f * sin(t * 10f)
                    OrbFrameState(lh = m, rh = m, ringColor = hex("#3B82F6"), eyeColor = hex("#93C5FD"))
                }
                56 -> {
                    val s = 0.85f + 0.35f * (0.5f + 0.5f * sin(t * 5f))
                    OrbFrameState(arcMode = EyeArcMode.RING, lw = s, lh = s, rw = s, rh = s, ringColor = hex("#06B6D4"), eyeColor = hex("#67E8F9"))
                }
                57 -> {
                    val v = sin(t * 8.0f)
                    OrbFrameState(lh = 0.9f + 0.5f * v, rh = 0.9f - 0.5f * v, ringColor = hex("#6366F1"), eyeColor = hex("#A5B4FC"))
                }
                58 -> {
                    val p = (t % 2.0f) / 2.0f
                    val nod = if (p in 0.5f..0.85f) sin(((p - 0.5f) / 0.35f) * PI_F) * 0.35f else 0f
                    OrbFrameState(ly = nod, ry = nod, lh = 1f + nod * 0.3f, rh = 1f + nod * 0.3f, eyeColor = hex("#34D399"), ringColor = hex("#10B981"))
                }
                59 -> OrbFrameState(arcMode = EyeArcMode.LINE, lh = 0.16f, rh = 0.16f, eyeColor = hex("#A1A1AA"), ringColor = hex("#3F3F46"))
                60 -> {
                    val p = t % 2.0f
                    val n = if (p < 0.6f) abs(sin(p * PI_F * 3.3f)) * 0.42f else 0f
                    OrbFrameState(ly = n, ry = n, bodyTiltY = n * 0.6f, eyeColor = hex("#6EE7B7"), ringColor = hex("#10B981"))
                }
                61 -> {
                    val k = sin(t * 3.0f)
                    OrbFrameState(lh = 0.55f, rh = 1.28f, lRotRad = 0.25f * k, rRotRad = 0.25f * k, lx = 0.2f * k, rx = 0.2f * k, eyeColor = hex("#FCD34D"), ringColor = hex("#F59E0B"))
                }

                // === 6. AI 思考、視覺掃描與自動化執行 (62-73) ===
                62 -> OrbFrameState(ly = 0.32f * sin(t * 5.0f), ry = 0.32f * cos(t * 5.0f), eyeColor = hex("#D8B4FE"), ringColor = hex("#A855F7"))
                63 -> {
                    val s = sin(t * 3.6f)
                    OrbFrameState(ly = s * 0.35f, ry = s * 0.35f, scanLineY = s, eyeColor = hex("#6EE7B7"), ringColor = hex("#10B981"))
                }
                64 -> {
                    val k = 0.5f + 0.5f * sin(t * 4.0f)
                    OrbFrameState(lw = 0.75f + 0.5f * k, rw = 0.75f + 0.5f * k, lh = 1.2f - 0.35f * k, rh = 1.2f - 0.35f * k, eyeColor = hex("#67E8F9"), ringColor = hex("#0891B2"))
                }
                65 -> {
                    val r = 0.35f * sin(t * 4.2f)
                    val x = 0.2f * cos(t * 4.2f)
                    OrbFrameState(lRotRad = r, rRotRad = r, lx = x, rx = x, eyeColor = hex("#C4B5FD"), ringColor = hex("#7C3AED"))
                }
                66 -> {
                    val p = (t % 1.5f) / 1.5f
                    val lock = if (p < 0.5f) p * 2f else 1f
                    OrbFrameState(lx = 0.18f * lock, rx = -0.18f * lock, lw = 1.15f, rw = 1.15f, lh = 1.15f - 0.3f * lock, rh = 1.15f - 0.3f * lock, eyeColor = hex("#22D3EE"), ringColor = hex("#06B6D4"))
                }
                67 -> {
                    val p = (t % 1.4f) / 1.4f
                    val y = 0.45f - p * 0.9f
                    val h = 0.85f + 0.3f * sin(p * PI_F)
                    OrbFrameState(ly = y, ry = y, lh = h, rh = h, eyeColor = hex("#7DD3FC"), ringColor = hex("#0284C7"))
                }
                68 -> {
                    val p = (t % 1.4f) / 1.4f
                    val y = -0.45f + p * 0.9f
                    val h = 0.85f + 0.3f * sin(p * PI_F)
                    OrbFrameState(ly = y, ry = y, lh = h, rh = h, eyeColor = hex("#7DD3FC"), ringColor = hex("#0284C7"))
                }
                69 -> {
                    val p = (t % 1.2f) / 1.2f
                    val x = if (p < 0.4f) (p / 0.4f) * 0.8f else -0.8f + ((p - 0.4f) / 0.6f) * 0.8f
                    OrbFrameState(lx = x, rx = x, lw = 1.35f, rw = 1.35f, lh = 0.65f, rh = 0.65f, eyeColor = hex("#38BDF8"), ringColor = hex("#2563EB"))
                }
                70 -> {
                    val y = abs(sin(t * 5.0f)) * 0.25f
                    OrbFrameState(arcMode = EyeArcMode.HAPPY_ARC, ly = -0.1f + y, ry = -0.1f + y, eyeColor = hex("#34D399"), ringColor = hex("#10B981"), blushColor = rgba(52, 211, 153, 0.28f))
                }
                71 -> {
                    val x = 0.45f * sin(t * 10.0f)
                    OrbFrameState(lx = x, rx = x, bodyTiltX = x * 0.5f, eyeColor = hex("#FCA5A5"), ringColor = hex("#EF4444"))
                }
                72 -> {
                    val s = 1f + 0.06f * sin(t * 3.0f)
                    OrbFrameState(lw = s, lh = s, rw = s, rh = s, ringColor = hex("#F59E0B"), eyeColor = hex("#FDE68A"))
                }
                73 -> {
                    val h = 0.72f + 0.08f * sin(t * 1.8f)
                    OrbFrameState(lh = h, rh = h, eyeColor = hex("#71717A"), ringColor = hex("#27272A"))
                }

                // === 7. 拖曳吸邊物理與展開收合容器轉場 (74-88) ===
                74 -> {
                    val p = 0.5f + 0.5f * sin(t * 4.0f)
                    OrbFrameState(bodyScaleX = 1f - 0.08f * p, bodyScaleY = 1f - 0.08f * p, lh = 1f - 0.25f * p, rh = 1f - 0.25f * p, eyeColor = hex("#E0F2FE"), ringColor = hex("#38BDF8"))
                }
                75 -> {
                    val p = t % 1.8f
                    val s = 1f + exp(-p * 4.5f) * 0.22f * cos(p * 16f)
                    OrbFrameState(bodyScaleX = s, bodyScaleY = 2f - s, lh = s, rh = s, eyeColor = hex("#F4F4F5"), ringColor = hex("#52525B"))
                }
                76 -> {
                    val k = 0.5f + 0.5f * sin(t * 4.0f)
                    OrbFrameState(bodyScaleX = 1f + 0.12f * k, bodyScaleY = 1f - 0.08f * k, lx = -0.45f * k, rx = -0.45f * k, bodyTiltX = -0.35f * k, eyeColor = hex("#67E8F9"), ringColor = hex("#06B6D4"))
                }
                77 -> {
                    val k = 0.5f + 0.5f * sin(t * 4.0f)
                    OrbFrameState(bodyScaleX = 1f + 0.12f * k, bodyScaleY = 1f - 0.08f * k, lx = 0.45f * k, rx = 0.45f * k, bodyTiltX = 0.35f * k, eyeColor = hex("#67E8F9"), ringColor = hex("#06B6D4"))
                }
                78 -> {
                    val p = t % 1.6f
                    val d = exp(-p * 5f) * sin(p * 18f) * 0.22f
                    OrbFrameState(bodyScaleX = 1f - d, bodyScaleY = 1f + d * 0.7f, lx = -d * 1.5f, rx = -d * 1.5f, eyeColor = hex("#A5F3FC"), ringColor = hex("#0891B2"))
                }
                79 -> {
                    val p = t % 1.6f
                    val d = exp(-p * 5f) * sin(p * 18f) * 0.22f
                    OrbFrameState(bodyScaleX = 1f - d, bodyScaleY = 1f + d * 0.7f, lx = d * 1.5f, rx = d * 1.5f, eyeColor = hex("#A5F3FC"), ringColor = hex("#0891B2"))
                }
                80 -> {
                    val peek = 0.35f + 0.2f * sin(t * 2.4f)
                    OrbFrameState(lx = -peek, rx = -peek, bodyTiltX = 0.4f, lh = 1.1f, rh = 0.9f, eyeColor = hex("#FDE68A"), ringColor = hex("#D97706"))
                }
                81 -> {
                    val p = 0.5f + 0.5f * sin(t * 3.5f)
                    OrbFrameState(bodyScaleX = 1f + 0.22f * p, bodyScaleY = 1f - 0.08f * p, lx = -0.3f * p, rx = 0.3f * p, lh = 1f - 0.5f * p, rh = 1f - 0.5f * p, eyeColor = hex("#F4F4F5"), ringColor = hex("#71717A"))
                }
                82 -> {
                    val p = t % 1.8f
                    val s = 1f - exp(-p * 5f) * 0.2f * cos(p * 15f)
                    val b = if (p in 0.3f..0.46f) 0.85f else 0f
                    OrbFrameState(bodyScaleX = s, bodyScaleY = s, lh = 1f - b, rh = 1f - b, eyeColor = hex("#F4F4F5"), ringColor = hex("#52525B"))
                }
                83 -> {
                    val p = (t % 1.8f) / 1.8f
                    val charge = if (p < 0.65f) 1f - 0.12f * (p / 0.65f) else 1.14f
                    val rCol = if (p >= 0.65f) hex("#3B82F6") else hex("#52525B")
                    val eCol = if (p >= 0.65f) hex("#60A5FA") else hex("#F4F4F5")
                    OrbFrameState(bodyScaleX = charge, bodyScaleY = charge, lh = charge, rh = charge, ringColor = rCol, eyeColor = eCol)
                }
                84 -> {
                    val b = abs(sin(t * 5.2f))
                    OrbFrameState(
                        arcMode = if (b > 0.5f) EyeArcMode.HAPPY_ARC else EyeArcMode.PILL,
                        bodyTiltY = -0.35f * b, ly = -0.25f * b, ry = -0.25f * b,
                        eyeColor = hex("#FDE047"), ringColor = hex("#F59E0B"), blushColor = rgba(244, 114, 182, 0.35f)
                    )
                }
                85 -> {
                    val p = t % 2.2f
                    val w = exp(-p * 2.5f) * sin(p * 16f) * 0.55f
                    OrbFrameState(lx = w, rx = w, lRotRad = w * 0.5f, rRotRad = w * 0.5f, bodyTiltX = w * 0.5f, eyeColor = hex("#A3E635"), ringColor = hex("#65A30D"))
                }
                86 -> {
                    val p = t % 1.6f
                    val c = exp(-p * 4.5f) * sin(p * 14f) * 0.2f
                    OrbFrameState(bodyScaleY = 1f - c, bodyScaleX = 1f + c * 0.6f, ly = c * 1.4f, ry = c * 1.4f, eyeColor = hex("#FDBA74"), ringColor = hex("#EA580C"))
                }
                87 -> {
                    val lift = 0.35f * sin(t * 2.6f)
                    OrbFrameState(bodyTiltY = -lift, ly = 0.32f, ry = 0.32f, eyeColor = hex("#93C5FD"), ringColor = hex("#3B82F6"))
                }
                88 -> {
                    val p = (t % 2.2f) / 2.2f
                    val wink = if (p < 0.4f) sin((p / 0.4f) * PI_F) else 0f
                    val shrink = if (p > 0.45f) max(0.25f, 1f - ((p - 0.45f) / 0.55f) * 0.75f) else 1f
                    OrbFrameState(rh = 1f - 0.85f * wink, bodyScaleX = shrink, bodyScaleY = shrink, eyeColor = hex("#F9A8D4"), ringColor = hex("#EC4899"), blushColor = rgba(244, 114, 182, 0.32f))
                }
                else -> OrbFrameState()
            }
        }
    }
}
