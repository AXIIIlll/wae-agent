package com.wae.agent.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * L1 視覺召回通道（NanoDet/OmniParser icon_detect）。
 *
 * 定位：**a11y 樹的兜底**，不是替代。只有在下列情況才觸發，避免每輪都付出截圖+推理成本：
 *  1) a11y 候選 < 3（Canvas / Flutter 自繪 / 影片播放器等幾乎沒有節點的頁面）
 *  2) 畫面上沒有任何「可點或可輸入」的 a11y 候選（有節點但都不可操作）
 *
 * 融合策略：
 *  - 與 a11y 候選 IoU ≥ 0.5 → 視為同一元素，只補 isClickable（幾何以 a11y 為準）
 *  - 否則獨立加入，source = NANODET_ROI，viewId = "visual.icon"（L4 沒有節點 → 自動走物理手勢）
 *  - 若附近有 a11y 文字（1.2 倍框高內）→ 焊接成 coreText，讓 L3 的語意打分能用得上
 */
object VisualChannel {

    private const val TAG = "WAE_VISION"
    private const val MARK_ID_BASE = 10_000
    private const val MAX_VISUAL_CANDIDATES = 12
    private const val MAX_OCR_CANDIDATES = 12
    private const val MIN_BOX_DP = 8f
    private const val MERGE_IOU = 0.50f
    private const val WELD_RADIUS_RATIO = 1.2f

    data class A11yTextAnchor(val text: String, val rect: Rect)

    fun shouldRun(a11yCandidates: List<CandidateOption>): Boolean {
        if (!OmniParserIconDetector.isReady) return false
        if (OmniParserIconDetector.debugAlwaysRun) return true
        if (a11yCandidates.size < 3) return true
        val hasActionable = a11yCandidates.any { it.isClickable || it.isEditable }
        return !hasActionable
    }

    /**
     * OCR 的獨立門控（VoI 調度會再依成本/增益決定是否真的跑）。
     * 這裡只回答「OCR 有沒有可能帶來新資訊」：
     *  - 畫面候選很少（可能全是圖）
     *  - 有節點但幾乎沒有文字（Flutter 常見）
     *  - 完全沒有可操作控件
     */
    fun shouldRunOcr(a11yCandidates: List<CandidateOption>): Boolean {
        if (!PpOcrEngine.isReady) return false
        if (OmniParserIconDetector.debugAlwaysRun) return true
        if (a11yCandidates.size < 3) return true
        val textful = a11yCandidates.count { it.coreText.isNotBlank() }
        if (textful < 2) return true
        return a11yCandidates.none { it.isClickable || it.isEditable }
    }

    /**
     * @param shot 已截好的螢幕 Bitmap（呼叫方負責 recycle）
     * @param anchors 畫面上 a11y 文字節點，用於空間焊接
     */
    fun collect(
        shot: Bitmap,
        screenW: Int,
        screenH: Int,
        density: Float,
        windowId: Int,
        packageName: String,
        anchors: List<A11yTextAnchor>,
        /** VoI：是否跑圖示檢測 */
        includeIcons: Boolean = true,
        /** VoI：是否跑 OCR */
        includeOcr: Boolean = true
    ): List<CandidateOption> {
        val boxes = if (includeIcons) {
            OmniParserIconDetector.detect(shot, screenW, screenH)
        } else {
            emptyList()
        }

        // ── OCR：整屏一次檢測+識別（只在視覺通道被觸發時跑）──────────────
        // 目的：把「無標籤圖標」變成「有文字的候選」，否則 L3 的語意打分無從下手。
        // 注意：OCR 的最小文字高度是以「bitmap 像素」計算，因此必須把螢幕 density 換算到 bitmap 尺度，
        // 否則縮圖後每行字都會被誤判為「太小」而全部丟棄（實測 25px 的字被 28px 門檻濾掉）。
        val ocrDensity = density * (shot.height.toFloat() / screenH.toFloat())
        val ocrLines = if (includeOcr && PpOcrEngine.isReady) {
            try {
                PpOcrEngine.detectAndRecognize(shot, maxLines = 16, density = ocrDensity)
            } catch (t: Throwable) {
                Log.e(TAG, "ocr failed", t)
                emptyList()
            }
        } else {
            emptyList()
        }
        val textAnchors = ArrayList<A11yTextAnchor>(anchors.size + ocrLines.size)
        textAnchors.addAll(anchors)
        for (l in ocrLines) textAnchors.add(A11yTextAnchor(l.text, l.rect))

        val out = ArrayList<CandidateOption>(min(boxes.size, MAX_VISUAL_CANDIDATES) + ocrLines.size)
        val minPx = max(8f, MIN_BOX_DP * density)

        // 縮放：shot 可能是縮小過的截圖，OCR/偵測座標都落在 shot 上，需換算回螢幕座標
        val toScreenX = screenW.toFloat() / shot.width.toFloat()
        val toScreenY = screenH.toFloat() / shot.height.toFloat()

        for ((idx, b) in boxes.withIndex()) {
            if (out.size >= MAX_VISUAL_CANDIDATES) break
            if (b.w < minPx || b.h < minPx) continue
            // 過大的框（整頁/整個螢幕）不是「按鈕」，通常是誤檢
            if (b.area > screenW * screenH * 0.6f) continue

            val grid = intArrayOf(
                b.x1.toInt(), b.y1.toInt(), b.x2.toInt(), b.y2.toInt()
            )
            val weldedText = weldNearestText(grid, textAnchors)
            val roleTag = if (weldedText.isNotEmpty()) "visual.icon" else "visual.icon.unlabeled"
            out.add(
                CandidateOption(
                    markId = MARK_ID_BASE + idx,
                    nodeStableSig = 0L,                     // 無節點 → 不會進入死點黑名單比對
                    source = if (weldedText.isNotEmpty()) CandidateSource.HYBRID_WELDED
                    else CandidateSource.NANODET_ROI,
                    detectorConfidence = b.score,
                    grid = grid,
                    coreText = weldedText,
                    weldedDescription = if (weldedText.isNotEmpty()) {
                        "[VISUAL|ICON] 鄰近:$weldedText"
                    } else {
                        "[VISUAL|ICON] 無標籤圖標"
                    },
                    hybridSimScore = 0f,
                    isEnabled = true,
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    parentContainerType = "VISUAL",
                    gridZone = zoneOf(grid, screenW, screenH),
                    roleTag = roleTag,
                    viewId = "visual.icon",
                    className = "visual"
                )
            )
        }

        // OCR 文字行本身也是候選（Canvas/Flutter 頁面常常只有圖沒有節點）
        for ((i, line) in ocrLines.withIndex()) {
            if (out.size >= MAX_VISUAL_CANDIDATES + MAX_OCR_CANDIDATES) break
            val grid = intArrayOf(
                (line.rect.left * toScreenX).toInt(),
                (line.rect.top * toScreenY).toInt(),
                (line.rect.right * toScreenX).toInt(),
                (line.rect.bottom * toScreenY).toInt()
            )
            // 與既有視覺候選重疊的（同一個按鈕裡的字）不重複加入
            if (out.any { overlapIou(it.grid, grid) >= MERGE_IOU }) continue
            out.add(
                CandidateOption(
                    markId = MARK_ID_BASE + 500 + i,
                    nodeStableSig = 0L,
                    source = CandidateSource.HYBRID_WELDED,
                    detectorConfidence = line.confidence,
                    grid = grid,
                    coreText = line.text.take(40),
                    weldedDescription = "[OCR|TEXT] ${line.text.take(40)}",
                    hybridSimScore = 0f,
                    isEnabled = true,
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    parentContainerType = "OCR",
                    gridZone = zoneOf(grid, screenW, screenH),
                    roleTag = "ocr.text",
                    viewId = "ocr.text",
                    className = "ocr"
                )
            )
        }

        Log.i(TAG, "visual candidates=${out.size} icons=${boxes.size} ocrLines=${ocrLines.size} (pkg=$packageName win=$windowId)")
        return out
    }

    /**
     * 把視覺框與 a11y 文字候選融合：重疊者補 isClickable，其餘追加。
     */
    fun merge(a11y: List<CandidateOption>, visual: List<CandidateOption>): List<CandidateOption> {
        if (visual.isEmpty()) return a11y
        val merged = a11y.toMutableList()
        for (v in visual) {
            val hit = merged.indexOfFirst { overlapIou(it.grid, v.grid) >= MERGE_IOU }
            if (hit >= 0) {
                val a = merged[hit]
                merged[hit] = a.copy(
                    isClickable = a.isClickable || v.isClickable,
                    coreText = a.coreText.ifEmpty { v.coreText },
                    source = CandidateSource.HYBRID_WELDED,
                    detectorConfidence = max(a.detectorConfidence, v.detectorConfidence)
                )
            } else {
                merged.add(v)
            }
        }
        return merged
    }

    private fun weldNearestText(grid: IntArray, anchors: List<A11yTextAnchor>): String {
        if (anchors.isEmpty()) return ""
        val cx = (grid[0] + grid[2]) / 2f
        val cy = (grid[1] + grid[3]) / 2f
        val h = (grid[3] - grid[1]).coerceAtLeast(1)
        val radius = h * WELD_RADIUS_RATIO

        var best: A11yTextAnchor? = null
        var bestDist = Float.MAX_VALUE
        for (a in anchors) {
            if (a.text.isEmpty()) continue
            val ax = (a.rect.left + a.rect.right) / 2f
            val ay = (a.rect.top + a.rect.bottom) / 2f
            // 只焊接「垂直方向很近、且不想太遠」的文字（避免把整個列表的標題焊給圖標）
            val dy = abs(ay - cy)
            val dx = abs(ax - cx)
            if (dy > radius || dx > radius * 3f) continue
            val dist = dy + dx * 0.25f
            if (dist < bestDist) {
                bestDist = dist
                best = a
            }
        }
        return best?.text?.take(40) ?: ""
    }

    private fun overlapIou(a: IntArray, b: IntArray): Float {
        val ix1 = max(a[0], b[0])
        val iy1 = max(a[1], b[1])
        val ix2 = min(a[2], b[2])
        val iy2 = min(a[3], b[3])
        val iw = (ix2 - ix1).coerceAtLeast(0)
        val ih = (iy2 - iy1).coerceAtLeast(0)
        val inter = iw.toFloat() * ih
        if (inter <= 0f) return 0f
        val areaA = (a[2] - a[0]).toFloat() * (a[3] - a[1])
        val areaB = (b[2] - b[0]).toFloat() * (b[3] - b[1])
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun zoneOf(grid: IntArray, screenW: Int, screenH: Int): String {
        val cx = (grid[0] + grid[2]) / 2
        val cy = (grid[1] + grid[3]) / 2
        return when {
            cy < screenH * 0.15f && cx < screenW * 0.30f -> "TOP_LEFT"
            cy < screenH * 0.15f && cx > screenW * 0.70f -> "TOP_RIGHT"
            cy < screenH * 0.18f -> "TOP_BAR"
            cy > screenH * 0.85f -> "BOTTOM_BAR"
            else -> "CENTER_LIST"
        }
    }
}
