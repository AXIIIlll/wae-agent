package com.wae.agent.gen5

import android.content.Context
import com.wae.core.config.BundledData
import com.wae.core.config.Reason
import com.wae.core.intent.IntentRouter
import com.wae.core.model.Option

/**
 * 模型 A：目的型指令 → App（三層兩憶計畫 §2）。
 *
 * 為什麼獨立成一個檔案：這是「翻譯目的」的責任，不是執行。
 * 它只回兩種東西：**要執行的句子**（交給既有管線）或**要對使用者說的話**。
 *
 * 三條規則：
 *  1. 0 個候選 → 誠實說「沒有這類 App」（不要亂開最像的）
 *  2. 1 個候選 → 開；SEARCH 策略再補一句「在 X 裡搜尋 Y」
 *  3. 多個候選 → 反問（並記住「回答就是 App 名稱」）
 */
object VagueRunner {

    sealed class Result {
        /** 改成執行另一句（可選 afterCommand：成功後接著做）。 */
        data class Run(
            val command: String,
            val afterCommand: String? = null,
            /** HOME：進去後只要確認「有內容」；SEARCH：進去後搜尋。 */
            val strategy: IntentRouter.Strategy = IntentRouter.Strategy.HOME,
            /** 對應目的（能力表用）。 */
            val purpose: String = "",
            /** 選中的 App 套件（能力表用）。 */
            val pkg: String = ""
        ) : Result()

        /** 直接回報（不執行）。 */
        data class Answer(
            val summary: String,
            val reason: String,
            val options: List<Option> = emptyList()
        ) : Result()
    }

    @Volatile
    private var loaded = false

    /** 禮貌詞 → 條件句 → 繁簡；規則引擎用同一套（避免兩邊不一致）。 */
    private fun normalize(command: String): String {
        var text = com.wae.core.intent.Politeness.strip(command).trim()
        text = com.wae.core.intent.Conditional.strip(text)
        runCatching {
            val map = com.wae.core.rule.RuleEngine.simpTradOf(
                com.wae.core.config.BundledData.simpTradLines()
            )
            val sb = StringBuilder(text.length)
            for (ch in text) sb.append(map[ch.toString()] ?: ch.toString())
            text = sb.toString()
        }
        return text
    }

    private fun ensureLoaded() {
        if (loaded) return
        runCatching { IntentRouter.loadFromLines(BundledData.intentRoutes()) }
        loaded = true
    }

    /** 不是目的型指令就回 null（交回既有流程）。 */
    fun route(context: Context, command: String): Result? {
        ensureLoaded()
        // 先正規化：不然「我想找餐廳」不會命中「找餐廳」（前缀被禮貌詞擋住）
        val text = normalize(command)
        val route = runCatching { IntentRouter.route(text) }.getOrNull() ?: return null

        val candidates = runCatching {
            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)
        }.getOrDefault(emptyList()).filter { route.categories.contains(it.category) }
        // 事實層：已知「這個 App 不支援這個目的」就跳過（失敗 ≥2 次才會被標記）
        val apps = candidates.filter { app ->
            route.purpose.isEmpty() || !AppCapabilityStore.lacks(context, app.pkg, route.purpose)
        }.ifEmpty { candidates }

        if (apps.isEmpty()) {
            return Result.Answer(
                summary = "ABSTAIN reason=no_candidate route=" + route.term +
                    " categories=" + route.categories.joinToString("|") + " apps=0",
                reason = Reason.NO_CANDIDATE,
                options = listOf(Option("ok", "知道了"))
            )
        }

        if (apps.size > 1) {
            FollowUp.remember(context, template = "打開{slot}", question = "要用哪一個？")
            val names = apps.take(3).map { it.label }
            return Result.Answer(
                summary = "ABSTAIN reason=missing_slot route=" + route.term +
                    " candidates=" + names.joinToString("|"),
                reason = Reason.MISSING_SLOT,
                options = names.map { Option("app", it) }
            )
        }

        // 高風險類別：即使只有一個候選也不自動開（計畫 §2 硬規則）
        if (route.categories.any { it in HIGH_RISK_CATEGORIES }) {
            val label = apps.first().label
            return Result.Answer(
                summary = "ABSTAIN reason=missing_slot route=" + route.term +
                    " high_risk=" + route.categories.joinToString("|") + " candidate=" + label,
                reason = Reason.MISSING_SLOT,
                options = listOf(Option("app", label))
            )
        }

        val app = apps.first()
        return when (route.strategy) {
            IntentRouter.Strategy.SEARCH -> Result.Run(
                command = "打開" + app.label,
                afterCommand = "在" + app.label + "裡搜尋" + route.query,
                strategy = IntentRouter.Strategy.SEARCH,
                purpose = route.purpose,
                pkg = app.pkg
            )
            else -> Result.Run(
                command = "打開" + app.label,
                strategy = IntentRouter.Strategy.HOME,
                purpose = route.purpose,
                pkg = app.pkg
            )
        }
    }

    /** 這些類別即使只有一個候選也不自動開（錢、購物、銀行）。 */
    private val HIGH_RISK_CATEGORIES = setOf("BANK", "PAYMENT", "SHOPPING", "FINANCE")
}
