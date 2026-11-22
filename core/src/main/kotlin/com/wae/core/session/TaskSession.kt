package com.wae.core.session

/**
 * 任務狀態機（I4：**唯一**持有可變狀態的地方）。
 *
 * 狀態轉移是白名單；走錯格代表呼叫端邏輯有問題，直接丟例外（比默默錯下去好抓）。
 */
class TaskSession private constructor(
    val command: String,
    private val maxReplans: Int
) {

    enum class State { IDLE, COMPILING, PLANNING, EXECUTING, VERIFYING, DONE, ABSTAINED }

    var state: State = State.IDLE
        private set

    private var replanCount: Int = 0

    val replansUsed: Int get() = replanCount

    fun moveTo(next: State) {
        require(isAllowed(state, next)) { "非法狀態轉移：$state → $next" }
        state = next
    }

    /** 重規劃一次；用盡回 false（呼叫端據此 `ABSTAIN`）。 */
    fun replan(): Boolean {
        if (replanCount >= maxReplans) return false
        replanCount++
        if (state != State.PLANNING) moveTo(State.PLANNING)
        return true
    }

    /** 輔助步驟（捲動／退回）結束後回到規劃，不消耗重規劃額度。 */
    fun backToPlanning() {
        if (state != State.PLANNING) moveTo(State.PLANNING)
    }

    fun done() {
        moveTo(State.DONE)
    }

    fun abstained() {
        if (state != State.ABSTAINED && state != State.DONE) moveTo(State.ABSTAINED)
    }

    private fun isAllowed(from: State, to: State): Boolean = to in ALLOWED[from].orEmpty()

    companion object {
        /** 初始狀態 */
        private val ALLOWED: Map<State, Set<State>> = mapOf(
            State.IDLE to setOf(State.COMPILING, State.ABSTAINED),
            State.COMPILING to setOf(State.PLANNING, State.ABSTAINED),
            State.PLANNING to setOf(State.EXECUTING, State.ABSTAINED),
            State.EXECUTING to setOf(State.VERIFYING, State.ABSTAINED),
            // 驗證後：多步計畫 → 下一步執行；失敗 → 重規劃；全部成立 → 完成
            State.VERIFYING to setOf(State.EXECUTING, State.PLANNING, State.DONE, State.ABSTAINED),
            State.DONE to emptySet(),
            State.ABSTAINED to emptySet()
        )

        fun create(command: String, maxReplans: Int): TaskSession {
            require(maxReplans >= 0) { "maxReplans 不可為負" }
            return TaskSession(command, maxReplans)
        }
    }
}
