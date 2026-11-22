package com.wae.agent.core.grounding

/**
 * 多模態交叉驗證（S32 / 計畫「多模態交叉驗證」）。
 *
 * 問題：a11y 樹有時是**過期的**（WebView / Compose / 自繪元件）：
 *  - a11y 說某個節點在 (x,y)，但畫面已經換頁/捲動/彈窗
 *  - a11y 文字與 OCR 看到的文字不一致（a11y 拿到舊內容）
 *  - OCR 看得到文字，但 a11y 樹根本沒有對應節點（純 Canvas）
 *
 * 做法：拿「候選元素」與「OCR 文字框」在**空間重疊**的前提下比對文字，
 * 得到一致程度，再決定這一步能不能信：
 *
 * ```
 * MATCH      三者一致（a11y 文字 == OCR 文字）→ 可信任
 * PARTIAL    部分重疊（OCR 只在框內部分文字，或候選文字較長）→ 可點但降信心
 * MISMATCH   a11y 文字與框內 OCR 文字完全不同 → 不可信（要重感知/用 OCR 重建候選）
 * A11Y_ONLY  框內沒有 OCR 文字（可能純圖示）→ 用 a11y 可操作旗標決定
 * OCR_ONLY   只有 OCR、沒有 a11y 候選（呼叫端決定是否用它建立新候選）
 * ```
 *
 * 純 Kotlin（`OcrBox` 由呼叫端從 PpOcrEngine.OcrLine 轉），JVM 可測。
 */
object CrossValidation {

    /** OCR 文字框（px） */
    data class OcrBox(val text: String, val bounds: IntArray) {
        val left: Int get() = bounds.getOrElse(0) { 0 }
        val top: Int get() = bounds.getOrElse(1) { 0 }
        val right: Int get() = bounds.getOrElse(2) { 0 }
        val bottom: Int get() = bounds.getOrElse(3) { 0 }
        val centerX: Int get() = left + (right - left) / 2
        val centerY: Int get() = top + (bottom - top) / 2
    }

    enum class Verdict { MATCH, PARTIAL, MISMATCH, A11Y_ONLY, OCR_ONLY }

    data class Result(
        val verdict: Verdict,
        /** 0..1：越高越可信 */
        val agreement: Float,
        /** 命中/重疊的 OCR 文字（diagnostics 用） */
        val evidence: String
    ) {
        val trusted: Boolean get() = verdict == Verdict.MATCH || verdict == Verdict.A11Y_ONLY
    }

    /** 候選與 OCR 框重疊率下限（候選框面積的 25%） */
    private const val MIN_OVERLAP_RATIO = 0.25f

    /**
     * 驗證一個候選元素。
     *
     * @param candidate 要驗證的候選（a11y 或融合後的元素）
     * @param ocrBoxes  同一幀的 OCR 結果
     * @param a11yTexts 這幀所有 a11y 文字（用於判斷「OCR 看到但 a11y 沒有」）
     */
    fun validate(
        candidate: GroundingCandidate,
        ocrBoxes: List<OcrBox>,
        a11yTexts: List<String> = emptyList()
    ): Result {
        if (!candidate.hasBounds) {
            return Result(Verdict.A11Y_ONLY, 0.4f, "no_bounds")
        }
        val candText = normalize(candidate.text)
        if (candText.isEmpty()) {
            // 沒有文字的候選（純圖示）：只能靠 a11y 旗標，不算驗證失敗
            return Result(Verdict.A11Y_ONLY, 0.5f, "no_text")
        }

        val overlapping = ocrBoxes.filter { box -> overlapRatio(candidate, box) >= MIN_OVERLAP_RATIO }
        if (overlapping.isEmpty()) {
            return Result(Verdict.A11Y_ONLY, 0.45f, "no_ocr_in_bounds")
        }

        // 取重疊最大的幾個 OCR 文字合起來比
        val ocrText = normalize(overlapping.sortedByDescending { overlapRatio(candidate, it) }
            .take(2).joinToString(" ") { it.text })
        if (ocrText.isEmpty()) {
            return Result(Verdict.A11Y_ONLY, 0.45f, "ocr_empty")
        }

        val sim = textSimilarity(candText, ocrText)
        val evidence = "a11y='${candidate.text.take(24)}' ocr='${ocrText.take(24)}' sim=${
            "%.2f".format(sim)
        }"
        return when {
            sim >= 0.80f -> Result(Verdict.MATCH, sim, evidence)
            sim >= 0.35f -> Result(Verdict.PARTIAL, sim, evidence)
            // 框內 OCR 文字與 a11y 文字完全不同 → a11y 可能是過期的（WebView/Compose）
            else -> Result(Verdict.MISMATCH, sim, evidence)
        }
    }

    /**
     * 反向：找出「OCR 看到、但 a11y 沒有對應文字」的區域（WebView/Canvas 補位用）。
     */
    fun ocrOnlyBoxes(ocrBoxes: List<OcrBox>, a11yTexts: List<String>): List<OcrBox> {
        val normalized = a11yTexts.map { normalize(it) }.filter { it.isNotEmpty() }
        return ocrBoxes.filter { box ->
            val t = normalize(box.text)
            t.isNotEmpty() && normalized.none { it.contains(t) || t.contains(it) }
        }
    }

    // ── 幾何 / 文字 ────────────────────────────────────────────────

    /** OCR 框覆蓋候選框面積的比例（0..1） */
    fun overlapRatio(candidate: GroundingCandidate, box: OcrBox): Float {
        val l = maxOf(candidate.left, box.left)
        val t = maxOf(candidate.top, box.top)
        val r = minOf(candidate.right, box.right)
        val b = minOf(candidate.bottom, box.bottom)
        if (r <= l || b <= t) return 0f
        val inter = (r - l).toLong() * (b - t).toLong()
        val candArea = candidate.areaPx.coerceAtLeast(1L)
        return (inter.toFloat() / candArea).coerceIn(0f, 1f)
    }

    /**
     * 文字相似度 0..1：中文用 2-gram、英文用空白詞。
     * 這是純啟發式（不花模型成本），只用在「a11y 與 OCR 是否同一段文字」。
     */
    fun textSimilarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0f
        if (na == nb) return 1f
        if (na.contains(nb) || nb.contains(na)) {
            val shorter = minOf(na.length, nb.length).toFloat()
            val longer = maxOf(na.length, nb.length).toFloat()
            return (0.6f + 0.3f * (shorter / longer)).coerceIn(0f, 0.95f)
        }
        val ta = tokens(na)
        val tb = tokens(nb)
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val inter = ta.intersect(tb).size
        val union = ta.union(tb).size
        return if (union == 0) 0f else inter.toFloat() / union
    }

    private fun tokens(s: String): Set<String> {
        val out = HashSet<String>()
        val ascii = s.filter { it.code < 128 }
        for (w in ascii.split(' ', '\t', '\n', ',', '.', ':', ';', '/', '-', '_')) {
            if (w.length >= 2) out.add(w)
        }
        val cjk = s.filter { it.code >= 0x4E00 && it.code <= 0x9FFF }
        for (i in 0 until cjk.length - 1) {
            out.add(cjk.substring(i, i + 2))
        }
        if (cjk.length == 1) out.add(cjk)
        return out
    }

    /** 正規化：小寫、去空白與常見標點 */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        return sb.toString()
    }
}
