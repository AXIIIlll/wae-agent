package com.wae.core.exec

import com.wae.core.config.Config
import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.config.Reason
import com.wae.core.model.Outcome
import com.wae.core.model.Step
import com.wae.core.time.Clock

/**
 * 執行器（L4）：**唯一的裝置入口**（I1）。
 *
 * 責任只有四件：支援檢查、冪等、送出、把原始回報翻成 `Outcome`。
 * **不重試、不重規劃、不判斷成功**——後置條件由呼叫端用新的一幀驗證。
 */
class Executor(
    private val actuator: Actuator,
    private val clock: Clock,
    private val config: Config
) {

    private val lastRanAt = HashMap<String, Long>()

    fun run(step: Step): Outcome {
        if (!actuator.supports(step.means)) {
            return Outcome.Unknown(Reason.MEANS_NOT_SUPPORTED + ":" + step.means.describe())
        }

        val now = clock.nowMs()
        val window = config.long(Key.EXEC_IDEMPOTENCY_WINDOW_MS, Default.IDEMPOTENCY_WINDOW_MS)
        val last = lastRanAt[step.idempotencyKey]
        if (last != null && now - last < window) {
            // 同一意圖在視窗內重複 → 視為已完成，不重送（防重複副作用）
            return Outcome.Success
        }

        val raw = actuator.run(step.means)

        // 只有「真的送出且成功」才記冪等；失敗必須允許重試，
        // 否則重規劃會在同一視窗內被誤判成「已完成」而永遠不動。
        if (raw.applied && !raw.wrongEffect) {
            lastRanAt[step.idempotencyKey] = now
        } else {
            lastRanAt.remove(step.idempotencyKey)
        }

        return when {
            raw.wrongEffect -> Outcome.WrongEffect
            raw.applied -> Outcome.Success
            else -> Outcome.NoEffect
        }
    }

    /** 後置條件驗證失敗時由呼叫端通知：解除冪等標記，讓重規劃可以真的再試一次。 */
    fun forget(step: Step) {
        lastRanAt.remove(step.idempotencyKey)
    }
}
