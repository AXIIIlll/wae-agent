package com.wae.core

import com.wae.core.match.Definition
import com.wae.core.match.DefinitionMatcher
import com.wae.core.match.DefinitionTable
import com.wae.core.model.ControlKind
import com.wae.core.predicate.PredicateControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多定義比對器（P1）測試。
 *
 * 核心主張只有一個：**加權最大，不是平均**——
 * 一個目標多寫幾條說法不該讓分數變低；而籠統詞（specificity 小）永遠壓不過具體詞。
 *
 * 另外把「圖示按鈕」這條真實失敗案例（地圖的「路線」沒有文字）寫成測試：
 * 只要 view id 裡有 `directions`，就該命中。
 */
class DefinitionMatcherTest {

    private fun control(label: String, key: String, kind: ControlKind = ControlKind.BUTTON): PredicateControl =
        object : PredicateControl {
            override val key: String = key
            override val label: String = label
            override val kind: ControlKind = kind
            override val checked: Boolean? = null
            override val editable: Boolean = false
            override val enabled: Boolean = true
            override val valueFraction: Float? = null
            override val textHash: String = ""
        }

    private fun table(): DefinitionTable = DefinitionTable.parse(
        sequenceOf(
            "MAP_DIRECTIONS_ROUTE\tmaps\tTEXT\t路線\t0.900",
            "MAP_DIRECTIONS_ROUTE\tmaps\tTEXT\t規劃路線\t0.900",
            "MAP_DIRECTIONS_ROUTE\tmaps\tICON\tdirections\t0.900",
            "MAP_DIRECTIONS_ROUTE\tmaps\tICON\talt_route\t0.900",
            "add\tcontent\tTEXT\t新增\t0.800",
            "add\tcontent\tICON\tadd\t0.800",
            "VOLUME\tmedia\tTEXT\t音量\t0.300"
        )
    )

    @Test
    fun `icon only control matches through its view id which is the known hard case`() {
        val m = DefinitionMatcher(table())
        // 地圖的「路線」：沒有 label，只有 view id
        val iconOnly = control("", "android.widget.ImageButton|com.google.android.apps.maps:id/directions_button")
        val s = m.score("路線", iconOnly)
        assertTrue("圖示按鈕應該命中，實際=$s", s >= 0.9f)
    }

    @Test
    fun `text evidence still works for a labelled control`() {
        val m = DefinitionMatcher(table())
        val labelled = control("路線", "android.widget.Button|com.x:id/route_btn")
        assertEquals(0.9f, m.score("路線", labelled), 0.001f)
    }

    @Test
    fun `unrelated control scores zero`() {
        val m = DefinitionMatcher(table())
        val other = control("音量", "android.widget.SeekBar|com.x:id/volume")
        assertEquals(0f, m.score("路線", other), 0.0001f)
    }

    @Test
    fun `token boundary prevents add from matching address`() {
        val m = DefinitionMatcher(table())
        val address = control("", "android.widget.Button|com.x:id/address_book")
        assertEquals("add 不該命中 address（誤配比找不到更糟）", 0f, m.score("新增", address), 0.0001f)
        val addButton = control("", "android.widget.ImageButton|com.x:id/add_button")
        assertTrue(m.score("新增", addButton) >= 0.8f)
    }

    @Test
    fun `specificity decides when two entities both match`() {
        val m = DefinitionMatcher(table())
        // 「音量」只屬於低具體度的 entity（0.3）
        val volume = control("音量", "com.x:id/volume")
        assertEquals(0.3f, m.score("音量", volume), 0.001f)
        // 「路線」屬於高具體度的 entity（0.9）
        val route = control("路線", "com.x:id/route")
        assertEquals(0.9f, m.score("路線", route), 0.001f)
    }

    @Test
    fun `adding more aliases never lowers the score which is why max not average`() {
        val base = DefinitionMatcher(table())
        val many = DefinitionMatcher(
            DefinitionTable.parse(
                sequenceOf(
                    "MAP_DIRECTIONS_ROUTE\tmaps\tTEXT\t路線\t0.900",
                    "MAP_DIRECTIONS_ROUTE\tmaps\tTEXT\t規劃路線\t0.900",
                    "MAP_DIRECTIONS_ROUTE\tmaps\tTEXT\t怎麼走\t0.200",
                    "MAP_DIRECTIONS_ROUTE\tmaps\tTEXT\t路徑\t0.100",
                    "MAP_DIRECTIONS_ROUTE\tmaps\tTEXT\t導航路線\t0.900"
                )
            )
        )
        val c = control("路線", "com.x:id/route")
        val a = base.score("路線", c)
        val b = many.score("路線", c)
        assertTrue("多寫說法不該拉低分數：base=$a many=$b", b >= a - 0.0001f)
    }

    @Test
    fun `query with no matching entity scores zero`() {
        val m = DefinitionMatcher(table())
        val c = control("路線", "com.x:id/route")
        assertEquals(0f, m.score("完全無關的句子", c), 0.0001f)
    }

    @Test
    fun `query cache does not leak between goals`() {
        val m = DefinitionMatcher(table())
        val route = control("路線", "com.x:id/route")
        val volume = control("音量", "com.x:id/volume")
        assertTrue(m.score("路線", route) > 0f)
        assertEquals(0f, m.score("音量", route), 0.0001f)
        assertTrue(m.score("音量", volume) > 0f)
    }

    @Test
    fun `view weights can down weight icon evidence`() {
        val m = DefinitionMatcher(table(), mapOf(Definition.View.TEXT to 0.55f, Definition.View.ICON to 0.055f))
        val iconOnly = control("", "com.x:id/directions_button")
        val s = m.score("路線", iconOnly)
        assertEquals("ICON 權重壓到 1/10 時，分數也該是 1/10", 0.09f, s, 0.001f)
    }

    @Test
    fun `entitiesFor returns candidate entities for diagnostics`() {
        val m = DefinitionMatcher(table())
        val ents = m.entitiesFor("幫我看一下路線")
        assertTrue("應該找到 MAP_DIRECTIONS_ROUTE，實際=$ents", ents.contains("MAP_DIRECTIONS_ROUTE"))
    }
}
