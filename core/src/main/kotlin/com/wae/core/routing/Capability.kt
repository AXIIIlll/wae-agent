package com.wae.core.routing

/**
 * 一個 App 對某類結果的**本地證據**。
 *
 * 注意：這裡沒有「App 會不會做這件事」的先驗宣告，只有「我做過幾次、成功幾次、平均幾步」。
 * 宣告式的能力表會過期（App 改版），歷史不會說謊。
 */
data class Capability(
    val appId: String,
    val goalKind: GoalKind,
    val attempts: Int = 0,
    val successes: Int = 0,
    val avgSteps: Int = 0
) {

    init {
        require(attempts >= 0) { "attempts 不可為負" }
        require(successes in 0..attempts) { "successes 必須介於 0..attempts" }
        require(avgSteps >= 0) { "avgSteps 不可為負" }
    }

    fun withSuccess(steps: Int): Capability = copy(
        attempts = attempts + 1,
        successes = successes + 1,
        avgSteps = blendedSteps(steps)
    )

    fun withFailure(steps: Int): Capability = copy(
        attempts = attempts + 1,
        avgSteps = blendedSteps(steps)
    )

    /** 指數移動平均：新的觀察佔 1/3，避免單次極端值翻盤。 */
    private fun blendedSteps(newSteps: Int): Int {
        if (avgSteps == 0) return newSteps
        return (avgSteps * 2 + newSteps) / 3
    }
}

/** 記憶體內的能力索引；正式版由 `Memory` 落盤（S7 再接）。 */
class CapabilityIndex {

    private val byKey = LinkedHashMap<String, Capability>()

    fun recordSuccess(appId: String, goalKind: GoalKind, steps: Int) {
        val key = keyOf(appId, goalKind)
        val current = byKey[key] ?: Capability(appId, goalKind, avgSteps = steps)
        byKey[key] = current.withSuccess(steps)
    }

    fun recordFailure(appId: String, goalKind: GoalKind, steps: Int) {
        val key = keyOf(appId, goalKind)
        val current = byKey[key] ?: Capability(appId, goalKind, avgSteps = steps)
        byKey[key] = current.withFailure(steps)
    }

    fun candidates(goalKind: GoalKind): List<Capability> =
        byKey.values.filter { it.goalKind == goalKind }

    fun all(): List<Capability> = byKey.values.toList()

    /** 從落盤資料還原（載入時不重新計算平均，直接採用歷史）。 */
    fun seed(rows: List<Capability>) {
        for (row in rows) byKey[keyOf(row.appId, row.goalKind)] = row
    }

    private fun keyOf(appId: String, goalKind: GoalKind): String = appId + SEP + goalKind.name

    private companion object {
        const val SEP = "\u0001"
    }
}
