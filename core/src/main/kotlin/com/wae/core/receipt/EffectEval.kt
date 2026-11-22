package com.wae.core.receipt

/**
 * 結果級評測（B18）：不看路徑、不看畫面，只看「效果有沒有成立」。
 *
 * 這是「任意 App」唯一能誠實量的方式：同一個結果，不管走哪支 App、幾步，
 * 只看最後有沒有成立。
 */
data class EffectStats(
    val effectKey: String,
    val tasks: Int,
    val succeeded: Int
) {
    val rate: Float get() = if (tasks == 0) 0f else succeeded.toFloat() / tasks
}

object EffectEval {

    fun summarize(receipts: List<TaskReceipt>): List<EffectStats> {
        val byEffect = LinkedHashMap<String, IntArray>() // [tasks, success]
        for (r in receipts) {
            val success = if (r.outcome == OUTCOME_SUCCESS) 1 else 0
            for (e in r.effects.distinct()) {
                val slot = byEffect.getOrPut(e) { IntArray(2) }
                slot[0]++
                slot[1] += success
            }
        }
        return byEffect
            .map { (key, v) -> EffectStats(key, v[0], v[1]) }
            .sortedWith(compareBy<EffectStats> { it.rate }.thenByDescending { it.tasks })
    }

    /** 有出現過的效果種類數（廣度的分子）。 */
    fun distinctEffects(receipts: List<TaskReceipt>): Int =
        receipts.flatMap { it.effects }.distinct().size

    /** 整體成功率（只算「有明確結果」的任務）。 */
    fun successRate(receipts: List<TaskReceipt>): Float {
        if (receipts.isEmpty()) return 0f
        return receipts.count { it.outcome == OUTCOME_SUCCESS }.toFloat() / receipts.size
    }

    const val OUTCOME_SUCCESS = "SUCCESS"
}
