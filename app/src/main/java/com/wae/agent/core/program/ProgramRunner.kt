package com.wae.agent.core.program

import com.wae.agent.core.automaton.Automaton
import com.wae.agent.core.automaton.AutomatonEdge
import com.wae.agent.core.goal.GoalCompiler
import com.wae.agent.core.goal.PageFacts

/**
 * S16 執行器：位元碼程式的「取指 → 執行 → 驗證」。
 *
 * 這一層**不做任何決策**：只檢查守衛、派發動作、驗證後置條件。
 * 任何一個 OP_VERIFY 失敗 → 該程式降級（回退到圖規劃）。
 *
 * 之所以要把執行與決策分開：決策（感知＋打分＋規劃）是成本大頭，
 * 而經過 ≥ 10 次驗證的熱路徑其實不需要再決策。
 */
object ProgramRunner {

    data class Facts(
        val presentSelectors: Set<String> = emptySet(),
        val spineChanged: Boolean = false,
        val pkg: String = "",
        val texts: List<String> = emptyList()
    )

    data class StepResult(val index: Int, val op: ProgramCompiler.Op, val ok: Boolean, val note: String)

    data class Verdict(val ok: Boolean, val failedAt: Int, val reason: String, val trace: List<StepResult>)

    /** 乾跑（不碰裝置）：檢查整支程式的守衛是否都成立 */
    fun dryRun(program: ProgramCompiler.Program, facts: Facts): Verdict {
        val trace = ArrayList<StepResult>(program.ops.size)
        for ((i, op) in program.ops.withIndex()) {
            when (op) {
                is ProgramCompiler.Op.Guard -> {
                    val ok = op.selector.isEmpty() || facts.presentSelectors.any { matches(it, op.selector) }
                    trace.add(StepResult(i, op, ok, if (ok) "guard ok" else "guard missing"))
                    if (!ok) return Verdict(false, i, "GUARD_FAILED(${op.selector})", trace)
                }
                is ProgramCompiler.Op.Tap -> {
                    val ok = op.selector.isEmpty() || facts.presentSelectors.any { matches(it, op.selector) }
                    trace.add(StepResult(i, op, ok, if (ok) "tap ready" else "tap target missing"))
                    if (!ok) return Verdict(false, i, "TAP_TARGET_MISSING", trace)
                }
                is ProgramCompiler.Op.SetValue -> {
                    val ok = op.selector.isEmpty() || facts.presentSelectors.any { matches(it, op.selector) }
                    trace.add(StepResult(i, op, ok, if (ok) "value target ready" else "value target missing"))
                    if (!ok) return Verdict(false, i, "SETVAL_TARGET_MISSING", trace)
                }
                is ProgramCompiler.Op.Wait -> trace.add(StepResult(i, op, true, "wait ${op.ms}ms"))
                is ProgramCompiler.Op.Verify -> {
                    val cond = GoalCompiler.compile(com.wae.agent.core.ActionKind.TAP)
                    val ok = cond.evaluate(PageFacts(pkg = facts.pkg, spineChanged = facts.spineChanged, texts = facts.texts))
                    trace.add(StepResult(i, op, ok, if (ok) "verify ok" else "verify failed"))
                    if (!ok) return Verdict(false, i, "VERIFY_FAILED(${op.post})", trace)
                }
            }
        }
        return Verdict(true, -1, "program ok", trace)
    }

    private fun matches(present: String, want: String): Boolean {
        if (present == want) return true
        val key = want.substringBefore(':')
        return present.startsWith("$key:") || present.contains(want)
    }

    /** 程式是否值得執行（乾跑通過且非空） */
    fun isExecutable(verdict: Verdict): Boolean = verdict.ok && verdict.trace.isNotEmpty()

    /** 決策次數：位元碼模式下一律為 0（這是 S16 的關鍵指標） */
    fun decisionCount(verdict: Verdict): Int = 0
}
