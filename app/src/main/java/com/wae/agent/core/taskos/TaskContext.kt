package com.wae.agent.core.taskos

/**
 * Task OS 的黑板（TaskContext）：跨步驟共享的變數與世界狀態。
 *
 * 這是通用長任務的必要條件之一：沒有資料流，就永遠只能做「一步指令」，
 * 做不了「查到訂單號 → 貼到另一頁」這種真正的工作。
 *
 * 純 Kotlin，JVM 可測。
 */
class TaskContext(
    /** 使用者輸入的原始指令（除錯/續跑顯示用） */
    val command: String = "",
    /** 起始變數（例如從使用者資料帶入） */
    initial: Map<String, String> = emptyMap()
) {
    private val vars = LinkedHashMap<String, String>()
    private val history = ArrayList<StepRecord>()

    init {
        vars.putAll(initial)
    }

    data class StepRecord(
        val nodeId: String,
        val goalText: String,
        val ok: Boolean,
        val collected: Map<String, String>
    )

    fun put(name: String, value: String) {
        if (name.isBlank()) return
        vars[name] = value
    }

    fun get(name: String): String? = vars[name]

    fun has(name: String): Boolean = !vars[name].isNullOrBlank()

    fun remove(name: String) {
        vars.remove(name)
    }

    fun snapshotVars(): Map<String, String> = LinkedHashMap(vars)

    fun putAll(from: Map<String, String>) {
        vars.putAll(from)
    }

    /** 未解析的 `$var`（給使用者反問用） */
    fun missingVars(required: Set<String>): Set<String> =
        required.filter { !has(it) }.toSet()

    /** 代入 `$var`；未定義的變數原樣保留（讓呼叫端知道缺資料） */
    fun resolve(template: String): String {
        if (template.isEmpty() || !template.contains('$')) return template
        return TASK_VAR_RE.replace(template) { m ->
            val name = m.groupValues[1]
            vars[name] ?: m.value
        }
    }

    fun record(step: TaskStep, ok: Boolean, collected: Map<String, String>) {
        history.add(StepRecord(step.nodeId, step.goal.goalText, ok, collected))
    }

    fun historySnapshot(): List<StepRecord> = history.toList()

    /** 最近 n 筆成功（技能編譯用） */
    fun recentSuccesses(n: Int = 8): List<StepRecord> =
        history.filter { it.ok }.takeLast(n)

    fun toLineFormat(): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        sb.append("command=").append(oneLine(command)).append('\n')
        for ((k, v) in vars) {
            sb.append("var=").append(oneLine(k)).append('\t').append(oneLine(v)).append('\n')
        }
        return sb.toString()
    }

    companion object {
        const val HEADER = "# WAE_TASK_CONTEXT v1"

        fun fromLineFormat(text: String): TaskContext? {
            if (!text.startsWith(HEADER)) return null
            var command = ""
            val initial = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                when {
                    line.startsWith("command=") -> command = unescape(line.removePrefix("command="))
                    line.startsWith("var=") -> {
                        val rest = line.removePrefix("var=")
                        val idx = rest.indexOf('\t')
                        if (idx > 0) {
                            initial[unescape(rest.substring(0, idx))] =
                                unescape(rest.substring(idx + 1))
                        }
                    }
                }
            }
            return TaskContext(command, initial)
        }

        private fun oneLine(raw: String): String = raw
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "")

        private fun unescape(raw: String): String {
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    when (val n = raw[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '\\' -> sb.append('\\')
                        else -> sb.append(n)
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
