package com.example.newandroidapp.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 四階段 AI 視覺管線透視渲染視圖（VisionVisualizerView）
 *
 * 將底層畫面、2D 遮罩佔用格網、被剔除的幽靈座標、L 型部分遮擋位移向量、
 * 最小視覺外框收斂、無字圖示空間吸附、以及局部 ROI 雙軌補盲結果繪製於同一張互動畫布上。
 */
class VisionVisualizerView(context: Context) : View(context) {

    var sceneBitmap: Bitmap? = null
    var report: VisionPipelineReport? = null
    var showOccupancyGrid: Boolean = true
    var showCulledGhosts: Boolean = true
    var showConvergenceVectors: Boolean = true
    var showRoiFallbackBoxes: Boolean = true
    var onNodeSelected: ((Stage2ConvergedNode?) -> Unit)? = null

    private var selectedNode: Stage2ConvergedNode? = null
    var aiLockedNode: Stage2ConvergedNode? = null
        set(value) {
            field = value
            if (value != null) selectedNode = value
            invalidate()
        }

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gridCellPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val ghostBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#EF4444")
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val roiVoidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#6366F1")
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }
    private val rawBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#80F59E0B")
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val convergedBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#10B981")
    }
    private val roiRecoveredBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#F59E0B")
    }
    private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#F59E0B")
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun updateData(bitmap: Bitmap?, pipelineReport: VisionPipelineReport) {
        this.sceneBitmap = bitmap
        this.report = pipelineReport
        this.selectedNode = pipelineReport.finalNodes.firstOrNull()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rep = report ?: return
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val scaleX = vw / rep.screenWidth.toFloat()
        val scaleY = vh / rep.screenHeight.toFloat()

        // 1. 繪製底層畫面 Bitmap
        sceneBitmap?.let { bmp ->
            com.example.newandroidapp.stability.CrashGuardManager.useBitmapSafely(bmp) { safeBmp ->
                if (safeBmp != null && !safeBmp.isRecycled) {
                    runCatching {
                        canvas.drawBitmap(safeBmp, null, RectF(0f, 0f, vw, vh), bmpPaint)
                    }
                }
            }
        }

        // 2. 繪製第一階段：2D 遮罩佔用格網（Occupancy Grid 半透明光柵化熱力圖）
        if (showOccupancyGrid) {
            val cellW = vw / rep.gridCols
            val cellH = vh / rep.gridRows
            val snap = rep.occupancyGridSnapshot
            for (r in 0 until rep.gridRows) {
                val rowOff = r * rep.gridCols
                for (c in 0 until rep.gridCols) {
                    val layerVal = snap[rowOff + c].toInt()
                    if (layerVal > 0) {
                        // Layer 2 (頂層懸浮窗) 顯示青藍色網格，Layer 1 顯示淡紫網格
                        gridCellPaint.color = if (layerVal >= 2) {
                            Color.argb(46, 0, 229, 255)
                        } else {
                            Color.argb(24, 124, 77, 255)
                        }
                        canvas.drawRect(
                            c * cellW,
                            r * cellH,
                            (c + 1) * cellW,
                            (r + 1) * cellH,
                            gridCellPaint
                        )
                    }
                }
            }
        }

        // 3. 繪製第一階段被剔除的「幽靈座標（遮擋 > 80%）」
        if (showCulledGhosts) {
            for (s1 in rep.stage1AllNodes) {
                if (!s1.isCulled) continue
                val b = s1.raw.bounds
                val rf = RectF(b.left * scaleX, b.top * scaleY, b.right * scaleX, b.bottom * scaleY)
                canvas.drawRoundRect(rf, 10f, 10f, ghostBoxPaint)
                // 畫紅色叉號代表已被 Z-Order 遮罩剔除
                canvas.drawLine(rf.left + 8f, rf.top + 8f, rf.right - 8f, rf.bottom - 8f, ghostBoxPaint)
                canvas.drawLine(rf.right - 8f, rf.top + 8f, rf.left + 8f, rf.bottom - 8f, ghostBoxPaint)

                val ghostTitle = "已剔除:${s1.raw.text.ifBlank { "幽靈節點" }}(露${(s1.visibleRatio * 100).toInt()}%)"
                drawTagBadge(
                    canvas = canvas,
                    x = rf.left + 6f,
                    y = (rf.bottom - 28f).coerceAtLeast(rf.top + 4f),
                    text = ghostTitle,
                    bgColor = Color.argb(220, 183, 28, 28)
                )
            }
        }

        // 4. 繪製第三階段：偵測到的結構空洞（面積 > 15% 的自繪 Canvas 黑盒 ROI）
        if (showRoiFallbackBoxes) {
            for (voidRoi in rep.detectedRoiVoids) {
                val r = voidRoi.roiRect
                val rf = RectF(r.left * scaleX, r.top * scaleY, r.right * scaleX, r.bottom * scaleY)
                canvas.drawRoundRect(rf, 14f, 14f, roiVoidPaint)
                val areaPct = (voidRoi.screenAreaRatio * 100).toInt()
                val cacheTag = if (voidRoi.cacheHit) "dHash快取" else "ROI雙軌介入"
                drawTagBadge(
                    canvas = canvas,
                    x = rf.left + 8f,
                    y = rf.top + 8f,
                    text = "黑盒空洞(${areaPct}%螢幕) -> $cacheTag 補回${voidRoi.recoveredNodes.size}項",
                    bgColor = Color.argb(225, 106, 27, 154)
                )
            }
        }

        // 5. 繪製所有最終存活並收斂的節點（含熱區收斂偏移箭頭、空間吸附標記、準星與 #ID 網格座標）
        for (node in rep.finalNodes) {
            val conv = node.convergedBounds
            val cf = RectF(conv.left * scaleX, conv.top * scaleY, conv.right * scaleX, conv.bottom * scaleY)

            val isRoiNode = node.sourceType == NodeSourceType.ROI_OCR_TEXT ||
                node.sourceType == NodeSourceType.ROI_EDGE_BUTTON

            // 若發生過「最小視覺外框收斂」或「部分遮擋安全點位移」，畫出原始外框與位移向量
            val origCx = node.rawOriginalBounds.centerX() * scaleX
            val origCy = node.rawOriginalBounds.centerY() * scaleY
            val tapX = node.finalTapPx.x * scaleX
            val tapY = node.finalTapPx.y * scaleY

            if (showConvergenceVectors && (node.wasHotspotConverged || node.visibleRatio < 0.98f)) {
                val ob = node.rawOriginalBounds
                val of = RectF(ob.left * scaleX, ob.top * scaleY, ob.right * scaleX, ob.bottom * scaleY)
                canvas.drawRoundRect(of, 8f, 8f, rawBoxPaint)

                if (hypot((tapX - origCx).toDouble(), (tapY - origCy).toDouble()) > 8.0) {
                    // 原始中心點（空心橘圈）-> 收斂後真實安全點擊點（實心綠準星）
                    badgeBgPaint.color = Color.parseColor("#FFA726")
                    canvas.drawCircle(origCx, origCy, 6f, badgeBgPaint)
                    canvas.drawLine(origCx, origCy, tapX, tapY, vectorPaint)
                }
            }

            // 繪製收斂後的有效邊界
            val boxPaint = if (isRoiNode) roiRecoveredBoxPaint else convergedBoxPaint
            if (node == selectedNode) {
                boxPaint.strokeWidth = 5.5f
            } else {
                boxPaint.strokeWidth = 3.2f
            }
            canvas.drawRoundRect(cf, 8f, 8f, boxPaint)

            // 繪製 100% 精準點擊準星（Target Crosshair）
            val crossColor = when (node.sourceType) {
                NodeSourceType.ROI_OCR_TEXT, NodeSourceType.ROI_EDGE_BUTTON -> Color.parseColor("#F59E0B")
                NodeSourceType.SPATIAL_MERGED_ICON -> Color.parseColor("#0EA5E9")
                NodeSourceType.GEOMETRIC_ICON_CLASSIFIED -> Color.parseColor("#6366F1")
                else -> Color.parseColor("#10B981")
            }
            badgeBgPaint.color = crossColor
            canvas.drawCircle(tapX, tapY, 8f, badgeBgPaint)
            badgeBgPaint.color = Color.WHITE
            canvas.drawCircle(tapX, tapY, 3f, badgeBgPaint)

            // 若此節點為本地 AI 推理鎖定之目標，繪製雙層青色鎖定光環與 AI 標籤
            if (node.nodeId == aiLockedNode?.nodeId) {
                badgeBgPaint.style = Paint.Style.STROKE
                badgeBgPaint.strokeWidth = 4f
                badgeBgPaint.color = Color.parseColor("#0EA5E9")
                canvas.drawCircle(tapX, tapY, 22f, badgeBgPaint)
                canvas.drawCircle(tapX, tapY, 32f, badgeBgPaint)
                badgeBgPaint.style = Paint.Style.FILL
                drawTagBadge(
                    canvas = canvas,
                    x = (tapX - 85f).coerceIn(4f, (vw - 200f).coerceAtLeast(4f)),
                    y = (tapY + 16f).coerceAtMost(vh - 32f),
                    text = "AI鎖定點 #${node.shortMarkId} (${node.finalTapPx.x},${node.finalTapPx.y})",
                    bgColor = Color.argb(235, 37, 99, 235)
                )
            }

            // 繪製 #ID + 狀態內嵌 + 0~999 網格座標標籤（統一深石板色底）
            val badgeColor = Color.argb(225, 15, 23, 42)
            val shortLbl = if (node.label.length > 9) node.label.take(9) + "…" else node.label
            val badgeText = "#${node.shortMarkId} ${node.inlineStatePrefix}$shortLbl @${node.gridTapPoint.x},${node.gridTapPoint.y}"
            drawTagBadge(
                canvas = canvas,
                x = cf.left.coerceIn(4f, (vw - 240f).coerceAtLeast(4f)),
                y = (cf.top - 26f).coerceAtLeast(4f),
                text = badgeText,
                bgColor = badgeColor
            )
        }
    }

    private fun drawTagBadge(canvas: Canvas, x: Float, y: Float, text: String, bgColor: Int) {
        val padH = 8f
        val textW = labelTextPaint.measureText(text)
        val boxH = 26f
        val rect = RectF(x, y, x + textW + padH * 2, y + boxH)
        badgeBgPaint.color = bgColor
        canvas.drawRoundRect(rect, 6f, 6f, badgeBgPaint)
        canvas.drawText(text, x + padH, y + boxH - 6f, labelTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val rep = report ?: return super.onTouchEvent(event)
            val vw = width.toFloat().coerceAtLeast(1f)
            val vh = height.toFloat().coerceAtLeast(1f)
            val screenX = (event.x / vw) * rep.screenWidth
            val screenY = (event.y / vh) * rep.screenHeight

            val hit = rep.finalNodes.minByOrNull { node ->
                hypot(
                    (node.finalTapPx.x - screenX).toDouble(),
                    (node.finalTapPx.y - screenY).toDouble()
                )
            }
            if (hit != null) {
                selectedNode = hit
                onNodeSelected?.invoke(hit)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
