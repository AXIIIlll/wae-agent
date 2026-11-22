package com.wae.core.rule

/**
 * HTN 方法：目的 → 子任務樹（`ui/task_methods.tsv`）。
 *
 * DSL（刻意小，夠用就好）：
 * ```
 * SEQ[cmd:打開$app ; wait:400 ; cmd:在$app裡搜尋$query]
 * CHOICE[SEQ[...] | SEQ[...]]
 * call:A5.1
 * ```
 * - `cmd:` 直接交給既有的 Gen5 管線執行（v1 用這個接線最穩）
 * - `call:` 呼叫另一個目的（遞迴展開）
 * - `wait:N` 等 N 毫秒
 */
sealed class TaskNode {
    data class Seq(val children: List<TaskNode>) : TaskNode()
    data class Choice(val options: List<TaskNode>) : TaskNode()
    data class Cmd(val text: String) : TaskNode()

    /** 操作層節點（`op:INTENT_LAUNCH($app)`）：描述「用哪種操作」，不是一句人話。 */
    data class Op(val call: OpCall) : TaskNode()
    data class Call(val purposeId: String) : TaskNode()
    data class Wait(val ms: Int) : TaskNode()
    data object Empty : TaskNode()
}

data class Method(
    val purpose: String,
    val id: String,
    val priority: Int,
    /** 什麼事實成立時才用這個方法（空字串＝永遠可用）。 */
    val appliesWhen: String,
    val steps: TaskNode
)

object MethodParser {

    fun parse(lines: Sequence<String>): List<Method> {
        val out = ArrayList<Method>()
        for (raw in lines) {
            val line = raw.trimStart('\uFEFF').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val c = line.split('\t')
            if (c.size < 4) continue
            val purpose = c[0].trim()
            val id = c[1].trim()
            val priority = c[2].trim().toIntOrNull() ?: 0
            val applies = c[3].trim()
            val steps = c.getOrNull(4)?.trim().orEmpty()
            if (purpose.isEmpty() || steps.isEmpty()) continue
            val node = runCatching { parseNode(steps) }.getOrNull() ?: continue
            out.add(Method(purpose, id, priority, applies, node))
        }
        return out.sortedByDescending { it.priority }
    }

    fun parseNode(text: String): TaskNode {
        val t = text.trim()
        return when {
            t.startsWith("SEQ[") -> TaskNode.Seq(splitTopLevel(t.removePrefix("SEQ[").removeSuffix("]"), ';').map { parseNode(it) })
            t.startsWith("CHOICE[") -> TaskNode.Choice(splitTopLevel(t.removePrefix("CHOICE[").removeSuffix("]"), '|').map { parseNode(it) })
            t.startsWith("wait:") -> TaskNode.Wait(t.removePrefix("wait:").trim().toIntOrNull() ?: 0)
            t.startsWith("cmd:") -> TaskNode.Cmd(t.removePrefix("cmd:").trim())
            t.startsWith("op:") -> OpCatalog.parse(t.removePrefix("op:").trim())
                ?.let { TaskNode.Op(it) } ?: TaskNode.Empty
            t.startsWith("call:") -> TaskNode.Call(t.removePrefix("call:").trim())
            t.isEmpty() -> TaskNode.Empty
            else -> TaskNode.Cmd(t)
        }
    }

    /** 只在最外層切（括號內的 ';' 或 '|' 不算）。 */
    private fun splitTopLevel(text: String, separator: Char): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        for (i in text.indices) {
            when (text[i]) {
                '[', '(' -> depth++
                ']', ')' -> depth--
                separator -> if (depth == 0) {
                    out.add(text.substring(start, i).trim())
                    start = i + 1
                }
            }
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }
}

/**
 * 把方法樹展開成「要執行的句子」序列（v1 走既有管線），並收集用到的目的。
 *
 * 為什麼是「句子」而不是直接操作：v1 的驗收要能跟現有 114 條案例對照；
 * 用既有管線執行，行為等價性最容易確認。之後把 `cmd:` 換成操作層即可。
 */
object MethodExpander {

    /** 計畫裡的一步：既有管線的句子，或操作層的呼叫。 */
    sealed class Step {
        data class Cmd(val text: String) : Step()
        data class Op(val call: OpCall) : Step()
        data class Wait(val ms: Int) : Step()
    }

    data class Expanded(
        val commands: List<String>,
        val ops: List<OpCall> = emptyList(),
        val purposes: List<String> = emptyList(),
        val notes: List<String> = emptyList(),
        /** 有序步驟（cmd 與 op 可混用；執行時照這個順序）。 */
        val steps: List<Step> = emptyList()
    )

    fun expand(
        method: Method,
        slots: Map<String, String>,
        methodsByPurpose: Map<String, List<Method>>,
        depth: Int = 0
    ): Expanded {
        val commands = ArrayList<String>()
        val ops = ArrayList<OpCall>()
        val purposes = ArrayList<String>()
        val notes = ArrayList<String>()
        val steps = ArrayList<Step>()
        walk(method.steps, slots, methodsByPurpose, depth, commands, ops, purposes, notes, steps)
        return Expanded(commands, ops, purposes, notes, steps)
    }

    private fun walk(
        node: TaskNode,
        slots: Map<String, String>,
        methodsByPurpose: Map<String, List<Method>>,
        depth: Int,
        commands: MutableList<String>,
        ops: MutableList<OpCall>,
        purposes: MutableList<String>,
        notes: MutableList<String>,
        steps: MutableList<Step>
    ) {
        when (node) {
            is TaskNode.Empty -> Unit
            is TaskNode.Wait -> {
                notes.add("wait:${node.ms}")
                steps.add(Step.Wait(node.ms))
            }
            is TaskNode.Cmd -> {
                val text = substitute(node.text, slots)
                commands.add(text)
                steps.add(Step.Cmd(text))
            }
            is TaskNode.Op -> {
                val call = OpCall(node.call.id, node.call.args.map { substitute(it, slots) })
                ops.add(call)
                steps.add(Step.Op(call))
            }
            is TaskNode.Seq -> node.children.forEach { walk(it, slots, methodsByPurpose, depth, commands, ops, purposes, notes, steps) }
            is TaskNode.Choice -> node.options.firstOrNull()?.let { walk(it, slots, methodsByPurpose, depth, commands, ops, purposes, notes, steps) }
            is TaskNode.Call -> {
                if (depth > 4) {
                    notes.add("recursion_guard:${node.purposeId}")
                    return
                }
                val child = methodsByPurpose[node.purposeId]?.firstOrNull()
                if (child == null) {
                    notes.add("no_method:${node.purposeId}")
                    return
                }
                purposes.add(node.purposeId)
                walk(child.steps, slots, methodsByPurpose, depth + 1, commands, ops, purposes, notes, steps)
            }
        }
    }

    /** `$slot` 換成值；沒有值的槽位保留原樣（上層會發現並反問）。 */
    fun substitute(text: String, slots: Map<String, String>): String {
        var out = text
        for ((k, v) in slots) {
            out = out.replace("\$$k", v)
        }
        return out
    }
}
