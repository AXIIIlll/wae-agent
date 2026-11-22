package com.wae.agent.core.omega

/**
 * WAE-Ω 第 3 層：型別化 Task IR（意圖編譯器的產物、策略驗證器與執行器的輸入）。
 *
 * 為什麼不直接用步驟清單：
 *  - 步驟清單「不可檢查」：看不出資料流是否完備、看不出型別是否正確、看不出風險。
 *  - Task IR 是**有型別的程式**：`READ(field) → WRITE(field, $var) → COMMIT`。
 *    型別讓「編譯期」就能做靜態檢查（可達性、資料流、風險），而不是執行到一半才失敗。
 *
 * 純 Kotlin，JVM 可測。
 */
data class TaskIrPlan(
    val id: String,
    val command: String,
    val steps: List<TaskIrStep>,
    /** 需要讀出的欄位名（資料流來源） */
    val reads: Set<String> = emptySet(),
    /** 需要寫入的欄位名 */
    val writes: Set<String> = emptySet(),
    /** 不可逆提交步數（COMMIT） */
    val commits: Int = 0
) {
    fun isEmpty(): Boolean = steps.isEmpty()

    fun describe(): String =
        "TaskIr(id=$id steps=${steps.size} reads=$reads writes=$writes commits=$commits)"
}

/**
 * 一個 Task IR 步驟。
 *
 * @param target 目標元素（型別＋文字提示/欄位名）；NAVIGATE/COMMIT 可為 null
 * @param value  寫入值或計算結果（WRITE/COMPUTE 用）
 * @param guard  守衛（例如「畫面出現同意」）；空字串＝無守衛
 */
data class TaskIrStep(
    val intent: Intent,
    val target: TargetRef? = null,
    val value: ValueRef? = null,
    val guard: String = "",
    /** 對應到既有 LocalGoal 的提示文字（讓執行層可以重用舊的路徑） */
    val goalHint: String = ""
) {
    enum class Intent {
        /** 導覽（開 App/點頁面/返回/捲動） */
        NAVIGATE,

        /** 從畫面讀出資料到變數 */
        READ,

        /** 把變數/常數寫進欄位 */
        WRITE,

        /** 計算（純本地、可審計；不呼叫網路） */
        COMPUTE,

        /** 條件分支（v1：只用守衛表示，不展開兩條路徑） */
        BRANCH,

        /** 不可逆提交（送出/付款前置） */
        COMMIT
    }
}

/** 目標元素參照：用本體論型別＋提示，而不是 App 專屬 selector */
data class TargetRef(
    val type: UiOntology.UiType,
    val textHint: String = "",
    val fieldName: String = ""
) {
    override fun toString(): String =
        "Target(${type.name}" +
            (if (textHint.isNotBlank()) " hint='${textHint.take(20)}'" else "") +
            (if (fieldName.isNotBlank()) " field=$fieldName" else "") + ")"
}

/** 值參照：變數、常數、或本地計算結果 */
data class ValueRef(
    val kind: Kind,
    val name: String = "",
    val literal: String = "",
    /** 計算式（只支援安全子集：四則運算、字串串接；不支援任意程式碼） */
    val expr: String = ""
) {
    enum class Kind { VAR, LITERAL, COMPUTE }

    override fun toString(): String = when (kind) {
        Kind.VAR -> "\$$name"
        Kind.LITERAL -> "'$literal'"
        Kind.COMPUTE -> "=($expr)"
    }

    companion object {
        fun variable(name: String): ValueRef = ValueRef(Kind.VAR, name = name)
        fun literal(text: String): ValueRef = ValueRef(Kind.LITERAL, literal = text)
        fun compute(expr: String): ValueRef = ValueRef(Kind.COMPUTE, expr = expr)
    }
}
