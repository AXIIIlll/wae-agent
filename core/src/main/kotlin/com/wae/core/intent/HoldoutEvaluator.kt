package com.wae.core.intent

import com.wae.core.config.ConfigParser
import com.wae.core.model.Effect

/**
 * 保留句評測（B9）：用**詞庫裡沒有的說法**測意圖層。
 *
 * 為什麼要這一套：用「表上有的句子」驗收，等於自己騙自己。
 * 這裡的句子刻意用不同語序、不同客套詞、不同量詞，只靠角色組裝。
 */
object HoldoutEvaluator {

    data class Case(val command: String, val expectedKey: String)

    data class Report(
        val passed: Int,
        val total: Int,
        val failures: List<String>
    ) {
        val rate: Float get() = if (total == 0) 0f else passed.toFloat() / total
        fun summary(): String = "holdout=$passed/$total rate=${"%.2f".format(java.util.Locale.ROOT, rate)}" +
            if (failures.isEmpty()) "" else " failures=" + failures.joinToString("; ")
    }

    fun parseCases(lines: Sequence<String>): List<Case> {
        val out = ArrayList<Case>()
        for (raw in lines) {
            val line = raw.trimStart('\uFEFF').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cells = line.split('\t')
            if (cells.size < 2) continue
            val command = cells[0].trim()
            val expected = cells[1].trim()
            if (command.isNotEmpty() && expected.isNotEmpty()) out.add(Case(command, expected))
        }
        return out
    }

    /**
     * 跑一輪；`compile` 通常是 `FrameParser.parse` 接 `EffectCompiler.compile`。
     * 只看第一個效果（多效果任務另有一套）。
     */
    fun evaluate(cases: List<Case>, compile: (String) -> List<Effect>): Report {
        var passed = 0
        val failures = ArrayList<String>()
        for (case in cases) {
            val actual = compile(case.command).firstOrNull()?.key() ?: "<none>"
            if (actual == case.expectedKey) {
                passed++
            } else {
                failures.add("${case.command} → $actual（預期 ${case.expectedKey}）")
            }
        }
        return Report(passed, cases.size, failures)
    }

    /** 從 TSV 文字直接跑（給 debug 指令與離線工具用）。 */
    fun evaluateTsv(text: String, compile: (String) -> List<Effect>): Report =
        evaluate(parseCases(text.lineSequence()), compile)

    /** 給設定頁／診斷用的一行；`ConfigParser` 只用來確保引號與欄位觀念一致。 */
    fun looksLikeTsv(text: String): Boolean =
        ConfigParser.parse(text.lineSequence()).isNotEmpty() || text.contains('\t')
}
