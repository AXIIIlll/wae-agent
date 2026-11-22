package com.wae.agent.core.omega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WAE-Ω 第 3 層：意圖編譯器（NL → Task IR + 靜態檢查）。
 */
class IntentCompilerTest {

    @Before
    fun setUp() {
        IntentCompiler.resetForTest()
        IntentCompiler.loadFromLines(
            listOf(
                "ir\tread-version\t(?i)(?:讀|查看|查).*版本\tREAD:version->\$version",
                "ir\tfill-submit\t(?i)(?:填寫|輸入).*(?:送出|提交)\t" +
                    "WRITE:'範例'->comment|COMMIT:送出",
                "ir\tmissing-var\t(?i)測試缺變數\tWRITE:\$nope->target",
                "ir\tbad-token\t(?i)壞模板\tFLY:away",
                "ir\tnav-dialog\t(?i)打開對話框\tNAV:DIALOG:確定"
            )
        )
    }

    private fun modelWith(vararg fields: FieldSchema): AppModel = AppModel(
        pkg = "com.example",
        states = listOf(
            AppState("home", listOf(1L), setOf(UiOntology.UiType.BUTTON, UiOntology.UiType.DIALOG))
        ),
        fields = fields.toList(),
        risks = listOf(RiskMark(RiskKind.PUBLIC_WRITE, "comment"))
    ).withComputedHash()

    @Test
    fun `read command compiles to single READ step`() {
        val r = IntentCompiler.compile("查一下版本", modelWith(FieldSchema("version", UiOntology.UiType.TEXT_LABEL)))
        assertTrue(r is IntentCompiler.Result.Compiled)
        val plan = (r as IntentCompiler.Result.Compiled).plan
        assertEquals(1, plan.steps.size)
        assertEquals(TaskIrStep.Intent.READ, plan.steps.first().intent)
        assertEquals("version", plan.steps.first().value?.name)
        assertEquals(UiOntology.UiType.TEXT_LABEL, plan.steps.first().target?.type)
        assertTrue(plan.reads.contains("version"))
    }

    @Test
    fun `fill and submit compiles with commit count`() {
        val r = IntentCompiler.compile(
            "填寫並送出",
            modelWith(FieldSchema("comment", UiOntology.UiType.TEXT_FIELD))
        )
        val plan = (r as IntentCompiler.Result.Compiled).plan
        assertEquals(2, plan.steps.size)
        assertEquals(TaskIrStep.Intent.WRITE, plan.steps[0].intent)
        assertEquals(TaskIrStep.Intent.COMMIT, plan.steps[1].intent)
        assertEquals(1, plan.commits)
    }

    @Test
    fun `no template returns unsupported`() {
        val r = IntentCompiler.compile("今天天氣如何", null)
        assertTrue(r is IntentCompiler.Result.Unsupported)
        assertEquals("no_template", (r as IntentCompiler.Result.Unsupported).reason)
    }

    @Test
    fun `missing model field is unsupported`() {
        val r = IntentCompiler.compile("查一下版本", modelWith())
        assertTrue(r is IntentCompiler.Result.Unsupported)
        assertTrue((r as IntentCompiler.Result.Unsupported).reason.contains("field_not_in_model"))
    }

    @Test
    fun `missing dataflow variable is reported`() {
        val r = IntentCompiler.compile("測試缺變數", modelWith(FieldSchema("target", UiOntology.UiType.TEXT_FIELD)))
        assertTrue(r is IntentCompiler.Result.MissingVars)
        assertTrue((r as IntentCompiler.Result.MissingVars).missing.contains("nope"))
    }

    @Test
    fun `provided variable satisfies dataflow`() {
        val r = IntentCompiler.compile(
            "測試缺變數",
            modelWith(FieldSchema("target", UiOntology.UiType.TEXT_FIELD)),
            providedVars = mapOf("nope" to "v")
        )
        assertTrue(r is IntentCompiler.Result.Compiled)
    }

    @Test
    fun `unknown token is unsupported`() {
        val r = IntentCompiler.compile("壞模板", null)
        assertTrue(r is IntentCompiler.Result.Unsupported)
        assertTrue((r as IntentCompiler.Result.Unsupported).reason.contains("unknown_token"))
    }

    @Test
    fun `navigation type checked against model`() {
        val ok = IntentCompiler.compile("打開對話框", modelWith())
        assertTrue(ok is IntentCompiler.Result.Compiled)
        assertEquals(TaskIrStep.Intent.NAVIGATE, (ok as IntentCompiler.Result.Compiled).plan.steps.first().intent)

        // 模型只有 BUTTON/DIALOG，沒有 SLIDER → SLIDER 導覽應被拒
        IntentCompiler.loadFromLines(
            listOf("ir\tnav-slider\t(?i)滑桿\tNAV:SLIDER:音量")
        )
        val bad = IntentCompiler.compile("滑桿", modelWith())
        assertTrue(bad is IntentCompiler.Result.Unsupported)
        assertTrue((bad as IntentCompiler.Result.Unsupported).reason.contains("type_not_in_model"))
    }

    @Test
    fun `version to chat compiles two steps with dataflow`() {
        IntentCompiler.loadFromLines(
            listOf("ir\tv2chat\t(?i)版本.*貼\tREAD:version->\$version|WRITE:\$version->chatInput")
        )
        val r = IntentCompiler.compile(
            "把版本貼到聊天",
            modelWith(
                FieldSchema("version", UiOntology.UiType.TEXT_LABEL),
                FieldSchema("chatInput", UiOntology.UiType.TEXT_FIELD)
            )
        )
        val plan = (r as IntentCompiler.Result.Compiled).plan
        assertEquals(2, plan.steps.size)
        assertEquals("version", plan.steps[1].value?.name)
    }

    @Test
    fun `empty command is unsupported`() {
        assertTrue(IntentCompiler.compile("  ") is IntentCompiler.Result.Unsupported)
        assertTrue(IntentCompiler.templateCount() >= 1)
    }
}
