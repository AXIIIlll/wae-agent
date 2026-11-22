package com.wae.agent.core.omega

import android.content.Context
import java.io.File

/**
 * WAE-Ω 第 7/8 層：運算子倉庫（OperatorRegistry）。
 *
 * 存放「反統一合成出來的可重用運算子」，可離線分享（純結構、不含內容）：
 *  - `filesDir/omega/operators.txt`（逐行，JVM 可測）
 *  - 同 id 覆蓋並累加 usage
 *  - 上限 300 條，超過丟掉最少使用的
 *
 * 純 Kotlin（Context 只是存檔位置），JVM 可測。
 */
object OperatorRegistry {

    private const val HEADER = "# WAE_OMEGA_OPERATORS v1"
    private const val MAX_OPERATORS = 300

    private val ops = LinkedHashMap<String, Entry>()

    private var appContext: Context? = null

    data class Entry(
        val operator: OperatorSynthesizer.Operator,
        val usage: Int = 0,
        val createdAtMs: Long = 0L
    )

    fun ensureLoaded(context: Context?) {
        if (context != null && appContext == null) appContext = context.applicationContext
        if (ops.isNotEmpty()) return
        synchronized(ops) {
            ops.putAll(loadFromDisk())
        }
    }

    fun resetForTest() {
        synchronized(ops) { ops.clear() }
        appContext = null
    }

    fun count(): Int = synchronized(ops) { ops.size }

    fun all(): List<OperatorSynthesizer.Operator> = synchronized(ops) {
        ops.values.map { it.operator }
    }

    fun save(op: OperatorSynthesizer.Operator, nowMs: Long = 0L): Boolean {
        synchronized(ops) {
            val prev = ops[op.id]
            ops[op.id] = Entry(
                operator = op,
                usage = (prev?.usage ?: 0) + 1,
                createdAtMs = prev?.createdAtMs ?: if (nowMs > 0L) nowMs else System.currentTimeMillis()
            )
            while (ops.size > MAX_OPERATORS) {
                val least = ops.entries.minWithOrNull(
                    compareBy<Map.Entry<String, Entry>> { it.value.usage }
                        .thenBy { it.value.createdAtMs }
                ) ?: break
                ops.remove(least.key)
            }
            return writeToDisk()
        }
    }

    fun recordUsage(id: String): Boolean = synchronized(ops) {
        val e = ops[id] ?: return false
        ops[id] = e.copy(usage = e.usage + 1)
        writeToDisk()
    }

    /** 找出能套用在這條軌跡上的運算子（support 高者優先） */
    fun findApplicable(trace: List<String>): OperatorSynthesizer.Operator? = synchronized(ops) {
        ops.values.map { it.operator }.filter { it.matches(trace) }
            .maxByOrNull { it.support }
    }

    fun exportText(): String = synchronized(ops) {
        val sb = StringBuilder(HEADER).append('\n')
        for (e in ops.values) {
            sb.append("op=")
                .append(esc(e.operator.id)).append('\t')
                .append(esc(e.operator.name)).append('\t')
                .append(e.operator.support).append('\t')
                .append(e.usage).append('\t')
                .append(esc(e.operator.toPatternSpec())).append('\n')
        }
        sb.toString()
    }

    fun importText(text: String): Int {
        if (!text.startsWith(HEADER)) return 0
        var n = 0
        synchronized(ops) {
            for (line in text.lineSequence()) {
                if (!line.startsWith("op=")) continue
                val p = line.removePrefix("op=").split('\t')
                if (p.size < 5) continue
                val spec = unesc(p[4])
                val pattern = spec.split('|').map { t ->
                    if (t.startsWith("$")) OperatorSynthesizer.PatternToken.Param(t.removePrefix("$"))
                    else OperatorSynthesizer.PatternToken.Literal(t)
                }
                val op = OperatorSynthesizer.Operator(
                    id = unesc(p[0]),
                    name = unesc(p[1]),
                    support = p[2].toIntOrNull() ?: 1,
                    pattern = pattern
                )
                ops[op.id] = Entry(op, p[3].toIntOrNull() ?: 0, System.currentTimeMillis())
                n++
            }
            writeToDisk()
        }
        return n
    }

    // ── 磁碟 ──────────────────────────────────────────────────────

    private fun file(): File? = appContext?.let { File(it.filesDir, "omega/operators.txt") }

    private fun writeToDisk(): Boolean {
        val f = file() ?: return true
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(exportText(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    private fun loadFromDisk(): Map<String, Entry> {
        val f = file() ?: return emptyMap()
        if (!f.exists()) return emptyMap()
        return runCatching {
            val out = LinkedHashMap<String, Entry>()
            for (line in f.readText(Charsets.UTF_8).lineSequence()) {
                if (!line.startsWith("op=")) continue
                val p = line.removePrefix("op=").split('\t')
                if (p.size < 5) continue
                val pattern = unesc(p[4]).split('|').map { t ->
                    if (t.startsWith("$")) OperatorSynthesizer.PatternToken.Param(t.removePrefix("$"))
                    else OperatorSynthesizer.PatternToken.Literal(t)
                }
                val op = OperatorSynthesizer.Operator(
                    id = unesc(p[0]),
                    name = unesc(p[1]),
                    support = p[2].toIntOrNull() ?: 1,
                    pattern = pattern
                )
                out[op.id] = Entry(op, p[3].toIntOrNull() ?: 0)
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun esc(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")

    private fun unesc(raw: String): String {
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
