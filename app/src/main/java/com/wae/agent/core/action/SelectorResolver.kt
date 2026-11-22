package com.wae.agent.core.action

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.twin.TwinStep
import kotlin.math.abs

/**
 * 選擇器自癒（P3）——把「一個字串」升級成「帶降級鏈的定位器」。
 *
 * 降級鏈（命中即停）：
 *  1. viewId 完全一致
 *  2. viewId 尾段一致（去掉包名前綴，跨版本常見）
 *  3. 角色 + 文字一致
 *  4. 文字互相包含（輕微文案改動）
 *  5. 同角色 + 位置最近（記錄的座標附近 200px 內）
 *  6. 最後手段：完全不匹配 → null（交給呼叫端回退完整流程）
 *
 * 全程不修改程式碼，只更新 Twin 的選擇器資料；
 * 每次自癒都回報層級，供 P6 遥測統計「哪些 App 常需要自癒」。
 */
object SelectorResolver {

    const val MAX_POSITION_DISTANCE_PX = 220

    data class Resolved(
        val candidate: CandidateOption,
        /** 0 = 完全一致，數字越大代表降級越多 */
        val level: Int,
        val levelName: String
    )

    fun resolve(step: TwinStep, options: List<CandidateOption>): Resolved? {
        if (options.isEmpty()) return null
        val recordedViewId = step.viewId
        val recordedText = step.target
        val recordedRole = step.roleTag

        // 1. viewId 完全一致
        if (recordedViewId.isNotEmpty()) {
            options.firstOrNull { it.viewId == recordedViewId }?.let {
                return Resolved(it, 0, "viewId")
            }
        }

        // 2. viewId 尾段一致（跨版本前綴改變）
        if (recordedViewId.isNotEmpty()) {
            val tail = recordedViewId.substringAfterLast('/')
            if (tail.isNotEmpty()) {
                options.firstOrNull { c ->
                    c.viewId.isNotEmpty() && c.viewId.substringAfterLast('/') == tail
                }?.let { return Resolved(it, 1, "viewIdTail") }
            }
        }

        // 3. 角色 + 文字一致
        if (recordedRole.isNotEmpty() && recordedText.isNotEmpty()) {
            options.firstOrNull { it.roleTag == recordedRole && it.coreText == recordedText }?.let {
                return Resolved(it, 2, "role+text")
            }
        }

        // 4. 文字互相包含（僅對長度 ≥2 的文字，避免亂配）
        if (recordedText.length >= 2) {
            options.firstOrNull { c ->
                c.coreText.length >= 2 &&
                    (c.coreText.contains(recordedText) || recordedText.contains(c.coreText))
            }?.let { return Resolved(it, 3, "textContains") }
        }

        // 5. 同角色 + 位置最近
        if (recordedRole.isNotEmpty() && step.gridX >= 0 && step.gridY >= 0) {
            val nearest = options
                .filter { it.roleTag == recordedRole }
                .minByOrNull { dist(it.centerX(), it.centerY(), step.gridX, step.gridY) }
            if (nearest != null &&
                dist(nearest.centerX(), nearest.centerY(), step.gridX, step.gridY) <= MAX_POSITION_DISTANCE_PX
            ) {
                return Resolved(nearest, 4, "role+nearest")
            }
        }

        // 6. 純位置最近（不限角色，但要求距離很近）
        if (step.gridX >= 0 && step.gridY >= 0) {
            val nearest = options
                .filter { it.isClickable || it.isEditable }
                .minByOrNull { dist(it.centerX(), it.centerY(), step.gridX, step.gridY) }
            if (nearest != null &&
                dist(nearest.centerX(), nearest.centerY(), step.gridX, step.gridY) <= MAX_POSITION_DISTANCE_PX / 2
            ) {
                return Resolved(nearest, 5, "nearest")
            }
        }
        return null
    }

    private fun dist(x1: Int, y1: Int, x2: Int, y2: Int): Int =
        abs(x1 - x2) + abs(y1 - y2)
}
