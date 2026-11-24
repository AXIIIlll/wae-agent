package com.wae.core.screen

import com.wae.core.model.Channel
import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.ControlState
import com.wae.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 畫面簡化器的契約測試。
 *
 * 這裡只測「純邏輯」：哪些東西算選項、群組怎麼來、id 穩不穩定。
 * 真機上的節點與座標不在測試範圍（那要靠 gateway 的既有測試與真機驗證）。
 */
class OptionExtractorTest {

    private fun control(
        key: String,
        kind: ControlKind,
        label: String,
        top: Int,
        left: Int = 0,
        clickable: Boolean = true,
        enabled: Boolean = true,
        channel: Channel = Channel.A11Y
    ) = Control(
        key = key,
        kind = kind,
        label = label,
        textHash = "",
        bounds = RectI(left, top, left + 300, top + 80),
        enabled = enabled,
        clickable = clickable,
        scrollable = false,
        classHint = "",
        state = ControlState(),
        source = channel
    )

    @Test
    fun `only interactive controls become options`() {
        val frame = listOf(
            control("h1", ControlKind.UNKNOWN, "連接", top = 100, clickable = false),
            control("c1", ControlKind.ROW, "Wi-Fi", top = 200),
            control("t1", ControlKind.UNKNOWN, "所有鬧鐘皆已關閉", top = 400, clickable = false)
        )
        val model = OptionExtractor.from("com.example", "fp", frame, screenHeightPx = 2000)

        assertEquals(1, model.options.size)
        assertEquals("Wi-Fi", model.options[0].label)
        // 唯讀文字進 context，不進選項
        assertTrue(model.contextLines.contains("連接"))
        assertTrue(model.contextLines.contains("所有鬧鐘皆已關閉"))
        assertFalse(model.options.any { it.label.contains("所有鬧鐘") })
    }

    @Test
    fun `option takes the nearest header above as its group`() {
        val frame = listOf(
            control("h1", ControlKind.UNKNOWN, "連接", top = 100, clickable = false),
            control("c1", ControlKind.ROW, "Wi-Fi", top = 200),
            control("h2", ControlKind.UNKNOWN, "位置", top = 600, clickable = false),
            control("c2", ControlKind.ROW, "定位", top = 700)
        )
        val model = OptionExtractor.from("com.example", "fp", frame, screenHeightPx = 2000)

        assertEquals("連接", model.options.first { it.label == "Wi-Fi" }.group)
        assertEquals("位置", model.options.first { it.label == "定位" }.group)
    }

    @Test
    fun `ids stay stable when the same option scrolls to a different position`() {
        val before = OptionExtractor.from(
            "com.example", "fp1",
            listOf(control("c1", ControlKind.ROW, "Wi-Fi", top = 200)), 2000
        )
        val after = OptionExtractor.from(
            "com.example", "fp2",
            listOf(control("c1", ControlKind.ROW, "Wi-Fi", top = 1200)), 2000
        )
        assertNotNull(before.options.firstOrNull())
        assertEquals(before.options[0].id, after.options[0].id)
    }

    @Test
    fun `duplicate labels get distinct ordinals and distinct ids`() {
        val frame = listOf(
            control("a", ControlKind.ROW, "Wi-Fi", top = 100),
            control("b", ControlKind.ROW, "Wi-Fi", top = 300)
        )
        val model = OptionExtractor.from("com.example", "fp", frame, screenHeightPx = 2000)
        assertEquals(listOf(1, 2), model.options.map { it.ordinal })
        assertEquals(2, model.options.map { it.id }.distinct().size)
    }

    @Test
    fun `disabled options are kept but flagged`() {
        val frame = listOf(control("a", ControlKind.BUTTON, "儲存", top = 100, enabled = false))
        val model = OptionExtractor.from("com.example", "fp", frame, screenHeightPx = 2000)
        assertEquals(1, model.options.size)
        assertFalse(model.options[0].enabled)
    }

    @Test
    fun `unnamed interactive control still appears with a role label`() {
        val frame = listOf(control("a", ControlKind.BUTTON, "", top = 100))
        val model = OptionExtractor.from("com.example", "fp", frame, screenHeightPx = 2000)
        assertEquals(1, model.options.size)
        assertTrue(model.options[0].label.contains("BUTTON"))
    }

    @Test
    fun `empty frame yields empty model`() {
        val model = OptionExtractor.from("com.example", "fp", emptyList(), 2000)
        assertTrue(model.options.isEmpty())
        assertTrue(model.contextLines.isEmpty())
    }

    @Test
    fun `options are capped so the list stays focused`() {
        val frame = (0 until 100).map { i ->
            control("c$i", ControlKind.ROW, "項目$i", top = i * 100)
        }
        val model = OptionExtractor.from("com.example", "fp", frame, screenHeightPx = 20000, maxOptions = 10)
        assertEquals(10, model.options.size)
    }

    @Test
    fun `ocr sourced option keeps its source so callers can trust it less`() {
        val frame = listOf(control("o1", ControlKind.ROW, "歷史紀錄", top = 100, channel = Channel.OCR))
        val model = OptionExtractor.from("com.example", "fp", frame, screenHeightPx = 2000)
        assertEquals("OCR", model.options[0].source)
    }

    @Test
    fun `locate maps an option id back to its control`() {
        val frame = listOf(
            control("h", ControlKind.UNKNOWN, "連接", top = 100, clickable = false),
            control("wifi", ControlKind.ROW, "Wi-Fi", top = 200),
            control("bt", ControlKind.ROW, "藍牙", top = 300)
        )
        val model = OptionExtractor.from("com.example", "fp", frame, screenHeightPx = 2000)
        val wifiOption = model.options.first { it.label == "Wi-Fi" }
        val located = OptionExtractor.locate(frame, wifiOption.id, screenHeightPx = 2000)
        assertEquals("wifi", located?.key)
    }

    @Test
    fun `locate returns null for an unknown option id`() {
        val frame = listOf(control("wifi", ControlKind.ROW, "Wi-Fi", top = 200))
        assertEquals(null, OptionExtractor.locate(frame, "opt-deadbeef", screenHeightPx = 2000))
    }
}
