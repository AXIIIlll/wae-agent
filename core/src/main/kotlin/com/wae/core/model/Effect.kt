package com.wae.core.model

/** 效果的操作種類（app-agnostic；不含任何 App 名稱）。 */
enum class Op { SET, TOGGLE, SELECT, SUBMIT, NAVIGATE, INPUT }

/** 這個效果做不到時怎麼辦（由使用者意圖決定，不是由程式猜）。 */
enum class FailPolicy { ASK, SKIP, ABORT }

/**
 * **效果**：使用者要的是一個狀態改變，不是一次點擊。
 *
 * 例：`Effect(target="bluetooth", op=SET, value="off")`
 *     `Effect(target="contact:媽媽", op=SUBMIT)`
 */
data class Effect(
    val target: String,
    val op: Op,
    val value: String = "",
    val onFail: FailPolicy = FailPolicy.ASK,
    /**
     * 這個效果的**述詞表示**（可選）。
     *
     * 有值時，驗證與規劃都用它；沒有時才由 `EffectPhi` 做保守推導（推不出就回 null）。
     * 注意：**不參與 [key]**，所以反例庫／路徑快取／能力索引的鍵格式不變。
     */
    val goal: com.wae.core.predicate.Phi? = null
) {

    init {
        require(target.isNotBlank()) { "Effect.target 不可為空" }
    }

    /** 反例庫與 plan cache 用的穩定鍵（不含使用者輸入值）。 */
    fun key(): String = buildString {
        append(op.name).append(':').append(target)
        if (value.isNotEmpty()) append('=').append(value)
    }

    companion object {
        fun of(op: Op, target: String, value: String = ""): Effect = Effect(target = target, op = op, value = value)
    }
}
