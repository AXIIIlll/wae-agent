package com.wae.agent.core.fixture

import com.wae.core.model.Channel
import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.ControlState
import com.wae.core.model.RectI
import java.io.File

/**
 * 把真機 `uiautomator dump` 的 XML 轉成 `Control` 清單，給測試當「真實畫面」用。
 *
 * 為什麼需要：019／025 這兩題我們修了三次才對（頁籤點不動、概念對不上），
 * 而單元測試當時只用手寫的假資料——假資料不會出現「同一頁有三個同名節點」、
 * 「頁籤 clickable=false」這種真實特性。把真機 dump 凍結成 fixture 之後，
 * 同樣的 bug 會在下一次 push 就被擋下來。
 *
 * 做法刻意簡單：只解析測試需要的那幾個屬性（text / content-desc / class /
 * clickable / focusable / selected / enabled / bounds），不追求完整支援 uiautomator 格式。
 */
object UiDumpFixture {

    fun load(name: String): List<Control> {
        val file = listOf(
            File("src/test/resources/fixtures/$name"),
            File("app/src/test/resources/fixtures/$name")
        ).firstOrNull { it.isFile }
            ?: error("找不到 fixture: $name（工作目錄 ${File(".").absolutePath}）")
        return parse(file.readText(Charsets.UTF_8))
    }

    fun parse(xml: String): List<Control> {
        // 用真正的 XML parser：uiautomator 的屬性裡可能有 `>`（例如文字內容），
        // 用 regex 抓 <node …> 會整份抓不到（實測 0 個節點）。
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            // 明確關掉外部實體：測試只讀本地 fixture，不需要網路
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder()
            .parse(xml.byteInputStream(Charsets.UTF_8))
        val nodes = doc.getElementsByTagName("node")
        val out = ArrayList<Control>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val text = el.getAttribute("text").orEmpty()
            val desc = el.getAttribute("content-desc").orEmpty()
            val label = text.ifEmpty { desc }
            val cls = el.getAttribute("class").orEmpty()
            val viewId = el.getAttribute("resource-id").orEmpty()
            val clickable = el.getAttribute("clickable").toBoolean()
            val focusable = el.getAttribute("focusable").toBoolean()
            val selected = el.getAttribute("selected").toBoolean()
            val enabled = el.getAttribute("enabled").ifEmpty { "true" }.toBoolean()
            val bounds = parseBounds(el.getAttribute("bounds")) ?: continue
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            out.add(
                Control(
                    key = "fx:$i",
                    kind = kindOf(cls, clickable, focusable, desc, text),
                    label = label,
                    textHash = "",
                    bounds = bounds,
                    enabled = enabled,
                    // 與 A11yFrameSource 相同的判定：可點、可聚焦，都算「可互動」
                    clickable = clickable || focusable,
                    scrollable = el.getAttribute("scrollable").toBoolean(),
                    classHint = cls,
                    state = ControlState(
                        selected = if (selected) true else null,
                        checked = null
                    ),
                    source = Channel.A11Y,
                    viewId = viewId,
                    layer = 1,
                    inWeb = false
                )
            )
        }
        return out
    }

    private fun kindOf(
        cls: String,
        clickable: Boolean,
        focusable: Boolean,
        desc: String,
        text: String
    ): ControlKind {
        val c = cls.lowercase()
        return when {
            c.contains("edittext") || c.contains("autocompletetextview") -> ControlKind.FIELD
            c.contains("switch") || c.contains("checkbox") || c.contains("toggle") -> ControlKind.TOGGLE
            c.contains("seekbar") || c.contains("slider") -> ControlKind.SLIDER
            c.contains("button") || c.contains("imagebutton") -> ControlKind.BUTTON
            c.contains("tab") -> ControlKind.TAB
            clickable || focusable || desc.isNotEmpty() || text.isNotEmpty() -> ControlKind.ROW
            else -> ControlKind.UNKNOWN
        }
    }

    private fun attr(tag: String, name: String): String {
        val m = Regex("\\b$name=\"([^\"]*)\"").find(tag) ?: return ""
        return m.groupValues[1]
    }

    private fun parseBounds(raw: String): RectI? {
        // 格式：[x1,y1][x2,y2]
        val m = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(raw) ?: return null
        val (x1, y1, x2, y2) = m.destructured
        return RectI(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
    }

    private fun RectI.width(): Int = right - left
    private fun RectI.height(): Int = bottom - top
}
