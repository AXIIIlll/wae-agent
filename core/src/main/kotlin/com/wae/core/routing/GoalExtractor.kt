package com.wae.core.routing

import com.wae.core.model.Effect

/**
 * 效果 → 結果（B12 的入口）。
 *
 * 路由要在「結果」層面選 App，所以先把效果翻成 `Goal`。
 * 只有能被多個 App 完成的效果才需要路由（傳訊息、搜尋、開啟項目）。
 */
object GoalExtractor {

    private const val PREFIX_CONTACT = "contact:"
    private const val PREFIX_QUERY = "query:"
    private const val PREFIX_TOGGLE = "toggle:"
    private const val PREFIX_APP = "app:"

    fun of(effects: List<Effect>): Goal? {
        val first = effects.firstOrNull() ?: return null
        val target = first.target
        return when {
            target.startsWith(PREFIX_CONTACT) -> Goal(
                kind = GoalKind.SEND_TEXT,
                subject = first.value,
                recipient = target.removePrefix(PREFIX_CONTACT)
            )
            target.startsWith(PREFIX_QUERY) -> Goal(
                kind = GoalKind.SEARCH,
                subject = target.removePrefix(PREFIX_QUERY)
            )
            target.startsWith(PREFIX_TOGGLE) -> Goal(
                kind = GoalKind.TOGGLE,
                subject = target.removePrefix(PREFIX_TOGGLE),
                pinnedApp = first.value.takeIf { it.startsWith(PKG_PREFIX) }?.removePrefix(PKG_PREFIX)
            )
            target.startsWith(PREFIX_APP) -> Goal(
                kind = GoalKind.OPEN_ITEM,
                subject = target.removePrefix(PREFIX_APP)
            )
            else -> null
        }
    }

    /** 使用者若明講「用 X」，效果值可以帶 `pkg:com.x` 把路由釘死。 */
    const val PKG_PREFIX = "pkg:"
}
