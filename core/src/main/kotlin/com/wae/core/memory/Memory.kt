package com.wae.core.memory

import com.wae.core.model.Attribution
import com.wae.core.model.Constraints
import com.wae.core.model.Frame
import com.wae.core.model.Outcome
import com.wae.core.model.Refutation
import com.wae.core.model.Step

/**
 * 記憶體的**唯一介面**（I4）。四個庫都在它後面：
 * frames（Frame Ledger）、contracts、refutations、programs。
 *
 * 只有 `Memory` 的實作可以寫檔；其他層只能透過這個介面讀寫。
 */
interface Memory {

    fun rememberFrame(frame: Frame)

    fun frameOf(fp: String): Frame?

    /** 失敗 → 反例（I3）。回傳更新後的反例，方便記 log。 */
    fun recordFailure(
        step: Step,
        outcome: Outcome,
        attribution: Attribution,
        frame: Frame
    ): Refutation

    /** 成功 → 反例計數減一。 */
    fun noteStepSuccess(step: Step, frame: Frame)

    fun constraintsFor(frame: Frame): Constraints

    fun refutations(): List<Refutation>

    /**
     * 使用者說「這步錯了」（G7）：直接把它禁掉。
     *
     * 跟 `recordFailure` 的差別：這是**人的判斷**，一次就生效，
     * 而且只需要（畫面指紋、效果、手段）三個識別，不需要 Step 物件。
     */
    fun ban(effectKey: String, means: String, frameFp: String): Boolean = false

    /** 使用者手動刪除一條反例（C10）；回傳是否真的刪到。 */
    fun removeRefutation(key: String): Boolean = false

    /**
     * 靜默失敗：執行器說「送出了／成功了」，但後置條件不成立。
     * 這是全系統最傷信任的一種錯 —— 使用者以為完成了，其實沒有。
     */
    fun recordSilentFailure(step: Step, frame: Frame) = Unit

    /** 本次行程累計的靜默失敗次數（主指標，見 MWM 吸收清單 §3.1）。 */
    fun silentFailures(): Int = 0

    /** 落盤（App 層在 onPause / 任務結束時呼叫）。 */
    fun flush()
}
