package com.wae.core.step

/**
 * 步驟目標與單步決策的**共用 schema**（三層兩憶計畫 §5）。
 *
 * 為什麼要這個：大模型輸出「步驟目標」、小模型輸出「這一步怎麼做」，
 * 兩邊必須用同一套封閉詞彙，否則會各說各話（一個說「找搜尋框」、一個說「點第三個」）。
 *
 * 純資料＋純函式：不碰 Android、不碰模型，JVM 可測。
 */
enum class StepKind {
    /** 找到並聚焦輸入框。 */
    QUERY_FIELD,

    /** 在輸入框寫字（hint 是要寫的內容）。 */
    SET_TEXT,

    /** 送出／按搜尋。 */
    SUBMIT,

    /** 清單中選一項（hint 是條件，例：「第一支」）。 */
    LIST_ITEM,

    /** 打開某一項。 */
    OPEN_ITEM,

    /** 滑動找東西（hint 是方向）。 */
    SCROLL,

    /** 返回。 */
    BACK,

    /** 這一步代表完成。 */
    DONE,

    /** 放棄。 */
    ABORT
}

/** 大模型給的「這一步要達成什麼」。 */
data class StepGoal(
    val kind: StepKind,
    val hint: String = "",
    val required: Boolean = true
)

/** 小模型給的「這一步打算怎麼做」——**不含座標、不含節點 id**。 */
data class StepDecision(
    val kind: StepKind,
    val hint: String,
    val confidence: Float,
    val alternative: StepGoal? = null
)

/**
 * 仲裁規則（硬）：小模型只能在「當前步驟允許的動作型別」裡選。
 *
 * 例：步驟是「找輸入框」，小模型卻決定「點第一支影片」→ 不准行動。
 * 這條規則存在的理由：小模型看得到畫面，但**不該決定任務方向**。
 */
object StepContract {

    private val allowed: Map<StepKind, Set<StepKind>> = mapOf(
        StepKind.QUERY_FIELD to setOf(StepKind.QUERY_FIELD, StepKind.SCROLL, StepKind.BACK, StepKind.ABORT),
        StepKind.SET_TEXT to setOf(StepKind.SET_TEXT, StepKind.ABORT),
        StepKind.SUBMIT to setOf(StepKind.SUBMIT, StepKind.BACK, StepKind.ABORT),
        StepKind.LIST_ITEM to setOf(StepKind.LIST_ITEM, StepKind.SCROLL, StepKind.BACK, StepKind.ABORT),
        StepKind.OPEN_ITEM to setOf(StepKind.OPEN_ITEM, StepKind.SCROLL, StepKind.BACK, StepKind.ABORT),
        StepKind.SCROLL to setOf(StepKind.SCROLL, StepKind.BACK, StepKind.ABORT),
        StepKind.BACK to setOf(StepKind.BACK, StepKind.ABORT),
        StepKind.DONE to setOf(StepKind.DONE),
        StepKind.ABORT to setOf(StepKind.ABORT)
    )

    fun allowedFor(goal: StepKind): Set<StepKind> = allowed[goal] ?: setOf(StepKind.ABORT)

    fun isAllowed(goal: StepKind, decision: StepKind): Boolean = decision in allowedFor(goal)

    /** 不合契約就回 null（呼叫端必須「不行動」，不是將就著做）。 */
    fun validate(goal: StepGoal, decision: StepDecision): StepDecision? =
        if (isAllowed(goal.kind, decision.kind)) decision else null
}
