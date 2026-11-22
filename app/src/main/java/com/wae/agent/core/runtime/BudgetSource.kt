package com.wae.agent.core.runtime

import android.content.Context
import com.wae.agent.agent.AgentLimits
import com.wae.core.config.Config
import com.wae.core.config.Key
import com.wae.core.safety.ContractBudget

/**
 * 把 [Tunables] 接成 core 的 [Config]。
 *
 * 為什麼需要這一層：core 模組不能碰 `Context`，規定由 App 層注入設定；
 * 而 App 層的設定其實就是同一張 `ui_tunables.tsv`。有了這個適配器，
 * 「Gen5 的合約預算」與「舊路徑的迴圈上限」讀的就是同一份數字，
 * 不會再出現兩份鍵各自演化（一個改、另一個沒改）。
 */
class TunablesConfig(private val context: Context? = null) : Config {

    override fun str(key: String, def: String): String = Tunables.raw(key) ?: def

    override fun int(key: String, def: Int): Int = Tunables.getInt(context, key, def)

    override fun long(key: String, def: Long): Long = Tunables.getLong(context, key, def)

    override fun bool(key: String, def: Boolean): Boolean = Tunables.getBool(context, key, def)

    override fun float(key: String, def: Float): Float = Tunables.getFloat(context, key, def)
}

/**
 * 任務預算的**唯一出口**。
 *
 * 三個數字（步數／總時限／高風險次數）只認 `budget.*` 這組鍵，值只存在
 * `ui_tunables.tsv`；程式碼裡的 `Default` 只是連資料都讀不到時的最後防線。
 * 兩條執行路徑（Gen5 `ContractBudget`、舊迴圈 `AgentLimits`）都從這裡取，
 * 所以「改一份」就是「全部生效」。
 *
 * 尚未遷移：每一步重試次數與重規劃次數仍用舊鍵 `agent.max_retries_per_step`
 * / `agent.max_replans`。它們的值域與 core 的 `step.max_attempts` /
 * `plan.max_replans` 不同（1 對 2），直接合併會改變行為，要真機 A/B 後再動。
 */
object BudgetSource {

    const val RETRIES_KEY = "agent.max_retries_per_step"
    const val REPLANS_KEY = "agent.max_replans"

    /** 合約預算：步數、總時限、高風險次數。兩條路徑共用。 */
    fun contract(context: Context?): ContractBudget = ContractBudget.fromConfig(TunablesConfig(context))

    /** 舊迴圈的 `AgentLimits`：預算部分改用 [contract]，只保留尚未遷移的兩個鍵。 */
    fun limits(context: Context?): AgentLimits {
        val budget = contract(context)
        val fallback = AgentLimits()
        return AgentLimits(
            maxSteps = budget.maxSteps,
            maxRetriesPerStep = Tunables.getInt(context, RETRIES_KEY, fallback.maxRetriesPerStep),
            maxReplans = Tunables.getInt(context, REPLANS_KEY, fallback.maxReplans),
            deadlineMs = budget.maxWallClockMs
        )
    }

    /** 診斷用：目前生效的預算三個鍵（值為空表示落到程式碼預設）。 */
    fun describe(context: Context?): String {
        val b = contract(context)
        return "budget.max_steps=" + b.maxSteps +
            " budget.max_wallclock_ms=" + b.maxWallClockMs +
            " budget.max_high_risk=" + b.maxHighRiskOps
    }

    /** 這組鍵的權威定義在 core；這裡只是轉出去給守門測試用。 */
    val KEYS: List<String> = listOf(
        Key.BUDGET_MAX_STEPS,
        Key.BUDGET_MAX_WALLCLOCK_MS,
        Key.BUDGET_MAX_HIGH_RISK
    )
}
