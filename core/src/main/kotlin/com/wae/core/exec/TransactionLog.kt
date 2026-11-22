package com.wae.core.exec

import com.wae.core.model.Means
import com.wae.core.model.Step

/** 交易日誌的一筆：做了什麼、前後畫面、能不能逆。 */
data class TxEntry(
    val effectKey: String,
    val means: String,
    val beforeFp: String,
    val afterFp: String,
    val reversible: Boolean,
    val reverse: Means?
)

/**
 * 交易日誌（C5）：讓「復原」變成操作，而不是按 BACK 碰運氣。
 *
 * 逆操作的來源有三種：
 *  1. 同一個開關再點一次（toggle）
 *  2. 回到上一頁（navigate）
 *  3. 沒 有（不可逆，例如送出訊息）
 *
 * 只有前三種可逆；不可逆的操作一定要在 `Risk` 上標成 HIGH 或 FORBIDDEN。
 */
class TransactionLog(private val maxEntries: Int = DEFAULT_MAX) {

    private val entries = ArrayList<TxEntry>()

    fun record(step: Step, beforeFp: String, afterFp: String, reverse: Means?) {
        entries.add(
            TxEntry(
                effectKey = step.effect.key(),
                means = step.means.describe(),
                beforeFp = beforeFp,
                afterFp = afterFp,
                reversible = reverse != null,
                reverse = reverse
            )
        )
        if (entries.size > maxEntries) entries.removeAt(0)
    }

    /** 由後往前產生回滾步驟（只回傳可逆的，不可逆的直接跳過並記錄）。 */
    fun rollbackPlan(): List<Means> = entries.asReversed().mapNotNull { entry ->
        if (!entry.reversible) null else entry.reverse
    }

    /** 本次任務有幾個不可逆操作（給「要不要確認」用）。 */
    fun irreversibleCount(): Int = entries.count { !it.reversible }

    fun entries(): List<TxEntry> = entries.toList()

    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_MAX = 50

        /** 常見可逆手段的推導：toggle 再點一次；開啟 App 用 BACK 退回；輸入清空。 */
        fun reverseOf(step: Step, beforeFp: String): Means? = when (val means = step.means) {
            is Means.A11y -> when (means.action) {
                ACTION_CLICK -> means
                ACTION_FOCUS -> null
                else -> null
            }
            is Means.Input -> if (means.text.isEmpty()) null else Means.Input(text = "", viaIme = means.viaIme)
            is Means.Intent -> Means.SystemApi(api = API_BACK)
            is Means.SystemApi -> when (means.api) {
                API_HOME, API_BACK -> null
                else -> null
            }
            is Means.Gesture -> null
            is Means.Teach -> null
        }

        private const val ACTION_CLICK = "click"
        const val ACTION_FOCUS = "focus"
        private const val API_HOME = "global_action:HOME"
        private const val API_BACK = "global_action:BACK"
    }
}
