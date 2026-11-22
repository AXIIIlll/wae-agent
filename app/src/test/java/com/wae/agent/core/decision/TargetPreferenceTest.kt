package com.wae.agent.core.decision

import com.wae.agent.core.ActionKind
import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * B3／B4 的根因修法測試。
 *
 * 核心觀念：**相似度相同 ≠ 等價**。
 * 歧義（要不要打斷使用者）必須定義在「與該動作相關的特徵」上。
 */
class TargetPreferenceTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    private fun cand(
        id: Int,
        viewId: String = "",
        text: String = "",
        className: String = "android.view.View",
        editable: Boolean = false,
        clickable: Boolean = true
    ) = CandidateOption(
        markId = id,
        nodeStableSig = id.toLong(),
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = intArrayOf(0, id * 100, 200, id * 100 + 80),
        coreText = text,
        weldedDescription = text,
        hybridSimScore = 0.97f,
        isClickable = clickable,
        isEditable = editable,
        viewId = viewId,
        className = className
    )

    @Before
    fun setUp() {
        TargetPreference.resetForTest()
        TargetPreference.loadFromLines(asset("ui_target_preference.tsv").readLines(Charsets.UTF_8))
        assertTrue(TargetPreference.ruleCount() > 0)
    }

    // ── 設定頁三方同分的真實案例 ─────────────────────────────────────

    @Test
    fun `real text field outranks search entry and voice button`() {
        val plate = cand(1, viewId = "search_plate", text = "")
        val voice = cand(2, viewId = "search_voice_btn", text = "語音搜尋")
        val field = cand(3, viewId = "search_src_text", text = "搜尋", clickable = false)

        val sField = TargetPreference.score(ActionKind.TYPE, field)
        val sPlate = TargetPreference.score(ActionKind.TYPE, plate)
        val sVoice = TargetPreference.score(ActionKind.TYPE, voice)

        assertTrue("真正輸入框必須最高（field=$sField plate=$sPlate voice=$sVoice）", sField > sPlate)
        assertTrue("也要高於語音鈕（field=$sField voice=$sVoice）", sField > sVoice)
        // 註：真實 id `search_plate` 與 `search_voice_btn` 都含 search，
        //     在「搜尋入口」這個維度上本來就同級——這不需要澄清，因為真正的輸入框已經明顯勝出。
    }

    @Test
    fun `near tie on similarity is not ambiguous when preference differs`() {
        val plate = cand(1, viewId = "search_plate")
        val field = cand(2, viewId = "search_src_text", clickable = false)
        assertEquals("原始相似度假設相同", 0.97f, field.hybridSimScore, 0.0f)
        assertFalse(
            "偏好明顯不同 → 不該打斷使用者澄清（B3 迴歸）",
            TargetPreference.isEquivalent(ActionKind.TYPE, plate, field)
        )
    }

    @Test
    fun `equivalent candidates still ask the user`() {
        val a = cand(1, text = "美式")
        val b = cand(2, text = "拿鐵")
        assertTrue(
            "偏好完全相同 → 才是真的要澄清",
            TargetPreference.isEquivalent(ActionKind.TYPE, a, b)
        )
    }

    // ── B4 階梯：要輸入但只有入口 ───────────────────────────────────

    @Test
    fun `search entry needs a ladder step`() {
        val entry = cand(1, viewId = "search_plate")
        assertTrue(TargetPreference.needsEntryLadder(ActionKind.TYPE, entry))
    }

    @Test
    fun `real editable field needs no ladder`() {
        val field = cand(1, viewId = "search_src_text", editable = true)
        assertFalse(TargetPreference.needsEntryLadder(ActionKind.TYPE, field))
    }

    @Test
    fun `ladder only applies to type goals`() {
        val entry = cand(1, viewId = "search_plate")
        assertFalse("點擊目標不需要階梯", TargetPreference.needsEntryLadder(ActionKind.TAP, entry))
    }

    @Test
    fun `non interactive text does not trigger ladder`() {
        val label = cand(1, text = "搜尋結果", clickable = false)
        assertFalse(TargetPreference.needsEntryLadder(ActionKind.TYPE, label))
    }

    // ── 其它動作 ───────────────────────────────────────────────────

    @Test
    fun `tap prefers clickable and penalizes editable`() {
        val button = cand(1, viewId = "btn_ok", text = "確定", clickable = true)
        val field = cand(2, viewId = "et_comment", text = "留言", editable = true)
        assertTrue(
            TargetPreference.score(ActionKind.TAP, button) > TargetPreference.score(ActionKind.TAP, field)
        )
    }

    @Test
    fun `bootstrap defaults keep behaviour when asset is missing`() {
        TargetPreference.resetForTest()
        // 未載入資料表 → 使用內建 bootstrap
        val field = cand(1, viewId = "search_src_text", clickable = false)
        val voice = cand(2, viewId = "search_voice_btn", text = "語音搜尋")
        assertTrue(TargetPreference.score(ActionKind.TYPE, field) > TargetPreference.score(ActionKind.TYPE, voice))
    }
}
