package com.wae.core.predicate

/**
 * **葉子執行器**：把任務樹展開後的葉子序列，逐格接到畫面上。
 *
 * 分工（這是整個設計的關鍵）：
 *  - **匹配**決定「指到哪一行」（[ScreenGateway.find]）
 *  - **執行器**決定「什麼時候問、什麼時候放棄」（本檔案）
 *  - **述詞**決定「做完沒」（`eval`，由 gateway 提供）
 *
 * 它刻意不知道 Android、不知道 a11y：全部透過 [ScreenGateway] 注入，
 * 所以可以在 JVM 上完整測試，也可以在真機上跑同一份邏輯。
 */
object TaskExecutor {

    /** 畫面的抽象（真機由 a11y/OCR 實作；測試用假的）。 */
    interface ScreenGateway {
        /** 當前畫面文字（每行一個元素，格式建議用 ScreenText）。 */
        fun screenLines(): List<String>

        /** 語意匹配：這個目標最像哪一行？回 (行索引, 分數)。 */
        fun find(target: String): Pair<Int, Float>

        /** 執行一個動作；回傳是否「有機會成功」（不是「已完成」）。 */
        fun act(op: TaskNode.Op, target: String, arg: String): Boolean

        /** 這個葉子是否已經成立（幂等：已經是開的就不必再點）。 */
        fun alreadySatisfied(leaf: TaskNode.Leaf): Boolean = false
    }

    enum class Status { DONE, ASK, FAILED }

    data class StepOutcome(
        val index: Int,
        val leaf: TaskNode.Leaf,
        val ok: Boolean,
        val note: String
    )

    data class Result(
        val status: Status,
        val steps: List<StepOutcome>,
        /** 卡在第幾個葉子（-1 = 全部完成）。 */
        val failedAt: Int = -1,
        /** 需要問使用者時的問題。 */
        val question: String = ""
    )

    /**
     * 逐葉執行。
     *
     * @param groundFloor 匹配分數低於此值 → 不在這一頁，直接問（不要亂點）
     */
    fun run(
        expanded: TaskExpander.Expanded,
        gateway: ScreenGateway,
        groundFloor: Float = 0.55f
    ): Result {
        val outcomes = ArrayList<StepOutcome>()
        expanded.leaves.forEachIndexed { i, leaf ->
            // 幂等：已經成立就跳過（「如果沒開就開」在這裡自然成立）
            if (gateway.alreadySatisfied(leaf)) {
                outcomes.add(StepOutcome(i, leaf, true, "already"))
                return@forEachIndexed
            }

            // **不需要接地的原語**：target 是「參數」（App 名稱／要打的字／固定語意按鈕），
            // 不是畫面上的元素。用「畫面上找不找得到」來判斷它們是錯的。
            when (leaf.op) {
                TaskNode.Op.ASK -> {
                    return Result(Status.ASK, outcomes, i, lease(leaf))
                }
                TaskNode.Op.LAUNCH, TaskNode.Op.FIND, TaskNode.Op.NEW,
                TaskNode.Op.SAVE, TaskNode.Op.BACK -> {
                    val ok = gateway.act(leaf.op, leaf.target, leaf.arg)
                    outcomes.add(StepOutcome(i, leaf, ok, if (ok) "self_located" else "act_failed"))
                    if (!ok) return Result(Status.FAILED, outcomes, i, "「${leaf.target}」這一步做不動")
                    return@forEachIndexed
                }
                else -> Unit
            }

            // 需要接地：用語意匹配找目標
            // **重規劃（最小版）**：失敗就重新擷取 → 重新接地 → 再試一次。
            // 不是盲目重點同一個座標：每次重試都重新找目標（畫面可能已變）。
            var acted = false
            var everFound = false
            var lastScore = 0f
            var attempt = 0
            while (attempt < 2) {
                attempt += 1
                val (idx, score) = gateway.find(leaf.target)
                lastScore = score
                if (score >= groundFloor && idx >= 0) {
                    everFound = true
                    if (gateway.act(leaf.op, leaf.target, leaf.arg)) {
                        acted = true
                        break
                    }
                }
                if (attempt == 1) Thread.sleep(600)
            }

            outcomes.add(
                StepOutcome(i, leaf, acted, "attempts=$attempt score=%.2f".format(lastScore))
            )
            if (!acted) {
                // 從沒找到 → 問使用者（不是失敗）；找到但做不動 → 失敗
                return if (!everFound) {
                    Result(
                        Status.ASK,
                        outcomes,
                        i,
                        "我在畫面上找不到「${leaf.target}」，可以再說得具體一點嗎？"
                    )
                } else {
                    Result(Status.FAILED, outcomes, i, "「${leaf.target}」試了 $attempt 次都做不動")
                }
            }
        }
        return Result(Status.DONE, outcomes)
    }

    private fun lease(leaf: TaskNode.Leaf): String =
        if (leaf.target.isNotBlank()) "我不確定「${leaf.target}」要怎麼做，可以換個說法嗎？"
        else "我不確定要怎麼做，可以換個說法嗎？"
}
