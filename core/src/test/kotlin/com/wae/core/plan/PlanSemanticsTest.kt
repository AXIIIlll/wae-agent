package com.wae.core.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二層計畫語意的契約測試：前置／後置條件、計畫變數、明確度判定。
 *
 * 這三件事都是「行為改變」的來源，所以先把規則釘死在測試裡，
 * 之後接真機時才不會「改了行為卻說不出為什麼」。
 */
class PlanSemanticsTest {

    private val facts = PlanCondition.Facts(
        packageName = "com.android.settings",
        fingerprint = "fp2",
        labels = listOf("Wi-Fi", "藍牙", "飛航模式"),
        checked = mapOf("Wi-Fi" to true, "藍牙" to false),
        previousFingerprint = "fp1"
    )

    // ── 條件 ──────────────────────────────────────────────────

    @Test
    fun `empty condition always holds`() {
        assertTrue(PlanCondition.holds("", facts))
    }

    @Test
    fun `package condition is case insensitive`() {
        assertTrue(PlanCondition.holds("pkg=com.android.settings", facts))
        assertTrue(PlanCondition.holds("pkg=COM.ANDROID.SETTINGS", facts))
        assertFalse(PlanCondition.holds("pkg=com.android.chrome", facts))
    }

    @Test
    fun `label condition looks for a visible option`() {
        assertTrue(PlanCondition.holds("label:藍牙", facts))
        assertFalse(PlanCondition.holds("label:歷史紀錄", facts))
    }

    @Test
    fun `changed compares against the previous fingerprint`() {
        assertTrue(PlanCondition.holds("changed", facts))
        assertFalse(PlanCondition.holds("changed", facts.copy(fingerprint = "fp1")))
        // 沒有前一幀時不能假裝「變了」
        assertFalse(PlanCondition.holds("changed", facts.copy(previousFingerprint = "")))
    }

    @Test
    fun `checked condition compares the switch state`() {
        assertTrue(PlanCondition.holds("checked:Wi-Fi=true", facts))
        assertFalse(PlanCondition.holds("checked:Wi-Fi=false", facts))
        assertTrue(PlanCondition.holds("checked:藍牙=false", facts))
        // 不存在的開關 → false（不是「剛好通過」）
        assertFalse(PlanCondition.holds("checked:飛航模式=true", facts))
    }

    @Test
    fun `unknown condition fails instead of silently passing`() {
        assertFalse(PlanCondition.holds("whatever:foo", facts))
    }

    // ── 變數 ──────────────────────────────────────────────────

    @Test
    fun `variables are substituted in targets and args`() {
        val vars = PlanVars()
        vars.put("app", "Chrome")
        vars.put("query", "貓咪")
        assertEquals("Chrome", vars.substitute("\$app"))
        assertEquals("在 Chrome 搜尋貓咪", vars.substitute("在 \$app 搜尋\$query"))
    }

    @Test
    fun `missing variables stay visible instead of becoming empty`() {
        val vars = PlanVars()
        vars.put("app", "Chrome")
        assertEquals("\$unknown 與 Chrome", vars.substitute("\$unknown 與 \$app"))
    }

    @Test
    fun `blank values are ignored so a later good value can win`() {
        val vars = PlanVars()
        vars.put("app", "  ")
        vars.put("app", "Chrome")
        assertEquals("Chrome", vars.substitute("\$app"))
    }

    // ── 明確度 ────────────────────────────────────────────────

    @Test
    fun `clear when frame app and target are all present`() {
        val v = Ambiguity.decide(
            Ambiguity.Signals(frameType = "FIND", appResolved = true, targetExtracted = true, lexiconCoverage = 0.9f)
        )
        assertEquals(Ambiguity.Verdict.CLEAR, v)
    }

    @Test
    fun `unknown when nothing can be bound`() {
        val v = Ambiguity.decide(
            Ambiguity.Signals(frameType = null, appResolved = false, targetExtracted = false, lexiconCoverage = 0.1f)
        )
        assertEquals(Ambiguity.Verdict.UNKNOWN, v)
    }

    @Test
    fun `weak when app and target are known but the wording is unfamiliar`() {
        val v = Ambiguity.decide(
            Ambiguity.Signals(frameType = null, appResolved = true, targetExtracted = true, lexiconCoverage = 0.1f)
        )
        assertEquals(Ambiguity.Verdict.WEAK, v)
    }

    @Test
    fun `no frame but familiar wording still counts as clear`() {
        val v = Ambiguity.decide(
            Ambiguity.Signals(frameType = null, appResolved = true, targetExtracted = true, lexiconCoverage = 0.8f)
        )
        assertEquals(Ambiguity.Verdict.CLEAR, v)
    }
}
