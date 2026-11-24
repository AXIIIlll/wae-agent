package com.wae.core.plan

/**
 * L1 明確度判定：先決定「這句話我懂不懂」，再決定要不要做。
 *
 * 為什麼要獨立一層：現在是「先分類、再規劃」，分類錯了整條就錯，
 * 而且錯得很有自信。先問「有沒有把握」可以在還沒動手前就停下（或反問）。
 *
 * 判準刻意用可量測的訊號，不用感覺：
 *  - 句型規則有沒有命中（FIND／VIEW／LAUNCH…）
 *  - App 有沒有解析出來
 *  - 目標物有沒有抽出來
 *  - 這句話有多大比例是詞典裡的已知詞
 */
object Ambiguity {

    enum class Verdict {
        /** 可以直接做：四個訊號都有。 */
        CLEAR,
        /** 可以做，但不夠確定：至少還有 App 與目標物，只是說法不熟。 */
        WEAK,
        /** 不該直接做：連 App 或目標物都沒有。 */
        UNKNOWN
    }

    data class Signals(
        val frameType: String? = null,
        val appResolved: Boolean = false,
        val targetExtracted: Boolean = false,
        val lexiconCoverage: Float = 0f
    )

    /** 低於這個覆蓋率，代表大部分字詞我們沒見過。 */
    const val COVERAGE_FLOOR = 0.35f

    fun decide(signals: Signals): Verdict {
        val hasFrame = !signals.frameType.isNullOrBlank()
        val knowsWords = signals.lexiconCoverage >= COVERAGE_FLOOR
        return when {
            // 有句型、有 App、有目標物 → 明確
            hasFrame && signals.appResolved && signals.targetExtracted -> Verdict.CLEAR
            // 沒句型但 App 與目標物都在，且詞彙認識 → 還算明確（分類器會接手）
            !hasFrame && signals.appResolved && signals.targetExtracted && knowsWords -> Verdict.CLEAR
            // 有 App 也有目標物，只是說法不熟 → 可以做，但記錄
            signals.appResolved && signals.targetExtracted -> Verdict.WEAK
            // 剩下：連 App 或目標物都缺
            else -> Verdict.UNKNOWN
        }
    }

    /** 給日誌用。 */
    fun describe(signals: Signals): String =
        "frame=${signals.frameType ?: "-"} app=${signals.appResolved} " +
            "target=${signals.targetExtracted} cov=%.2f".format(signals.lexiconCoverage)
}
