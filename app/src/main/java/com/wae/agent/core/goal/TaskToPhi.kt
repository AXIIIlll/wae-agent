package com.wae.agent.core.goal

import com.wae.core.predicate.P
import com.wae.core.predicate.Phi

/**
 * 任務 → `Phi` + 槽位（計畫 WP3 的離線部分）。
 *
 * ## 為什麼要有這一層
 *
 * 圖導航只會回答「往哪走」，不會回答「到了沒」。現在 `AutomatonPlanner` 的目標是
 * 「走到含某個 selector 的頁面」——如果目標元素根本不在圖上，或到了頁面但任務沒完成，
 * 圖會回報成功而實際失敗。
 *
 * 這一層把任務編譯成**可驗證的目標狀態**（`Phi`）＋**要填的槽位**：
 *
 * ```
 * 「在 YouTube 搜尋音樂並播放第一首」
 *   Phi  = 存在一個元素，同時是「播放中的影片」且標籤含「音樂」
 *   slots = query=音樂, ordinal=1
 * ```
 *
 * ## 三個刻意的規則
 *
 * 1. **編譯不出來就誠實回 `NotCompilable`**，呼叫端不能用圖。寧可走舊路徑，也不要
 *    「到了目標頁但任務沒完成」的假成功。
 * 2. **不猜槽位**：`query` 這類必要槽位缺了就是編譯失敗，不是給空字串。
 * 3. **不新增 predicate 語言**：直接用既有的 [Phi] / [P]，避免第五套規則。
 */
object TaskToPhi {

    /** 任務的結構化輸入（由既有的句型規則／槽位抽取產生，不在這裡重做解析）。 */
    data class TaskGoal(
        /** 指令原文，只用於記錄與除錯。 */
        val command: String,
        /** 解析出的 App 套件（空字串＝未解析）。 */
        val appPackage: String = "",
        /** 句型／意圖：LAUNCH / FIND / VIEW / TOGGLE / TYPE（大小寫不拘）。 */
        val intent: String = "",
        /** 已抽取的槽位，例如 query / target / desired。 */
        val slots: Map<String, String> = emptyMap()
    )

    sealed class Result {
        /** 編譯成功：有可驗證的目標，且必要槽位齊全。 */
        data class Compiled(
            val phi: Phi,
            val slots: Map<String, String>,
            val note: String
        ) : Result()

        /** 編譯失敗：呼叫端不得使用圖導航，應退回現有路徑。 */
        data class NotCompilable(val reason: String) : Result()
    }

    /** 這些意圖需要「目標物」才能編譯；沒有就失敗。 */
    private val needsTarget = setOf("VIEW", "TOGGLE", "TYPE", "FIND", "PICK")

    fun compile(goal: TaskGoal): Result {
        val intent = goal.intent.trim().uppercase()
        val target = firstNonBlank(
            goal.slots["target"],
            goal.slots["object"],
            goal.slots["payload"],
            goal.slots["query"]
        )

        // 純啟動：目標就是「前景是這個 App」。沒有 App 就編譯不出來。
        if (intent == "LAUNCH") {
            if (goal.appPackage.isBlank()) return Result.NotCompilable("LAUNCH 缺少 App 套件")
            return Result.Compiled(P.pkg(goal.appPackage), emptyMap(), "前景＝${goal.appPackage}")
        }

        if (intent in needsTarget && target.isBlank()) {
            return Result.NotCompilable("$intent 缺少目標物（target/query）")
        }

        // 開關類：目標是「某個開關變成指定狀態」。
        if (intent == "TOGGLE") {
            val desired = parseBool(goal.slots["desired"] ?: goal.slots["on"])
                ?: return Result.NotCompilable("TOGGLE 缺少 desired（on/off）")
            return Result.Compiled(
                phi = P.toggle(target, desired),
                slots = mapOf("target" to target, "desired" to desired.toString()),
                note = "存在開關『$target』且狀態=$desired"
            )
        }

        // 搜尋／查看：目標是「畫面上出現目標物」。有 App 就一併要求在前景。
        if (intent == "FIND" || intent == "VIEW" || intent == "PICK") {
            val label = P.label(target)
            val phi = if (goal.appPackage.isNotBlank()) {
                P.and(P.pkg(goal.appPackage), label)
            } else {
                label
            }
            val slots = LinkedHashMap<String, String>()
            slots["target"] = target
            goal.slots["query"]?.let { slots["query"] = it }
            goal.slots["ordinal"]?.let { slots["ordinal"] = it }
            return Result.Compiled(
                phi = phi,
                slots = slots,
                note = if (goal.appPackage.isNotBlank()) {
                    "前景＝${goal.appPackage} 且畫面出現『$target』"
                } else {
                    "畫面出現『$target』"
                }
            )
        }

        // 其他（TYPE／ADJUST／未知名稱）：目前不編譯，交給現有路徑。
        return Result.NotCompilable("意圖『${goal.intent}』尚未支援編譯")
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun parseBool(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true", "on", "開", "開啟", "打開", "開著", "1" -> true
        "false", "off", "關", "關閉", "關掉", "0" -> false
        else -> null
    }
}
