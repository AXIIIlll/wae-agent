package com.wae.core.screen

/**
 * 畫面簡化器：把無障礙樹的原始控制項，簡化成「這一頁可以選什麼」。
 *
 * 為什麼要這一層：
 *  - 決策層原本拿到的是 100+ 個節點，其中大多數不能點（標題、說明、分隔線）。
 *  - 人看畫面時只會看到「可以按的東西」；這一層就是那個視角。
 *  - 選項有穩定 id，可以記錄「我選了哪一個」，而不是記座標（座標會過期）。
 *
 * 三個刻意設計：
 *  1. **只收可互動的元素**（可點／可聚焦／祖先可點）——由 [com.wae.core.model.Control.clickable] 決定。
 *  2. **保留唯讀文字當上下文**（`contextLines`），但不列為選項：標題對理解畫面很重要，卻不該被點。
 *  3. **id 跨幀穩定**：用「角色＋標籤＋群組＋同標籤序號」推導，不含座標。
 */

/** 一個可選項（可以點、可以打字的東西）。 */
data class ScreenOption(
    /** 跨幀穩定的識別碼（不含座標）。 */
    val id: String,
    /** 畫面顯示的文字或無障礙描述。 */
    val label: String,
    /** 角色：BUTTON／TAB／ROW／TOGGLE／EDITABLE…（來自 ControlKind）。 */
    val role: String,
    /** 所屬區塊（最近的唯讀標題），用來區分同名選項。 */
    val group: String,
    /** 來源：A11Y（無障礙樹）／OCR／ICON。 */
    val source: String,
    /** 是否可用（灰掉的按鈕要能被決策層排除）。 */
    val enabled: Boolean,
    /** 開關類的目前狀態；非開關為 null。 */
    val checked: Boolean?,
    /**
     * 這一幀的原始像素座標，給「拿不到節點時的座標退路」用。
     * 注意：只對產生它的那一幀有效；畫面一變就要重新取得。
     */
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** 同一個標籤出現多次時用來區分（1 起算）。 */
    val ordinal: Int
) {
    /** 給日誌／除錯用的一行摘要。 */
    fun describe(): String =
        "[$id] $label（$role${if (group.isNotEmpty()) "／$group" else ""}" +
            (if (checked != null) if (checked) "／已開" else "／已關" else "") +
            (if (enabled) "" else "／不可用") + "）"
}

/** 一頁的簡化結果。 */
data class ScreenModel(
    val packageName: String,
    /** 畫面指紋（跟 Frame.fp 一致），用來判斷「還是不是同一頁」。 */
    val fingerprint: String,
    /** 可以選的項目（依畫面順序）。 */
    val options: List<ScreenOption>,
    /** 唯讀文字（標題、區塊名、說明），只給決策參考，不可點。 */
    val contextLines: List<String>
) {
    fun find(id: String): ScreenOption? = options.firstOrNull { it.id == id }

    /** 給除錯輸出用：一行一個選項。 */
    fun dump(): String = buildString {
        appendLine("screen pkg=$packageName fp=$fingerprint options=${options.size}")
        options.forEach { appendLine("  " + it.describe()) }
        if (contextLines.isNotEmpty()) {
            appendLine("  context=" + contextLines.take(6).joinToString(" | "))
        }
    }
}
