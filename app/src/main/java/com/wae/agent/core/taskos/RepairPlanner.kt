package com.wae.agent.core.taskos

/**
 * 修復規劃器：把「失敗」轉成「最小的下一步」，而不是整條任務重跑。
 *
 * 通用長任務的失敗有幾種型態，修復策略也不同：
 *  - 目標元素不在目前頁面 → 先返回一層再重試（頁面可能跑掉了）
 *  - 目標元素被彈窗蓋住 → 先按返回/關閉再重試
 *  - 輸入失敗（欄位沒對到）→ 清除後重填
 *  - 連續失敗 → 放棄（交回既有恢復機制/使用者）
 *
 * 原則是**最多修一次**：修復本身也會失敗，無限重試只會讓長任務卡死。
 *
 * 純 Kotlin，JVM 可測。
 */
object RepairPlanner {

    /** 修復建議 */
    data class Repair(
        val reason: String,
        val steps: List<TaskStep>,
        /** 修復後是否重試原本失敗的步驟 */
        val retryOriginal: Boolean
    )

    /** 同一步驟最多修幾次（避免無限循環） */
    const val MAX_REPAIRS_PER_STEP = 1

    /**
     * @param failed 失敗的步驟
     * @param attempt 這是同一步的第幾次嘗試（1 起算）
     * @param screenTexts 目前畫面文字（判斷是否被彈窗/其他頁面佔住）
     * @param planPrefix 修復步驟的 nodeId 前綴
     */
    fun plan(
        failed: TaskStep,
        attempt: Int,
        screenTexts: List<String> = emptyList(),
        planPrefix: String = "repair"
    ): Repair? {
        if (attempt > MAX_REPAIRS_PER_STEP) return null
        // 修復步驟本身不再修（避免「修復的修復」無限遞迴）
        if (failed.nodeId.contains("$planPrefix-")) return null
        val kind = failed.goal.actionKind
        // 清除類失敗不再修（清除失敗通常代表欄位不存在；交回上層重新感知）
        if (kind == com.wae.agent.core.ActionKind.CLEAR_TEXT) return null
        val screen = screenTexts.joinToString(" ").lowercase()

        // 1) 輸入類失敗：先清空再重填（欄位可能只吃得到部分文字）
        if (kind == com.wae.agent.core.ActionKind.TYPE ||
            kind == com.wae.agent.core.ActionKind.REPLACE_TEXT
        ) {
            val clear = TaskStep(
                nodeId = "$planPrefix-clear-${failed.nodeId}",
                goal = com.wae.agent.core.LocalGoal(
                    stepIdx = failed.goal.stepIdx,
                    goalId = "$planPrefix-clear-${failed.goal.goalId}",
                    goalText = "清除輸入內容",
                    canonicalIntentTag = "CLEAR_TEXT:" + failed.goal.goalText.take(20),
                    actionKind = com.wae.agent.core.ActionKind.CLEAR_TEXT
                )
            )
            return Repair("input_retry", listOf(clear), retryOriginal = true)
        }

        // 2) 畫面疑似被彈窗蓋住（權限/廣告/詢問）→ 先返回
        val popupWords = listOf("允許", "拒絕", "權限", "廣告", "稍後", "not now", "allow", "deny")
        if (popupWords.any { screen.contains(it) }) {
            return Repair("dismiss_popup", listOf(backStep(failed, planPrefix)), retryOriginal = true)
        }

        // 3) 非首頁的操作失敗（找不到元素）→ 返回一層再重試
        if (kind != com.wae.agent.core.ActionKind.BACK &&
            kind != com.wae.agent.core.ActionKind.LAUNCH_APP
        ) {
            return Repair("back_and_retry", listOf(backStep(failed, planPrefix)), retryOriginal = true)
        }

        // 4) 開 App 失敗或返回失敗：不修，交回上層（避免亂按）
        return null
    }

    private fun backStep(failed: TaskStep, planPrefix: String): TaskStep = TaskStep(
        nodeId = "$planPrefix-back-${failed.nodeId}",
        goal = com.wae.agent.core.LocalGoal(
            stepIdx = failed.goal.stepIdx,
            goalId = "$planPrefix-back-${failed.goal.goalId}",
            goalText = "返回",
            canonicalIntentTag = "BACK",
            actionKind = com.wae.agent.core.ActionKind.BACK
        )
    )
}
