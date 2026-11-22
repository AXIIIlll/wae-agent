package com.wae.core.predicate

import com.wae.core.model.ControlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 語意模糊偵測的契約測試。
 *
 * 這個模組存在的理由：編譯器**永遠會硬編出一個 Φ**，不會說「我不確定」。
 * 所以「該不該動手」必須由一個獨立的判斷來守。
 */
class AmbiguityDetectorTest {

    private val compiler = NlPredicateCompiler(
        PredicateLexicon(
            toggleWords = setOf("開", "關", "打開", "關掉"),
            onWords = setOf("開", "打開"),
            offWords = setOf("關", "關掉"),
            fillers = setOf("幫我", "請"),
            adjustVerbs = setOf("調"),
            directions = mapOf("亮" to 1, "暗" to -1, "大" to 1, "小" to -1),
            degreeSmall = setOf("一點"),
            degreeLarge = setOf("很多"),
            valueLabels = mapOf("亮度" to "亮度", "音量" to "音量"),
            settingsPkg = "com.android.settings"
        )
    )

    private fun world(vararg labels: String): PredicateWorld = FactsWorld(
        pkg = "com.android.settings",
        controls = labels.mapIndexed { i, l ->
            FactsControl(
                key = "k$i",
                label = l,
                kind = if (l.contains("Wi-Fi") || l.contains("藍牙")) ControlKind.TOGGLE else ControlKind.ROW
            )
        }
    )

    @Test
    fun `clear command is not ambiguous`() {
        val c = compiler.compile("開藍牙")
        val v = AmbiguityDetector.ofCompiled(c, world("藍牙", "Wi-Fi"))
        assertFalse(v.isAmbiguous)
        assertEquals(AmbiguityDetector.Kind.NONE, v.kind)
    }

    @Test
    fun `same target appearing twice on screen is ambiguous`() {
        val c = compiler.compile("開 Wi-Fi")
        // 畫面上有兩個都叫 Wi-Fi 的開關（例如「Wi-Fi」與「Wi-Fi 通話」）
        val v = AmbiguityDetector.ofCompiled(c, world("Wi-Fi", "Wi-Fi 通話"))
        assertTrue(v.isAmbiguous)
        assertEquals(AmbiguityDetector.Kind.MULTIPLE_CANDIDATES, v.kind)
        assertTrue("必須先問，不要動手", v.blocking)
        assertTrue(v.question.contains("Wi-Fi"))
    }

    @Test
    fun `relative adjust without a target is ambiguous`() {
        val c = compiler.compile("調亮一點")   // 有方向、有幅度，但沒有目標
        val v = AmbiguityDetector.ofCompiled(c, world("Wi-Fi"))
        assertTrue(v.isAmbiguous)
        assertEquals(AmbiguityDetector.Kind.UNDEFINED_TARGET, v.kind)
        assertTrue(v.blocking)
    }

    @Test
    fun `relative adjust with a target is clear`() {
        val c = compiler.compile("亮度調亮一點")
        val v = AmbiguityDetector.ofCompiled(c, world("亮度"))
        assertEquals(AmbiguityDetector.Kind.NONE, v.kind)
    }

    @Test
    fun `not compiled at all is unknown not ambiguous`() {
        val c = compiler.compile("這個東西怎麼辦")
        val v = AmbiguityDetector.ofCompiled(c, world("Wi-Fi"))
        assertTrue(v.isAmbiguous)
        assertEquals(AmbiguityDetector.Kind.UNKNOWN_INTENT, v.kind)
        assertTrue(v.blocking)
    }

    @Test
    fun `close decision scores are ambiguous`() {
        val v = AmbiguityDetector.ofDecision(top1 = 0.62f, top2 = 0.58f, labels = listOf("Wi-Fi", "Wi-Fi 通話"))
        assertEquals(AmbiguityDetector.Kind.CLOSE_SCORES, v.kind)
        assertTrue(v.blocking)
    }

    @Test
    fun `clear decision margin is fine`() {
        val v = AmbiguityDetector.ofDecision(top1 = 0.82f, top2 = 0.40f)
        assertEquals(AmbiguityDetector.Kind.NONE, v.kind)
    }

    @Test
    fun `low absolute score asks instead of guessing`() {
        val v = AmbiguityDetector.ofDecision(top1 = 0.30f, top2 = 0.10f)
        assertTrue(v.blocking)
        assertEquals(AmbiguityDetector.Kind.UNDEFINED_TARGET, v.kind)
    }
}
