package com.wae.agent.core.component

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 從節點讀出「執行期訊號」（滑桿範圍、可展開/可選、清單維度、輸入型別…）。
 *
 * 從 4,000 多行的無障礙服務抽出（服務瘦身第六塊）：它只依賴一個節點與 className，
 * 不碰任何服務狀態，所以可以獨立替換與測試。
 *
 * 注意：每個區塊都各自 try/catch——節點隨時可能失效（TOCTOU），
 * 讀不到就當作「沒有這個訊號」，不要讓一個壞節點拖垮整個感知。
 */
object RuntimeSignalsBuilder {

    fun build(node: AccessibilityNodeInfo, className: String): RuntimeSignals {
        var hasRange = false
        var rMin = 0f
        var rMax = 0f
        var rCur = 0f
        var rIsPercent = false
        try {
            val ri = node.rangeInfo
            if (ri != null) {
                hasRange = true
                rMin = ri.min
                rMax = ri.max
                rCur = ri.current
                rIsPercent = ri.type == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT
            }
        } catch (_: Throwable) {
            // 忽略：節點隨時可能失效（TOCTOU），讀不到就當沒有這個訊號
        }

        var hasSetProgress = false
        var hasExpand = false
        var hasSelect = false
        try {
            for (action in node.actionList) {
                when (action.id) {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> hasSetProgress = true
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id -> hasExpand = true
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SELECT.id -> hasSelect = true
                }
            }
        } catch (_: Throwable) {
            // 忽略：節點隨時可能失效（TOCTOU），讀不到就當沒有這個訊號
        }

        var rows = 0
        var cols = 0
        try {
            val ci = node.collectionInfo
            if (ci != null) {
                rows = ci.rowCount
                cols = ci.columnCount
            }
        } catch (_: Throwable) {
            // 忽略：節點隨時可能失效（TOCTOU），讀不到就當沒有這個訊號
        }

        var inputTypeText = false
        try {
            val it0 = node.inputType
            if (it0 != 0 && (it0 and android.text.InputType.TYPE_MASK_CLASS) ==
                android.text.InputType.TYPE_CLASS_TEXT
            ) {
                inputTypeText = true
            }
        } catch (_: Throwable) {
            // 忽略：節點隨時可能失效（TOCTOU），讀不到就當沒有這個訊號
        }

        return com.wae.agent.core.component.RuntimeSignals(
            className = className,
            isClickable = node.isClickable,
            isLongClickable = node.isLongClickable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            inputTypeText = inputTypeText,
            hasRange = hasRange,
            rangeMin = rMin,
            rangeMax = rMax,
            rangeCurrent = rCur,
            rangeIsPercent = rIsPercent,
            hasSetProgressAction = hasSetProgress,
            hasExpandAction = hasExpand,
            hasSelectAction = hasSelect,
            collectionRows = rows,
            collectionColumns = cols,
            collectionScrollable = false
        )
    }
}

