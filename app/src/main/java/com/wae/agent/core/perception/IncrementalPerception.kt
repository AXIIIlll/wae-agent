package com.wae.agent.core.perception

/**
 * S25：事件驅動的增量感知（純邏輯，可在 JVM 測試）。
 *
 * 背景：目前每一幀都走完整棵無障礙樹（約 200ms）。但多數時候畫面只有一小塊在變
 * （輸入框打字、列表項切換、開關切換）。若能把「走訪」限制在變化區域，
 * 其餘候選沿用上一幀，就能把感知成本從 O(樹大小) 降到 O(變化量)。
 *
 * 三個必須同時成立才敢用增量：
 *  1. 同一個 App、同一個視窗、同一份結構指紋
 *  2. 有「髒區」且面積佔比不大（≤ 35%）
 *  3. 距離上一次**全量**感知不久（≤ 1200ms）——這是安全網，避免錯誤累積
 *
 * 任何一項不成立就退回全量，行為與舊版完全一致。
 */
object IncrementalPerception {

    /** 單次增量可接受的髒區總面積上限（占螢幕比例） */
    const val MAX_DIRTY_AREA_RATIO = 0.35f

    /** 距離上次全量感知的上限（毫秒）；超過就強制全量 */
    const val MAX_MS_SINCE_FULL = 1200L

    /** 最多追蹤幾個髒區（超過代表變化太大，直接全量） */
    const val MAX_DIRTY_RECTS = 4

    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val width: Int get() = (r - l).coerceAtLeast(0)
        val height: Int get() = (b - t).coerceAtLeast(0)
        val area: Long get() = width.toLong() * height.toLong()
        val isEmpty: Boolean get() = width <= 0 || height <= 0
    }

    fun intersects(a: Box, b: Box): Boolean =
        !a.isEmpty && !b.isEmpty &&
            a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b

    fun intersectsAny(a: Box, rects: List<Box>): Boolean = rects.any { intersects(a, it) }

    /** 多個髒區的聯集面積占比（用外接框估，偏保守——寧可高估而退回全量） */
    fun dirtyAreaRatio(rects: List<Box>, screenW: Int, screenH: Int): Float {
        if (rects.isEmpty() || screenW <= 0 || screenH <= 0) return 1f
        val l = rects.minOf { it.l }
        val t = rects.minOf { it.t }
        val r = rects.maxOf { it.r }
        val b = rects.maxOf { it.b }
        val boxArea = (r - l).coerceAtLeast(0).toLong() * (b - t).coerceAtLeast(0).toLong()
        val screenArea = screenW.toLong() * screenH.toLong()
        return (boxArea.toFloat() / screenArea.toFloat()).coerceIn(0f, 1f)
    }

    fun canGoIncremental(
        samePackage: Boolean,
        sameWindow: Boolean,
        sameAmbient: Boolean,
        dirtyCount: Int,
        dirtyAreaRatio: Float,
        msSinceFull: Long,
        maxMsSinceFull: Long = MAX_MS_SINCE_FULL
    ): Boolean {
        if (!samePackage || !sameWindow || !sameAmbient) return false
        if (dirtyCount <= 0 || dirtyCount > MAX_DIRTY_RECTS) return false
        if (dirtyAreaRatio <= 0f || dirtyAreaRatio > MAX_DIRTY_AREA_RATIO) return false
        if (msSinceFull < 0 || msSinceFull > maxMsSinceFull) return false
        return true
    }

    /**
     * 合併：以「新鮮候選」為主，補上「舊候選中未被髒區覆蓋、且不在新鮮集合裡」的項目。
     * 順序保持穩定（新鮮的先），避免下游排序出現非確定性。
     */
    fun <T> merge(
        kept: List<T>,
        fresh: List<T>,
        keyOf: (T) -> Long,
        boundsOf: (T) -> Box,
        dirty: List<Box>
    ): List<T> {
        val freshKeys = fresh.map { keyOf(it) }.toHashSet()
        val out = ArrayList<T>(fresh.size + kept.size)
        out.addAll(fresh)
        for (item in kept) {
            if (keyOf(item) in freshKeys) continue          // 重複
            if (intersectsAny(boundsOf(item), dirty)) continue // 舊資料已被髒區取代
            out.add(item)
        }
        return out
    }
}
