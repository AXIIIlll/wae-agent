package com.wae.core.component

/**
 * 數值調整的「決策表」（服務瘦身第十塊，但先做成純函式再搬）。
 *
 * 為什麼要先抽這段：`performAdjustValue` 有 130 行，其中一半是**判斷要不要動手**。
 * 那些判斷以前只能靠真機踩坑（0→0 的假成功、盲點擊、唯讀元件…），
 * 抽成純函式後可以用單元測試釘住，搬家才不會把行為搬歪。
 *
 * 誠實邊界：
 *  - 這裡只決定「做什麼」，不執行；送出 set_progress／拖曳由呼叫端負責。
 *  - `OpenDeeper` 不是失敗，是「這一步只是導覽」。
 */
object ValueAdjustDecider {

    /** 目標元素的可信度（來自 CandidateOption）。 */
    data class Target(
        val hasOwnSemanticEvidence: Boolean,
        val hybridSimScore: Float,
        val readOnlyOnly: Boolean
    )

    data class Range(val min: Float, val max: Float, val current: Float)

    sealed class Decision {
        /** 沒有 live node：只能點座標，但**不能算完成**。 */
        data object TapOnlyNoNode : Decision()

        /** 不知道自己在點什麼 → 拒絕（盲目點擊比不動更糟）。 */
        data object RefuseBlindClick : Decision()

        /** 不是可調元件 → 往更深一層找（導覽步，不算完成）。 */
        data object OpenDeeper : Decision()

        /** 量程無效（min==max 或負）→ 拒絕；真實踩過 0→0 被當成拖曳成功。 */
        data object RefuseInvalidRange : Decision()

        /** 唯讀元件 → 拒絕。 */
        data object RefuseReadOnly : Decision()

        /** 可以調：帶回量程與目標值。 */
        data class Apply(val range: Range, val desired: Float, val intentMatched: String) : Decision()
    }

    /** 低於這個相似度、又沒有語意證據，就當作「不知道在點什麼」。 */
    const val MIN_SEMANTIC_SIM = 0.65f

    fun decide(
        nodePresent: Boolean,
        range: Range?,
        target: Target,
        intentRequested: Boolean,
        desiredValue: Float = 0f,
        intentMatched: String = ""
    ): Decision = when {
        !nodePresent -> Decision.TapOnlyNoNode
        range == null && !target.hasOwnSemanticEvidence && target.hybridSimScore < MIN_SEMANTIC_SIM ->
            Decision.RefuseBlindClick
        range == null -> Decision.OpenDeeper
        range.max - range.min <= 0f -> Decision.RefuseInvalidRange
        target.readOnlyOnly -> Decision.RefuseReadOnly
        !intentRequested -> Decision.OpenDeeper
        else -> Decision.Apply(range, desiredValue, intentMatched)
    }
}
