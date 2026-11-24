package com.wae.core.model

/**
 * 螢幕矩形（像素）。**只用整數**，避免浮點在不同裝置上造成指紋不一致。
 */
data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    init {
        require(right >= left) { "RectI: right($right) < left($left)" }
        require(bottom >= top) { "RectI: bottom($bottom) < top($top)" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
    fun centerX(): Int = left + width / 2
    fun centerY(): Int = top + height / 2

    companion object {
        val EMPTY: RectI = RectI(0, 0, 0, 0)
    }
}

/** 這筆 UI 資訊是從哪個通道來的（Gen-5 §3 L0）。 */
enum class Channel { A11Y, OCR, ICON, GEOMETRY }

/**
 * 控制項的**語意型別**。這是 Gen-5 的核心抽象：
 * 決策不再問「這個按鈕像什麼」，而是問「這是哪一種控制項」。
 */
enum class ControlKind { TOGGLE, ROW, FIELD, SLIDER, TAB, BUTTON, UNKNOWN }

/** 控制項的**狀態**（僅放能從無障礙/OCR 客觀取得的欄位）。 */
data class ControlState(
    val on: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val text: String? = null
)

/**
 * 數值範圍（來自 `AccessibilityNodeInfo.RangeInfo`：min / max / current）。
 *
 * 為什麼要**正規化**：滑桿的原始範圍每個 App 都不同（0..255、0..100、1..10）。
 * 述詞用「比例」表達「調到 40%」，才能在跨 App 時共用同一個目標形狀。
 */
data class ControlRange(val min: Float, val max: Float, val current: Float) {

    init {
        require(max > min) { "ControlRange: max($max) 必須大於 min($min)" }
        require(current >= min && current <= max) { "ControlRange: current($current) 不在 [$min, $max]" }
    }

    /** 目前值在 0..1 的比例。 */
    val fraction: Float get() = (current - min) / (max - min)
}

/** 這一幀的感知成本與來源（用來算「第二次進同畫面要便宜多少」）。 */
data class Evidence(val channels: Set<Channel>, val costMs: Long) {

    init {
        require(costMs >= 0) { "Evidence.costMs 不可為負" }
    }
}

/** 畫面元素（已正規化；key 必須跨幀穩定）。 */
data class Control(
    val key: String,
    val kind: ControlKind,
    /**
     * 可見文字（只在記憶體中；落盤一律只用 `textHash`）。
     *
     * 為什麼要留原文：目標解析階梯需要「模糊比對」，只有 hash 是比不了的。
     * 這不違反隱私承諾——承諾是「不把內容寫出去／寫到檔案」，不是「不准看」。
     */
    val label: String = "",
    val textHash: String = "",
    val bounds: RectI,
    /**
     * 元件資源名（`viewIdResourceName`，例如 `com.android.chrome:id/menu_button`）。
     * 用途：同名元件（例如畫面上兩個「更多選項」）只能靠它區分。
     */
    val viewId: String = "",
    /**
     * 視窗層級：0＝彈出層（選單／對話框，最上層）、1＝主視窗。
     * 用途：決策時優先看使用者眼前那一層。
     */
    val layer: Int = 1,
    /**
     * 是否位在網頁內容裡（祖先含 WebView）。
     * 用途：Chrome 頁面內嵌的三點與工具列三點標籤相同，靠這個把內嵌那顆排除。
     */
    val inWeb: Boolean = false,
    val enabled: Boolean = true,
    /**
     * 無障礙樹判定「可以點」：本身可點，或**祖先可點**（清單列的常見寫法：TextView 有文字、Row 才是可點的）。
     * 用途：找點擊目標時只從可點的候選裡挑，避免點到純說明文字（實測第 19 題）。
     */
    val clickable: Boolean = false,
    /** 這個元素本身可捲動（`AccessibilityNodeInfo.isScrollable`）。找東西找不到時要靠它。 */
    val scrollable: Boolean = false,
    /** 原始 class 名稱（例：`android.widget.Switch`）。只當型別推導的特徵，不做語意。 */
    val classHint: String = "",
    val state: ControlState = ControlState(),
    val source: Channel = Channel.A11Y,
    /** 數值範圍（只有滑桿／進度類才有）；述詞用它的比例，不用原始值。 */
    val range: ControlRange? = null
) {

    init {
        require(key.isNotEmpty()) { "Control.key 不可為空" }
    }

    /** 目前值在 0..1 的比例（讀不到範圍時為 null）。 */
    val valueFraction: Float? get() = range?.fraction
}

/**
 * 一幀畫面。`fp` 是**指紋**（同一畫面必須得到同一個值），
 * 它是 Frame Ledger 的鍵，也是反例庫的 scope。
 */
data class Frame(
    val fp: String,
    val pkg: String,
    val appVersion: Long,
    val controls: List<Control>,
    val evidence: Evidence = Evidence(emptySet(), 0L),
    val atMs: Long = 0L
) {

    fun control(key: String): Control? = controls.firstOrNull { it.key == key }

    fun controlsOf(kind: ControlKind): List<Control> = controls.filter { it.kind == kind }

    companion object {
        /** 空畫面（測試與失敗路徑用）。 */
        val EMPTY: Frame = Frame(fp = "fp:empty", pkg = "", appVersion = 0L, controls = emptyList())
    }
}
