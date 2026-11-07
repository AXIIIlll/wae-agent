package com.example.newandroidapp.vision

import android.graphics.Point
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第一階段核心：2D 遮罩佔用格網（Occupancy Grid）+ 動態布林差集裁切 + 最大內切視覺中心（Pole of Inaccessibility）
 *
 * 解決問題：
 * 1. 幽靈座標：底層被 Dialog / BottomSheet / 懸浮窗蓋住 >80% 的節點直接剔除。
 * 2. 凹多邊形遮擋重心偏移：當按鈕被遮擋成 L 型或月牙形時，傳統幾何平均中心（Centroid）可能落在遮擋物上；
 *    此處採用雙趟曼哈頓距離變換（Manhattan Distance Transform），保證點擊座標 100% 落在「最寬裕的未遮擋安全區中心」。
 */
class OccupancyGrid2D(
    val screenWidth: Int,
    val screenHeight: Int,
    val cols: Int = 120,
    val rows: Int = 240
) {
    // 0 代表尚未被上層遮擋；>0 代表被對應 Z-Order 層級的不透明元件佔用
    private val grid = ByteArray(cols * rows)

    private val cellW: Float = screenWidth.toFloat() / cols.toFloat()
    private val cellH: Float = screenHeight.toFloat() / rows.toFloat()

    fun clear() {
        grid.fill(0)
    }

    fun snapshot(): ByteArray = grid.copyOf()

    private fun makeRect(l: Int, t: Int, r: Int, b: Int): Rect {
        return Rect().apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    private fun makePoint(px: Int, py: Int): Point {
        return Point().apply {
            x = px
            y = py
        }
    }

    /**
     * 計算下層節點與目前 2D 佔用遮罩的布林差集（減去被上層遮擋的部分）
     * @param occlusionCullThreshold 遮擋超過 80%（即剩餘可見比例 < 0.20f）時直接剔除
     */
    fun evaluateNodeVisibilityAndSafePoint(
        raw: RawVisionNode,
        occlusionCullThreshold: Float = 0.20f
    ): Stage1OccludedNode {
        // 先與父級可捲動視口（Viewport）及螢幕邊界做交集裁切
        val vp = raw.viewportClipBounds
        val hasValidVp = vp.right > vp.left && vp.bottom > vp.top
        val clipLeft = max(0, if (hasValidVp) max(raw.bounds.left, vp.left) else raw.bounds.left)
        val clipTop = max(0, if (hasValidVp) max(raw.bounds.top, vp.top) else raw.bounds.top)
        val clipRight = min(screenWidth, if (hasValidVp) min(raw.bounds.right, vp.right) else raw.bounds.right)
        val clipBottom = min(screenHeight, if (hasValidVp) min(raw.bounds.bottom, vp.bottom) else raw.bounds.bottom)
        val clipped = makeRect(clipLeft, clipTop, clipRight, clipBottom)
        val clipW = clipRight - clipLeft
        val clipH = clipBottom - clipTop
        val rawCenterX = (raw.bounds.left + raw.bounds.right) / 2
        val rawCenterY = (raw.bounds.top + raw.bounds.bottom) / 2
        if (clipW <= 2 || clipH <= 2) {
            return Stage1OccludedNode(
                raw = raw,
                visibleRatio = 0f,
                isCulled = true,
                wasPartiallyOccluded = true,
                safeTapPointPx = makePoint(rawCenterX, rawCenterY),
                visibleBoundingBox = makeRect(0, 0, 0, 0)
            )
        }

        val cStart = (clipped.left / cellW).toInt().coerceIn(0, cols - 1)
        val cEnd = ((clipped.right - 1) / cellW).toInt().coerceIn(cStart, cols - 1)
        val rStart = (clipped.top / cellH).toInt().coerceIn(0, rows - 1)
        val rEnd = ((clipped.bottom - 1) / cellH).toInt().coerceIn(rStart, rows - 1)

        val subCols = cEnd - cStart + 1
        val subRows = rEnd - rStart + 1
        val totalCells = subCols * subRows
        val clipCenterX = (clipped.left + clipped.right) / 2
        val clipCenterY = (clipped.top + clipped.bottom) / 2
        if (totalCells <= 0) {
            return Stage1OccludedNode(
                raw = raw,
                visibleRatio = 0f,
                isCulled = true,
                wasPartiallyOccluded = true,
                safeTapPointPx = makePoint(clipCenterX, clipCenterY),
                visibleBoundingBox = makeRect(0, 0, 0, 0)
            )
        }

        var visibleCells = 0
        var minVisC = cols
        var maxVisC = -1
        var minVisR = rows
        var maxVisR = -1

        // 局部距離變換矩陣（計算最大內切圓中心 Pole of Inaccessibility）
        val dist = IntArray(totalCells)
        for (sr in 0 until subRows) {
            val r = rStart + sr
            val rowOffset = r * cols
            val subRowOffset = sr * subCols
            for (sc in 0 until subCols) {
                val c = cStart + sc
                val occupied = grid[rowOffset + c].toInt() != 0
                if (!occupied) {
                    visibleCells++
                    if (c < minVisC) minVisC = c
                    if (c > maxVisC) maxVisC = c
                    if (r < minVisR) minVisR = r
                    if (r > maxVisR) maxVisR = r
                    dist[subRowOffset + sc] = 10000
                } else {
                    dist[subRowOffset + sc] = 0
                }
            }
        }

        val visibleRatio = visibleCells.toFloat() / totalCells.toFloat()
        if (visibleRatio < occlusionCullThreshold) {
            return Stage1OccludedNode(
                raw = raw,
                visibleRatio = visibleRatio,
                isCulled = true,
                wasPartiallyOccluded = visibleRatio < 0.99f,
                safeTapPointPx = makePoint(clipCenterX, clipCenterY),
                visibleBoundingBox = makeRect(0, 0, 0, 0)
            )
        }

        // 若 100% 完全未受遮擋，直接取裁切後外框的幾何中心
        if (visibleCells == totalCells) {
            return Stage1OccludedNode(
                raw = raw,
                visibleRatio = 1.0f,
                isCulled = false,
                wasPartiallyOccluded = false,
                safeTapPointPx = makePoint(clipCenterX, clipCenterY),
                visibleBoundingBox = clipped
            )
        }

        // 若部分被遮擋（例如被擋住 20%~80%），執行雙趟曼哈頓距離變換（Manhattan Distance Transform）
        // 找出距離所有「被遮擋格點」與「節點邊界」最遠的內部安全格點
        // Pass 1: 由左上往右下
        for (sr in 0 until subRows) {
            val subRowOffset = sr * subCols
            for (sc in 0 until subCols) {
                val idx = subRowOffset + sc
                if (dist[idx] == 0) continue
                val top = if (sr > 0) dist[(sr - 1) * subCols + sc] else 0
                val left = if (sc > 0) dist[subRowOffset + (sc - 1)] else 0
                dist[idx] = min(dist[idx], min(top, left) + 1)
            }
        }

        // Pass 2: 由右下往左上，同時選出距離最大且最靠近原始中心的格點
        val origCenterC = (cStart + cEnd) * 0.5f
        val origCenterR = (rStart + rEnd) * 0.5f
        var bestDist = -1
        var bestTieBreak = Float.MAX_VALUE
        var bestC = (minVisC + maxVisC) / 2
        var bestR = (minVisR + maxVisR) / 2

        for (sr in (subRows - 1) downTo 0) {
            val subRowOffset = sr * subCols
            for (sc in (subCols - 1) downTo 0) {
                val idx = subRowOffset + sc
                if (dist[idx] == 0) continue
                val bottom = if (sr < subRows - 1) dist[(sr + 1) * subCols + sc] else 0
                val right = if (sc < subCols - 1) dist[subRowOffset + (sc + 1)] else 0
                val d = min(dist[idx], min(bottom, right) + 1)
                dist[idx] = d

                val c = cStart + sc
                val r = rStart + sr
                val centerBias = abs(c - origCenterC) + abs(r - origCenterR)
                if (d > bestDist || (d == bestDist && centerBias < bestTieBreak)) {
                    bestDist = d
                    bestTieBreak = centerBias
                    bestC = c
                    bestR = r
                }
            }
        }

        val safePxX = ((bestC + 0.5f) * cellW).toInt().coerceIn(clipped.left, max(clipped.left, clipped.right - 1))
        val safePxY = ((bestR + 0.5f) * cellH).toInt().coerceIn(clipped.top, max(clipped.top, clipped.bottom - 1))

        val visRect = makeRect(
            (minVisC * cellW).toInt().coerceAtLeast(clipped.left),
            (minVisR * cellH).toInt().coerceAtLeast(clipped.top),
            ((maxVisC + 1) * cellW).toInt().coerceAtMost(clipped.right),
            ((maxVisR + 1) * cellH).toInt().coerceAtMost(clipped.bottom)
        )

        return Stage1OccludedNode(
            raw = raw,
            visibleRatio = visibleRatio,
            isCulled = false,
            wasPartiallyOccluded = true,
            safeTapPointPx = makePoint(safePxX, safePxY),
            visibleBoundingBox = visRect
        )
    }

    /**
     * 將不透明容器、懸浮面板、Dialog 或實心互動元件的矩形區域標記為「已佔用」
     */
    fun markRectOccupied(rect: Rect, layerMarker: Int = 1) {
        val clipLeft = max(0, rect.left)
        val clipTop = max(0, rect.top)
        val clipRight = min(screenWidth, rect.right)
        val clipBottom = min(screenHeight, rect.bottom)
        if (clipRight <= clipLeft || clipBottom <= clipTop) return

        val cStart = (clipLeft / cellW).toInt().coerceIn(0, cols - 1)
        val cEnd = ((clipRight - 1) / cellW).toInt().coerceIn(cStart, cols - 1)
        val rStart = (clipTop / cellH).toInt().coerceIn(0, rows - 1)
        val rEnd = ((clipBottom - 1) / cellH).toInt().coerceIn(rStart, rows - 1)

        val markerByte = layerMarker.coerceIn(1, 127).toByte()
        for (r in rStart..rEnd) {
            val rowOffset = r * cols
            for (c in cStart..cEnd) {
                if (grid[rowOffset + c].toInt() == 0) {
                    grid[rowOffset + c] = markerByte
                }
            }
        }
    }
}
