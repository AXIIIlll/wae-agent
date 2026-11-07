package com.example.newandroidapp.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.vision.dataset.Phase1TreeImmunityConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第三階段核心：雙軌動態補盲（解決 Canvas / Flutter / Unity / WebView 遊戲與廣告黑盒）
 *
 * 1. 異常空洞偵測：檢查可見面積 >= 螢幕 15% 且內部沒有任何子節點語義的巨型空白節點或自繪畫布。
 * 2. 感知雜湊快取（dHash64 Dirty Check）：對 ROI 區域進行 9x8 灰階梯度雜湊比對，若畫面未變動則 0ms 直接複用快取。
 * 3. 局部 ROI 雙通道 100% 原創自研視覺介入（零第三方 SDK 依賴）：
 *    - 通道 A：`OriginalStrokeTextDetector` 本地自研水平筆畫交越率與 RLSA 形態學文字行提取。
 *    - 通道 B：極輕量 Sobel 邊緣梯度與連通域偵測（捕捉無文字的純視覺按鈕，如右上角關閉按鈕、圖形按鈕）。
 * 4. 座標無縫拼接：將局部 ROI 座標 (localX + roi.left, localY + roi.top) 轉回全域 UI 樹座標系。
 */
object RoiVisionFallbackEngine {

    private data class CachedRoiResult(
        val dHash64: Long,
        val nodes: List<Stage2ConvergedNode>
    )

    private val roiCache = ConcurrentHashMap<String, CachedRoiResult>()

    /**
     * 偵測畫面中的結構空洞（面積 >= 螢幕 15% 且無子元件語義），並對局部 ROI 進行視覺補盲
     */
    fun detectAndFillBlackBoxVoids(
        stage1Nodes: List<Stage1OccludedNode>,
        existingStage2Nodes: List<Stage2ConvergedNode>,
        screenBitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        startingMarkId: Int,
        voidAreaThresholdRatio: Float = 0.15f
    ): List<BlackBoxRoiVoid> {
        val screenArea = (screenWidth * screenHeight).toFloat().coerceAtLeast(1f)
        val detectedVoids = ArrayList<BlackBoxRoiVoid>()
        var nextMarkId = startingMarkId

        // 找出可見、沒有子節點（或子節點完全無語義）、且可見面積 >= 15% 螢幕面積的黑盒節點
        val candidateVoids = stage1Nodes.filter { s1 ->
            if (s1.isCulled) return@filter false
            val visRect = s1.visibleBoundingBox
            val visW = (visRect.right - visRect.left).coerceAtLeast(0)
            val visH = (visRect.bottom - visRect.top).coerceAtLeast(0)
            val visAreaRatio = (visW.toFloat() * visH.toFloat()) / screenArea
            val hasNoChildren = s1.raw.childIds.isEmpty()
            val isBlankContent = s1.raw.text.isBlank() && s1.raw.contentDescription.isBlank()
            (s1.raw.isCanvasOrBlackBoxCandidate && visAreaRatio >= 0.10f) ||
                (hasNoChildren && isBlankContent && visAreaRatio >= voidAreaThresholdRatio)
        }

        val acceptedRoiRects = ArrayList<Rect>()
        val dedupeIouThreshold = Phase1TreeImmunityConfig.voidDedupeIouThreshold

        fun computeRectIou(a: Rect, b: Rect): Float {
            val ix0 = max(a.left, b.left)
            val iy0 = max(a.top, b.top)
            val ix1 = min(a.right, b.right)
            val iy1 = min(a.bottom, b.bottom)
            if (ix1 <= ix0 || iy1 <= iy0) return 0f
            val inter = (ix1 - ix0).toFloat() * (iy1 - iy0).toFloat()
            val areaA = (a.right - a.left).coerceAtLeast(1).toFloat() * (a.bottom - a.top).coerceAtLeast(1).toFloat()
            val areaB = (b.right - b.left).coerceAtLeast(1).toFloat() * (b.bottom - b.top).coerceAtLeast(1).toFloat()
            val union = (areaA + areaB - inter).coerceAtLeast(1f)
            val minArea = min(areaA, areaB).coerceAtLeast(1f)
            return max(inter / union, inter / minArea)
        }

        for (voidNode in candidateVoids) {
            val vb = voidNode.visibleBoundingBox
            val rLeft = max(0, vb.left)
            val rTop = max(0, vb.top)
            val rRight = min(screenWidth, vb.right)
            val rBottom = min(screenHeight, vb.bottom)
            val rW = rRight - rLeft
            val rH = rBottom - rTop
            if (rW < 40 || rH < 40) continue
            val roiRect = Rect(rLeft, rTop, rRight, rBottom).apply {
                left = rLeft
                top = rTop
                right = rRight
                bottom = rBottom
            }

            if (acceptedRoiRects.any { computeRectIou(it, roiRect) >= dedupeIouThreshold }) {
                continue
            }
            acceptedRoiRects.add(roiRect)

            val areaRatio = (rW.toFloat() * rH.toFloat()) / screenArea

            // 如果已經有大量一般 UI 樹節點落在此區域內，代表它只是背景底圖而非黑盒空洞
            val innerExistingCount = existingStage2Nodes.count { existing ->
                existing.nodeId != voidNode.raw.id &&
                    existing.finalTapPx.x in rLeft until rRight &&
                    existing.finalTapPx.y in rTop until rBottom
            }
            if (innerExistingCount >= 3 && !voidNode.raw.isCanvasOrBlackBoxCandidate) continue

            if (screenBitmap == null || screenBitmap.isRecycled) {
                detectedVoids.add(
                    BlackBoxRoiVoid(
                        voidNodeId = voidNode.raw.id,
                        className = voidNode.raw.className,
                        roiRect = roiRect,
                        screenAreaRatio = areaRatio,
                        dHash64 = 0L,
                        cacheHit = false,
                        recoveredNodes = emptyList()
                    )
                )
                continue
            }

            // 依照螢幕與 Bitmap 比例換算裁切範圍
            val scaleX = screenBitmap.width.toFloat() / screenWidth.toFloat()
            val scaleY = screenBitmap.height.toFloat() / screenHeight.toFloat()
            val bmpLeft = (roiRect.left * scaleX).toInt().coerceIn(0, screenBitmap.width - 1)
            val bmpTop = (roiRect.top * scaleY).toInt().coerceIn(0, screenBitmap.height - 1)
            val bmpRight = (roiRect.right * scaleX).toInt().coerceIn(bmpLeft + 1, screenBitmap.width)
            val bmpBottom = (roiRect.bottom * scaleY).toInt().coerceIn(bmpTop + 1, screenBitmap.height)

            val croppedRoi = runCatching {
                Bitmap.createBitmap(
                    screenBitmap,
                    bmpLeft,
                    bmpTop,
                    bmpRight - bmpLeft,
                    bmpBottom - bmpTop
                )
            }.getOrNull() ?: continue

            try {
                val dHash = computePerceptualDHash64(croppedRoi)
                val cacheKey = "${roiRect.left}_${roiRect.top}_${roiRect.width()}x${roiRect.height()}"
                val cached = roiCache[cacheKey]

                if (cached != null && hammingDistance(cached.dHash64, dHash) <= 2) {
                    // 命中感知雜湊快取（0ms 直接複用並重編 #ID）
                    val reindexed = cached.nodes.map { node ->
                        node.copy(shortMarkId = nextMarkId++)
                    }
                    detectedVoids.add(
                        BlackBoxRoiVoid(
                            voidNodeId = voidNode.raw.id,
                            className = voidNode.raw.className,
                            roiRect = roiRect,
                            screenAreaRatio = areaRatio,
                            dHash64 = dHash,
                            cacheHit = true,
                            recoveredNodes = reindexed
                        )
                    )
                    continue
                }

                val recovered = ArrayList<Stage2ConvergedNode>()
                val invScaleX = 1f / scaleX
                val invScaleY = 1f / scaleY

                // 通道 A：執行本地端離線中英文 ML Kit OCR（僅針對局部 ROI 區域）
                val ocrBoxes = runLocalMlKitOcrOnRoi(croppedRoi)

                // 通道 B（主視覺合作引擎）：執行第 8 號模型 NanoDet-Plus-m 多尺度 UI 偵測，
                // 並自動與【通道 A 的 ML Kit OCR 文字】及【16x16 TinyIconClassifier】進行多模型空間語義焊接！
                val weldedResult = NanoDetUiDetector.detectAndWeldOnRoi(
                    roiBitmap = croppedRoi,
                    roiGlobalRect = roiRect,
                    ocrBoxes = ocrBoxes,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    startingMarkId = nextMarkId,
                    voidNodeIdPrefix = voidNode.raw.id
                )
                recovered.addAll(weldedResult.weldedNodes)
                nextMarkId += weldedResult.weldedNodes.size

                // 將未被 NanoDet 視覺框吸收的獨立 OCR 文字框保留為 ROI_OCR_TEXT 節點
                for ((ocrIdx, ocrPair) in ocrBoxes.withIndex()) {
                    if (ocrIdx in weldedResult.consumedOcrIndices) continue
                    val (text, localBox) = ocrPair
                    val globalRect = Rect(
                        roiRect.left + (localBox.left * invScaleX).toInt(),
                        roiRect.top + (localBox.top * invScaleY).toInt(),
                        roiRect.left + (localBox.right * invScaleX).toInt(),
                        roiRect.top + (localBox.bottom * invScaleY).toInt()
                    )
                    val tapPx = Point(globalRect.centerX(), globalRect.centerY())
                    val gx = ((tapPx.x.toFloat() / screenWidth.coerceAtLeast(1)) * 999f).toInt().coerceIn(0, 999)
                    val gy = ((tapPx.y.toFloat() / screenHeight.coerceAtLeast(1)) * 999f).toInt().coerceIn(0, 999)

                    recovered.add(
                        Stage2ConvergedNode(
                            nodeId = "${voidNode.raw.id}_ocr_${recovered.size}",
                            shortMarkId = nextMarkId++,
                            className = "Canvas.OcrElement",
                            shortTypeTag = "ROI",
                            label = text,
                            inlineStatePrefix = "[視覺OCR]",
                            rawOriginalBounds = globalRect,
                            convergedBounds = globalRect,
                            finalTapPx = tapPx,
                            gridTapPoint = Point(gx, gy),
                            visibleRatio = 1.0f,
                            wasHotspotConverged = true,
                            absorbedTextIds = emptyList(),
                            sourceType = NodeSourceType.ROI_OCR_TEXT
                        )
                    )
                }

                // 通道 C（幾何邊緣保底）：極輕量 Sobel 邊緣梯度與高對比色塊偵測（僅補充與 NanoDet / OCR 不重疊之熱區）
                val edgeBoxes = detectHighContrastButtonContours(croppedRoi)
                for ((hintLabel, localBox) in edgeBoxes) {
                    val globalRect = Rect(
                        roiRect.left + (localBox.left * invScaleX).toInt(),
                        roiRect.top + (localBox.top * invScaleY).toInt(),
                        roiRect.left + (localBox.right * invScaleX).toInt(),
                        roiRect.top + (localBox.bottom * invScaleY).toInt()
                    )
                    // 避免與已提取的 NanoDet 元件或 OCR 文字框重疊
                    val overlapsExisting = recovered.any { existingNode ->
                        Rect.intersects(existingNode.convergedBounds, globalRect)
                    }
                    if (overlapsExisting) continue

                    val tapPx = Point(globalRect.centerX(), globalRect.centerY())
                    val gx = ((tapPx.x.toFloat() / screenWidth.coerceAtLeast(1)) * 999f).toInt().coerceIn(0, 999)
                    val gy = ((tapPx.y.toFloat() / screenHeight.coerceAtLeast(1)) * 999f).toInt().coerceIn(0, 999)

                    recovered.add(
                        Stage2ConvergedNode(
                            nodeId = "${voidNode.raw.id}_edge_${recovered.size}",
                            shortMarkId = nextMarkId++,
                            className = "Canvas.EdgeHotspot",
                            shortTypeTag = "ROI",
                            label = hintLabel,
                            inlineStatePrefix = "[視覺框]",
                            rawOriginalBounds = globalRect,
                            convergedBounds = globalRect,
                            finalTapPx = tapPx,
                            gridTapPoint = Point(gx, gy),
                            visibleRatio = 1.0f,
                            wasHotspotConverged = true,
                            absorbedTextIds = emptyList(),
                            sourceType = NodeSourceType.ROI_EDGE_BUTTON
                        )
                    )
                }

                if (roiCache.size > 64) {
                    roiCache.clear()
                }
                roiCache[cacheKey] = CachedRoiResult(dHash64 = dHash, nodes = recovered)

                detectedVoids.add(
                    BlackBoxRoiVoid(
                        voidNodeId = voidNode.raw.id,
                        className = voidNode.raw.className,
                        roiRect = roiRect,
                        screenAreaRatio = areaRatio,
                        dHash64 = dHash,
                        cacheHit = false,
                        recoveredNodes = recovered
                    )
                )
            } finally {
                if (!croppedRoi.isRecycled) {
                    runCatching { croppedRoi.recycle() }
                }
            }
        }

        return detectedVoids
    }

    /**
     * 全畫面視覺補盲（Full-Screen Visual Rescue）：
     * 當畫面無障礙節點極少（< 3 個）且沒有單一巨型空洞節點時，直接對全螢幕截圖執行
     * 【NanoDet-Plus-m + ML Kit OCR + 16x16 TinyIconClassifier】三模型空間語義焊接。
     */
    fun detectFullScreenVisualFallback(
        existingNodes: List<Stage2ConvergedNode>,
        screenBitmap: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        startingMarkId: Int
    ): List<Stage2ConvergedNode> {
        if (screenBitmap == null || screenBitmap.isRecycled || screenWidth < 64 || screenHeight < 64) {
            return emptyList()
        }
        val fullRect = Rect(0, 0, screenWidth, screenHeight)
        val ocrBoxes = runLocalMlKitOcrOnRoi(screenBitmap)
        val welded = NanoDetUiDetector.detectAndWeldOnRoi(
            roiBitmap = screenBitmap,
            roiGlobalRect = fullRect,
            ocrBoxes = ocrBoxes,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            startingMarkId = startingMarkId,
            voidNodeIdPrefix = "fullscreen"
        )
        return welded.weldedNodes.filter { candidate ->
            existingNodes.none { existing ->
                NanoDetUiDetector.computeRectIoU(existing.convergedBounds, candidate.convergedBounds) >= 0.50f
            }
        }
    }

    /**
     * 執行 100% 原創自研局部筆畫交越率與 RLSA 文字行檢測（零第三方閉源 SDK 依賴）
     */
    private fun runLocalMlKitOcrOnRoi(roiBitmap: Bitmap): List<Pair<String, Rect>> {
        return runCatching {
            OriginalStrokeTextDetector.detectTextLinesOnRoi(roiBitmap)
        }.getOrDefault(emptyList())
    }

    /**
     * 極輕量 Sobel 梯度與色塊連通域偵測：
     * 將局部 ROI 降採樣至 64x48 格網，找出高梯度邊緣聚集的獨立按鈕框（如右上角關閉圖示、底部 CTA 色塊按鈕）
     */
    private fun detectHighContrastButtonContours(roiBitmap: Bitmap): List<Pair<String, Rect>> {
        val w = roiBitmap.width
        val h = roiBitmap.height
        if (w < 32 || h < 32) return emptyList()

        val gridW = 48
        val gridH = 36
        val stepX = max(1f, w.toFloat() / gridW)
        val stepY = max(1f, h.toFloat() / gridH)

        val luma = IntArray(gridW * gridH)
        val sat = IntArray(gridW * gridH)

        for (gy in 0 until gridH) {
            val py = (gy * stepY).toInt().coerceIn(0, h - 1)
            for (gx in 0 until gridW) {
                val px = (gx * stepX).toInt().coerceIn(0, w - 1)
                val c = roiBitmap.getPixel(px, py)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                luma[gy * gridW + gx] = (r * 77 + g * 150 + b * 29) shr 8
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                sat[gy * gridW + gx] = maxC - minC
            }
        }

        // 計算邊緣與高彩度激發遮罩
        val edgeMask = BooleanArray(gridW * gridH)
        for (gy in 1 until gridH - 1) {
            for (gx in 1 until gridW - 1) {
                val idx = gy * gridW + gx
                val dx = abs(luma[idx + 1] - luma[idx - 1])
                val dy = abs(luma[idx + gridW] - luma[idx - gridW])
                val gradient = dx + dy
                if (gradient > 58 || sat[idx] > 95) {
                    edgeMask[idx] = true
                }
            }
        }

        // 連通域搜尋（Connected Components BFS）找出獨立視覺熱區
        val visited = BooleanArray(gridW * gridH)
        val results = ArrayList<Pair<String, Rect>>()
        val q = IntArray(gridW * gridH)

        for (gy in 1 until gridH - 1) {
            for (gx in 1 until gridW - 1) {
                val startIdx = gy * gridW + gx
                if (!edgeMask[startIdx] || visited[startIdx]) continue

                var head = 0
                var tail = 0
                q[tail++] = startIdx
                visited[startIdx] = true

                var minGx = gx
                var maxGx = gx
                var minGy = gy
                var maxGy = gy
                var cellCount = 0

                while (head < tail) {
                    val cur = q[head++]
                    cellCount++
                    val cy = cur / gridW
                    val cx = cur % gridW
                    if (cx < minGx) minGx = cx
                    if (cx > maxGx) maxGx = cx
                    if (cy < minGy) minGy = cy
                    if (cy > maxGy) maxGy = cy

                    for (ny in (cy - 1)..(cy + 1)) {
                        for (nx in (cx - 1)..(cx + 1)) {
                            if (nx in 1 until (gridW - 1) && ny in 1 until (gridH - 1)) {
                                val nIdx = ny * gridW + nx
                                if (edgeMask[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    q[tail++] = nIdx
                                }
                            }
                        }
                    }
                }

                val boxW = maxGx - minGx + 1
                val boxH = maxGy - minGy + 1
                // 篩選符合按鈕或圖示尺寸比例的連通域（排除過小的雜訊與過大的全圖背景框）
                if (cellCount in 4..280 && boxW in 2..36 && boxH in 2..20) {
                    val rectPx = Rect(
                        (minGx * stepX).toInt(),
                        (minGy * stepY).toInt(),
                        ((maxGx + 1) * stepX).toInt().coerceAtMost(w),
                        ((maxGy + 1) * stepY).toInt().coerceAtMost(h)
                    )
                    // 根據在 ROI 內的相對位置推斷語義提示（例如右上角小型圖示通常為關閉按鈕）
                    val isTopRightCorner = minGx > gridW * 0.72f && maxGy < gridH * 0.35f && boxW <= 10 && boxH <= 10
                    val hint = if (isTopRightCorner) "右上角圖示鈕(關閉)" else "自繪按鈕熱區"
                    results.add(Pair(hint, rectPx))
                }
            }
        }

        return results.take(8)
    }

    /**
     * 計算 9x8 灰階水平梯度感知雜湊（64-bit dHash），耗時 < 0.2ms
     */
    private fun computePerceptualDHash64(bmp: Bitmap): Long {
        val w = bmp.width
        val h = bmp.height
        if (w <= 1 || h <= 1) return 0L
        var hash = 0L
        var bit = 0
        for (ry in 0 until 8) {
            val py = (ry * h) / 8
            var prevLuma = 0
            for (rx in 0..8) {
                val px = (rx * (w - 1)) / 8
                val c = bmp.getPixel(px, py)
                val luma = (Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) shr 8
                if (rx > 0) {
                    if (luma > prevLuma) {
                        hash = hash or (1L shl bit)
                    }
                    bit++
                }
                prevLuma = luma
            }
        }
        return hash
    }

    private fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
