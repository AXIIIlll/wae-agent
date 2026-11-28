package com.wae.agent.core.automaton

import com.wae.agent.core.action.SelectorKeys

import com.wae.agent.core.CandidateOption

/**
 * 自動機與執行層之間的薄轉接層。
 *
 * 選擇器格式必須與 `TwinFastPath.selectorOf` 完全一致，
 * 否則規劃出來的邊在畫面上永遠定位不到：
 * ```
 * id:<viewId 末段> ｜ role:<roleTag>:<文字雜湊> ｜ text:<文字雜湊> ｜ grid:x,y
 * ```
 */
object AutomatonRuntime {

    /** 規劃出來的第一步是否值得直接執行（保守門檻） */
    fun isTrustworthy(
        plan: AutomatonPlanner.Plan,
        minSuccess: Float = 0.60f,
        maxSteps: Int = 1
    ): Boolean = plan.steps in 1..maxSteps && plan.expectedSuccess >= minSuccess

    fun selectorOf(c: CandidateOption): String =
        com.wae.agent.core.action.SelectorKeys.of(c)

    /** 在當前候選中找出規劃邊所指向的元素（找不到就回 null，絕不亂猜） */
    fun locate(selector: String, options: List<CandidateOption>): CandidateOption? {
        if (selector.isEmpty() || options.isEmpty()) return null
        val body = selector.substringAfter(':', "")
        return when {
            selector.startsWith("id:") -> {
                val token = selector.removePrefix("id:")
                options.firstOrNull { it.viewId.isNotEmpty() && it.viewId.substringAfterLast('/') == token }
            }
            selector.startsWith("role:") -> {
                val token = selector.removePrefix("role:")
                val role = token.substringBefore(':')
                val text = token.substringAfter(':', "")
                // 有文字時要求角色與文字都符合（避免同一角色的不同列表項被誤配）
                if (text.isEmpty()) {
                    options.firstOrNull { it.roleTag == role }
                } else {
                    options.firstOrNull {
                        it.roleTag == role && it.coreText.isNotEmpty() &&
                            SelectorKeys.hashOf(it.coreText) == text
                    } ?: options.firstOrNull { it.roleTag == role }
                }
            }
            selector.startsWith("text:") -> {
                val token = selector.removePrefix("text:")
                options.firstOrNull {
                    it.coreText.isNotEmpty() && SelectorKeys.hashOf(it.coreText) == token
                }
            }
            selector.startsWith("grid:") -> {
                val parts = body.split(',')
                val gx = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val gy = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (gx == null || gy == null) null
                else options.filter {
                    kotlin.math.abs(it.centerX() - gx) <= 24 && kotlin.math.abs(it.centerY() - gy) <= 24
                }.minByOrNull {
                    kotlin.math.abs(it.centerX() - gx) + kotlin.math.abs(it.centerY() - gy)
                }
            }
            else -> null
        }
    }
}
