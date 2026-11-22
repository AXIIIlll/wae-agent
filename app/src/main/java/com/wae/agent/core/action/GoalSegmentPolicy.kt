package com.wae.agent.core.action

import com.wae.agent.core.LocalGoal

/**
 * 部分失敗策略（任務分段）。
 *
 * 原則：
 *  - **同一段的目標共用同一個 App 上下文**（targetPackageHint 相同）→ 一個失敗，其餘同段目標一起停。
 *  - **不同段（不同 App / 不同階段）互相獨立** → 一段失敗不影響其他段繼續執行。
 *  - 全部失敗或整段失敗時，呼叫端必須明確告知「無法完成」，而不是繼續亂點。
 *
 * 純邏輯，不含任何 App 資料（分段依據是目標自身的套件欄位）。
 */
object GoalSegmentPolicy {

    data class Outcome(
        val continueExecution: Boolean,
        val skippedGoals: List<LocalGoal>,
        val reason: String
    )

    /**
     * @param goals 全部目標（依序）
     * @param failedIndex 失敗目標的索引
     */
    fun afterFailure(goals: List<LocalGoal>, failedIndex: Int): Outcome {
        if (failedIndex !in goals.indices) {
            return Outcome(false, emptyList(), "目標索引無效")
        }
        val failed = goals[failedIndex]
        val remaining = goals.drop(failedIndex + 1)
        if (remaining.isEmpty()) {
            return Outcome(false, emptyList(), "已無後續目標")
        }
        // S28：若下一個目標「依賴前一步」（序列指令），前一步失敗就不能繼續
        if (remaining.firstOrNull()?.dependsOnPrevious == true) {
            return Outcome(
                continueExecution = false,
                skippedGoals = remaining,
                reason = "後續 ${remaining.size} 個目標依賴前一步（序列指令）→ 全部停止"
            )
        }
        val sameSegment = remaining.filter { it.targetPackageHint == failed.targetPackageHint }
        val otherSegments = remaining.filter { it.targetPackageHint != failed.targetPackageHint }
        if (otherSegments.isEmpty()) {
            // 剩下的都屬於同一段 → 全部放棄，不要繼續點
            return Outcome(
                continueExecution = false,
                skippedGoals = sameSegment,
                reason = "同一階段的後續目標已一併停止（${sameSegment.size} 個）"
            )
        }
        return Outcome(
            continueExecution = true,
            skippedGoals = sameSegment,
            reason = "跳過同階段 ${sameSegment.size} 個目標，繼續執行其他階段 ${otherSegments.size} 個"
        )
    }

    /** 給使用者看的最終結果摘要 */
    fun summaryMessage(totalGoals: Int, succeededGoals: Int, failedGoalText: String?): String {
        return when {
            failedGoalText == null && succeededGoals == totalGoals -> "✓ 任務完成（$succeededGoals/$totalGoals）"
            succeededGoals == 0 -> "❌ 無法完成：任務在「$failedGoalText」失敗（0/$totalGoals）"
            else -> "⚠ 部分完成：$succeededGoals/$totalGoals（「$failedGoalText」失敗，其餘已執行）"
        }
    }
}