package com.wae.core.exec

/**
 * 裝置動作的**唯一介面**（I1）。
 *
 * 只有 `core/device` 的實作會碰無障礙／手勢／Intent；
 * 其餘所有層看到的都是這個介面，因此核心可在 JVM 上跑假 Actuator。
 */
interface Actuator {

    /** 這個手段在這台裝置上能不能用（權限、通道、ABI…）。 */
    fun supports(means: com.wae.core.model.Means): Boolean

    /** 真的去做。**不重試、不判斷成敗語意**，只回報觀察到的事實。 */
    fun run(means: com.wae.core.model.Means): RawResult
}

/**
 * Actuator 的原始回報。
 *
 * - `applied=false, wrongEffect=false` → 什麼都沒發生（`NO_EFFECT`）
 * - `wrongEffect=true` → 跳到非預期的地方（`WRONG_EFFECT`，要回退）
 */
data class RawResult(
    val applied: Boolean,
    val wrongEffect: Boolean = false,
    val note: String = ""
) {
    companion object {
        fun applied(note: String = ""): RawResult = RawResult(applied = true, note = note)
        fun noEffect(note: String = ""): RawResult = RawResult(applied = false, note = note)
        fun wrong(note: String = ""): RawResult = RawResult(applied = false, wrongEffect = true, note = note)
    }
}
