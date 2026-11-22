package com.wae.core.predicate

import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.ControlRange
import com.wae.core.model.ControlState
import com.wae.core.model.Frame
import com.wae.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「畫面即文字」的契約測試。
 *
 * 這份序列化是模型唯一會讀的輸入，所以它必須：
 *  1. 穩定（同一幀 → 同一份文字）
 *  2. 可指涉（每行有索引）
 *  3. 把「沒有文字」的元素誠實標成（無文字），而不是假裝不存在
 */
class ScreenTextTest {

    private fun ctl(
        key: String,
        label: String,
        kind: ControlKind = ControlKind.ROW,
        checked: Boolean? = null,
        range: ControlRange? = null,
        enabled: Boolean = true
    ) = Control(
        key = key,
        kind = kind,
        label = label,
        textHash = "",
        bounds = RectI(10, 20, 210, 120),
        enabled = enabled,
        state = ControlState(checked = checked),
        range = range
    )

    private fun frame(vararg c: Control) = Frame(
        fp = "fp:abc123", pkg = "com.android.settings", appVersion = 1, controls = c.toList()
    )

    @Test
    fun `renders one line per control with index kind label state`() {
        val f = frame(
            ctl("k1", "連接", ControlKind.ROW),
            ctl("k2", "Wi-Fi", ControlKind.TOGGLE, checked = false),
            ctl("k3", "亮度", ControlKind.SLIDER, range = ControlRange(0f, 100f, 40f)),
            ctl("k4", "", ControlKind.BUTTON)
        )
        val ls = ScreenText.lines(f)
        assertEquals(4, ls.size)

        assertEquals("ROW 連接", ls[0].text)
        assertEquals("TOGGLE Wi-Fi 未開啟", ls[1].text)
        assertEquals("SLIDER 亮度 值=40%", ls[2].text)
        assertEquals("BUTTON", ls[3].text.trim())

        // 沒有文字的元素要被誠實標示，不能假裝不存在
        assertEquals("（無文字）", ls[3].label)

        val text = ScreenText.render(f)
        assertTrue(text.contains("[1] TOGGLE"))
        assertTrue(text.contains("Wi-Fi"))
        assertTrue(text.contains("未開啟"))
    }

    @Test
    fun `rendering is stable for the same frame`() {
        val f = frame(ctl("k1", "Wi-Fi", ControlKind.TOGGLE, checked = true))
        assertEquals(ScreenText.render(f), ScreenText.render(f))
    }

    @Test
    fun `lines are individually addressable`() {
        val f = frame(
            ctl("k1", "藍牙", ControlKind.TOGGLE, checked = true),
            ctl("k2", "熱點", ControlKind.TOGGLE, checked = false)
        )
        assertEquals("熱點", ScreenText.lineAt(f, 1)?.label)
        assertEquals(ControlKind.TOGGLE, ScreenText.lineAt(f, 1)?.kind)
    }

    @Test
    fun `empty frame still renders a header`() {
        val text = ScreenText.render(Frame.EMPTY)
        assertTrue(text.contains("元素=0"))
    }
}
