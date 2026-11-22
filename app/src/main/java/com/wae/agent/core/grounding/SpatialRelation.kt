package com.wae.agent.core.grounding

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 純幾何空間關係（I2 多通道融合與 I3 標籤歸屬的共同底層）。
 *
 * 設計原則：
 *  - **零 Android API**：只吃 `[left, top, right, bottom]` 整數陣列與 [GroundingCandidate]，
 *    JVM 單元測試可直接構造任意幾何（含零面積、反向座標、空陣列）做邊界回歸。
 *  - **不拋例外**：任何無效 rect 一律回 0 / NaN 以外的有限值 / null，讓壞資料安全流過管線。
 *  - **確定性**：所有排序都有完整 tie-break（距離 → 分數 → id），同一輸入永遠同一輸出，
 *    方便真機 trace 重播（I6）逐位元比對。
 *
 * 座標慣例同 [GroundingCandidate.bounds]：`[left, top, right, bottom]`，px，原點左上。
 * 「距離」一律用邊框距離（兩個矩形最近邊的歐氏距離，重疊=0），
 * 不用中心距離，因為大面積列狀元件的中心距離會把「緊鄰的開關」推得比實際遠。
 */
object SpatialRelation {

    /** `to` 相對 `from` 的方向（以中心點位移的主軸判定；位移相同時取水平軸） */
    enum class Direction {
        LEFT,
        RIGHT,
        ABOVE,
        BELOW,
        /** 兩個矩形中心幾乎重合 */
        OVERLAP,
        /** 幾何無效，無法判定 */
        UNKNOWN
    }

    /** 預設「同一列」垂直容差（px）：多數 1080p 手機的行高 40~120px，24 是保守下限 */
    const val DEFAULT_ROW_TOLERANCE_PX: Int = 24

    /**
     * 自動推估行高後，容差上限（px）。
     * 避免畫面上一張巨大圖卡把 median 行高拉高、把整頁節點併成一列。
     */
    const val MAX_ROW_TOLERANCE_PX: Int = 160

    /** [nearestClickable] 預設回傳數量 */
    const val DEFAULT_NEAREST_LIMIT: Int = 8

    /** 無效幾何的距離：排序時自然排到最後 */
    private const val FAR: Float = Float.POSITIVE_INFINITY

    // ── 有效性與基本量測 ────────────────────────────────────────────

    /** rect 是否有效：長度 4、right>left、bottom>top（零面積視為無效） */
    fun isValid(rect: IntArray?): Boolean =
        rect != null && rect.size == 4 && rect[2] > rect[0] && rect[3] > rect[1]

    /** 候選元素是否帶有效幾何 */
    fun isValid(candidate: GroundingCandidate?): Boolean =
        candidate != null && isValid(candidate.bounds)

    /** 寬度（px）；無效回 0 */
    fun width(rect: IntArray?): Int {
        val r = rect ?: return 0
        if (!isValid(r)) return 0
        return r[2] - r[0]
    }

    /** 高度（px）；無效回 0 */
    fun height(rect: IntArray?): Int {
        val r = rect ?: return 0
        if (!isValid(r)) return 0
        return r[3] - r[1]
    }

    /** 面積（px²）；無效回 0 */
    fun area(rect: IntArray?): Long =
        width(rect).toLong() * height(rect).toLong()

    fun centerX(rect: IntArray?): Int {
        val r = rect ?: return 0
        if (!isValid(r)) return 0
        return r[0] + (r[2] - r[0]) / 2
    }

    fun centerY(rect: IntArray?): Int {
        val r = rect ?: return 0
        if (!isValid(r)) return 0
        return r[1] + (r[3] - r[1]) / 2
    }

    // ── 重疊 / 包含 ────────────────────────────────────────────────

    /** 兩個 rect 的交集面積（px²）；不重疊回 0 */
    fun intersectionArea(a: IntArray?, b: IntArray?): Long {
        if (!isValid(a) || !isValid(b)) return 0L
        val ra = a!!
        val rb = b!!
        val w = min(ra[2], rb[2]) - max(ra[0], rb[0])
        val h = min(ra[3], rb[3]) - max(ra[1], rb[1])
        if (w <= 0 || h <= 0) return 0L
        return w.toLong() * h.toLong()
    }

    /** 是否真的重疊（面積 > 0；只在邊緣相接不算） */
    fun overlaps(a: IntArray?, b: IntArray?): Boolean = intersectionArea(a, b) > 0L

    /**
     * `outer` 是否完整包含 `inner`。
     *
     * @param tolerancePx 容許的邊界誤差（OCR 框與 a11y 框常有 1~4px 抖動）
     */
    fun contains(outer: IntArray?, inner: IntArray?, tolerancePx: Int = 0): Boolean {
        if (!isValid(outer) || !isValid(inner)) return false
        val ro = outer!!
        val ri = inner!!
        val tol = max(0, tolerancePx)
        return ri[0] >= ro[0] - tol &&
            ri[1] >= ro[1] - tol &&
            ri[2] <= ro[2] + tol &&
            ri[3] <= ro[3] + tol
    }

    /** 垂直重疊長度（px） */
    fun verticalOverlap(a: IntArray?, b: IntArray?): Int {
        if (!isValid(a) || !isValid(b)) return 0
        val ra = a!!
        val rb = b!!
        return max(0, min(ra[3], rb[3]) - max(ra[1], rb[1]))
    }

    /** 水平重疊長度（px） */
    fun horizontalOverlap(a: IntArray?, b: IntArray?): Int {
        if (!isValid(a) || !isValid(b)) return 0
        val ra = a!!
        val rb = b!!
        return max(0, min(ra[2], rb[2]) - max(ra[0], rb[0]))
    }

    /**
     * 垂直重疊比例（0..1）：以**較矮**的那個高度正規化。
     * 同列的「標籤文字」與「右側開關」通常接近 1；跨列的節點接近 0。
     */
    fun verticalOverlapRatio(a: IntArray?, b: IntArray?): Float {
        val ha = height(a)
        val hb = height(b)
        if (ha <= 0 || hb <= 0) return 0f
        return (verticalOverlap(a, b).toFloat() / min(ha, hb).toFloat()).coerceIn(0f, 1f)
    }

    /** 水平重疊比例（0..1）：以較窄的那個寬度正規化 */
    fun horizontalOverlapRatio(a: IntArray?, b: IntArray?): Float {
        val wa = width(a)
        val wb = width(b)
        if (wa <= 0 || wb <= 0) return 0f
        return (horizontalOverlap(a, b).toFloat() / min(wa, wb).toFloat()).coerceIn(0f, 1f)
    }

    /** Intersection over Union（0..1）；任一無效回 0 */
    fun iou(a: IntArray?, b: IntArray?): Float {
        val inter = intersectionArea(a, b)
        if (inter <= 0L) return 0f
        val union = area(a) + area(b) - inter
        if (union <= 0L) return 0f
        return (inter.toFloat() / union.toFloat()).coerceIn(0f, 1f)
    }

    // ── 距離 ────────────────────────────────────────────────────────

    /**
     * 邊框距離（px）：兩個矩形最近邊的歐氏距離；重疊或相接回 0。
     * 任一幾何無效回 [Float.POSITIVE_INFINITY]（排序時自然最後）。
     */
    fun edgeDistance(a: IntArray?, b: IntArray?): Float {
        if (!isValid(a) || !isValid(b)) return FAR
        val ra = a!!
        val rb = b!!
        if (overlaps(ra, rb)) return 0f
        val dx = max(0, max(ra[0] - rb[2], rb[0] - ra[2]))
        val dy = max(0, max(ra[1] - rb[3], rb[1] - ra[3]))
        return hypot(dx.toFloat(), dy.toFloat())
    }

    /** 中心距離（px）；任一無效回 [Float.POSITIVE_INFINITY] */
    fun centerDistance(a: IntArray?, b: IntArray?): Float {
        if (!isValid(a) || !isValid(b)) return FAR
        val dx = (centerX(a) - centerX(b)).toFloat()
        val dy = (centerY(a) - centerY(b)).toFloat()
        return hypot(dx, dy)
    }

    // ── 方向 / 對齊 / 同列 ──────────────────────────────────────────

    /** `to` 的中心在 `from` 的哪一側（主軸取 |dx|、|dy| 較大者） */
    fun direction(from: IntArray?, to: IntArray?): Direction {
        if (!isValid(from) || !isValid(to)) return Direction.UNKNOWN
        val dx = centerX(to) - centerX(from)
        val dy = centerY(to) - centerY(from)
        if (dx == 0 && dy == 0) return Direction.OVERLAP
        return if (abs(dx) >= abs(dy)) {
            if (dx < 0) Direction.LEFT else Direction.RIGHT
        } else {
            if (dy < 0) Direction.ABOVE else Direction.BELOW
        }
    }

    /** 上緣對齊（同一列常見） */
    fun isTopAligned(a: IntArray?, b: IntArray?, tolerancePx: Int = DEFAULT_ROW_TOLERANCE_PX): Boolean {
        if (!isValid(a) || !isValid(b)) return false
        return abs(a!![1] - b!![1]) <= max(0, tolerancePx)
    }

    /** 左緣對齊（同一行常見） */
    fun isLeftAligned(a: IntArray?, b: IntArray?, tolerancePx: Int = DEFAULT_ROW_TOLERANCE_PX): Boolean {
        if (!isValid(a) || !isValid(b)) return false
        return abs(a!![0] - b!![0]) <= max(0, tolerancePx)
    }

    /** 右緣對齊（右側控制項常見） */
    fun isRightAligned(a: IntArray?, b: IntArray?, tolerancePx: Int = DEFAULT_ROW_TOLERANCE_PX): Boolean {
        if (!isValid(a) || !isValid(b)) return false
        return abs(a!![2] - b!![2]) <= max(0, tolerancePx)
    }

    /**
     * 是否同一列：垂直中心差 <= tolerance，**或**垂直重疊比例 >= 0.5。
     *
     * 用「或」而不是「且」：標籤文字比列高矮很多時（例如小字 + 高輸入框），
     * 中心差可能超過容差但重疊比例仍明確同列；只用其中一種會漏判。
     */
    fun sameRow(
        a: IntArray?,
        b: IntArray?,
        tolerancePx: Int = DEFAULT_ROW_TOLERANCE_PX
    ): Boolean {
        if (!isValid(a) || !isValid(b)) return false
        if (abs(centerY(a) - centerY(b)) <= max(0, tolerancePx)) return true
        return verticalOverlapRatio(a, b) >= 0.5f
    }

    // ── 行高推估 / 分列 ────────────────────────────────────────────

    /** 節點高度的中位數（px）；沒有有效節點回 0 */
    fun medianRowHeight(nodes: List<GroundingCandidate>): Int {
        val heights = nodes
            .filter { isValid(it.bounds) }
            .map { height(it.bounds) }
            .filter { it > 0 }
            .sorted()
        if (heights.isEmpty()) return 0
        val mid = heights.size / 2
        return if (heights.size % 2 == 1) {
            heights[mid]
        } else {
            (heights[mid - 1] + heights[mid]) / 2
        }
    }

    /**
     * 自動列對齊容差：`max(DEFAULT, medianHeight/2)`，上限 [MAX_ROW_TOLERANCE_PX]。
     * 沒有可用節點時回 [DEFAULT_ROW_TOLERANCE_PX]。
     */
    fun rowTolerance(nodes: List<GroundingCandidate>): Int {
        val median = medianRowHeight(nodes)
        if (median <= 0) return DEFAULT_ROW_TOLERANCE_PX
        return min(MAX_ROW_TOLERANCE_PX, max(DEFAULT_ROW_TOLERANCE_PX, median / 2))
    }

    /**
     * 把候選節點按列分組（每列內的節點保持輸入相對順序，列按 y 排序）。
     *
     * @param tolerancePx <= 0 表示用 [rowTolerance] 自動推估
     */
    fun rows(
        nodes: List<GroundingCandidate>,
        tolerancePx: Int = 0
    ): List<List<GroundingCandidate>> {
        val valid = nodes.filter { isValid(it.bounds) }
        if (valid.isEmpty()) return emptyList()
        val tol = if (tolerancePx > 0) tolerancePx else rowTolerance(valid)
        val sorted = valid.sortedWith(
            compareBy<GroundingCandidate> { centerY(it.bounds) }
                .thenBy { it.bounds[0] }
                .thenBy { it.id }
        )
        val out = ArrayList<MutableList<GroundingCandidate>>()
        for (c in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.isNotEmpty() && sameRow(last[0].bounds, c.bounds, tol)) {
                last.add(c)
            } else {
                out.add(mutableListOf(c))
            }
        }
        return out
    }

    // ── 最近可點元素 ───────────────────────────────────────────────

    /**
     * 找出離 `textRect` 最近的可點擊元素（I3 標籤歸屬的候選集合）。
     *
     * 規則：
     *  - 只回 `clickable && enabled` 且幾何有效的節點（disabled 永遠不能當目標）
     *  - 排序：邊框距離 → 中心距離 → 分數高者 → id（完整 tie-break，可重播）
     *  - `textRect` 無效時退化為「按分數排序」的前 [limit] 個（不猜距離）
     *
     * @param limit 最多回傳幾個（<=0 回空清單）
     */
    fun nearestClickable(
        textRect: IntArray?,
        nodes: List<GroundingCandidate>,
        limit: Int = DEFAULT_NEAREST_LIMIT
    ): List<GroundingCandidate> {
        if (limit <= 0) return emptyList()
        val actionable = nodes.filter { it.clickable && it.enabled && isValid(it.bounds) }
        if (actionable.isEmpty()) return emptyList()
        if (!isValid(textRect)) {
            return actionable
                .sortedWith(compareByDescending<GroundingCandidate> { it.score }.thenBy { it.id })
                .take(limit)
        }
        return actionable
            .sortedWith(
                compareBy<GroundingCandidate> { edgeDistance(textRect, it.bounds) }
                    .thenBy { centerDistance(textRect, it.bounds) }
                    .thenByDescending { it.score }
                    .thenBy { it.id }
            )
            .take(limit)
    }

    // ── 列 owner（純幾何版） ────────────────────────────────────────

    /**
     * 推論「這段文字屬於哪個可點元素」的**純幾何**版本（不會做列型分類；
     * 權重化/列型分類見 [LabelOwnership.ownerOf]）。
     *
     * 只在與 `textRect` 同列的可點元素裡挑，評分直覺：
     *  1. 完整包含文字（整列可點）最高分
     *  2. 垂直涵蓋文字中心（例如右側開關的垂直中線對齊）次高
     *  3. 水平越近越好；寬度 >= 半個畫面的列狀元件加分
     *  4. 垂直重疊比例越高越好
     *
     * @param screenW 螢幕寬（px，用於正規化；<=0 時以 1 代替避免除以 0）
     * @return null＝同列沒有任何可點元素
     */
    fun rowOwner(
        textRect: IntArray?,
        nodes: List<GroundingCandidate>,
        screenW: Int
    ): GroundingCandidate? {
        val target = textRect ?: return null
        if (!isValid(target)) return null
        val tol = rowTolerance(nodes)
        val rowNodes = nodes.filter {
            it.enabled && it.clickable && isValid(it.bounds) && sameRow(it.bounds, target, tol)
        }
        if (rowNodes.isEmpty()) return null
        return rowNodes
            .sortedWith(
                compareByDescending<GroundingCandidate> { ownerScore(it, target, screenW) }
                    .thenBy { edgeDistance(target, it.bounds) }
                    .thenByDescending { it.score }
                    .thenBy { it.id }
            )
            .first()
    }

    /** 列 owner 幾何評分（[rowOwner] 用；分數本身沒有絕對意義，只做相對排序） */
    private fun ownerScore(c: GroundingCandidate, textRect: IntArray, screenW: Int): Float {
        var s = 0f
        if (contains(c.bounds, textRect)) {
            s += 0.60f
        } else if (c.bounds.size == 4 &&
            c.bounds[1] <= centerY(textRect) &&
            c.bounds[3] >= centerY(textRect)
        ) {
            s += 0.40f
        }
        val dist = edgeDistance(c.bounds, textRect)
        val widthNorm = max(1, screenW).toFloat()
        s += (0.30f - dist / widthNorm).coerceIn(0f, 0.30f)
        if (overlaps(c.bounds, textRect)) s += 0.10f
        if (screenW > 0 && width(c.bounds) >= screenW * 0.5f) s += 0.15f
        s += 0.10f * verticalOverlapRatio(c.bounds, textRect)
        s -= (dist / widthNorm) * 0.10f
        return s
    }
}
