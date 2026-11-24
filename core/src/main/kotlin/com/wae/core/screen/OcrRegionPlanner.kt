package com.wae.core.screen

import com.wae.core.model.Control

/**
 * 區域式 OCR 的「該看哪裡」（純邏輯，可單元測試）。
 *
 * 為什麼不整頁 OCR：
 *  - 整頁 OCR 會被背景文字干擾（實測白圖會產生幾十個假框），而且慢。
 *  - 真正缺資訊的地方很少：沒有標籤的可點元素、以及彈出層。
 *
 * 這裡只決定「區域」，不負責截圖與辨識——那兩件事留在 App 層，
 * 這樣核心邏輯可以在 JVM 測試，不需要裝置。
 */
object OcrRegionPlanner {

    /** 一個要 OCR 的矩形（像素座標，已限制在螢幕內）。 */
    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Long get() = width.toLong() * height.toLong()
        fun describe(): String = "[$left,$top][$right,$bottom]"
    }

    /**
     * 產生要 OCR 的區域。
     *
     * 規則：
     *  1. 沒有標籤的可互動元素 → 以它為中心擴張一小圈（圖示按鈕的文字常常在旁邊）
     *  2. 有標籤但很短（≤2 字）的可互動元素 → 也看一圈，補上下文
     *  3. 彈出層（呼叫端已標記為 popup 的區域）→ 整個區域
     *  4. 合併重疊區域，避免同一塊 OCR 兩次
     *  5. 面積太小（< minArea）的丟掉；總數上限 [maxRegions]
     */
    fun plan(
        controls: List<Control>,
        popupBounds: List<Region> = emptyList(),
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        expandPx: Int = 48,
        minArea: Int = 2_000,
        maxRegions: Int = 6
    ): List<Region> {
        val out = ArrayList<Region>()
        out.addAll(popupBounds)

        for (c in controls) {
            if (!c.clickable) continue
            val label = c.label.trim()
            // 有清楚標籤的元素不需要 OCR；只有「沒標籤」或「極短標籤」才看
            if (label.length > 2) continue
            out.add(
                Region(
                    left = c.bounds.left - expandPx,
                    top = c.bounds.top - expandPx,
                    right = c.bounds.right + expandPx,
                    bottom = c.bounds.bottom + expandPx
                )
            )
        }

        val clamped = out.mapNotNull { clamp(it, screenWidth, screenHeight) }
        val merged = merge(clamped)
        return merged
            .filter { it.area >= minArea }
            .sortedByDescending { it.area }
            .take(maxRegions.coerceAtLeast(1))
    }

    private fun clamp(r: Region, screenW: Int, screenH: Int): Region? {
        val w = if (screenW > 0) screenW else Int.MAX_VALUE
        val h = if (screenH > 0) screenH else Int.MAX_VALUE
        val l = r.left.coerceIn(0, w)
        val t = r.top.coerceIn(0, h)
        val rr = r.right.coerceIn(0, w)
        val b = r.bottom.coerceIn(0, h)
        if (rr <= l || b <= t) return null
        return Region(l, t, rr, b)
    }

    /** 合併重疊或相鄰的區域（避免同一塊 OCR 兩次）。 */
    fun merge(regions: List<Region>): List<Region> {
        if (regions.size <= 1) return regions
        val remaining = regions.sortedBy { it.top }.toMutableList()
        val out = ArrayList<Region>(remaining.size)
        while (remaining.isNotEmpty()) {
            var cur = remaining.removeAt(0)
            var changed = true
            while (changed) {
                changed = false
                val it = remaining.iterator()
                while (it.hasNext()) {
                    val other = it.next()
                    if (overlaps(cur, other)) {
                        cur = union(cur, other)
                        it.remove()
                        changed = true
                    }
                }
            }
            out.add(cur)
        }
        return out
    }

    private fun overlaps(a: Region, b: Region): Boolean =
        a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom

    private fun union(a: Region, b: Region): Region = Region(
        minOf(a.left, b.left), minOf(a.top, b.top),
        maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)
    )
}
