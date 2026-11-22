package com.wae.core.frame

import com.wae.core.model.ControlKind

/**
 * 控制項型別推導（A9）。
 *
 * 為什麼要獨立一層：Gen-4 把「class 名稱含 Switch 就叫 toggle」寫在感知層裡，
 * 那是猜謎；這裡只吃**特徵**，特徵從哪來（a11y／OCR／幾何）由呼叫端決定。
 *
 * 順序有語意：可編輯 > 有範圍 > 可勾選 > 分頁 > 可點 > 未知。
 */
data class KindFeatures(
    val editable: Boolean = false,
    val hasRange: Boolean = false,
    val checkable: Boolean = false,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val classHint: String = "",
    val label: String = ""
)

object KindDeriver {

    fun derive(f: KindFeatures): ControlKind = when {
        f.editable -> ControlKind.FIELD
        f.hasRange -> ControlKind.SLIDER
        f.checkable -> ControlKind.TOGGLE
        isTab(f) -> ControlKind.TAB
        f.clickable -> ControlKind.ROW
        // 可捲動但不可點：容器，當成列處理（目標解析仍需要它）
        f.scrollable -> ControlKind.ROW
        else -> ControlKind.UNKNOWN
    }

    private fun isTab(f: KindFeatures): Boolean =
        f.selected && (f.classHint.contains(TAB_HINT, ignoreCase = true) || f.label.isNotEmpty())

    private const val TAB_HINT = "tab"
}
