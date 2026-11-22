package com.wae.agent.core.action

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.twin.TwinStep

/**
 * 選擇器鍵：**整個系統唯一的實作**。
 *
 * 為什麼要集中：先前有 5 個地方各自組字串，而且分成兩種格式：
 *  - `role:<角色>`（Twin 快路徑、自動機）
 *  - `role:<角色>:<文字>`（決策打分、遙測、負向邊）
 *
 * 後果是「負邊記錄時用 A 格式、規劃器查詢時用 B 格式」→ 永遠對不上，
 * 負邊形同失效（這是 M3 整合檢查抓到的缺陷）。
 *
 * 統一採用**最specific**的格式：
 * ```
 * id:<viewId 末段>              最強（跨版本仍可能穩定）
 * role:<角色>:<文字前 16 字>    次之（同一角色下可區分不同項目）
 * text:<文字前 16 字>           再次
 * grid:<x>,<y>                  最後手段
 * ```
 */
object SelectorKeys {

    fun of(
        viewId: String,
        roleTag: String,
        text: String,
        centerX: Int,
        centerY: Int
    ): String = when {
        viewId.isNotEmpty() -> "id:" + viewId.substringAfterLast('/')
        roleTag.isNotEmpty() && text.isNotEmpty() -> "role:" + roleTag + ":" + text.take(16)
        text.isNotEmpty() -> "text:" + text.take(16)
        else -> "grid:" + centerX + "," + centerY
    }

    fun of(c: CandidateOption): String =
        of(c.viewId, c.roleTag, c.coreText, c.centerX(), c.centerY())

    fun of(step: TwinStep): String =
        of(step.viewId, step.roleTag, step.target, step.gridX, step.gridY)

    /**
     * 解析鍵的「主體」：`role:x:y` → `role` 與 `x:y` 的組合。
     * 供需要部分比對（例如只比角色）的呼叫端使用。
     */
    fun rolePart(key: String): String = key.removePrefix("role:").substringBefore(':')

    fun textPart(key: String): String {
        val body = key.substringAfter(':', "")
        return if (key.startsWith("role:")) body.substringAfter(':', "") else body
    }
}
