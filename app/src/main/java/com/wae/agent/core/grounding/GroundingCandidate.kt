package com.wae.agent.core.grounding

/**
 * Grounding 2.0 的四個證據通道（I2 多通道融合）。
 *
 * 每個通道都把自己的觀測包成 [GroundingCandidate]，由 [GroundingFusion] 統一分數；
 * 通道產生器（a11y 樹、OCR、圖示偵測、空間推論）各自負責把來源資料轉成這個格式，
 * 因此融合/幾何/歸屬邏輯**完全不需要 Android API**，可以在 JVM 單元測試裡構造與回歸。
 *
 * 通道的分工（詳見 docs/WAE_Grounding2_Plan.md I2）：
 *  - [A11Y]：無障礙節點，語意最強（clickable/editable/checkable/viewId）
 *  - [OCR]：螢幕文字，補 WebView / Compose / Canvas 這類沒有節點語意的畫面
 *  - [ICON]：圖示辨識，補「無文字按鈕」
 *  - [SPATIAL]：純幾何推論（標籤歸屬、行/列結構），本身不產生新像素觀測
 */
enum class GroundingChannel {
    /** 無障礙節點樹 */
    A11Y,
    /** 螢幕 OCR 文字 */
    OCR,
    /** 圖示辨識（無文字按鈕 / toolbar icon） */
    ICON,
    /** 純空間推論（由幾何關係補出的候選） */
    SPATIAL;

    companion object {
        /**
         * 從 TSV/JSONL 的字串反查通道（大小寫不敏感）。
         *
         * @return null 代表無法辨識；由呼叫端決定要丟棄或走 fallback，
         *         這裡刻意不拋例外，避免壞資料影響主流程。
         */
        fun fromName(raw: String?): GroundingChannel? {
            if (raw == null) return null
            val name = raw.trim()
            if (name.isEmpty()) return null
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }
}

/**
 * 一個跨通道的 grounding 候選元素（I2 融合、I3 標籤歸屬的統一輸入格式）。
 *
 * 座標系：`bounds` 固定為 `[left, top, right, bottom]` 四個整數（螢幕像素），
 * 與無障礙 API 的 `getBoundsInScreen()` 欄位順序一致（left/top/right/bottom），
 * 如此一來 A11Y／OCR／ICON 三個通道不需要各自轉座標就能互相比較。
 *
 * 無效/未知幾何用空陣列（[EMPTY_GROUNDING_BOUNDS]）表示：所有幾何查詢一律回 0，
 * **不拋例外**，讓「OCR 只給文字沒有框」這種退化輸入也能安全流過整個管線。
 *
 * 為什麼 `id` 是 String：不同通道的 id 來源不同（a11y 的 markId、OCR 的行索引、
 * icon 的偵測框 id），字串可以穩定跨通道去重；同一元素的多通道證據由呼叫端
 * 用相同 id 合併（合併後可把多個 GroundingCandidate 一起交給 [GroundingFusion]）。
 *
 * 這個 data class 覆寫了 equals/hashCode/toString，因為 `bounds` 是 IntArray：
 * 預設的陣列比較是「參考相等」，會讓去重、HashMap、assertEquals 全部失效。
 */
data class GroundingCandidate(
    /** 跨通道穩定識別碼（同一 UI 元素的多通道候選應共用） */
    val id: String,
    /** 證據來源通道 */
    val channel: GroundingChannel,
    /** 可見文字（a11y text/contentDescription、OCR 文字；純圖示可為空） */
    val text: String = "",
    /** 類別名（a11y className；其他通道可留空） */
    val className: String = "",
    /** a11y viewId / resource-id（其他通道可留空） */
    val viewId: String = "",
    /** 螢幕座標 `[left, top, right, bottom]`（px） */
    val bounds: IntArray = EMPTY_GROUNDING_BOUNDS,
    /** 可點擊（a11y clickable） */
    val clickable: Boolean = false,
    /** 可輸入（a11y editable / EditText） */
    val editable: Boolean = false,
    /** 可勾選/切換（a11y checkable，例如開關、checkbox） */
    val checkable: Boolean = false,
    /** 目前勾選狀態（開關是否已開） */
    val checked: Boolean = false,
    /** 元件目前是否可用（disabled 不該被選為動作目標） */
    val enabled: Boolean = true,
    /** 通道自帶的信心/相似度（0..1，由通道產生器負責正規化） */
    val score: Float = 0f
) {

    /** 安全取 bounds 元素：陣列太短或為空時回 0 */
    private fun at(index: Int): Int =
        if (index >= 0 && index < bounds.size) bounds[index] else 0

    val left: Int get() = at(0)
    val top: Int get() = at(1)
    val right: Int get() = at(2)
    val bottom: Int get() = at(3)

    /** 寬度（px）；無效幾何回 0 */
    val width: Int get() = (right - left).coerceAtLeast(0)

    /** 高度（px）；無效幾何回 0 */
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    /** 面積（px²，用 Long 避免大畫面溢位） */
    val areaPx: Long get() = width.toLong() * height.toLong()

    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    /** 幾何是否有效（四個值且 right>left、bottom>top；零面積視為無效） */
    val hasBounds: Boolean
        get() = bounds.size == 4 && right > left && bottom > top

    /** 是否帶有可見文字 */
    val hasText: Boolean get() = text.isNotBlank()

    /** 是否「可以對它做動作」（enabled 且至少一種動作能力） */
    val isActionable: Boolean
        get() = enabled && (clickable || editable || checkable)

    /** 人類可讀的 bounds 字串（log/可解釋性用，不參與 equals） */
    val boundsString: String get() = "[$left,$top,$right,$bottom]"

    /** 換一個分數（回傳新物件，維持 data class 不可變性） */
    fun withScore(newScore: Float): GroundingCandidate = copy(score = newScore)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroundingCandidate) return false
        return id == other.id &&
            channel == other.channel &&
            text == other.text &&
            className == other.className &&
            viewId == other.viewId &&
            bounds.contentEquals(other.bounds) &&
            clickable == other.clickable &&
            editable == other.editable &&
            checkable == other.checkable &&
            checked == other.checked &&
            enabled == other.enabled &&
            score == other.score
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + channel.hashCode()
        h = 31 * h + text.hashCode()
        h = 31 * h + className.hashCode()
        h = 31 * h + viewId.hashCode()
        h = 31 * h + bounds.contentHashCode()
        h = 31 * h + clickable.hashCode()
        h = 31 * h + editable.hashCode()
        h = 31 * h + checkable.hashCode()
        h = 31 * h + checked.hashCode()
        h = 31 * h + enabled.hashCode()
        h = 31 * h + score.hashCode()
        return h
    }

    override fun toString(): String =
        "GroundingCandidate(id=$id, channel=$channel, text='${text.take(24)}', " +
            "bounds=$boundsString, score=$score, " +
            "clickable=$clickable, editable=$editable, checkable=$checkable, " +
            "checked=$checked, enabled=$enabled)"
}

/** 方便的 bounds 建構子：`GroundingCandidate.rect(l, t, r, b)`（與 a11y 欄位順序一致） */
fun groundingRect(left: Int, top: Int, right: Int, bottom: Int): IntArray =
    intArrayOf(left, top, right, bottom)

/** 共享的空 bounds（長度 0，不可變內容，安全共用） */
val EMPTY_GROUNDING_BOUNDS: IntArray = IntArray(0)
