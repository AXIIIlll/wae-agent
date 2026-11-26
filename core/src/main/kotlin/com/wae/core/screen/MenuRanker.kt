package com.wae.core.screen

/**
 * 選單按鈕的排序（通用）：解決「畫面上有兩個三點」的問題。
 *
 * 只用通用事實排序，不看套件名：
 *  1. **不在網頁內容裡**（Chrome 頁面內嵌的三點在 WebView 內，工具列那顆不在）
 *  2. **位置在上方右側**（工具列溢出的通則：上 15%、右 30%）
 *  3. 有資源名（真正的工具列按鈕通常有 id）
 *  4. 標籤越符合越好（交給呼叫端的標籤比對，這裡只做結構排序）
 *
 * 排序結果給呼叫端依序嘗試：點錯（沒出現彈出層）就換下一個。
 */
object MenuRanker {

    data class Ranked(val option: ScreenOption, val score: Float, val reason: String)

    /** 排序權重（全部可調，不寫死在邏輯裡）。 */
    data class Weights(
        val notInWeb: Float = 0.5f,
        val inWeb: Float = -1.0f,
        val top: Float = 0.3f,
        val right: Float = 0.3f,
        val hasViewId: Float = 0.2f,
        val popupLayer: Float = -0.2f,
        val topRatio: Float = 0.15f,
        val rightRatio: Float = 0.70f
    )

    /** 上方多少比例內算「工具列」。 */
    const val TOP_RATIO = 0.15f

    /** 右側多少比例內算「右側」。 */
    const val RIGHT_RATIO = 0.70f

    fun rank(
        options: List<ScreenOption>,
        screenHeightPx: Int,
        screenWidthPx: Int,
        /** 這些權重可從 ui_tunables.tsv 注入；預設值只是沒有設定時的後備。 */
        weights: Weights = Weights()
    ): List<Ranked> {
        if (options.isEmpty()) return emptyList()
        val h = screenHeightPx.coerceAtLeast(1)
        val w = screenWidthPx.coerceAtLeast(1)
        return options.map { o ->
            var score = 0f
            val reasons = ArrayList<String>(3)
            // 在網頁內容裡 → 幾乎一定不是瀏覽器工具列
            if (o.inWeb) {
                score += weights.inWeb
                reasons.add("inWeb")
            } else {
                score += weights.notInWeb
            }
            // 上方
            if (o.top <= (h * weights.topRatio).toInt()) {
                score += weights.top
                reasons.add("top")
            }
            // 右側（用中心點判斷，避免寬按鈕被判錯）
            val cx = (o.left + o.right) / 2f
            if (cx >= w * weights.rightRatio) {
                score += weights.right
                reasons.add("right")
            }
            // 有資源名：真正的工具列按鈕幾乎都有
            if (o.viewId.isNotEmpty()) {
                score += weights.hasViewId
                reasons.add("viewId")
            }
            // 彈出層裡的選單按鈕反而是「已經開過的選單」，優先度低
            if (o.layer == 0) {
                score += weights.popupLayer
                reasons.add("popupLayer")
            }
            Ranked(o, score, reasons.joinToString("+").ifEmpty { "plain" })
        }.sortedByDescending { it.score }
    }
}
