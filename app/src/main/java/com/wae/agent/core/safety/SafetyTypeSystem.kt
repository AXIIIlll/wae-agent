package com.wae.agent.core.safety

import com.wae.agent.core.automaton.AutomatonEdge
import com.wae.agent.core.automaton.AutomatonPlanner

/**
 * S8：安全型別系統（編譯期拒絕，不靠執行期閘門）。
 *
 * 現況的安全是「執行期閘門」：動作要送出去之前才檢查。
 * 極限做法是在**規劃期**就把不安全的邊排除，讓不安全動作根本不會進到候選。
 *
 * 三條靜態規則（皆為硬拒絕，沒有例外）：
 *  1. 敏感套件（權限控制器/安裝器）上的任何動作 → Reject
 *  2. 目標文字含禁忌詞（付款/刪除/權限/密碼/轉帳…）→ Reject
 *  3. 不可逆動作（SUBMIT/CLEAR_TEXT）作用在敏感/破壞性目標 → Reject
 */
object SafetyTypeSystem {

    sealed class Verdict {
        object Allow : Verdict()
        data class Reject(val rule: String, val detail: String) : Verdict()
    }

    /** 禁忌詞（與 S5 探索護欄共用同一份資料） */
    private fun forbidden() = com.wae.agent.core.explore.ExplorationGuard.effective().forbiddenKeywords

    fun isSensitivePackage(pkg: String): Boolean =
        com.wae.agent.core.safety.SystemSafetyPackages.isBlocked(pkg)

    /** 單一動作的靜態檢查 */
    fun checkAction(pkg: String, op: String, selector: String, text: String = ""): Verdict {
        if (pkg.isEmpty()) return Verdict.Reject("NO_PACKAGE", "缺少目標套件")
        if (isSensitivePackage(pkg)) {
            return Verdict.Reject("SENSITIVE_PACKAGE", pkg)
        }
        val hay = (selector + " " + text).lowercase()
        val hit = forbidden().firstOrNull { hay.contains(it.lowercase()) }
        if (hit != null && isDestructive(op)) {
            return Verdict.Reject("FORBIDDEN_TARGET", "$op on '$hit'")
        }
        return Verdict.Allow
    }

    fun isDestructive(op: String): Boolean = when (op.uppercase()) {
        "SUBMIT", "CLEAR_TEXT", "LONG_PRESS", "TAP" -> true
        else -> false
    }

    /** 整條計畫的靜態檢查：任一步不安全就拒絕整條 */
    fun checkPlan(pkg: String, plan: AutomatonPlanner.Plan, textOf: (AutomatonEdge) -> String = { "" }): Verdict {
        for (e in plan.edges) {
            val v = checkAction(pkg, e.op, e.selector, textOf(e))
            if (v is Verdict.Reject) return v
        }
        return Verdict.Allow
    }

    /** 過濾出「靜態安全」的邊（供規劃器當候選集） */
    fun safeEdges(pkg: String, edges: List<AutomatonEdge>): List<AutomatonEdge> =
        edges.filter { checkAction(pkg, it.op, it.selector) is Verdict.Allow }
}
