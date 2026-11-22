package com.wae.core.routing

/**
 * 使用者要的**結果**（不是指令、不是 App）。
 *
 * 這是效果路由的起點：`Goal` 講「要達成什麼」，不講「用哪個 App」。
 */
enum class GoalKind {
    SEND_TEXT,
    SEND_MEDIA,
    SEARCH,
    TOGGLE,
    OPEN_ITEM,
    CALL
}

data class Goal(
    val kind: GoalKind,
    val subject: String = "",
    val recipient: String = "",
    /** 使用者有沒有明講要用哪個 App；有講就變成硬約束。 */
    val pinnedApp: String? = null
) {

    fun key(): String = buildString {
        append(kind.name)
        if (subject.isNotEmpty()) append('|').append(subject)
        if (recipient.isNotEmpty()) append('|').append(recipient)
    }
}
