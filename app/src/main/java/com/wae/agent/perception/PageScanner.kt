package com.wae.agent.perception

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 頁面文字掃描（從無障礙服務抽出的第三塊）。
 *
 * 只做一件事：把當前畫面上「看得到的文字」收集起來，給 GoalVerifier 判斷用。
 * 純函式（輸入 root 節點），不依賴服務狀態——所以可以獨立測試、獨立替換。
 */
object PageScanner {

    private const val MAX_DEPTH = 20
    private const val MAX_CHILDREN = 12
    private const val MAX_TEXT_LENGTH = 32

    fun texts(root: AccessibilityNodeInfo?, limit: Int): List<String> {
        if (root == null || limit <= 0) return emptyList()
        val out = ArrayList<String>(64)

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || out.size >= limit) return
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) out.add(text.take(MAX_TEXT_LENGTH))
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.isNotEmpty() && desc != text) out.add(desc.take(MAX_TEXT_LENGTH))
            for (i in 0 until minOf(node.childCount, MAX_CHILDREN)) {
                walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1)
            }
        }

        walk(root, 0)
        return out
    }
}
