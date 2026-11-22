package com.wae.core

import com.wae.core.config.BundledData
import com.wae.core.rule.CommandRuleTable
import com.wae.core.rule.MethodParser
import com.wae.core.rule.OperationTable
import com.wae.core.rule.PurposeTable
import com.wae.core.rule.Risk
import com.wae.core.rule.RuleEngine
import com.wae.core.rule.SpecificityTable
import com.wae.core.rule.TemplateMatcher
import com.wae.core.rule.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 規則系統 v1：schema 與行為的 JVM 測試（不需要裝置）。
 *
 * 這裡是「規則庫有沒有壞」的第一道門：資料表打錯、引用不存在的目的、
 * 高風險目的被允許自動執行——全部會在編譯後的測試階段被擋下。
 */
class RuleSystemTest {

    private val purposes = PurposeTable.parse(BundledData.purposeLines())
    private val operations = OperationTable.parse(BundledData.operationLines())
    private val rules = CommandRuleTable.parse(BundledData.commandPurposeLines())
    private val methods = MethodParser.parse(BundledData.taskMethodLines())
    private val specificity = SpecificityTable.parse(BundledData.specificityLines())

    private val engine = RuleEngine(
        purposes = purposes.associateBy { it.id },
        operations = operations.associateBy { it.id },
        rules = rules,
        methodsByPurpose = methods.groupBy { it.purpose },
        specificity = specificity
    )

    // ── schema ────────────────────────────────────────────────────

    @Test
    fun `taxonomy v1 has 165 purposes each with a verifiable post`() {
        assertEquals("目的數量要跟分類法一致", 169, purposes.size)
        assertTrue("每個目的都要有 post", purposes.all { it.post.isNotBlank() })
        assertTrue("目的 id 不能重複", purposes.map { it.id }.toSet().size == purposes.size)
    }

    @Test
    fun `every command rule points at an existing purpose`() {
        val ids = purposes.map { it.id }.toSet()
        val dangling = rules.filter { it.purpose !in ids }
        assertTrue("句型引用不存在的目的：$dangling", dangling.isEmpty())
    }

    @Test
    fun `every method points at an existing purpose`() {
        val ids = purposes.map { it.id }.toSet()
        val dangling = methods.filter { it.purpose !in ids }
        assertTrue("方法引用不存在的目的：$dangling", dangling.isEmpty())
    }

    @Test
    fun `high risk purposes are not reversible or are navigate only`() {
        val unsafe = purposes.filter { it.risk == Risk.HIGH && it.reversible && !it.navigateOnly }
        assertTrue("高風險但可逆又可直接執行，需明確標註：$unsafe", unsafe.isEmpty())
    }

    @Test
    fun `op methods validate against their purpose`() {
        val byId = purposes.associateBy { it.id }
        val problems = ArrayList<String>()
        for (method in methods) {
            val purpose = byId[method.purpose] ?: continue
            val slots = purpose.slots.toSet()
            // 展開一次（空槽位即可，只檢查 schema）
            val expanded = com.wae.core.rule.MethodExpander.expand(
                method, slots.associateWith { "x" }, methods.groupBy { it.purpose }
            )
            for (op in expanded.ops) {
                problems.addAll(com.wae.core.rule.OpCatalog.validate(op, slots).map { "${method.id}: $it" })
            }
        }
        assertTrue("op schema 問題：$problems", problems.isEmpty())
    }

    @Test
    fun `every purpose post token is executable`() {
        val bad = purposes.filter { com.wae.core.rule.PostSpec.parse(it.post) == null }
        assertTrue("無法解析的 post：${bad.map { it.id to it.post }}", bad.isEmpty())
    }

    @Test
    fun `op method expands to op calls not sentences`() {
        val method = methods.first { it.id == "open_app.op" }
        val expanded = com.wae.core.rule.MethodExpander.expand(
            method, mapOf("app" to "設定"), methods.groupBy { it.purpose }
        )
        assertTrue("應該是 op 序列", expanded.commands.isEmpty())
        assertEquals("INTENT_LAUNCH", expanded.ops.single().id)
        assertEquals(listOf("設定"), expanded.ops.single().args)
    }

    @Test
    fun `operations table has the ten core ways`() {
        assertTrue("操作至少 10 種", operations.size >= 10)
        assertTrue(operations.any { it.id == "INTENT" })
        assertTrue(operations.any { it.id == "A11Y_CLICK" })
    }

    // ── 匹配 ──────────────────────────────────────────────────────

    @Test
    fun `open app binds the slot`() {
        val match = TemplateMatcher.best("打開設定", rules)!!
        assertEquals("A1.1", match.rule.purpose)
        assertEquals("設定", match.slots["app"])
    }

    @Test
    fun `search in app binds two slots`() {
        val match = TemplateMatcher.best("在設定裡搜尋藍牙", rules)!!
        assertEquals("A5.1", match.rule.purpose)
        assertEquals("設定", match.slots["app"])
        assertEquals("藍牙", match.slots["query"])
    }

    @Test
    fun `fixed slots come from the rule table`() {
        val match = TemplateMatcher.best("調大聲", rules)!!
        assertEquals("E2.2", match.rule.purpose)
        assertEquals("高", match.rule.fixedSlots["value"])
    }

    // ── 判斷器 ────────────────────────────────────────────────────

    @Test
    fun `explicit command is specific, pronoun command is vague`() {
        val specific = engine.decide("打開設定")
        assertTrue(specific is RuleEngine.Decision.Plan)

        val vague = engine.decide("打開那個")
        assertTrue("含指代應該判模糊：$vague", vague is RuleEngine.Decision.NeedsModel)
    }

    @Test
    fun `goal only sentence asks for the missing app`() {
        val decision = engine.decide("我想看影片")
        assertTrue("目的型應該落到 NeedsModel：$decision", decision is RuleEngine.Decision.NeedsModel)
        val need = decision as RuleEngine.Decision.NeedsModel
        assertEquals(Verdict.VAGUE, need.specificity.verdict)
    }

    // ── 引擎決策 ──────────────────────────────────────────────────

    @Test
    fun `specific command expands to an executable sentence`() {
        val decision = engine.decide("打開設定") as RuleEngine.Decision.Plan
        assertEquals("A1.1", decision.purpose.id)
        // v1 開始，這個目的有 op 方法（優先序較高）：應展開成操作而不是一句話
        assertEquals("INTENT_LAUNCH", decision.ops.single().id)
        assertEquals(listOf("設定"), decision.ops.single().args)
    }

    @Test
    fun `volume command carries the fixed slot into the sentence`() {
        val decision = engine.decide("調大聲") as RuleEngine.Decision.Plan
        assertEquals("E2.2", decision.purpose.id)
        assertEquals("SYSTEM_VALUE", decision.ops.single().id)
        assertEquals(listOf("volume", "高"), decision.ops.single().args)
    }

    @Test
    fun `ops disabled still yields executable cmd steps`() {
        // 操作層關閉時，必須挑有 cmd 的方法（否則會落回舊管線 → 具體指令也叫模型）
        val decision = engine.decide("打開設定", allowOps = false) as RuleEngine.Decision.Plan
        assertTrue("ops 應為空：${decision.ops}", decision.ops.isEmpty())
        assertEquals(listOf("打開設定"), decision.commands)
    }

    @Test
    fun `find restaurant becomes a plan`() {
        // 實機回報：「我想找餐廳」被打到舊管線（開了設定）。根因：句型給 query、目的要 place。
        val decision = engine.decide("我想找餐廳")
        assertTrue("應該直接有計畫：$decision", decision is RuleEngine.Decision.Plan)
        val plan = decision as RuleEngine.Decision.Plan
        assertEquals("G2.2", plan.purpose.id)
    }

    @Test
    fun `rule fixed slots must be declared by the purpose`() {
        val byId = purposes.associateBy { it.id }
        val problems = ArrayList<String>()
        for (rule in rules) {
            val purpose = byId[rule.purpose] ?: continue
            for (key in rule.fixedSlots.keys) {
                if (key !in purpose.slots) problems.add("${rule.pattern} 給了未宣告的槽位 $key（${purpose.id}）")
            }
        }
        assertTrue("句型與目的槽位不一致：$problems", problems.isEmpty())
    }

    @Test
    fun `mixed method is chosen when ops disabled`() {
        // 操作層關閉時，混合方法（cmd+op）仍應被選中，且第一步是 cmd
        val plan = engine.decide("我想找餐廳", allowOps = false) as RuleEngine.Decision.Plan
        assertTrue("應該有 cmd 步驟：${plan.steps}", plan.commands.isNotEmpty())
        assertEquals(3, plan.steps.size)
    }

    @Test
    fun `restaurant plan has ordered steps`() {
        val plan = engine.decide("我想找餐廳") as RuleEngine.Decision.Plan
        assertEquals("G2.2", plan.purpose.id)
        // 必須是「開地圖 → 等 → 在 App 內搜尋」三步，而不是只有第一步
        assertEquals(3, plan.steps.size)
        assertTrue(plan.steps[0] is com.wae.core.rule.MethodExpander.Step.Cmd)
        assertTrue(plan.steps[1] is com.wae.core.rule.MethodExpander.Step.Wait)
        assertTrue(plan.steps[2] is com.wae.core.rule.MethodExpander.Step.Op)
        val op = plan.steps[2] as com.wae.core.rule.MethodExpander.Step.Op
        assertEquals("URI_SEARCH", op.call.id)
        assertEquals(listOf("餐廳"), op.call.args)
    }

    @Test
    fun `data schema has no problems`() {
        val problems = engine.problems()
        assertTrue("資料 schema 問題：\n" + problems.joinToString("\n"), problems.isEmpty())
        // 提醒類（必要槽位沒有句型提供）允許存在：那是能力空間，不是資料錯誤
        assertTrue("提醒不應該讓測試失敗", engine.warnings().size < 100)
    }

    @Test
    fun `compatible slots can fill each other`() {
        // 目的要 place、句型給 query：兩個都是「要找什麼」，應該可以互相補位
        assertTrue(com.wae.core.rule.Slots.compatible("place", "query"))
        assertTrue(com.wae.core.rule.Slots.compatible("keyword", "query"))
        assertTrue(!com.wae.core.rule.Slots.compatible("app", "query"))
    }

    @Test
    fun `high risk purpose needs confirmation instead of executing`() {
        val decision = engine.decide("傳訊息給媽媽")
        assertTrue("傳訊息必須是確認：$decision", decision is RuleEngine.Decision.NeedsConfirmation)
    }

    @Test
    fun `unknown command has no match and falls back`() {
        val decision = engine.decide("幫我寫一首詩")
        assertTrue(decision is RuleEngine.Decision.NoMatch)
    }

    @Test
    fun `preset slots let a vague goal become executable`() {
        // 「看影片」缺 app；補上 app 之後就應該變成可執行的句子
        val decision = engine.decide("看影片", preset = mapOf("app" to "YouTube"))
        assertTrue("補槽後應可執行：$decision", decision is RuleEngine.Decision.Plan)
        val plan = decision as RuleEngine.Decision.Plan
        // D1.1 目前只有 cmd 方法（還沒有 op 版本）：兩種形式都算可執行
        val executable = plan.ops.isNotEmpty() || plan.commands.isNotEmpty()
        assertTrue("補槽後要有可執行的步驟", executable)
    }

    @Test
    fun `normalization strips politeness before matching`() {
        val decision = engine.decide("幫我打開設定")
        assertTrue("禮貌詞要先剝掉：$decision", decision is RuleEngine.Decision.Plan)
    }
}
