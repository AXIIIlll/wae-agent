package com.wae.agent.vision

/**
 * OCR 後處理的純函式（可在 JVM 單元測試覆蓋）：
 *  - DB 機率圖 → 二值遮罩 → 連通域 → 文字框
 *  - 訓練座標 ↔ 原圖座標的縮放
 *  - CTC 貪婪解碼（含 blank / 重複摺疊）
 *
 * 注意：刻意不使用 android.graphics.Rect —— 它沒有 JVM 實作（單元測試會拋 not mocked），
 * 這裡用純 Kotlin 資料類，邊界（Bitmap 裁切）再轉回 Rect。
 */
object OcrPostProcess {

    data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class TextBox(val box: OcrBox, val detScore: Float)

    /**
     * 將機率圖二值化後取 8-連通域，輸出文字框（模型輸入座標系）。
     *
     * @param prob 機率圖，長度 modelH*modelW，值域 [0,1]
     */
    fun extractBoxes(
        prob: FloatArray,
        modelW: Int,
        modelH: Int,
        threshold: Float,
        minAreaPx: Int
    ): List<TextBox> {
        if (prob.size < modelW * modelH) return emptyList()
        val visited = BooleanArray(modelW * modelH)
        val out = ArrayList<TextBox>(32)
        val queue = IntArray(modelW * modelH)
        for (start in 0 until modelW * modelH) {
            if (visited[start] || prob[start] < threshold) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = -1
            var maxY = -1
            var scoreSum = 0f
            var count = 0
            while (head < tail) {
                val idx = queue[head++]
                val x = idx % modelW
                val y = idx / modelW
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                scoreSum += prob[idx]
                count++
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= modelW || ny >= modelH) continue
                        val nIdx = ny * modelW + nx
                        if (visited[nIdx] || prob[nIdx] < threshold) continue
                        visited[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
            }
            if (count < minAreaPx) continue
            out.add(
                TextBox(
                    OcrBox(minX, minY, maxX + 1, maxY + 1),
                    if (count > 0) scoreSum / count else 0f
                )
            )
        }
        // 由上而下、由左而右排序，讓候選順序穩定
        out.sortWith(compareBy({ it.box.top / 12 }, { it.box.left }))
        return out
    }

    /**
     * 文字框外擴（DB 的 unclip 簡化版），並 clamp 到影像範圍。
     */
    fun unclipAndClamp(box: OcrBox, ratio: Float, imgW: Int, imgH: Int): OcrBox {
        // 用「+0.5 取整」而非 kotlin.math.round：後者是 5 取偶（2.5 → 2），
        // 會讓小框外擴被地板化；這裡要的是常規四捨五入。
        val dw = (box.width * ratio / 2f + 0.5f).toInt()
        val dh = (box.height * ratio / 2f + 0.5f).toInt()
        return OcrBox(
            (box.left - dw).coerceIn(0, imgW),
            (box.top - dh).coerceIn(0, imgH),
            (box.right + dw).coerceIn(0, imgW),
            (box.bottom + dh).coerceIn(0, imgH)
        )
    }

    /**
     * CTC 貪婪解碼。
     *
     * @param logits [T][C] 的 softmax 輸出；C = charset.size + 2（0=blank，其餘對應 charset，最後一格為空白）
     * @return 文字與平均信心度
     */
    fun ctcGreedyDecode(
        logits: Array<FloatArray>,
        charset: List<String>
    ): Pair<String, Float> {
        if (logits.isEmpty() || charset.isEmpty()) return Pair("", 0f)
        val sb = StringBuilder()
        var prev = -1
        var probSum = 0f
        var probCount = 0
        for (row in logits) {
            if (row.isEmpty()) continue
            var best = 0
            var bestP = row[0]
            for (i in 1 until row.size) {
                if (row[i] > bestP) { bestP = row[i]; best = i }
            }
            if (best != 0 && best != prev) {
                val chIndex = best - 1
                if (chIndex in charset.indices) sb.append(charset[chIndex])
                else if (chIndex == charset.size) sb.append(' ')
                probSum += bestP
                probCount++
            }
            prev = best
        }
        val conf = if (probCount > 0) probSum / probCount else 0f
        return Pair(sb.toString().trim(), conf)
    }
}
