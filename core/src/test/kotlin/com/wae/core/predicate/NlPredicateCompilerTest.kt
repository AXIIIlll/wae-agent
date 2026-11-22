package com.wae.core.predicate

import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.ControlRange
import com.wae.core.model.ControlState
import com.wae.core.model.Frame
import com.wae.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「通用性」的驗收測試：
 *
 * 這一整個檔案裡**沒有任何一句寫死的句型**。它證明的是：
 * 只要詞彙資料（開關詞／方向詞／程度詞／標籤線索）不變，
 * 「開 WiFi」「開熱點」「把亮度調到 40%」都走同一條編譯路徑，
 * 而「開熱點」從未被任何技能或句型列舉過。
 */
class NlPredicateCompilerTest {

    private val lex = PredicateLexicon(
        toggleWords = setOf("打開", "開啟", "開", "關閉", "關掉", "關", "打開"),
        onWords = setOf("打開", "開啟", "開", "on", "enable"),
        offWords = setOf("關閉", "關掉", "關", "off", "disable"),
        fillers = setOf("幫我", "請", "把", "將", "一下", "螢幕", "螢幕的"),
        adjustVerbs = setOf("調", "設", "改", "轉", "推", "拉"),
        directions = mapOf("高" to 1, "低" to -1, "亮" to 1, "暗" to -1, "大" to 1, "小" to -1),
        degreeSmall = setOf("一點", "一些", "稍微"),
        degreeLarge = setOf("很多", "大幅"),
        valueLabels = mapOf("亮度" to "亮度", "音量" to "音量"),
        appAliases = mapOf("相機" to "com.android.camera", "設定" to "com.android.settings"),
        settingsPkg = "com.android.settings"
    )
    private val compiler = NlPredicateCompiler(lex)

    private fun frame(vararg c: Control, pkg: String = "com.android.settings") =
        Frame(fp = "fp:x", pkg = pkg, appVersion = 1, controls = c.toList())

    private fun ctl(
        key: String,
        label: String,
        kind: ControlKind = ControlKind.TOGGLE,
        checked: Boolean? = false,
        range: ControlRange? = null
    ) = Control(
        key = key, kind = kind, label = label, textHash = "",
        bounds = RectI(0, 0, 10, 10), state = ControlState(on = checked, checked = checked), range = range
    )

    @Test
    fun `open wifi compiles to a toggle predicate`() {
        val out = compiler.compile("打開 WiFi")
        assertTrue(out.issues.toString(), out.operable)
        assertEquals(NlPredicateCompiler.GoalKind.TOGGLE, out.kind)
        assertNotNull(out.goal)

        val world = frame(ctl("k1", "Wi-Fi", checked = false)).asWorld()
        assertFalse("一開始是關的", PredicateEval.eval(out.goal!!, world))

        val after = frame(ctl("k1", "Wi-Fi", checked = true)).asWorld()
        assertTrue("打開後成立", PredicateEval.eval(out.goal!!, after))
    }

    @Test
    fun `open hotspot - a command never enumerated anywhere - compiles the same way`() {
        val out = compiler.compile("開熱點")
        assertTrue(out.issues.toString(), out.operable)
        assertEquals(NlPredicateCompiler.GoalKind.TOGGLE, out.kind)

        val world = frame(ctl("h", "行動熱點", checked = false)).asWorld()
        assertFalse(PredicateEval.eval(out.goal!!, world))
        val after = frame(ctl("h", "行動熱點", checked = true)).asWorld()
        assertTrue("同一個述詞形狀直接命中沒見過的標籤", PredicateEval.eval(out.goal!!, after))
    }

    @Test
    fun `brightness to 40 percent compiles to a slider predicate`() {
        val out = compiler.compile("把亮度調到 40%")
        assertTrue(out.issues.toString(), out.operable)
        assertEquals(NlPredicateCompiler.GoalKind.VALUE, out.kind)

        val world = frame(ctl("s", "亮度", ControlKind.SLIDER, range = ControlRange(0f, 255f, 102f))).asWorld()
        assertTrue("255 的 40% = 102", PredicateEval.eval(out.goal!!, world))
    }

    @Test
    fun `relative brightness needs the current value`() {
        val out = compiler.compile("調亮一點")
        assertNull("相對目標不該有絕對述詞", out.goal)
        val rel = out.relative
        assertNotNull(rel)
        assertEquals(1, rel!!.direction)
        assertEquals(0.10f, rel.delta, 0.0001f)

        val abs = compiler.absoluteOf(rel, 0.40f)
        val world = frame(ctl("s", "亮度", ControlKind.SLIDER, range = ControlRange(0f, 100f, 50f))).asWorld()
        assertTrue("40% + 10% = 50%", PredicateEval.eval(abs, world))
    }

    @Test
    fun `direction word alone is not enough (guards against long press)`() {
        val out = compiler.compile("長按這個圖片")
        assertFalse(out.operable)
        assertTrue(out.issues.isNotEmpty())
    }

    @Test
    fun `app name compiles to a package predicate`() {
        val out = compiler.compile("打開相機")
        // 「打開」同時是開關詞；目標「相機」在 appAliases 裡 → 這裡必須是 NAVIGATE 而不是亂猜的 TOGGLE
        assertEquals(NlPredicateCompiler.GoalKind.NAVIGATE, out.kind)
        assertEquals("com.android.camera", out.targetPkg)
    }

    @Test
    fun `unknown sentence abstains instead of guessing`() {
        val out = compiler.compile("這個東西怎麼辦")
        assertFalse(out.operable)
        assertEquals(NlPredicateCompiler.GoalKind.UNKNOWN, out.kind)
    }
}
