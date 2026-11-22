package com.wae.agent.gen5

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 畫面元素 ↔ 無障礙節點 的對照表（Gen-5 S2）。
 *
 * 為什麼要這張表：core 的 `Control.key` 是字串（純 JVM 不能碰 `AccessibilityNodeInfo`），
 * 執行動作時必須把 key 換回真的節點。表只保存**最近一幀**的節點，換幀即清空，
 * 避免拿舊節點去點（那正是 Gen-4 的 TOCTOU 問題）。
 */
object NodeRegistry {

    private const val MAX_NODES = 400

    private val nodes = LinkedHashMap<String, AccessibilityNodeInfo>()

    fun clear() {
        nodes.clear()
    }

    fun put(key: String, node: AccessibilityNodeInfo) {
        if (nodes.size >= MAX_NODES) return
        nodes[key] = node
    }

    fun get(key: String): AccessibilityNodeInfo? = nodes[key]

    /** 需要「找第一個符合條件的節點」時用（例如找可輸入的欄位）。 */
    fun firstOrNull(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        nodes.values.firstOrNull(predicate)

    fun size(): Int = nodes.size
}
