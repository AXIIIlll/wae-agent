package com.wae.agent.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min

/**
 * 收集「帶語境的節點」（服務瘦身第七塊）。
 *
 * 從 4,000 多行的無障礙服務抽出：它只依賴節點樹與輸出清單，
 * 不碰任何服務狀態；遞迴深度、節點上限、髒區剪枝全都在這裡。
 *
 * 邊界：只讀、不改畫面；輸出格式與原本一致（RawCollectedNode）。
 */
object NodeContextCollector {

    fun collect(
        node: AccessibilityNodeInfo?,
        depth: Int,
        parentRole: String,
        siblingHint: String,
        isBehindModal: Boolean,
        outList: MutableList<RawCollectedNode>,
        scopeRects: List<com.wae.agent.core.perception.IncrementalPerception.Box>? = null
    ) {
        if (node == null || depth > 42 || outList.size >= 480) return
        if (!node.isVisibleToUser) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val cls = node.className?.toString()?.trim().orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName?.trim().orEmpty()

        val thisContainerRole = when {
            cls.contains("RecyclerView", ignoreCase = true) || cls.contains("ListView", ignoreCase = true) ->
                "LIST_ITEM"
            cls.contains("Toolbar", ignoreCase = true) || cls.contains("ActionBar", ignoreCase = true) ->
                "TOOLBAR"
            cls.contains("BottomNavigation", ignoreCase = true) || cls.contains("TabLayout", ignoreCase = true) ->
                "BOTTOM_NAV"
            cls.contains("CardView", ignoreCase = true) -> "CARD"
            else -> parentRole
        }

        // 收集兄弟節點或子節點的鄰近文字，供無文字圖標焊接語境 (替代 SHADOW 50M)
        var childTextSummary = ""
        val childCount = node.childCount
        for (i in 0 until min(childCount, 6)) {
            val ch = try {
                node.getChild(i)
            } catch (_: Throwable) {
                // 忽略：節點可能在走訪途中失效（TOCTOU）
                null
            } ?: continue
            val ct = ch.text?.toString()?.trim().orEmpty()
                .ifEmpty { ch.contentDescription?.toString()?.trim().orEmpty() }
            if (ct.isNotEmpty() && childTextSummary.length < 24) {
                childTextSummary = if (childTextSummary.isEmpty()) ct else "$childTextSummary $ct"
            }
        }

        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable

        // ── 元件能力圖譜：把「類別先驗」與「運行期語意訊號」融合成能力集合 ──
        val signals = com.wae.agent.core.component.RuntimeSignalsBuilder.build(node, cls)
        val affordances = com.wae.agent.core.component.AffordanceFusion.resolve(signals)
        val adjustable = affordances.adjustable

        if (rect.width() > 0 && rect.height() > 0 &&
            (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable || scrollable || adjustable)
        ) {
            outList.add(
                RawCollectedNode(
                    nodeRef = node,
                    depth = depth,
                    bounds = rect,
                    text = text,
                    contentDesc = desc,
                    viewId = viewId,
                    className = cls,
                    parentRole = thisContainerRole,
                    siblingText = childTextSummary.ifEmpty { siblingHint },
                    isEnabled = node.isEnabled,
                    isClickable = clickable,
                    isEditable = editable,
                    isScrollable = scrollable,
                    isPassword = node.isPassword,
                    isBehindModal = isBehindModal,
                    widgetFamily = affordances.familyId,
                    capabilities = affordances.capabilities,
                    hasRange = signals.hasRange,
                    rangeMin = signals.rangeMin,
                    rangeMax = signals.rangeMax,
                    rangeCurrent = signals.rangeCurrent,
                    rangeIsPercent = signals.rangeIsPercent,
                    isCheckable = signals.isCheckable,
                    isChecked = signals.isChecked
                )
            )
        }

        // S25：與髒區完全無交集的子樹直接跳過（祖先因為包含髒區仍會被走訪）
        if (scopeRects != null) {
            val box = com.wae.agent.core.perception.IncrementalPerception.Box(
                rect.left, rect.top, rect.right, rect.bottom
            )
            if (!com.wae.agent.core.perception.IncrementalPerception.intersectsAny(box, scopeRects)) {
                return
            }
        }

        for (i in 0 until childCount) {
            if (outList.size >= 480) break
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                // 忽略：節點可能在走訪途中失效（TOCTOU）
                null
            } ?: continue
            collect(
                node = child,
                depth = depth + 1,
                parentRole = thisContainerRole,
                siblingHint = childTextSummary.ifEmpty { text },
                isBehindModal = isBehindModal,
                outList = outList,
                scopeRects = scopeRects
            )
        }
    }
}

data class RawCollectedNode(
        val nodeRef: AccessibilityNodeInfo,
        val depth: Int,
        val bounds: Rect,
        val text: String,
        val contentDesc: String,
        val viewId: String,
        val className: String,
        val parentRole: String,
        val siblingText: String,
        val isEnabled: Boolean,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isPassword: Boolean,
        val isBehindModal: Boolean,
        /** 元件能力圖譜：家族 id（未知為空字串） */
        val widgetFamily: String = "",
        /** 元件能力圖譜：能力集合 */
        val capabilities: Set<com.wae.agent.core.component.Capability> = emptySet(),
        val hasRange: Boolean = false,
        val rangeMin: Float = 0f,
        val rangeMax: Float = 0f,
        val rangeCurrent: Float = 0f,
        val rangeIsPercent: Boolean = false,
        val isCheckable: Boolean = false,
        val isChecked: Boolean = false
)
