package com.wae.core.plan

/**
 * 計畫（Plan）——「這個提示詞該怎麼達成」。
 *
 * 為什麼要和提示詞分開：
 *  - 提示詞是使用者說的，不會變；計畫是我們的判斷，**可以隨時間與畫面改變**。
 *  - 同一個提示詞在不同 App／版本／畫面下，計畫可以不同（例如「看時間」在時鐘是 VIEW、在設定是 FIND）。
 *
 * 每一步都必須帶「執行前預期」與「執行後條件」，否則重規劃只能靠猜：
 *  - [PlanStep.precondition]：我應該在哪一種畫面做這件事（例如 pkg、頁面關鍵字）
 *  - [PlanStep.postcondition]：做完之後什麼必須成立（例如狀態變了、畫面變了、輸入框有字）
 *
 * 這一層刻意沒有任何 Android 依賴，全部可以在 JVM 測試。
 */
data class Plan(
    /** 計畫來源：TEMPLATE（資料模板）／RULE（句型規則）／RECOVERY（失敗後重規劃）。 */
    val origin: String,
    /** 一句人類看得懂的摘要，寫進軌跡用。 */
    val summary: String,
    val steps: List<PlanStep>,
    /** 這個計畫預期的目標 App（沒有就空字串）。 */
    val targetPackage: String = "",
    /** 版本號：重規劃時 +1，軌跡用它區分「第幾版計畫」。 */
    val revision: Int = 1
) {
    fun isEmpty(): Boolean = steps.isEmpty()
}

/**
 * 計畫的一步。
 *
 * @param action 動作語意（OPEN／FIND／PICK／TYPE／TOGGLE_ON／BACK／ADJUST…），
 *               對應到執行層的 TaskNode.Op；這裡用字串是為了讓 core 不依賴 app 的列舉。
 * @param target 目標物（畫面用詞，例如「歷史紀錄」）；可以是空字串（例如 BACK）
 * @param arg 參數（要輸入的文字、要調的方向…）
 * @param precondition 執行前應該成立的條件（人類可讀，`pkg=com.android.chrome` 之類）
 * @param postcondition 執行後必須成立的條件
 * @param risk 風險等級：safe／confirm／risky（高風險要確認，對應 VoiceRiskPolicy）
 * @param bannedKey 負面知識的鍵（`pkg|target|action`），執行前會查是否被禁
 */
data class PlanStep(
    val action: String,
    val target: String = "",
    val arg: String = "",
    val precondition: String = "",
    val postcondition: String = "",
    val risk: String = "safe",
    val bannedKey: String = ""
) {
    fun describe(): String = buildString {
        append(action)
        if (target.isNotBlank()) append(" '").append(target).append("'")
        if (arg.isNotBlank()) append(" arg=").append(arg)
        if (risk != "safe") append(" risk=").append(risk)
    }
}

/** 計畫執行的一筆軌跡（寫進 `filesDir/eval/plan_trace.tsv`）。 */
data class PlanTrace(
    val planOrigin: String,
    val planRevision: Int,
    val stepIndex: Int,
    val action: String,
    val target: String,
    /** 執行前畫面是否符合 precondition（null＝這步沒有 precondition）。 */
    val preconditionOk: Boolean?,
    /** 當下這一頁有幾個可選項。 */
    val optionCount: Int,
    /** 最後選了哪一個選項（沒有就空字串）。 */
    val chosenOption: String,
    /** 分數。 */
    val score: Float,
    /** 是否被負面知識擋下（跳過）。 */
    val skippedByBan: Boolean,
    /** 這一步花了多少毫秒。 */
    val elapsedMs: Long
) {
    /** TSV 一行（不含換行）。 */
    fun toTsv(): String = listOf(
        planOrigin, planRevision.toString(), stepIndex.toString(), action, target,
        preconditionOk?.toString() ?: "n/a", optionCount.toString(), chosenOption,
        "%.2f".format(score), skippedByBan.toString(), elapsedMs.toString()
    ).joinToString("\t")
}
