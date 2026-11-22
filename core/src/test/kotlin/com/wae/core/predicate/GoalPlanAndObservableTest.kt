package com.wae.core.predicate

import com.wae.core.model.ControlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺口 1（序列目標）與缺口 2（可觀測狀態）的契約測試。
 */
class GoalPlanAndObservableTest {

    /** 測試用的語言詞彙（內容與 `ui_language_words.tsv` 一致）。 */
    @org.junit.Before
    fun installWords() {
        LanguageWords.install(
            separators = listOf("然後", "接著", "之後", "再幫我", "，再", ",再", "再"),
            leads = listOf("首先", "先", "麻煩", "幫我", "請"),
            searchWords = setOf("搜尋", "搜索", "查找", "search", "find"),
            labels = mapOf("share" to "分享", "send" to "送出", "new" to "新增", "save" to "儲存")
        )
    }

    // ── 缺口 1：序列 ──

    @Test
    fun `splitter cuts explicit sequence words`() {
        assertEquals(listOf("開 WiFi", "開藍牙"), ClauseSplitter.split("開 WiFi 然後開藍牙"))
        assertEquals(listOf("開飛航", "開熱點"), ClauseSplitter.split("先開飛航，再開熱點"))
    }

    @Test
    fun `splitter keeps single command intact`() {
        assertEquals(listOf("開 WiFi"), ClauseSplitter.split("開 WiFi"))
        assertEquals(listOf("把亮度調到 40%"), ClauseSplitter.split("把亮度調到 40%"))
    }

    @Test
    fun `plan reports first pending step`() {
        val wifiOn = Phi.Exists(
            listOf(Atom.LabelContains("Wi-Fi"), Atom.KindIs(ControlKind.TOGGLE), Atom.CheckedIs(true))
        )
        val btOn = Phi.Exists(
            listOf(Atom.LabelContains("藍牙"), Atom.KindIs(ControlKind.TOGGLE), Atom.CheckedIs(true))
        )
        val plan = GoalPlan(listOf(GoalPlan.Step(wifiOn, "開 WiFi"), GoalPlan.Step(btOn, "開藍牙")))

        val empty = FactsWorld(
            "com.android.settings",
            listOf(
                FactsControl(key = "w", label = "Wi-Fi", kind = ControlKind.TOGGLE, checked = false),
                FactsControl(key = "b", label = "藍牙", kind = ControlKind.TOGGLE, checked = false)
            )
        )
        assertEquals(0, plan.nextPending(empty))

        val wifiDone = FactsWorld(
            "com.android.settings",
            listOf(
                FactsControl(key = "w", label = "Wi-Fi", kind = ControlKind.TOGGLE, checked = true),
                FactsControl(key = "b", label = "藍牙", kind = ControlKind.TOGGLE, checked = false)
            )
        )
        assertEquals("第一步成立 → 換第二步", 1, plan.nextPending(wifiDone))
        assertFalse(plan.isSatisfied(wifiDone))
    }

    // ── 缺口 2：可觀測狀態 ──

    @Test
    fun `observable atom is answered by the world not the screen`() {
        val wifiOn = Phi.Exists(listOf(Atom.Observable("wifi")))

        // 畫面上什麼都沒有，但系統說 WiFi 開著 → 成立
        val offScreen = FactsWorld(pkg = "com.android.launcher", controls = emptyList(), observables = mapOf("wifi" to true))
        assertTrue(PredicateEval.eval(wifiOn, offScreen))

        val off = FactsWorld(pkg = "com.android.launcher", controls = emptyList(), observables = mapOf("wifi" to false))
        assertFalse(PredicateEval.eval(wifiOn, off))

        // 查不到 → 視為「不知道」，不阻擋（三態語意）
        val unknown = FactsWorld(pkg = "com.android.launcher", controls = emptyList())
        assertTrue("查不到不該阻擋", PredicateEval.eval(wifiOn, unknown))
    }

    @Test
    fun `observable and screen atoms can be combined`() {
        val phi = Phi.Exists(
            listOf(
                Atom.Observable("wifi"),
                Atom.LabelContains("Wi-Fi"),
                Atom.KindIs(ControlKind.TOGGLE),
                Atom.CheckedIs(true)
            )
        )
        val w = FactsWorld(
            "com.android.settings",
            listOf(FactsControl(key = "w", label = "Wi-Fi", kind = ControlKind.TOGGLE, checked = true)),
            observables = mapOf("wifi" to true)
        )
        assertTrue(PredicateEval.eval(phi, w))

        val w2 = FactsWorld(
            "com.android.settings",
            listOf(FactsControl(key = "w", label = "Wi-Fi", kind = ControlKind.TOGGLE, checked = true)),
            observables = mapOf("wifi" to false)
        )
        assertFalse("系統說沒開 → 畫面再像也不成立", PredicateEval.eval(phi, w2))
    }

    @Test
    fun `distance counts unmet observables`() {
        val phi = Phi.Exists(listOf(Atom.Observable("wifi")))
        val unknown = FactsWorld(pkg = "x", controls = emptyList())
        assertEquals("查不到 → 距離 0（不知道不等於失敗）", 0, PredicateEval.distance(phi, unknown))
        assertTrue("只有觀測原子不算過寬", PredicateEval.wellFormed(phi).isEmpty())
    }
}
