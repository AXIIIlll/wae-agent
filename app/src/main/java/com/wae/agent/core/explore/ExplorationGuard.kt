package com.wae.agent.core.explore

import android.content.Context
import com.wae.agent.core.data.AssetTable

/**
 * S5：探索護欄（架構書 §7.2「三層護欄」）。
 *
 * 探索是**主動點擊陌生元素**，風險最高，所以所有門檻與禁忌詞都放在資料檔
 * （`ui_exploration_policy.tsv`），程式只讀表；任何一項不滿足就不准探索。
 *
 * ```
 * 探索只在：充電中 ∧ 螢幕狀態允許 ∧ App 白名單 ∧ 預算未用完
 * 每輪預算：≤ 3 分鐘、≤ 40 個動作
 * 絕對禁區：輸入框、付款、刪除、權限對話框、設定的危險項
 * ```
 */
object ExplorationGuard {

    private const val DB_PATH = "open_source_ai_databases/ui_exploration_policy.tsv"

    data class Policy(
        val maxActionsPerRound: Int = 40,
        val maxMinutesPerRound: Int = 3,
        val maxActionsPerDay: Int = 120,
        val minIdleMs: Long = 6000,
        val requireCharging: Boolean = true,
        val requireScreenOff: Boolean = false,
        val forbiddenKeywords: List<String> = DEFAULT_FORBIDDEN
    )

    /** bootstrap：資料檔載入失敗時仍要有禁區（寧可保守） */
    val DEFAULT_FORBIDDEN = listOf(
        "付款", "購買", "訂閱", "刪除", "清除資料", "還原", "登出",
        "解除安裝", "權限", "密碼", "信用卡", "轉帳", "送出訂單"
    )

    @Volatile
    private var policy: Policy? = null

    private val table = AssetTable(DB_PATH)

    fun effective(): Policy = policy ?: Policy()

    fun ensureLoaded(context: Context?) {
        val lines = table.linesOrNull(context)
        if (lines == null) {
            policy = Policy()
            return
        }
        loadFromLines(lines)
    }

    fun loadFromLines(lines: List<String>): Unit {
        var maxActions = 40
        var maxMinutes = 3
        var maxPerDay = 120
        var minIdle = 6000L
        var requireCharging = true
        var requireScreenOff = false
        val forbidden = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 2) continue
            val k = p[0].trim().uppercase()
            val v = p[1].trim()
            when (k) {
                "MAX_ACTIONS_PER_ROUND" -> maxActions = v.toIntOrNull() ?: maxActions
                "MAX_MINUTES_PER_ROUND" -> maxMinutes = v.toIntOrNull() ?: maxMinutes
                "MAX_ACTIONS_PER_DAY" -> maxPerDay = v.toIntOrNull() ?: maxPerDay
                "MIN_IDLE_MS" -> minIdle = v.toLongOrNull() ?: minIdle
                "REQUIRE_CHARGING" -> requireCharging = v == "1"
                "REQUIRE_SCREEN_OFF" -> requireScreenOff = v == "1"
                "FORBIDDEN_KEYWORD" -> if (v.isNotEmpty()) forbidden.add(v)
            }
        }
        policy = Policy(
            maxActionsPerRound = maxActions,
            maxMinutesPerRound = maxMinutes,
            maxActionsPerDay = maxPerDay,
            minIdleMs = minIdle,
            requireCharging = requireCharging,
            requireScreenOff = requireScreenOff,
            forbiddenKeywords = if (forbidden.isEmpty()) DEFAULT_FORBIDDEN else forbidden
        )
    }

    fun resetForTest() {
        policy = null
        table.reset()
    }

    /** 第一層：環境與預算 */
    fun canExploreNow(
        charging: Boolean,
        screenOn: Boolean,
        actionsThisRound: Int,
        minutesThisRound: Float,
        actionsToday: Int,
        packageWhitelisted: Boolean
    ): Boolean {
        val p = effective()
        if (!packageWhitelisted) return false
        if (p.requireCharging && !charging) return false
        if (p.requireScreenOff && screenOn) return false
        if (!p.requireScreenOff && !screenOn) return false
        if (actionsThisRound >= p.maxActionsPerRound) return false
        if (minutesThisRound >= p.maxMinutesPerRound) return false
        if (actionsToday >= p.maxActionsPerDay) return false
        return true
    }

    /**
     * 第二層：單一元素的硬禁區（絕對不可探索）。
     * 只要文字或描述含禁忌詞 → 一律拒絕。
     */
    fun isForbidden(selector: String, text: String, description: String = ""): Boolean {
        val p = effective()
        val hay = (selector + " " + text + " " + description).lowercase()
        if (hay.isBlank()) return false
        return p.forbiddenKeywords.any { hay.contains(it.lowercase()) }
    }

    /** 第三層：只允許「安全的動作種類」——輸入/送出一律不探索 */
    fun isSafeAction(op: String): Boolean = when (op.uppercase()) {
        "TAP", "SCROLL_DOWN", "SCROLL_UP", "SWIPE_HORIZONTAL" -> true
        else -> false
    }
}
