package com.wae.agent.core.taskos

import android.content.Context
import java.io.File

/**
 * 任務檢查點（CheckpointStore）：把長任務的進度落盤，讓中斷後可以續跑。
 *
 * 沒有這個，30 步任務只要切一次 App／服務被回收，就全部重來——這是通用長任務
 * 與「一次性指令」最大的差別之一。
 *
 * 存檔格式刻意用逐行文字（不用 org.json）：Android 的 org.json 在 JVM 單元測試裡
 * 會拋 "not mocked"，而這個模組必須能被單元測試完整覆蓋。
 *
 * 檔案位置：`filesDir/taskos/<planId>.task`（每任務一份，成功/放棄後刪除）。
 * 保留上限 [MAX_FILES]，避免長期累積。
 *
 * 純 Kotlin（Context 只是存檔位置），JVM 可測：context 為 null 時走記憶體模式。
 */
object CheckpointStore {

    const val HEADER = "# WAE_TASK_CHECKPOINT v1"
    private const val MAX_FILES = 20

    /** 記憶體模式（context == null）與測試用 */
    private val memory = HashMap<String, String>()

    private var appContext: Context? = null

    fun ensureLoaded(context: Context?) {
        if (context != null && appContext == null) appContext = context.applicationContext
    }

    fun resetForTest() {
        synchronized(memory) { memory.clear() }
        appContext = null
    }

    fun dir(): File? = appContext?.let { File(it.filesDir, "taskos") }

    fun save(cmdKey: String, text: String): Boolean {
        val safe = sanitize(cmdKey)
        synchronized(memory) { memory[safe] = text }
        val d = dir() ?: return true
        return runCatching {
            if (!d.exists()) d.mkdirs()
            File(d, "$safe.task").writeText(text, Charsets.UTF_8)
            prune(d)
            true
        }.getOrDefault(false)
    }

    fun load(cmdKey: String): String? {
        val safe = sanitize(cmdKey)
        synchronized(memory) { memory[safe] }?.let { return it }
        val d = dir() ?: return null
        val f = File(d, "$safe.task")
        if (!f.exists()) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun clear(cmdKey: String): Boolean {
        val safe = sanitize(cmdKey)
        synchronized(memory) { memory.remove(safe) }
        val d = dir() ?: return true
        return runCatching {
            val f = File(d, "$safe.task")
            if (f.exists()) f.delete() else true
        }.getOrDefault(false)
    }

    fun clearAll(): Boolean {
        synchronized(memory) { memory.clear() }
        val d = dir() ?: return true
        return runCatching {
            d.listFiles()?.forEach { it.delete() }
            true
        }.getOrDefault(false)
    }

    /** 暫停中任務的識別（同一指令同時只保留一份） */
    fun keyOf(command: String): String = "cmd-" + Integer.toHexString(command.hashCode())

    private fun prune(d: File) {
        val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_FILES) return
        files.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun sanitize(raw: String): String {
        val sb = StringBuilder(raw.length.coerceAtMost(64))
        for (c in raw.take(64)) {
            sb.append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_')
        }
        return if (sb.isEmpty()) "task" else sb.toString()
    }
}
