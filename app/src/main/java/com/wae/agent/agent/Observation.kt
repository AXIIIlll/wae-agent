package com.wae.agent.agent

/** 畫面元素（由無障礙/OCR/圖示偵測產生；索引是決策層使用的唯一識別）。 */
data class ObservedElement(
    val index: Int,
    val text: String,
    val type: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val visible: Boolean = true,
    val checked: Boolean = false,
    val checkable: Boolean = false,
    /**
     * 螢幕座標（-1 代表沒有）。無障礙元素靠 node 點擊；**OCR 元素沒有 node**，
     * 只能靠這個座標點，這是「沒有無障礙樹的 App」（Canvas/WebView/Flutter）唯一的操作方式。
     */
    val left: Int = -1,
    val top: Int = -1,
    val right: Int = -1,
    val bottom: Int = -1
) {
    /** 有有效座標才回 true（OCR 行或 a11y 補上的 bounds）。 */
    fun hasBounds(): Boolean = left >= 0 && top >= 0 && right > left && bottom > top
}

/** 畫面摘要（餵給決策層的觀察結果）。 */
data class Observation(
    val appPackage: String = "",
    val title: String = "",
    val elements: List<ObservedElement> = emptyList(),
    val ocrTexts: List<String> = emptyList(),
    val scrollTop: Float = 0f,
    val scrollBottom: Float = 1f
)

object ObservationFormatter {
    /**
     * 將觀察壓成決策層/提示用的文字（≤ maxChars）。
     * 格式：`APP=... SCREEN=...` + `[索引] 文字 (型別/可點/可輸入)`。
     */
    fun toText(obs: Observation, maxElements: Int = 60, maxChars: Int = 6000): String {
        val sb = StringBuilder()
        sb.append("APP=").append(obs.appPackage)
        sb.append(" SCREEN=").append(obs.title)
        sb.append('\n')
        obs.elements.asSequence()
            .filter { it.visible }
            .take(maxElements)
            .forEach { e ->
                sb.append('[').append(e.index).append("] ").append(e.text)
                val flags = buildList {
                    if (e.editable) add("可輸入")
                    if (e.clickable) add("可點")
                    if (e.type.isNotBlank()) add(e.type)
                }
                if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString("/")).append(')')
                sb.append('\n')
                if (sb.length > maxChars) return@forEach
            }
        if (obs.ocrTexts.isNotEmpty()) {
            sb.append("OCR: ").append(obs.ocrTexts.take(10).joinToString(" | "))
        }
        return sb.toString().take(maxChars)
    }

    fun screenSignature(obs: Observation): String =
        obs.appPackage + "|" + obs.title + "|" + obs.elements.joinToString(",") { it.text }.take(400)
}
