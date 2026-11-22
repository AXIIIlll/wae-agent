package com.wae.agent.perception

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 開關事實收集（從無障礙服務抽出的第四塊）。
 *
 * 目的：把畫面上「可勾選的元素」找出來，附上它的標籤與狀態，
 * 讓 GoalVerifier 能回答「這個開關現在是開還是關」。
 *
 * 純函式（輸入 root 節點），不依賴服務狀態——可獨立替換與測試。
 */
object ToggleFacts {

fun collect(root: AccessibilityNodeInfo?, limit: Int = 40): List<com.wae.agent.core.goal.ToggleFact> {
        if (root == null || limit <= 0) return emptyList()
        val out = ArrayList<com.wae.agent.core.goal.ToggleFact>(16)
        fun walk(n: AccessibilityNodeInfo?, depth: Int, labelHint: String = "") {
            if (n == null || depth > 24 || out.size >= limit) return
            runCatching {
                val ownLabel = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                val effectiveLabel = if (ownLabel.isNotEmpty()) ownLabel else labelHint
                if (n.isCheckable) {
                    var label = effectiveLabel
                    if (label.isEmpty()) {
                        // 往上一層找同一列的文字（三星設定：Switch 與標題是兄弟節點）
                        runCatching {
                            val parent = n.parent
                            if (parent != null) {
                                for (i in 0 until minOf(parent.childCount, 8)) {
                                    val sib = parent.getChild(i) ?: continue
                                    val t = (sib.text?.toString()
                                        ?: sib.contentDescription?.toString() ?: "").trim()
                                    if (t.isNotEmpty() && t.length <= 16) {
                                        label = t
                                        break
                                    }
                                }
                            }
                        }
                    }
                    val vid = n.viewIdResourceName
                    val sel = if (vid.isNullOrEmpty()) "text:$label"
                    else "id:" + vid.substringAfterLast('/')
                    out.add(
                        com.wae.agent.core.goal.ToggleFact(
                            selector = sel,
                            text = label,
                            checked = n.isChecked
                        )
                    )
                }
                for (i in 0 until minOf(n.childCount, 12)) {
                    walk(n.getChild(i), depth + 1, effectiveLabel)
                }
            }
        }
        walk(root, 0)
        return out
    }
}
