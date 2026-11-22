package com.wae.core.routing

import com.wae.core.model.Constraints

/**
 * 路由選擇器（B12）：把「哪個 App 做這件事」抽成介面。
 *
 * 為什麼是介面：真機上能力資料來自本機歷史（`CapabilityIndex`），
 * 離線測試可以注入假的；兩者行為一致才驗得起來。
 */
fun interface RouteSelector {
    fun select(goal: Goal, currentPkg: String, constraints: Constraints): Route?
}

/**
 * 預設實作：先排除「現在就在這個 App」的重複啟動，再交給 `Router` 排序。
 */
class CapabilityRouteSelector(
    private val index: CapabilityIndex,
    private val router: Router
) : RouteSelector {

    override fun select(goal: Goal, currentPkg: String, constraints: Constraints): Route? {
        val ranked = router.rank(goal, index, constraints)
        if (ranked.isEmpty()) return null
        // 已經在目標 App 裡就不要再多開一次（少一步、少一次跳轉風險）
        return ranked.firstOrNull { it.appId == currentPkg } ?: ranked.first()
    }
}
