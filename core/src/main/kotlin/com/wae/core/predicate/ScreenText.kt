package com.wae.core.predicate

import com.wae.core.model.ControlKind
import com.wae.core.model.Frame

/**
 * **畫面即文字**：把一幀畫面序列化成「模型唯一會讀的那份文字」。
 *
 * 為什麼要這一層（而不是讓模型直接看 `Frame`）：
 *  1. 模型讀的是文字 → 決策就是「在文字裡挑一行」，不需要另一套影像管線。
 *  2. log 與離線重放都吃同一份字串 → 出問題時看到的就是模型看到的。
 *  3. 圖示、OCR、無障礙三種來源**合流成同一種表示**，下游不必知道資料從哪來。
 *
 * 格式（每行一個元素，固定欄位順序，方便人與程式讀）：
 * ```
 * [索引] 型別  "文字"  狀態  @x,y  w×h  旗標
 * ```
 * 索引是**同一份序列化內**的穩定編號（不是座標、也不是 view id），
 * 方便「第 7 行」這種指涉在 log 與對話裡使用。
 */
object ScreenText {

    /** 一行＝一個可被指涉的元素。 */
    data class Line(
        val index: Int,
        val kind: ControlKind,
        val label: String,
        val state: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val flags: String,
        /** 給模型看的完整文字（型別 + 文字 + 狀態）。 */
        val text: String
    )

    /** 序列化成結構化的行（給程式用）。 */
    fun lines(frame: Frame): List<Line> = frame.controls.mapIndexed { i, c ->
        val kind = c.kind
        val label = c.label.ifBlank { "（無文字）" }
        val state = when {
            c.state.checked != null -> if (c.state.checked == true) "已開啟" else "未開啟"
            c.range != null -> "值=${(c.valueFraction ?: 0f).let { (it * 100).toInt() }}%"
            !c.enabled -> "不可用"
            else -> ""
        }
        val flags = buildList {
            if (c.state.selected == true) add("已選")
            if (c.scrollable) add("可捲動")
            if (!c.enabled) add("停用")
            when (c.source) {
                com.wae.core.model.Channel.OCR -> add("OCR")
                com.wae.core.model.Channel.ICON -> add("圖示")
                else -> Unit
            }
        }.joinToString(",")

        // 給模型讀的那一行：型別 + 文字 + 狀態（不要座標，模型不需要）
        val modelText = buildString {
            append(kind.name)
            append(' ')
            append(c.label.ifBlank { "" })
            if (state.isNotEmpty()) {
                append(' ')
                append(state)
            }
        }.trim()

        Line(
            index = i,
            kind = kind,
            label = label,
            state = state,
            x = c.bounds.left,
            y = c.bounds.top,
            w = c.bounds.width,
            h = c.bounds.height,
            flags = flags,
            text = modelText
        )
    }

    /** 序列化成一份完整文字（給 log 與離線重放用）。 */
    fun render(frame: Frame, maxLines: Int = 60): String {
        val sb = StringBuilder(1024)
        sb.append("── 畫面 pkg=").append(frame.pkg.ifEmpty { "?" })
            .append(" fp=").append(frame.fp.take(12))
            .append(" 元素=").append(frame.controls.size)
            .append(" ──\n")
        val ls = lines(frame).take(maxLines)
        for (l in ls) {
            sb.append('[').append(l.index).append("] ")
            sb.append(l.kind.name.padEnd(8))
            sb.append('"').append(l.label).append('"')
            if (l.state.isNotEmpty()) sb.append(' ').append(l.state)
            sb.append("  @").append(l.x).append(',').append(l.y)
            sb.append("  ").append(l.w).append('×').append(l.h)
            if (l.flags.isNotEmpty()) sb.append("  ").append(l.flags)
            sb.append('\n')
        }
        if (frame.controls.size > maxLines) {
            sb.append("…（其餘 ").append(frame.controls.size - maxLines).append(" 個元素省略）\n")
        }
        return sb.toString()
    }

    /** 取第 [index] 行的可讀文字（給「第 N 個」這種指涉用）。 */
    fun lineAt(frame: Frame, index: Int): Line? = lines(frame).firstOrNull { it.index == index }
}
