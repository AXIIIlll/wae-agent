package com.wae.agent.core.nlu

import com.wae.agent.core.ActionKind
import com.wae.agent.core.WaeV26PipelineOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * B1／B2 的根因修法測試。
 *
 * 這些測試刻意用「多個變體」而不是單一案例：
 * 根因修法的價值在於「換一句話也不會再犯」，所以每個規則都要有句型變體。
 */
class GrammarAndActionTableTest {

    private fun asset(name: String): File {
        val candidates = listOf(
            File("../app/src/main/assets/open_source_ai_databases/$name"),
            File("app/src/main/assets/open_source_ai_databases/$name"),
            File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
        )
        return candidates.first { it.exists() }
    }

    @Before
    fun setUp() {
        GrammarParticles.resetForTest()
        ActionSelectorTable.resetForTest()
        GrammarParticles.loadFromLines(asset("ui_grammar_particles.tsv").readLines(Charsets.UTF_8))
        ActionSelectorTable.loadFromLines(asset("ui_action_selector_map.tsv").readLines(Charsets.UTF_8))
        assertTrue("語法助詞表應載入", GrammarParticles.prefixCount() > 0)
        assertTrue("動作對應表應載入", ActionSelectorTable.ruleCount() > 0)
    }

    // ── B1：App 片語要吃乾淨（多句型）────────────────────────────────

    @Test
    fun `app phrase strips preposition and locative particle`() {
        assertEquals("搜尋藍牙", GrammarParticles.stripAppPhrase("在設定中搜尋藍牙", "設定"))
        assertEquals("搜尋invoice", GrammarParticles.stripAppPhrase("在gmail搜尋invoice", "gmail"))
        assertEquals("開啟藍牙", GrammarParticles.stripAppPhrase("從設定內開啟藍牙", "設定"))
        assertEquals("搜尋", GrammarParticles.stripAppPhrase("在設定裡面搜尋", "設定"))
        assertEquals("把亮度調高", GrammarParticles.stripAppPhrase("在顯示中把亮度調高", "顯示"))
    }

    @Test
    fun `launch-only phrase leaves empty residue`() {
        assertEquals("", GrammarParticles.stripAppPhrase("打開設定", "設定"))
        assertEquals("", GrammarParticles.stripAppPhrase("在設定", "設定"))
        assertEquals("", GrammarParticles.stripAppPhrase("到設定", "設定"))
    }

    @Test
    fun `non particle words are preserved`() {
        // 「顯示」不是助詞，不能被吃掉，否則「進入顯示設定」會失去導覽意圖
        // stripAppPhrase 只吃「App 片語」；「進入顯示」不是助詞，必須保留
        assertEquals(
            "進入顯示把亮度調高一點",
            GrammarParticles.stripAppPhrase("進入顯示設定把亮度調高一點", "設定")
        )
    }

    @Test
    fun `unknown alias returns original text`() {
        assertEquals("在設定中搜尋藍牙", GrammarParticles.stripAppPhrase("在設定中搜尋藍牙", "不存在"))
    }

    // ── B2：動作 = f(動詞類別, 受詞型別) ────────────────────────────────

    @Test
    fun `launch verb with app object launches`() {
        assertEquals(
            ActionKind.LAUNCH_APP,
            ActionSelectorTable.resolve(
                ActionSelectorTable.VerbClass.LAUNCH,
                ActionSelectorTable.ObjectType.APP
            )
        )
        assertEquals(
            ActionKind.LAUNCH_APP,
            ActionSelectorTable.resolve(
                ActionSelectorTable.VerbClass.LAUNCH,
                ActionSelectorTable.ObjectType.EMPTY
            )
        )
    }

    @Test
    fun `launch verb with non app object becomes tap`() {
        assertEquals(
            "「把深色模式打開」必須是點擊，不是啟動 App",
            ActionKind.TAP,
            ActionSelectorTable.resolve(
                ActionSelectorTable.VerbClass.LAUNCH,
                ActionSelectorTable.ObjectType.NON_APP
            )
        )
    }

    @Test
    fun `object type detection`() {
        assertEquals(
            ActionSelectorTable.ObjectType.EMPTY,
            ActionSelectorTable.objectTypeOf("", "設定")
        )
        assertEquals(
            ActionSelectorTable.ObjectType.APP,
            ActionSelectorTable.objectTypeOf("打開計算機", "計算機")
        )
        assertEquals(
            ActionSelectorTable.ObjectType.NON_APP,
            ActionSelectorTable.objectTypeOf("把深色模式", "設定")
        )
    }

    @Test
    fun `verb class mapping covers legacy codes`() {
        assertEquals(ActionSelectorTable.VerbClass.LAUNCH, ActionSelectorTable.verbClassOf('o'))
        assertEquals(ActionSelectorTable.VerbClass.TAP, ActionSelectorTable.verbClassOf('c'))
        assertEquals(ActionSelectorTable.VerbClass.TYPE, ActionSelectorTable.verbClassOf('t'))
        assertEquals(ActionSelectorTable.VerbClass.BACK, ActionSelectorTable.verbClassOf('b'))
        assertEquals(ActionSelectorTable.VerbClass.TAP, ActionSelectorTable.verbClassOf(null))
    }

    // ── 端到端：拆解結果（載入資料表後） ──────────────────────────────

    @Test
    fun `toggle by open verb is no longer a launch goal`() {
        val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals("在設定把深色模式打開")
        val g = goals.first()
        assertNotEquals("B2 迴歸：不可再被當成 LAUNCH_APP", ActionKind.LAUNCH_APP, g.actionKind)
    }

    @Test
    fun `opening a real app still launches`() {
        val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals("打開計算機")
        assertEquals(ActionKind.LAUNCH_APP, goals.first().actionKind)
    }

    @Test
    fun `search inside app keeps payload clean`() {
        val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals("在設定中搜尋藍牙")
        val g = goals.first()
        assertEquals(ActionKind.TYPE, g.actionKind)
        assertEquals("藍牙", g.inputPayload.trim())
        assertTrue("目標文字不該殘留助詞：${g.goalText}", !g.goalText.startsWith("中"))
    }

    @Test
    fun `compound navigate plus adjust still splits`() {
        val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals("進入顯示設定把亮度調高一點")
        assertEquals(2, goals.size)
        assertEquals(ActionKind.LAUNCH_APP, goals[0].actionKind)
        assertEquals(ActionKind.ADJUST_VALUE, goals[1].actionKind)
    }
}
