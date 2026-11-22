package com.wae.agent.perception

import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min

/**
 * 畫面幾何與結構訊號（純函式）。
 *
 * 從 `WaeVisionAccessibilityService` 抽出的第二塊「感知」邏輯：只做字串與座標計算，
 * 不碰服務狀態、不送手勢、不寫檔——所以可以直接在 JVM 上測。
 */
object FrameSignals {

    /** 依中心點把元素分類到五個區帶；用來讓決策層知道「這個按鈕在畫面的哪裡」。 */
    fun classifySpatialZone(screenW: Int, screenH: Int, bounds: IntArray): String {
        val cx = (bounds[0] + bounds[2]) / 2
        val cy = (bounds[1] + bounds[3]) / 2
        return when {
            cy < screenH * 0.15f && cx < screenW * 0.30f -> "TOP_LEFT"
            cy < screenH * 0.15f && cx > screenW * 0.70f -> "TOP_RIGHT"
            cy < screenH * 0.18f -> "TOP_BAR"
            cy > screenH * 0.85f -> "BOTTOM_BAR"
            else -> "CENTER_LIST"
        }
    }

    /** 把「位置｜父容器｜角色」焊成一段可讀的上下文，讓模型／規則知道自己在看哪裡。 */
    fun weldedContext(
        zone: String,
        parentRole: String,
        roleTag: String,
        coreText: String,
        siblingText: String
    ): String {
        val labelPart = when {
            coreText.isNotEmpty() -> coreText
            siblingText.isNotEmpty() -> "鄰近:$siblingText"
            else -> roleTag
        }
        return "[$zone|$parentRole|$roleTag] $labelPart"
    }

    /**
     * 子樹狀態快照（最多 2 層、最多 8 個子節點）。
     * 用途：判斷「這個容器在點擊前後有沒有變」——比對整棵樹便宜很多。
     */
    fun subtreeState(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (node == null || depth > 2 || sb.length > 512) return
        sb.append(node.text ?: "")
            .append('|').append(node.isChecked)
            .append('|').append(node.isSelected)
            .append('|').append(node.isFocused)
            .append(';')
        val count = min(node.childCount, 8)
        for (i in 0 until count) {
            val ch = try {
                node.getChild(i)
            } catch (_: Throwable) {
                // 忽略：節點可能在走訪途中失效（TOCTOU），讀不到就當沒有
                null
            }
            subtreeState(ch, depth + 1, sb)
        }
    }

    /** 往上最多 2 層父容器，回傳整個子樹的狀態字串（再由呼叫端做雜湊）。 */
    fun twoHopAncestorState(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        var ancestor: AccessibilityNodeInfo = node
        var hops = 0
        while (hops < 2) {
            val parent = ancestor.parent ?: break
            ancestor = parent
            hops++
        }
        val sb = StringBuilder()
        subtreeState(ancestor, 0, sb)
        return sb.toString()
    }
}
