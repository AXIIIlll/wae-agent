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

        /**
         * 打開溢出選單（右上三點）。找不到目標時的最後一招：
         * 「打開 Chrome 的歷史紀錄」這種項目藏在選單裡，畫面上看不到字。
         * 預設 false＝這個實作不支援（不影響既有測試）。
         */
        fun openOverflowMenu(): Boolean = false

        /**
         * 用 OCR 找文字並點下去（無障礙樹看不到的字，例如 Chrome 三點選單裡的項目）。
         * 預設 false＝不支援。
         */
        fun pickByOcr(target: String): Boolean = false

        /** 選單打開後要等多久才再擷取（各 App 動畫長短不同，交由實作提供）。 */
        fun menuSettleMs(): Long = 900L

        /**
         * 目前的畫面事實（套件、指紋、可選標籤、開關狀態、動作前指紋）。
         * 預設回空值＝沒有資訊，條件會因此判不成立（寧可看到 false，也不要假裝成立）。
         */
        fun facts(): com.wae.core.plan.PlanCondition.Facts = com.wae.core.plan.PlanCondition.Facts()
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
        groundFloor: Float = 0.55f,
        vars: com.wae.core.plan.PlanVars = com.wae.core.plan.PlanVars()
    ): Result {
        val outcomes = ArrayList<StepOutcome>()
        expanded.leaves.forEachIndexed { i, rawLeaf ->
            // 變數替換：target／arg 可以寫 $name，執行前換成已捕捉到的值
            val leaf = rawLeaf.copy(
                target = vars.substitute(rawLeaf.target),
                arg = vars.substitute(rawLeaf.arg)
            )

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
                // 前置條件：明確不成立就不要動作（例如計畫要求在某個 App 內做）
                if (leaf.pre.isNotBlank()) {
                    val facts = gateway.facts()
                    if (!com.wae.core.plan.PlanCondition.holds(leaf.pre, facts)) {
                        outcomes.add(StepOutcome(i, leaf, false, "precondition_failed(${leaf.pre})"))
                        return Result(
                            Status.FAILED, outcomes, i,
                            "「${leaf.target}」的前置條件不成立：${com.wae.core.plan.PlanCondition.describe(leaf.pre)}"
                        )
                    }
                }

                val (idx, score) = gateway.find(leaf.target)
                lastScore = score
                if (score >= groundFloor && idx >= 0) {
                    everFound = true
                    if (gateway.act(leaf.op, leaf.target, leaf.arg)) {
                        // 後置條件：說要變就必須真的變，否則視為這一步沒成（讓上層重規劃）
                        if (leaf.post.isNotBlank() &&
                            !com.wae.core.plan.PlanCondition.holds(leaf.post, gateway.facts())
                        ) {
                            outcomes.add(
                                StepOutcome(i, leaf, false, "postcondition_failed(${leaf.post})")
                            )
                            return Result(
                                Status.FAILED, outcomes, i,
                                "「${leaf.target}」做完但後置條件不成立：" +
                                    com.wae.core.plan.PlanCondition.describe(leaf.post)
                            )
                        }
                        acted = true
                        break
                    }
                }
                if (attempt == 1) {
                    Thread.sleep(600)
                    // 第一輪找不到 → 可能藏在右上選單裡：開選單後再找一次
                    if (!everFound && gateway.openOverflowMenu()) {
                        // 選單是彈出層：先等它畫完，再靠 a11y 找；找不到就改用 OCR 點。
                        Thread.sleep(gateway.menuSettleMs())
                        val (idx2, score2) = gateway.find(leaf.target)
                        lastScore = maxOf(lastScore, score2)
                        if (score2 >= groundFloor && idx2 >= 0) {
                            everFound = true
                            if (gateway.act(leaf.op, leaf.target, leaf.arg)) {
                                acted = true
                                break
                            }
                        } else if (gateway.pickByOcr(leaf.target)) {
                            // 選單項目 a11y 看不到（Chrome 是自繪彈出層），用 OCR 定位後點擊
                            everFound = true
                            acted = true
                            break
                        }
                    }
                }
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
