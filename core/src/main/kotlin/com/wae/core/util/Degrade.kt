package com.wae.core.util

/**
 * 可降級的失敗：**取代靜默的 `runCatching { ... }.getOrNull()`**。
 *
 * 為什麼要這個：這個專案踩過「runCatching 吞掉錯誤，一小時找不到原因」。
 * 吞錯本身不是問題——很多地方真的可以降級（模型沒載到、權限沒有、某個
 * 欄位讀不到）。問題是**安靜地**吞：出錯時沒有任何痕跡，行為卻變了。
 *
 * 這裡的規則很簡單：
 *  - 你決定要降級，就明說「哪裡失敗了」（[where]）；
 *  - 失敗一定留痕（[logger]，由 App 接到 logcat；core 本身不碰 Android）；
 *  - 不能降級的地方就**不要用這個**，讓例外往上丟。
 *
 * 用法：
 * ```kotlin
 * val model = Degrade.orNull("warmup:TinyBert") { TinyBert.ensureLoaded(ctx) }
 * val installed = Degrade.orElse("isInstalled($pkg)", false) { pm.getPackageInfo(pkg, 0); true }
 * ```
 */
object Degrade {

    /** 由 App 層接到 `Log.w`；測試可換成收集器。預設什麼都不做（core 沒有 Android）。 */
    @Volatile
    var logger: (String) -> Unit = {}

    fun report(where: String, t: Throwable?) {
        val detail = t?.let { it.javaClass.simpleName + ": " + it.message } ?: "unknown"
        val msg = "$where 失敗，已降級（$detail）"
        runCatching { logger(msg) }
    }

    fun <T> orNull(where: String, block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        report(where, t)
        null
    }

    fun <T> orElse(where: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        report(where, t)
        fallback
    }

    fun <T> orEmpty(where: String, block: () -> List<T>): List<T> = orElse(where, emptyList(), block)
}
