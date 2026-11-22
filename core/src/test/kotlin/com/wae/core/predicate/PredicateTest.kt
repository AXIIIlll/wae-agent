package com.wae.core.predicate

import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.ControlRange
import com.wae.core.model.ControlState
import com.wae.core.model.Frame
import com.wae.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 述詞層的單元測試（純 JVM，不需要 Android）。
 *
 * 這裡驗的是「通用性」的四根柱子：
 *  1. eval 對同一幀必得同一結果（可重放）
 *  2. distance 單調（成立後為 0；缺的原子越多，距離越大）
 *  3. 同一個述詞形狀可以套在不同標籤上（開 WiFi／開熱點共用形狀）
 *  4. well-formed 與 reachable 能在「動手前」判斷成敗
 */
class PredicateTest {

    private fun control(
        key: String,
        label: String,
        kind: ControlKind = ControlKind.ROW,
        checked: Boolean? = null,
        enabled: Boolean = true,
        range: ControlRange? = null,
        text: String? = null
    ) = Control(
        key = key,
        kind = kind,
        label = label,
        textHash = "",
        bounds = RectI(0, 0, 10, 10),
        enabled = enabled,
        state = ControlState(on = checked, checked = checked, text = text),
        range = range
    )

    private fun frame(vararg controls: Control, pkg: String = "com.android.settings") =
        Frame(fp = "fp:t", pkg = pkg, appVersion = 1L, controls = controls.toList())

    // ── 1. 同一個形狀，換標籤就是新目標（通用性的核心）──

    @Test
    fun `toggle shape works for any label without new code`() {
        val f = frame(
            control("k1", "Wi-Fi", ControlKind.TOGGLE, checked = false),
            control("k2", "藍牙", ControlKind.TOGGLE, checked = false),
            control("k3", "熱點", ControlKind.TOGGLE, checked = false)
        )
        val world = f.asWorld()

        // 同一個述詞形狀 P.toggle(label, true)
        val wifiOn = P.toggle("Wi-Fi", true)
        val btOn = P.toggle("藍牙", true)
        val hotspotOn = P.toggle("熱點", true)

        for (phi in listOf(wifiOn, btOn, hotspotOn)) {
            assertFalse("初始全部是關的，不該成立", PredicateEval.eval(phi, world))
            assertEquals("距離 1（只差 checked 原子）", 1, PredicateEval.distance(phi, world))
        }

        // 只打開熱點 → 只有熱點那個述詞成立
        val after = frame(
            control("k1", "Wi-Fi", ControlKind.TOGGLE, checked = false),
            control("k2", "藍牙", ControlKind.TOGGLE, checked = false),
            control("k3", "熱點", ControlKind.TOGGLE, checked = true)
        ).asWorld()

        assertTrue(PredicateEval.eval(hotspotOn, after))
        assertFalse(PredicateEval.eval(wifiOn, after))
        assertFalse(PredicateEval.eval(btOn, after))
    }

    // ── 2. 同一個控制項 vs 不同控制項（避免假成功）──

    @Test
    fun `label and state must be on the same control`() {
        // 有一列寫著「藍牙」但它是 ROW；另有一個無關開關是開的
        val f = frame(
            control("row", "藍牙", ControlKind.ROW),
            control("sw", "自動同步", ControlKind.TOGGLE, checked = true)
        )
        val world = f.asWorld()
        val phi = P.toggle("藍牙", true)

        assertFalse("不同元素不能湊出目標", PredicateEval.eval(phi, world))
        assertTrue("距離至少 1", PredicateEval.distance(phi, world) >= 1)
    }

    // ── 3. 數值目標：比例而非原始值 ──

    @Test
    fun `slider target uses normalized fraction`() {
        val f = frame(
            control("s1", "亮度", ControlKind.SLIDER, range = ControlRange(0f, 255f, 102f)) // 40%
        )
        val world = f.asWorld()

        assertTrue(PredicateEval.eval(P.slider("亮度", 0.39f, 0.41f), world))
        assertFalse(PredicateEval.eval(P.slider("亮度", 0.69f, 0.71f), world))
        assertEquals(1, PredicateEval.distance(P.slider("亮度", 0.69f, 0.71f), world))
    }

    // ── 4. 邏輯組合 ──

    @Test
    fun `and or not compose`() {
        val world = frame(
            control("s1", "亮度", ControlKind.SLIDER, range = ControlRange(0f, 255f, 51f))
        ).asWorld()

        val dark = P.slider("亮度", 0f, 0.21f)
        val bright = P.slider("亮度", 0.79f, 1f)

        assertTrue(PredicateEval.eval(dark, world))
        assertFalse(PredicateEval.eval(bright, world))
        assertTrue(PredicateEval.eval(P.not(bright), world))
        assertFalse(PredicateEval.eval(P.and(dark, bright), world))
        assertTrue(PredicateEval.eval(P.or(dark, bright), world))
        assertEquals("And 的距離是相加", PredicateEval.distance(P.and(dark, bright), world), 1)
        assertEquals("Or 的距離取最小", 0, PredicateEval.distance(P.or(dark, bright), world))
    }

    // ── 5. 套件層 ──

    @Test
    fun `package predicates`() {
        val world = frame(pkg = "com.android.settings").asWorld()
        assertTrue(PredicateEval.eval(P.pkg("com.android.settings"), world))
        assertFalse(PredicateEval.eval(P.pkg("com.android.chrome"), world))
        assertTrue(PredicateEval.eval(Phi.PkgIsNot("com.android.chrome"), world))
        assertFalse("空的 pkg 不能被當成『不是某 App』", PredicateEval.eval(Phi.PkgIsNot("x"), frame(pkg = "").asWorld()))
    }

    // ── 6. 靜態檢查 ──

    @Test
    fun `well formed rejects contradictory and overwide predicates`() {
        val contradictory = Phi.Exists(
            listOf(Atom.LabelContains("藍牙"), Atom.CheckedIs(true), Atom.CheckedIs(false))
        )
        assertTrue(PredicateEval.wellFormed(contradictory).isNotEmpty())

        val overWide = Phi.Exists(listOf(Atom.KindIs(ControlKind.TOGGLE)))
        assertTrue("沒有文字原子＝過寬", PredicateEval.wellFormed(overWide).isNotEmpty())

        val ok = P.toggle("藍牙", true)
        assertTrue(PredicateEval.wellFormed(ok).isEmpty())
    }

    @Test
    fun `reachable needs the target to exist somewhere`() {
        val seen = listOf(
            frame(control("r1", "網路與網際網路", ControlKind.ROW), pkg = "com.android.settings").asWorld(),
            frame(control("r2", "已連接的裝置", ControlKind.ROW), pkg = "com.android.settings").asWorld()
        )
        assertFalse(
            "歷史與當前畫面都沒見過「熱點」→ 不可達（寧可說不知道，不要瞎跑）",
            PredicateEval.reachable(P.toggle("熱點", true), seen)
        )
        val seenWithHotspot = seen + frame(
            control("h1", "熱點", ControlKind.TOGGLE, checked = false),
            pkg = "com.android.settings"
        ).asWorld()
        assertTrue(
            "一旦歷史裡出現過（只是還沒開），就視為可達",
            PredicateEval.reachable(P.toggle("熱點", true), seenWithHotspot)
        )
    }

    // ── 7. 可重放（I3）──

    @Test
    fun `eval is pure and replayable`() {
        val f = frame(control("k1", "Wi-Fi", ControlKind.TOGGLE, checked = true))
        val phi = P.toggle("Wi-Fi", true)
        val first = PredicateEval.eval(phi, f.asWorld())
        repeat(5) {
            assertTrue("同一幀必須同結果", first == PredicateEval.eval(phi, f.asWorld()))
        }
        // 離線快照（FactsWorld）與真 Frame 必須同結果 → 規劃與驗證共用一套標準
        assertEquals(first, PredicateEval.eval(phi, FactsWorld.from(f)) as Any)
    }

    // ── 8. 引導式動作選擇的原料 ──

    @Test
    fun `rank controls by unmet atoms`() {
        val world = frame(
            control("a", "一般設定", ControlKind.ROW),
            control("b", "藍牙", ControlKind.ROW),          // 文字對、型別不對
            control("c", "藍牙", ControlKind.TOGGLE, checked = false) // 只差 checked
        ).asWorld()

        val ranked = PredicateEval.rankControls(
            listOf(Atom.LabelContains("藍牙"), Atom.KindIs(ControlKind.TOGGLE), Atom.CheckedIs(true)),
            world
        )
        assertEquals("c", ranked.first().first.key)
        assertEquals(1, ranked.first().second)
    }
}
