package com.wae.core.step

/**
 * 失效階梯（三層兩憶計畫 §7）。
 *
 * 為什麼要有明確階梯：沒有階梯的系統會在「換手段」與「換目標」之間無限打轉，
 * 然後用「試了很多次」收場——使用者只看到它亂動。
 *
 * 預算：同目標換手段 ≤2 → 換目標 ≤1 → 重規劃 ≤2 → 問人
 */
class Escalation(
    private val maxMeansRetries: Int = 2,
    private val maxTargetRetries: Int = 1,
    private val maxReplans: Int = 2
) {

    enum class Action {
        /** 同一個目標、換一種手段（節點→祖先→座標）。 */
        RETRY_MEANS,

        /** 換一個目標（StepDecision.alternative）。 */
        ALT_TARGET,

        /** 重規劃整個任務（大模型，預算內）。 */
        REPLAN,

        /** 停下來問人。 */
        ASK_HUMAN
    }

    /** 累計失敗次數（成功一步才歸零）——單調，才不會在同階無限打轉。 */
    private var failedAttempts = 0
    private var replans = 0

    /**
     * 這一步失敗了，下一步該做什麼？
     *
     * 用**累計次數**而不是分開的計數器：分開的計數器會在「換目標」後把
     * 「換手段」的額度重設，結果永遠回到第一階（實測：連續失敗 6 次仍在換手段）。
     */
    fun onStepFailed(): Action {
        failedAttempts++
        return when {
            failedAttempts <= maxMeansRetries -> Action.RETRY_MEANS
            failedAttempts <= maxMeansRetries + maxTargetRetries -> Action.ALT_TARGET
            failedAttempts <= maxMeansRetries + maxTargetRetries + maxReplans -> {
                replans++
                Action.REPLAN
            }
            else -> Action.ASK_HUMAN
        }
    }

    /** 成功一步就把階梯歸零（重規劃次數是任務級預算，不歸零）。 */
    fun onStepSucceeded() {
        failedAttempts = 0
    }

    fun replansUsed(): Int = replans
}
