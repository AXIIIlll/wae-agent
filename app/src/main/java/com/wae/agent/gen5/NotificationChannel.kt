package com.wae.agent.gen5

import com.wae.core.model.Frame
import com.wae.core.model.Step
import com.wae.core.verify.VerifierChannel

/**
 * 通知驗證通道（C2 的第一條）。
 *
 * 判準刻意保守：只問「同一個 App 在動作之後有沒有發通知」。
 * 這一條成立時，Agent 會把它記成 `CHANNEL:notification`，
 * 而不是默默把失敗改寫成成功——收據上看得出差別。
 */
class NotificationChannel : VerifierChannel {

    override val name: String = "notification"

    override fun corroborate(step: Step, after: Frame, sinceMs: Long): Boolean {
        val pkg = after.pkg
        if (pkg.isEmpty()) return false
        return NotificationInbox.latestFor(pkg, sinceMs) != null
    }
}
