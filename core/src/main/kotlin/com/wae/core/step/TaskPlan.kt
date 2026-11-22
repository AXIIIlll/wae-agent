package com.wae.core.step

/**
 * 任務層：大模型只碰這裡，而且有預算。
 *
 * 三條規則：
 *  1. 大模型每任務 ≤ 2 次（開始 + 必要時重規劃）。
 *  2. 沒有大模型（lite 版）→ 規則計畫接手：一句話＝一步。
 *  3. 計畫一定要是 StepGoal 清單；解析不出來就退回規則計畫。
 */
interface TaskPlanner {
    fun plan(memory: TaskMemory): List<StepGoal>?
}

/**
 * 規則版任務計畫（沒有大模型時的主力）。
 *
 * 目前只做最常見的三種形狀：
 *  - 有搜尋字（「在設定裡搜尋藍牙」）→ 找搜尋框 → 輸入 → 送出
 *  - 只有目的地（「看影片」）→ 打開就完成（內容留給人）
 *  - 其他 → 一步完成（交給既有 Gen5 管線）
 */
class RuleTaskPlanner : TaskPlanner {

    override fun plan(memory: TaskMemory): List<StepGoal>? {
        val text = memory.goalText.ifBlank { memory.command }
        val query = searchTermOf(text)
        return when {
            query != null -> listOf(
                StepGoal(StepKind.QUERY_FIELD, "搜尋框"),
                StepGoal(StepKind.SET_TEXT, query),
                StepGoal(StepKind.SUBMIT, "搜尋"),
                StepGoal(StepKind.DONE, "已搜尋「$query」")
            )
            else -> listOf(StepGoal(StepKind.DONE, "已到達"))
        }
    }

    /** 從「在 X 裡搜尋 Y」「搜尋 Y」抓出 Y。 */
    fun searchTermOf(text: String): String? {
        val markers = listOf("搜尋", "搜索", "查詢")
        for (marker in markers) {
            val index = text.indexOf(marker)
            if (index >= 0) {
                val rest = text.substring(index + marker.length).trim()
                if (rest.isNotEmpty()) return rest
            }
        }
        return null
    }
}

/** 大模型呼叫預算；用完就永遠走規則。 */
class PlannerBudget(private val limit: Int = 2) {
    private var used = 0

    fun canCall(): Boolean = used < limit

    fun noteCall(): Int {
        used++
        return used
    }

    fun used(): Int = used
}
