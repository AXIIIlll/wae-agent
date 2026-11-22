package com.wae.core.worldmodel

import com.wae.core.time.Clock
import java.io.File

/**
 * 轉移紀錄器（E1 的第一步）：把每次執行的 before/after 留下來。
 *
 * 為什麼現在就做：世界模型探針需要資料，而資料**不必另外採**——
 * 每一輪正常的執行本來就是一條 (state, action, next state)。
 * 累積到 2 萬筆再談訓練，成本是零。
 */
class TransitionRecorder(
    private val file: File,
    private val clock: Clock
) {

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            runCatching { file.writeText(TransitionCodec.HEADER + "\n", Charsets.UTF_8) }
        }
    }

    fun record(transition: Transition) {
        com.wae.core.memory.AppendLog.append(
            file = file,
            line = TransitionCodec.encode(transition),
            maxBytes = com.wae.core.memory.AppendLog.TRANSITIONS_MAX_BYTES,
            keepLines = com.wae.core.memory.AppendLog.TRANSITIONS_KEEP_LINES
        )
    }

    fun count(): Int {
        if (!file.exists()) return 0
        return runCatching {
            file.useLines { lines -> lines.count { it.isNotBlank() && !it.startsWith("#") } }
        }.getOrDefault(0)
    }

    fun nowMs(): Long = clock.nowMs()
}
