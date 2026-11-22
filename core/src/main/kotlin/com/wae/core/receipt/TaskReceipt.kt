package com.wae.core.receipt

import com.wae.core.time.Clock
import java.io.File

/** 一步的收據（只存結構與結果，不存文字內容）。 */
data class ReceiptStep(
    val effect: String,
    val means: String,
    val outcome: String,
    val postHeld: Boolean,
    val attribution: String = ""
)

/**
 * 任務收據（C1）：每一次執行都留下一張可讀、可重播的紀錄。
 *
 * 為什麼要有：這是「它有没有騙我」的唯一證據。
 * 也因為有它，反例、路由、世界模型才有共同的資料單位。
 */
data class TaskReceipt(
    val traceId: String,
    val commandHash: String,
    val pkg: String,
    val appVersion: Long,
    val effects: List<String>,
    val steps: List<ReceiptStep>,
    val outcome: String,
    val silentFailures: Int,
    val startedAtMs: Long,
    val endedAtMs: Long
) {

    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0)

    fun encode(): String = buildString {
        append(traceId).append('\t')
        append(commandHash).append('\t')
        append(pkg).append('\t')
        append(appVersion).append('\t')
        append(effects.joinToString(SEP_LIST.toString())).append('\t')
        append(steps.joinToString(SEP_LIST.toString()) { encodeStep(it) }).append('\t')
        append(outcome).append('\t')
        append(silentFailures).append('\t')
        append(startedAtMs).append('\t')
        append(endedAtMs)
    }

    companion object {
        const val COLUMNS = 10
        private const val SEP_LIST = ';'
        private const val SEP_FIELD = '|'

        private fun encodeStep(s: ReceiptStep): String = listOf(
            s.effect, s.means, s.outcome, if (s.postHeld) "1" else "0", s.attribution
        ).joinToString(SEP_FIELD.toString()) { it.replace('\n', ' ').replace('\t', ' ') }

        fun decode(line: String): TaskReceipt? {
            val c = line.split('\t')
            if (c.size < COLUMNS) return null
            val effects = if (c[4].isEmpty()) emptyList() else c[4].split(SEP_LIST)
            val steps = if (c[5].isEmpty()) emptyList() else c[5].split(SEP_LIST).mapNotNull { decodeStep(it) }
            return TaskReceipt(
                traceId = c[0],
                commandHash = c[1],
                pkg = c[2],
                appVersion = c[3].toLongOrNull() ?: 0L,
                effects = effects,
                steps = steps,
                outcome = c[6],
                silentFailures = c[7].toIntOrNull() ?: 0,
                startedAtMs = c[8].toLongOrNull() ?: 0L,
                endedAtMs = c[9].toLongOrNull() ?: 0L
            )
        }

        private fun decodeStep(text: String): ReceiptStep? {
            val p = text.split(SEP_FIELD)
            if (p.size < 4) return null
            return ReceiptStep(
                effect = p[0],
                means = p[1],
                outcome = p[2],
                postHeld = p[3] == "1",
                attribution = p.getOrElse(4) { "" }
            )
        }
    }
}

/**
 * 收據寫入器：一行一張，append-only。
 *
 * 刻意不重寫整檔（append 便宜、斷電只損失最後一行），
 * 查詢／統計交給離線工具。
 */
class ReceiptRecorder(
    private val file: File,
    private val clock: Clock
) {

    init {
        file.parentFile?.mkdirs()
    }

    fun write(receipt: TaskReceipt) {
        com.wae.core.memory.AppendLog.append(
            file = file,
            line = receipt.encode(),
            maxBytes = com.wae.core.memory.AppendLog.RECEIPTS_MAX_BYTES,
            keepLines = com.wae.core.memory.AppendLog.RECEIPTS_KEEP_LINES
        )
    }

    fun all(): List<TaskReceipt> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.mapNotNull { TaskReceipt.decode(it) }.toList()
            }
        }.getOrDefault(emptyList())
    }

    /** 靜默失敗率：回報成功但後置條件不成立的比例。 */
    fun silentFailureRate(): Float {
        val all = all()
        if (all.isEmpty()) return 0f
        val tasksWithSilent = all.count { it.silentFailures > 0 }
        return tasksWithSilent.toFloat() / all.size
    }

    fun nowMs(): Long = clock.nowMs()
}
