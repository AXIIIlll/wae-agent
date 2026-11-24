package com.wae.core.plan

import com.wae.core.model.Channel
import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.ControlState
import com.wae.core.model.RectI
import com.wae.core.screen.ScreenModel
import com.wae.core.screen.ScreenOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 計畫層（P0–P3）的契約測試：穩定性、比對、負面知識、切詞。
 *
 * 這一層全部是純邏輯，所以不需要裝置；真機只驗證「接線正確」。
 */
class PlanCoreTest {

    // ── Stability ────────────────────────────────────────────

    @Test
    fun `stability requires both repeated frames and elapsed time`() {
        val s = Stability(requiredStableFrames = 2, minStableMs = 300)
        assertFalse(s.observe("fp1", 0))       // 第一次看到
        assertFalse(s.observe("fp1", 100))     // 幀數夠了但時間不夠
        assertFalse(s.observe("fp1", 250))     // 時間仍不夠
        assertTrue(s.observe("fp1", 320))      // 兩者都成立
    }

    @Test
    fun `stability resets when the screen changes`() {
        val s = Stability(requiredStableFrames = 2, minStableMs = 100)
        s.observe("a", 0)
        assertTrue(s.observe("a", 200))
        assertFalse(s.observe("b", 300))       // 畫面變了 → 重新等
    }

    @Test
    fun `empty fingerprint never counts as stable`() {
        val s = Stability()
        assertFalse(s.observe("", 0))
        assertFalse(s.observe("", 10_000))
    }

    // ── PlanMatcher ──────────────────────────────────────────

    private fun option(
        label: String,
        role: String = "ROW",
        group: String = "",
        checked: Boolean? = null,
        enabled: Boolean = true
    ) = ScreenOption(
        id = "opt-$label-$role-$group",
        label = label,
        role = role,
        group = group,
        source = "A11Y",
        enabled = enabled,
        checked = checked,
        left = 0, top = 0, right = 100, bottom = 50,
        ordinal = 1
    )

    private fun screen(vararg options: ScreenOption) =
        ScreenModel("com.example", "fp", options.toList(), emptyList())

    private val scoreByContains: (String, String) -> Float = { goal, label ->
        val g = com.wae.core.predicate.TextFold.fold(goal)
        val l = com.wae.core.predicate.TextFold.fold(label)
        when {
            g.isEmpty() || l.isEmpty() -> 0f
            g == l -> 1f
            l.contains(g) -> 0.88f
            else -> 0f
        }
    }

    @Test
    fun `toggle step prefers an option that actually has a switch state`() {
        val s = screen(
            option("飛航模式", role = "ROW", checked = null),   // 說明列／不能切
            option("飛航模式", role = "TOGGLE", checked = false) // 真正的開關
        )
        val step = PlanStep(action = "TOGGLE_ON", target = "飛航模式")
        val best = PlanMatcher.best(step, s, scoreByContains)
        assertEquals("TOGGLE", best?.option?.role)
    }

    @Test
    fun `type step only accepts editable candidates`() {
        val s = screen(
            option("搜尋", role = "BUTTON"),
            option("搜尋", role = "FIELD")
        )
        val step = PlanStep(action = "TYPE", target = "搜尋", arg = "藍牙")
        assertEquals("FIELD", PlanMatcher.best(step, s, scoreByContains)?.option?.role)
    }

    @Test
    fun `banned combination is excluded even if it matches best`() {
        val s = screen(option("歷史紀錄", role = "ROW"))
        val step = PlanStep(action = "PICK", target = "歷史紀錄")
        val key = PlanMatcher.banKey("com.android.chrome", "歷史紀錄", "PICK")
        assertNull(PlanMatcher.best(step, s, scoreByContains, banned = setOf(key), packageName = "com.android.chrome"))
    }

    @Test
    fun `group match still beats nothing but loses to exact label`() {
        val s = screen(
            option("其他裝置", role = "ROW", group = "藍牙"),
            option("藍牙", role = "ROW", group = "連接")
        )
        val step = PlanStep(action = "PICK", target = "藍牙")
        // 完全相符的標籤贏過只有群組相符的候選
        assertEquals("藍牙", PlanMatcher.best(step, s, scoreByContains)?.option?.label)
    }

    // ── Tokenizer ────────────────────────────────────────────

    @Test
    fun `longest match wins with the provided dictionary`() {
        val dict = listOf("在", "設定", "裡", "搜尋", "藍牙", "設定裡")
        val words = Tokenizer.words("在設定裡搜尋藍牙", dict)
        // 「設定裡」比「設定」長，會被優先匹配
        assertEquals(listOf("在", "設定裡", "搜尋", "藍牙"), words)
    }

    @Test
    fun `unknown characters fall back to single characters`() {
        val words = Tokenizer.words("打開Chrome", listOf("打開"))
        assertEquals(listOf("打開", "C", "h", "r", "o", "m", "e"), words)
    }

    @Test
    fun `coverage reflects how much of the sentence the dictionary explains`() {
        val dict = listOf("搜尋", "藍牙")
        assertTrue(Tokenizer.coverage("搜尋藍牙", dict) > 0.99f)
        assertTrue(Tokenizer.coverage("幫我搜尋藍牙", dict) < 0.9f)
    }

    // ── Plan / Trace ─────────────────────────────────────────

    @Test
    fun `plan trace serialises to tsv with the documented column order`() {
        val trace = PlanTrace(
            planOrigin = "TEMPLATE", planRevision = 2, stepIndex = 1,
            action = "PICK", target = "歷史紀錄", preconditionOk = true,
            optionCount = 12, chosenOption = "opt-abc", score = 0.88f,
            skippedByBan = false, elapsedMs = 640
        )
        val cols = trace.toTsv().split('\t')
        assertEquals(11, cols.size)
        assertEquals("TEMPLATE", cols[0])
        assertEquals("2", cols[1])
        assertEquals("opt-abc", cols[7])
        assertEquals("0.88", cols[8])
    }
}
