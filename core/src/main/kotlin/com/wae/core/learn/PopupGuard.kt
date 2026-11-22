package com.wae.core.learn

import com.wae.core.model.Control
import com.wae.core.model.Frame

/**
 * 彈窗守衛（B16）——**只偵測，不自動關**。
 *
 * 為什麼不自動關：關錯彈窗（例如把「刪除確認」按掉、或把登入頁按掉）
 * 比不動作更危險。這條守衛的產出只是「這裡可能需要人看一眼」，
 * 由上層決定要反問、要退、還是繼續。
 *
 * 判準刻意保守（寧可漏抓，不要誤抓）：
 *  - 可點元素集中在中央（上下左右各留 15% 邊界）
 *  - 至少 3 個、且都落在中央區域
 *  - 這些元素的總面積不足畫面的一半（彈窗通常不會鋪滿）
 */
object PopupGuard {

    private const val MARGIN_RATIO = 0.15
    private const val MIN_CENTER_CONTROLS = 3
    private const val MAX_COVER_RATIO = 0.5

    fun likelyDialog(frame: Frame): Boolean {
        if (frame.controls.isEmpty()) return false
        val screenW = frame.controls.maxOf { it.bounds.right }
        val screenH = frame.controls.maxOf { it.bounds.bottom }
        if (screenW <= 0 || screenH <= 0) return false

        val left = screenW * MARGIN_RATIO
        val right = screenW * (1 - MARGIN_RATIO)
        val top = screenH * MARGIN_RATIO
        val bottom = screenH * (1 - MARGIN_RATIO)

        val centered = frame.controls.filter { c ->
            c.enabled && c.bounds.width > 0 &&
                c.bounds.left >= left && c.bounds.right <= right &&
                c.bounds.top >= top && c.bounds.bottom <= bottom
        }
        if (centered.size < MIN_CENTER_CONTROLS) return false

        val covered = centered.sumOf { it.bounds.area }.toDouble()
        val screenArea = screenW.toDouble() * screenH.toDouble()
        if (screenArea <= 0.0) return false
        return covered / screenArea < MAX_COVER_RATIO
    }

    /** 給人看的一行（反問卡／收據用）。 */
    fun describe(frame: Frame): String =
        "可能的彈窗：中央有 " + frame.controls.count { it.enabled } + " 個可操作元素"
}
