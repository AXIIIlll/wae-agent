package com.wae.core.memory

import java.io.File

/**
 * 只增不減的日誌檔（收據、世界模型轉移）需要上限。
 *
 * 為什麼要有這個：`frames.tsv` 實測長到 12.8 MB（8,764 行）才被發現。
 * 手機上的慢性病不會自己好——使用者不知道要去刪，而且刪了下次又長回來。
 *
 * 策略：append 前檢查大小，超過就只留最近 `keepLines` 行。
 * TSV 的壞行本來就會被解析端跳過，所以截斷是安全的。
 */
object AppendLog {

    fun append(file: File, line: String, maxBytes: Long, keepLines: Int) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > maxBytes) compact(file, keepLines)
            file.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    fun compact(file: File, keepLines: Int) {
        runCatching {
            if (!file.exists()) return
            val kept = file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.toList().takeLast(keepLines)
            }
            file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"), Charsets.UTF_8)
        }
    }

    /** 收據：2 MB／2,000 筆。 */
    const val RECEIPTS_MAX_BYTES = 2L * 1024 * 1024
    const val RECEIPTS_KEEP_LINES = 2_000

    /** 世界模型轉移：4 MB／5,000 筆。 */
    const val TRANSITIONS_MAX_BYTES = 4L * 1024 * 1024
    const val TRANSITIONS_KEEP_LINES = 5_000
}
