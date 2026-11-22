package com.wae.core.verify

import com.wae.core.model.Frame
import com.wae.core.model.Step

/**
 * 驗證通道（C2）：螢幕以外，還有沒有別的證據說「這件事真的發生了」？
 *
 * 三通道驗證的第一版：
 *  - `screen`：後置條件（本來就有）
 *  - `notification`：同一支 App 在動作後出現通知
 *  - `audio`／`ledger`：未來的通道
 *
 * 重要限制（誠實欄）：通知只能「佐證」，不能單獨判定成功——
 * 一則「已送出」的通知也可能來自別的操作。所以通道成立時，
 * 收據上會標 `CHANNEL:<name>`，而不是直接把失敗改寫成成功。
 */
interface VerifierChannel {
    val name: String
    fun corroborate(step: Step, after: Frame, sinceMs: Long): Boolean
}

/** 多通道聚合：任何一個通道成立就算佐證。 */
class CompositeVerifier(private val channels: List<VerifierChannel>) {

    fun corroborate(step: Step, after: Frame, sinceMs: Long): String? =
        channels.firstOrNull { runCatching { it.corroborate(step, after, sinceMs) }.getOrDefault(false) }?.name

    companion object {
        val NONE = CompositeVerifier(emptyList())
    }
}
