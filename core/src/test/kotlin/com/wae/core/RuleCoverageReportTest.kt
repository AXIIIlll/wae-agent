package com.wae.core

import com.wae.core.config.BundledData
import com.wae.core.rule.CommandRuleTable
import com.wae.core.rule.MethodParser
import com.wae.core.rule.OperationTable
import com.wae.core.rule.PurposeTable
import com.wae.core.rule.RuleEngine
import com.wae.core.rule.SpecificityTable
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 規則覆蓋率報告（離線 gate G2／G3）。
 *
 * 為什麼要用測試產生報告：這樣「量測」跟「正式邏輯」是同一份程式，
 * 不會出現「Python 重寫一套 matcher，結果跟 App 行為不一致」的經典問題。
 *
 * 產出：`bench/rule_coverage.tsv`（每條案例一列＋SUMMARY）
 */
class RuleCoverageReportTest {

    private val engine: RuleEngine by lazy {
        RuleEngine(
            purposes = PurposeTable.parse(BundledData.purposeLines()).associateBy { it.id },
            operations = OperationTable.parse(BundledData.operationLines()).associateBy { it.id },
            rules = CommandRuleTable.parse(BundledData.commandPurposeLines()),
            methodsByPurpose = MethodParser.parse(BundledData.taskMethodLines()).groupBy { it.purpose },
            specificity = SpecificityTable.parse(BundledData.specificityLines()),
            tradMap = RuleEngine.tradMapOf(BundledData.tradMapLines())
        )
    }

    private fun caseFiles(): List<File> = listOf(
        "../tools/gen5_baseline20.tsv",
        "../tools/gen5_phrasings.tsv",
        "../tools/gen5_longphrases.tsv",
        "../tools/gen5_suite4.tsv"
    ).map { File(it) }

    @Test
    fun `rule coverage report is generated`() {
        val rows = ArrayList<String>()
        rows.add("# suite\tcommand\texpect\tdecision\tpurpose\tconsistent")
        var total = 0
        var matched = 0
        var consistent = 0

        for (file in caseFiles()) {
            if (!file.exists()) continue
            val suite = file.nameWithoutExtension
            for (raw in file.readLines(Charsets.UTF_8)) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cells = line.split('\t')
                if (cells.size < 2) continue
                val command = cells[0].trim()
                val expect = cells[1].trim()
                if (command.isEmpty()) continue

                val decision = engine.decide(command)
                val (kind, purposeId) = when (decision) {
                    is RuleEngine.Decision.Plan -> "PLAN" to decision.purpose.id
                    is RuleEngine.Decision.NeedsConfirmation -> "CONFIRM" to decision.purpose.id
                    is RuleEngine.Decision.NeedsModel -> "NEED_MODEL" to (decision.purpose?.id ?: "-")
                    is RuleEngine.Decision.NoMatch -> "NO_MATCH" to "-"
                }
                val isMatch = kind != "NO_MATCH"
                val isConsistent = when (expect) {
                    "SUCCESS" -> kind == "PLAN"
                    // 資訊型目的（查詢／複製／通知）本來就會回 ABSTAIN(info)，算一致
                    "ABSTAIN" -> kind != "PLAN" || purposeId.startsWith("B1") ||
                        purposeId.startsWith("A7") || purposeId == "A6.1" ||
                        purposeId == "E8.1" || purposeId == "D3.3"
                    else -> true
                }
                total++
                if (isMatch) matched++
                if (isConsistent) consistent++
                rows.add(listOf(suite, command, expect, kind, purposeId, if (isConsistent) 1 else 0).joinToString("\t"))
            }
        }

        val out = File("../bench/rule_coverage.tsv")
        out.parentFile?.mkdirs()
        val matchRate = if (total == 0) 0 else matched * 100 / total
        val planRate = if (total == 0) 0 else consistent * 100 / total
        rows.add("SUMMARY\tcases=$total\tmatched=$matched($matchRate%)\tconsistent=$consistent($planRate%)")

        out.writeText(rows.joinToString("\n") + "\n", Charsets.UTF_8)

        assertTrue("要有案例可以量", total > 0)
        // v1 的門檻：句型覆蓋要夠高（不要求 100%，缺口要能列出來補）
        assertTrue("句型匹配率過低：$matchRate%（看 bench/rule_coverage.tsv）", matchRate >= 80)
        assertTrue("計畫一致性過低：$planRate%", planRate >= 80)
    }
}
