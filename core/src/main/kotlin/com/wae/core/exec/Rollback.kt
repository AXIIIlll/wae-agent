package com.wae.core.exec

import com.wae.core.model.Effect
import com.wae.core.model.Means
import com.wae.core.model.Op
import com.wae.core.model.Step

/**
 * 回滾（F6）：把可逆的動作**倒著做回去**。
 *
 * 誠實界線：
 *  - 只有「推得出來的反向操作」才回滾（開關再按一次、輸入清空、退回上一頁）。
 *  - 送訊息、付款這類**不可逆**動作不會被回滾，它們在風險層就被攔住（F2）。
 *  - 回滾不是保證，只是把「誤觸」的代價降到最低；每一次回滾都寫進收據。
 */
class Rollback(private val executor: Executor) {

    data class Report(val attempted: Int, val rolledBack: List<String>)

    fun run(log: TransactionLog): Report {
        val plan = log.rollbackPlan()
        if (plan.isEmpty()) return Report(0, emptyList())

        val done = ArrayList<String>(plan.size)
        for ((index, means) in plan.withIndex()) {
            // 開 App 不是「副作用」，倒做它（按返回）只會把使用者剛開的 App 關掉
            if (means is Means.Intent && means.action.endsWith("action.MAIN")) continue
            val step = Step(
                effect = Effect(ROLLBACK_TARGET + index, Op.NAVIGATE),
                means = means,
                post = { true },
                idempotencyKey = "rollback#$index#" + means.describe()
            )
            val outcome = runCatching { executor.run(step) }.getOrNull()
            if (outcome is com.wae.core.model.Outcome.Success) done.add(means.describe())
        }
        log.clear()
        return Report(plan.size, done)
    }

    companion object {
        const val ROLLBACK_TARGET = "rollback:"
    }
}
