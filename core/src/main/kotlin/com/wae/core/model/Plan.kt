package com.wae.core.model

/** 風險等級（規則放 `risk_rules.tsv`，不在程式裡硬編）。 */
enum class Risk { LOW, MEDIUM, HIGH, FORBIDDEN }

/**
 * 後置條件：**可判定的謂詞**。
 *
 * I2：每個 Step 必帶一個；成功＝`holds(after)==true`，不是「動作已送出」。
 * 純函式，可用錄下來的 Frame 離線重放。
 */
fun interface PostCondition {
    fun holds(frame: Frame): Boolean
}

/** 一步＝「用某個手段，達到某個效果的某個面向」。 */
data class Step(
    val effect: Effect,
    val means: Means,
    val post: PostCondition,
    val risk: Risk = Risk.LOW,
    val idempotencyKey: String = ""
) {

    init {
        require(idempotencyKey.isNotEmpty()) { "Step 必須帶冪等鍵（I2／防重複副作用）" }
    }

    fun describe(): String = effect.key() + " via " + means.describe()
}

/**
 * 計畫。`unsatisfied` 是「編譯得出效果、但這一幀找不到手段」的部分——
 * 它不是失敗，而是「需要先換畫面／補感知」。
 */
data class Plan(
    val steps: List<Step>,
    val unsatisfied: List<Effect> = emptyList(),
    val notes: List<String> = emptyList()
) {

    val isEmpty: Boolean get() = steps.isEmpty()

    /** 全部效果都有對應手段 */
    val complete: Boolean get() = steps.isNotEmpty() && unsatisfied.isEmpty()

    companion object {
        val EMPTY: Plan = Plan(steps = emptyList())
    }
}
