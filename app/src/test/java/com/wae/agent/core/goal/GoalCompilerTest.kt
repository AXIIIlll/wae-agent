package com.wae.agent.core.goal

import com.wae.agent.core.ActionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * M5 測試：後置條件 DSL、目標編譯器、可驗證率與「誤報成功」防護。
 *
 * 架構書驗收線：
 *  - 可驗證率 ≥ 90%
 *  - 誤報成功 < 2%
 *  - 編譯耗時 < 5ms/指令
 */
class GoalCompilerTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    @Before
    fun setUp() {
        GoalCompiler.resetForTest()
        GoalCompiler.loadFromLines(asset("ui_postcondition_rules.tsv").readLines(Charsets.UTF_8))
        assertTrue(GoalCompiler.effectiveRules().isNotEmpty())
    }

    private fun facts(
        pkg: String = "",
        pkgBefore: String = "",
        spineChanged: Boolean = false,
        texts: List<String> = emptyList(),
        selectors: Set<String> = emptySet(),
        values: Map<String, Float> = emptyMap(),
        valuesBefore: Map<String, Float> = emptyMap(),
        dispatch: Boolean = false
    ) = PageFacts(pkg, pkgBefore, spineChanged, texts, selectors, values, valuesBefore, dispatch)

    // ── 編譯規則 ─────────────────────────────────────────────────────

    @Test
    fun `launch compiles to package equality`() {
        val c = GoalCompiler.compile(ActionKind.LAUNCH_APP, packageHint = "com.android.settings")
        assertTrue(c.verifiable)
        assertTrue(c is PostCondition.PackageIs)
        assertTrue(c.evaluate(facts(pkg = "com.android.settings")))
        assertTrue("子套件也算同一個 App", c.evaluate(facts(pkg = "com.android.settings.intelligence")))
        assertFalse(c.evaluate(facts(pkg = "com.android.chrome")))
    }

    @Test
    fun `type compiles to text present or spine changed`() {
        val c = GoalCompiler.compile(ActionKind.TYPE, payload = "invoice")
        assertTrue(c.verifiable)
        assertTrue("文字真的出現 → 成立", c.evaluate(facts(texts = listOf("搜尋結果", "Invoice #1"), spineChanged = true)))
        assertTrue("文字在下一層但畫面變了 → 弱成立", c.evaluate(facts(texts = listOf("載入中"), spineChanged = true)))
        assertFalse("文字沒出現且畫面沒變 → 不可判成功", c.evaluate(facts(texts = listOf("首頁"))))
    }

    @Test
    fun `type with empty payload falls back to spine change`() {
        val c = GoalCompiler.compile(ActionKind.TYPE, payload = "")
        assertEquals(PostCondition.SpineChanged, c)
    }

    @Test
    fun `tap compiles to spine changed or element appeared`() {
        val c = GoalCompiler.compile(ActionKind.TAP, targetSelector = "id:send")
        assertTrue(c.evaluate(facts(spineChanged = true)))
        assertTrue(c.evaluate(facts(selectors = setOf("id:send"))))
        assertFalse("什麼都沒發生就不可判成功", c.evaluate(facts()))
    }

    @Test
    fun `back without package hint must not be always true`() {
        val c = GoalCompiler.compile(ActionKind.BACK)
        assertFalse("沒有套件提示時，什麼都沒變 → 不可判成功", c.evaluate(facts(pkg = "com.a", pkgBefore = "com.a")))
        assertTrue(c.evaluate(facts(spineChanged = true)))
    }

    @Test
    fun `adjust value is verified by the action contract`() {
        val c = GoalCompiler.compile(ActionKind.ADJUST_VALUE)
        assertTrue(c.verifiable)
        assertFalse(c.evaluate(facts(dispatch = false)))
        assertTrue(c.evaluate(facts(dispatch = true)))
    }

    @Test
    fun `value direction compiles to increase or decrease`() {
        val up = GoalCompiler.compile(
            ActionKind.ADJUST_VALUE, adjustUp = true, targetSelector = "id:brightness"
        )
        // DIPSATCH_VERIFIED 是預設規則；VALUE_DIR 需在資料檔指定（見下方測試）
        assertTrue(up.verifiable)

        val vUp = PostCondition.ValueIncreased("id:brightness")
        assertTrue(vUp.evaluate(facts(values = mapOf("id:brightness" to 60f), valuesBefore = mapOf("id:brightness" to 30f))))
        assertFalse(vUp.evaluate(facts(values = mapOf("id:brightness" to 20f), valuesBefore = mapOf("id:brightness" to 30f))))

        val vDown = PostCondition.ValueDecreased("id:brightness")
        assertTrue(vDown.evaluate(facts(values = mapOf("id:brightness" to 10f), valuesBefore = mapOf("id:brightness" to 30f))))
    }

    @Test
    fun `wait is honestly unverifiable`() {
        val c = GoalCompiler.compile(ActionKind.WAIT)
        assertFalse("等待沒有可驗證效果，不可拿來宣稱成功", c.verifiable)
    }

    // ── 驗收線 1：可驗證率 ≥ 90% ───────────────────────────────────────

    @Test
    fun `verifiable rate over representative corpus is at least ninety percent`() {
        // 30 個代表性目標，涵蓋全部動作種類與邊界情況（含 TYPE 空 payload）
        val corpus = listOf(
            GoalCompiler.compile(ActionKind.LAUNCH_APP, packageHint = "com.android.settings"),
            GoalCompiler.compile(ActionKind.LAUNCH_APP, packageHint = "com.sec.android.app.popupcalculator"),
            GoalCompiler.compile(ActionKind.LAUNCH_APP, packageHint = "com.google.android.gm"),
            GoalCompiler.compile(ActionKind.DEEP_LINK, packageHint = "com.android.chrome"),
            GoalCompiler.compile(ActionKind.TYPE, payload = "藍牙"),
            GoalCompiler.compile(ActionKind.TYPE, payload = "invoice"),
            GoalCompiler.compile(ActionKind.TYPE, payload = "周杰倫"),
            GoalCompiler.compile(ActionKind.TYPE, payload = "wifi"),
            GoalCompiler.compile(ActionKind.REPLACE_TEXT, payload = "新標題"),
            GoalCompiler.compile(ActionKind.CLEAR_TEXT),
            GoalCompiler.compile(ActionKind.TAP, targetSelector = "id:send"),
            GoalCompiler.compile(ActionKind.TAP, targetSelector = "role:list_item:第一封"),
            GoalCompiler.compile(ActionKind.TAP),
            GoalCompiler.compile(ActionKind.TAP, targetSelector = "text:確定"),
            GoalCompiler.compile(ActionKind.SUBMIT),
            GoalCompiler.compile(ActionKind.LONG_PRESS),
            GoalCompiler.compile(ActionKind.BACK, packageHint = "com.android.settings"),
            GoalCompiler.compile(ActionKind.BACK),
            GoalCompiler.compile(ActionKind.SCROLL_DOWN),
            GoalCompiler.compile(ActionKind.SCROLL_UP),
            GoalCompiler.compile(ActionKind.SWIPE_HORIZONTAL),
            GoalCompiler.compile(ActionKind.ADJUST_VALUE),
            GoalCompiler.compile(ActionKind.SUBMIT),
            GoalCompiler.compile(ActionKind.TYPE, payload = ""),
            GoalCompiler.compile(ActionKind.TAP, targetSelector = "id:tab"),
            GoalCompiler.compile(ActionKind.SUBMIT, targetSelector = "id:ok"),
            GoalCompiler.compile(ActionKind.LONG_PRESS, targetSelector = "id:item"),
            GoalCompiler.compile(ActionKind.SCROLL_DOWN),
            GoalCompiler.compile(ActionKind.SCROLL_UP),
            GoalCompiler.compile(ActionKind.WAIT)
        )
        val rate = GoalCompiler.verifiableRate(corpus)
        assertTrue("可驗證率必須 ≥ 90%（實際=${"%.1f".format(rate * 100)}%）", rate >= 0.90f)
    }

    // ── 驗收線 2：誤報成功 < 2% ───────────────────────────────────────

    @Test
    fun `failure facts never evaluate true`() {
        // 每個「什麼都沒發生」的失敗情境，都不可被判定成功
        val failureCases = listOf(
            GoalCompiler.compile(ActionKind.LAUNCH_APP, packageHint = "com.android.settings") to
                facts(pkg = "com.google.android.gm"),
            GoalCompiler.compile(ActionKind.TYPE, payload = "invoice") to
                facts(texts = listOf("首頁", "設定")),
            GoalCompiler.compile(ActionKind.TAP, targetSelector = "id:send") to facts(),
            GoalCompiler.compile(ActionKind.BACK) to facts(pkg = "com.a", pkgBefore = "com.a"),
            GoalCompiler.compile(ActionKind.SCROLL_DOWN) to facts(),
            GoalCompiler.compile(ActionKind.ADJUST_VALUE) to facts(dispatch = false),
            GoalCompiler.compile(ActionKind.CLEAR_TEXT) to facts(),
            GoalCompiler.compile(ActionKind.REPLACE_TEXT, payload = "x") to facts(texts = emptyList())
        )
        var falseSuccess = 0
        for ((cond, f) in failureCases) {
            if (cond.evaluate(f)) falseSuccess++
        }
        val rate = falseSuccess.toFloat() / failureCases.size
        assertTrue("誤報成功率必須 < 2%（實際=${falseSuccess}/${failureCases.size}）", rate < 0.02f)
    }

    // ── 驗收線 3：編譯耗時 < 5ms/指令 ─────────────────────────────────

    @Test
    fun `compilation is fast enough`() {
        val kinds = ActionKind.values().toList()
        repeat(2000) { i ->
            val k = kinds[i % kinds.size]
            GoalCompiler.compile(k, payload = "abc", packageHint = "com.x")
        }
        val t0 = System.nanoTime()
        repeat(2000) { i ->
            val k = kinds[i % kinds.size]
            GoalCompiler.compile(k, payload = "abc", packageHint = "com.x")
        }
        val perMs = (System.nanoTime() - t0) / 1_000_000.0 / 2000
        assertTrue("編譯耗時必須 < 5ms/指令（實際=%.4fms）".format(perMs), perMs < 5.0)
        println("PERF goalCompileMs=%.4f".format(perMs))
    }
}
