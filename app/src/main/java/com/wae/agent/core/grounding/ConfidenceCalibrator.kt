package com.wae.agent.core.grounding

import android.content.Context

/**
 * 元素定位的信心校準（S32 / 計畫 I2 + I1 的第一道防線）。
 *
 * 為什麼需要：
 * 目前 `hybridSimScore` / 融合分數是「排序用的相對分」，不是機率。
 * 決策層卻直接拿相對分數硬點 Top-1 → 分數低也照點、兩個很像也照點。
 * 這裡把分數單調校準成 **P(這個元素就是目標)**，再用決策理論決定：
 *
 * ```
 * ACT       ：信心夠高、差距夠大 → 直接點
 * ASK       ：信心高但兩個很像 → 交給使用者澄清（寧可問，不要亂點）
 * RE_PERCEIVE：信心不足但頁面可能還在變 → 重感知一次
 * SCROLL    ：信心不足、頁面可捲動 → 先捲動找找看
 * ```
 *
 * 資料驅動：校準點放 `assets/open_source_ai_databases/ui_grounding_calibration.tsv`
 * （`point<TAB>score<TAB>prob`、`meta<TAB>key<TAB>value`），壞檔退回 bootstrap。
 * 校準點刻意保持單調（isotonic），保證「分數越高 → 機率不會更低」。
 *
 * 純 Kotlin：除了 `ensureLoaded(context)` 之外不碰 Android API，JVM 可測。
 */
object ConfidenceCalibrator {

    private const val DB_PATH = "open_source_ai_databases/ui_grounding_calibration.tsv"

    /** 沒有校準點時的 bootstrap（保守：寧可低估，不要高估信心） */
    private val DEFAULT_POINTS = listOf(
        0.0f to 0.02f,
        0.30f to 0.20f,
        0.45f to 0.40f,
        0.60f to 0.62f,
        0.75f to 0.80f,
        0.90f to 0.93f,
        1.00f to 0.99f
    )

    /** 決策門檻（可由 TSV meta 覆蓋） */
    private var minActProb = 0.70f
    private var minActMargin = 0.15f
    private var minReperceiveProb = 0.40f

    @Volatile
    private var points: List<Pair<Float, Float>> = DEFAULT_POINTS

    @Volatile
    private var loadAttempted = false

    fun ensureLoaded(context: Context?) {
        if (loadAttempted) return
        loadAttempted = true
        try {
            val lines = context?.assets?.open(DB_PATH)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
            if (lines != null) loadFromLines(lines) else resetToDefaults()
        } catch (_: Throwable) {
            resetToDefaults()
        }
    }

    /**
     * 解析：`point \t score \t prob`；`meta \t min_act_prob|min_act_margin|min_reperceive_prob \t value`。
     * 非法行跳過；解析後**強制排序 + 單調化**（機率只增不減）。
     */
    fun loadFromLines(lines: List<String>) {
        val parsed = ArrayList<Pair<Float, Float>>()
        var actP = minActProb
        var actM = minActMargin
        var reP = minReperceiveProb
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 3) continue
            when (p[0].trim().lowercase()) {
                "point" -> {
                    val s = p[1].trim().toFloatOrNull() ?: continue
                    val q = p[2].trim().toFloatOrNull() ?: continue
                    if (s.isFinite() && q.isFinite()) {
                        parsed.add(s.coerceIn(0f, 1f) to q.coerceIn(0f, 1f))
                    }
                }
                "meta" -> {
                    val v = p[2].trim().toFloatOrNull() ?: continue
                    when (p[1].trim().lowercase()) {
                        "min_act_prob" -> actP = v
                        "min_act_margin" -> actM = v
                        "min_reperceive_prob" -> reP = v
                    }
                }
            }
        }
        if (parsed.size < 2) {
            resetToDefaults()
            return
        }
        points = monotonic(parsed.sortedBy { it.first })
        minActProb = actP.coerceIn(0.3f, 0.95f)
        minActMargin = actM.coerceIn(0.01f, 0.5f)
        minReperceiveProb = reP.coerceIn(0.1f, minActProb)
    }

    fun resetForTest() {
        resetToDefaults()
        loadAttempted = false
    }

    private fun resetToDefaults() {
        points = DEFAULT_POINTS
        minActProb = 0.70f
        minActMargin = 0.15f
        minReperceiveProb = 0.40f
    }

    /** 單調化：確保 sorted 後機率不下降（違反時取前值） */
    private fun monotonic(sorted: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>(sorted.size)
        var prev = 0f
        for ((s, q) in sorted) {
            val v = maxOf(prev, q)
            out.add(s to v)
            prev = v
        }
        return out
    }

    fun pointCount(): Int = points.size

    /** 分數 → P(正確)。線性內插；超出範圍取端點值。 */
    fun pSuccess(score: Float): Float {
        val s = if (score.isFinite()) score.coerceIn(0f, 1f) else 0f
        val pts = points
        if (pts.isEmpty()) return s
        if (s <= pts.first().first) return pts.first().second
        if (s >= pts.last().first) return pts.last().second
        for (i in 1 until pts.size) {
            val (s1, q1) = pts[i]
            if (s <= s1) {
                val (s0, q0) = pts[i - 1]
                val t = if (s1 > s0) (s - s0) / (s1 - s0) else 0f
                return (q0 + (q1 - q0) * t).coerceIn(0f, 1f)
            }
        }
        return pts.last().second
    }

    /**
     * 決策：綜合「Top-1 機率」與「Top-1/Top-2 差距」決定動作。
     *
     * @param top1Prob 校準後的 Top-1 機率
     * @param margin   Top-1 與 Top-2 的機率差（只有一個候選時給 1.0）
     * @param scrollable 目前畫面是否可捲動（可捲動時低信心先捲動找）
     */
    fun decide(top1Prob: Float, margin: Float, scrollable: Boolean): GroundingAction {
        val p = top1Prob.coerceIn(0f, 1f)
        val m = margin.coerceIn(0f, 1f)
        return when {
            p >= minActProb && m >= minActMargin -> GroundingAction.ACT
            p >= minActProb -> GroundingAction.ASK
            p >= minReperceiveProb -> if (scrollable) GroundingAction.SCROLL else GroundingAction.RE_PERCEIVE
            scrollable -> GroundingAction.SCROLL
            else -> GroundingAction.RE_PERCEIVE
        }
    }

    fun thresholds(): Triple<Float, Float, Float> = Triple(minActProb, minActMargin, minReperceiveProb)

    enum class GroundingAction {
        /** 直接執行 */
        ACT,

        /** 請使用者澄清（兩個候選都很有可能） */
        ASK,

        /** 重感知一次（頁面還在變／分數不可信） */
        RE_PERCEIVE,

        /** 先捲動尋找（目標可能不在可見區） */
        SCROLL
    }
}
