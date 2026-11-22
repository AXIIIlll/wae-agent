package com.wae.agent.core.decision

/**
 * VoI（Value of Information）感知調度器。
 *
 * 把感知模組（a11y / 視覺檢測 / OCR / 小模型）視為「有成本、有信息增益的服務」，
 * 在延遲預算內挑選「每毫秒換到最多決策品質」的組合。
 *
 * 這是競品沒有的調度層：他們要嘛全跑（慢），要嘛不跑（不準）。
 */
object VoiScheduler {

    data class PerceptionOption(
        val name: String,
        /** 預估耗時（毫秒） */
        val costMs: Long,
        /** 預期決策增益（0..1，呼叫方估算） */
        val gain: Float
    )

    data class Plan(
        val chosen: List<String>,
        val skipped: List<String>,
        val spentMs: Long,
        val totalGain: Float
    )

    /**
     * 貪婪＋密度排序：優先選「每毫秒增益最高」的感知服務，直到預算用盡或增益低於門檻。
     * 對這個問題 greedy 已接近最佳（成本可加、增益近似獨立）。
     *
     * minGain 預設 0.2：任何感知模組至少要能把決策正確率提升 20% 才值得花算力，
     * 否則「資訊充足的頁面」也會被 5% 的邊際增益拖著跑 OCR（實測就是這樣變慢的）。
     */
    fun plan(
        options: List<PerceptionOption>,
        budgetMs: Long,
        minGain: Float = 0.2f
    ): Plan {
        val candidates = options
            .filter { it.gain >= minGain && it.costMs > 0 }
            .sortedWith(
                compareByDescending<PerceptionOption> { it.gain / it.costMs.toFloat() }
                    .thenBy { it.costMs }
            )
        val chosen = ArrayList<String>()
        val skipped = ArrayList<String>()
        var spent = 0L
        var gain = 0f
        for (c in candidates) {
            if (spent + c.costMs <= budgetMs) {
                chosen.add(c.name)
                spent += c.costMs
                gain += c.gain
            } else {
                skipped.add(c.name)
            }
        }
        // 未達 minGain 的也列為 skipped（保持可觀測）
        options.filter { it.gain < minGain || it.costMs <= 0 }.forEach { skipped.add(it.name) }
        return Plan(chosen, skipped, spent, gain)
    }

    /**
     * 決策是否需要「加碼感知」打破平局。
     *
     * @param top1/top2 前兩名語意分
     * @param top1Probability 第一名機率
     */
    fun shouldEscalate(
        top1: Float,
        top2: Float,
        top1Probability: Float = 1f,
        ambiguityGap: Float = 0.03f,
        confidenceFloor: Float = 0.80f
    ): Boolean {
        val gap = kotlin.math.abs(top1 - top2)
        val nearTie = gap < ambiguityGap && top1 >= confidenceFloor && top2 >= confidenceFloor
        val weakLeader = top1Probability < 0.35f
        return nearTie || weakLeader
    }
}
