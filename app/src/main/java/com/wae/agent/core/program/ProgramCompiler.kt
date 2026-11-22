package com.wae.agent.core.program

import com.wae.agent.core.automaton.AutomatonEdge
import com.wae.agent.core.automaton.AutomatonPlanner
import org.json.JSONArray
import org.json.JSONObject

/**
 * S16：路徑編譯成位元碼（架構書 §16 S16）。
 *
 * 當某條路徑被執行 ≥ 10 次且成功率 ≥ 95%：
 * ```
 *   [邊1, 邊2, …, 邊N] → bytecode:
 *     OP_GUARD(sel) / OP_TAP(sel) / OP_SETVAL(sel, expr) / OP_WAIT(ms) / OP_VERIFY(post)
 * ```
 * 執行時只剩「取指 → 執行 → 驗證」，**沒有任何決策**。
 * 任何 `OP_VERIFY` 失敗 → 該程式降級，回退到圖規劃。
 */
object ProgramCompiler {

    /** 編譯門檻（架構書：執行 ≥ 10 次、成功率 ≥ 95%） */
    const val MIN_EXECUTIONS = 10
    const val MIN_SUCCESS_RATE = 0.95f

    sealed class Op {
        data class Guard(val selector: String) : Op()
        data class Tap(val selector: String) : Op()
        data class SetValue(val selector: String, val direction: Int) : Op()
        data class Wait(val ms: Int) : Op()
        data class Verify(val post: String) : Op()
    }

    data class Program(
        val pkg: String,
        val entryState: String,
        val goalState: String,
        val ops: List<Op>,
        val executions: Int,
        val successRate: Float,
        val builtAt: Long = 0L
    ) {
        /** 緊湊編碼（可落盤、可比較） */
        fun encode(): String {
            val sb = StringBuilder()
            sb.append("v1;").append(entryState).append(";").append(goalState).append(";")
            sb.append(executions).append(";").append("%.3f".format(successRate)).append(";")
            sb.append(ops.joinToString("|") { op ->
                when (op) {
                    is Op.Guard -> "G:" + op.selector
                    is Op.Tap -> "T:" + op.selector
                    is Op.SetValue -> "S:" + op.selector + ":" + op.direction
                    is Op.Wait -> "W:" + op.ms
                    is Op.Verify -> "V:" + op.post
                }
            })
            return sb.toString()
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("pkg", pkg)
            put("code", encode())
            put("builtAt", builtAt)
        }
    }

    fun decode(pkg: String, code: String): Program? {
        val parts = code.split(";")
        if (parts.size < 6 || parts[0] != "v1") return null
        val ops = ArrayList<Op>()
        val body = parts[5]
        if (body.isNotEmpty()) {
            for (tok in body.split("|")) {
                when {
                    tok.startsWith("G:") -> ops.add(Op.Guard(tok.substring(2)))
                    tok.startsWith("T:") -> ops.add(Op.Tap(tok.substring(2)))
                    tok.startsWith("S:") -> {
                        val seg = tok.substring(2).split(":")
                        if (seg.size >= 2) ops.add(Op.SetValue(seg[0], seg[1].toIntOrNull() ?: 0))
                    }
                    tok.startsWith("W:") -> ops.add(Op.Wait(tok.substring(2).toIntOrNull() ?: 0))
                    tok.startsWith("V:") -> ops.add(Op.Verify(tok.substring(2)))
                }
            }
        }
        return Program(
            pkg = pkg,
            entryState = parts[1],
            goalState = parts[2],
            ops = ops,
            executions = parts[3].toIntOrNull() ?: 0,
            successRate = parts[4].toFloatOrNull() ?: 0f
        )
    }

    fun isEligible(executions: Int, successRate: Float): Boolean =
        executions >= MIN_EXECUTIONS && successRate >= MIN_SUCCESS_RATE

    /**
     * 把一條規劃路徑編譯成位元碼。
     * 每條邊 → `GUARD(選擇器)` + 動作 + `VERIFY(SPINE_CHANGED)`。
     */
    fun compile(
        pkg: String,
        plan: AutomatonPlanner.Plan,
        executions: Int,
        successRate: Float,
        nowMs: Long = System.currentTimeMillis(),
        minExecutions: Int = MIN_EXECUTIONS,
        minSuccessRate: Float = MIN_SUCCESS_RATE
    ): Program? {
        if (executions < minExecutions || successRate < minSuccessRate) return null
        if (plan.edges.isEmpty()) return null
        val ops = ArrayList<Op>()
        for (e in plan.edges) {
            ops.add(Op.Guard(e.selector))
            when (e.op.uppercase()) {
                "TAP" -> ops.add(Op.Tap(e.selector))
                "ADJUST_VALUE" -> ops.add(Op.SetValue(e.selector, 1))
                "TYPE", "REPLACE_TEXT" -> ops.add(Op.SetValue(e.selector, 0))
                else -> ops.add(Op.Tap(e.selector))
            }
            ops.add(Op.Verify("SPINE_CHANGED"))
        }
        return Program(
            pkg = pkg,
            entryState = plan.states.firstOrNull() ?: "",
            goalState = plan.states.lastOrNull() ?: "",
            ops = ops,
            executions = executions,
            successRate = successRate,
            builtAt = nowMs
        )
    }

    fun compileFromEdges(
        pkg: String,
        entryState: String,
        goalState: String,
        edges: List<AutomatonEdge>,
        executions: Int,
        successRate: Float,
        nowMs: Long = System.currentTimeMillis(),
        minExecutions: Int = MIN_EXECUTIONS,
        minSuccessRate: Float = MIN_SUCCESS_RATE
    ): Program? {
        if (executions < minExecutions || successRate < minSuccessRate || edges.isEmpty()) return null
        val plan = AutomatonPlanner.Plan(
            states = listOf(entryState) + edges.map { it.to },
            edges = edges,
            expectedCostMs = edges.sumOf { it.p95Estimate() },
            expectedSuccess = successRate
        )
        return compile(pkg, plan, executions, successRate, nowMs, minExecutions, minSuccessRate)
    }

    fun programsToJson(list: List<Program>): String {
        val arr = JSONArray()
        for (p in list) arr.put(p.toJson())
        return JSONObject().put("schema", 1).put("programs", arr).toString()
    }

    fun programsFromJson(json: String?): List<Program> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val o = JSONObject(json)
            if (o.optInt("schema", 0) != 1) return@runCatching emptyList()
            val arr = o.optJSONArray("programs") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.getJSONObject(i)
                decode(e.optString("pkg", ""), e.optString("code", ""))
            }
        }.getOrDefault(emptyList())
    }
}
