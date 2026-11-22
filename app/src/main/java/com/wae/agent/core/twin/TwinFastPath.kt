package com.wae.agent.core.twin

import com.wae.agent.core.CandidateOption

/**
 * Twin 編譯快路徑（M3a）。
 *
 * 目的：把 M2 的「可重放」變成「真的少做事」——命中時直接派發，跳過 L3 決策與恢復流程。
 *
 * ## 為什麼不是只靠「頁面簽名相似度」
 * 實測：Maps 首頁每次進入時結構簽名數量都在變（15 / 23 / 25 個），
 * 同一查詢的兩次執行前置簽名 Jaccard 可能低於 0.4 —— 單靠簽名會漏命中。
 * 因此匹配以**元素選擇器 + 頁面姿態**為主訊號，簽名相似度為輔。
 *
 * ## 守門（任一不滿足就不用快路徑）
 * 1. 同一個 App
 * 2. 同一種動作 + 同一頁面姿態
 * 3. 選擇器（viewId / 角色+文字）一致，**或** 前置簽名相似度 ≥ 0.4
 * 4. 該選擇器歷史成功次數 ≥ [minPriorSuccess]
 * 5. 記錄在 [maxAgeMs] 內（App 改版即失效）
 *
 * 安全閘門不因快路徑而繞過（由呼叫端保證）。
 */
object TwinFastPath {

    data class Hit(
        val step: TwinStep,
        val candidate: CandidateOption,
        /** 選擇器是否完全一致（最強證據） */
        val selectorMatch: Boolean,
        val similarity: Float,
        val priorSuccess: Int
    ) {
        fun summary(): String =
            "hit selector=${selectorOf(step)} selectorMatch=$selectorMatch " +
                "sim=${"%.2f".format(similarity)} prior=$priorSuccess " +
                "→ '${candidate.coreText.take(16)}'"
    }

    fun selectorOf(step: TwinStep): String =
        com.wae.agent.core.action.SelectorKeys.of(step)

    private fun selectorOf(c: CandidateOption): String =
        com.wae.agent.core.action.SelectorKeys.of(c)

    /**
     * @param options 當前畫面的候選（呼叫端已感知），用於比對選擇器
     * @param history 已落地的歷史軌跡
     */
    fun match(
        pkg: String,
        action: String,
        posture: String,
        sigsBefore: List<Long>,
        options: List<CandidateOption>,
        history: List<TwinStep>,
        commandKey: String = "",
        nowMs: Long = System.currentTimeMillis(),
        minSimilarity: Float = 0.4f,
        minPriorSuccess: Int = 2,
        maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000
    ): Hit? {
        if (options.isEmpty() || history.isEmpty()) return null
        val usable = history.filter { h ->
            h.pkg == pkg &&
                h.action == action &&
                h.ok &&
                TwinTaskKey.compatible(h.commandKey, commandKey) &&
                nowMs - h.ts <= maxAgeMs &&
                (posture.isEmpty() || h.posture.isEmpty() || h.posture == posture)
        }
        if (usable.isEmpty()) return null

        data class Best(val option: CandidateOption, val step: TwinStep, val selectorMatch: Boolean, val sim: Float, val prior: Int)

        var best: Best? = null
        for (opt in options) {
            if (!opt.isClickable && !opt.isEditable) continue
            // 同一選擇器的歷史成功紀錄
            val sameSelector = usable.filter { h ->
                when {
                    h.viewId.isNotEmpty() && opt.viewId.isNotEmpty() -> h.viewId == opt.viewId
                    h.roleTag.isNotEmpty() && h.target.isNotEmpty() ->
                        h.roleTag == opt.roleTag && h.target == opt.coreText
                    else -> h.target.isNotEmpty() && h.target == opt.coreText
                }
            }
            if (sameSelector.size < minPriorSuccess) continue

            val last = sameSelector.maxByOrNull { it.ts } ?: continue
            val stepSim = TwinStore.stateSimilarity(last.sigsBefore, sigsBefore)
            val idMatch = last.viewId.isNotEmpty() && last.viewId == opt.viewId
            // 命中條件：選擇器一致（強證據）或 前置簽名相似（弱證據）
            if (!idMatch && stepSim < minSimilarity) continue

            val score = stepSim + if (idMatch) 1f else 0f
            val bestScore = best?.let { it.sim + if (it.selectorMatch) 1f else 0f } ?: -1f
            if (score > bestScore) {
                best = Best(
                    option = opt,
                    step = last,
                    selectorMatch = idMatch,
                    sim = stepSim,
                    prior = sameSelector.size
                )
            }
        }
        val b = best ?: return null
        return Hit(
            step = b.step,
            candidate = b.option,
            selectorMatch = b.selectorMatch,
            similarity = b.sim,
            priorSuccess = b.prior
        )
    }

    /**
     * 在當前候選中找「同一個元素」（不帶 candidates 參數的相容入口）：
     *  ① viewId 完全相同（最強）② 角色 + 文字相同 ③ 文字相同
     */
    fun locate(hit: Hit, options: List<CandidateOption>): CandidateOption? {
        val resolved = com.wae.agent.core.action.SelectorResolver.resolve(hit.step, options)
        if (resolved != null && resolved.level > 0) {
            runCatching {
                android.util.Log.i(
                    "WAE_SELFHEAL",
                    "level=${resolved.level}(${resolved.levelName}) '${resolved.candidate.coreText.take(16)}'"
                )
            }
        }
        return resolved?.candidate
    }
}
