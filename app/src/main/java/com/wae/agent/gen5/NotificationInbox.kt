package com.wae.agent.gen5

import java.util.ArrayDeque

/**
 * 通知收件箱（C2）：無障礙服務收到的通知，留在記憶體一小段時間。
 *
 * 為什麼要留在記憶體：通知是「事情到底有沒有發生」的免費 ground truth
 * （銀行扣款、訂單成立、訊息送出都會發通知），但只在記憶體、只留最近幾則，
 * 不落盤、不外傳。
 */
object NotificationInbox {

    data class Note(val pkg: String, val text: String, val atMs: Long)

    private const val MAX_NOTES = 30

    private val notes = ArrayDeque<Note>()

    @Synchronized
    fun onEvent(pkg: String, texts: List<CharSequence>?, nowMs: Long = System.currentTimeMillis()) {
        if (pkg.isEmpty()) return
        val body = texts?.joinToString(" ") { it.toString() }.orEmpty()
        if (body.isBlank()) return
        notes.addLast(Note(pkg, body, nowMs))
        while (notes.size > MAX_NOTES) notes.removeFirst()
    }

    /** 這個 App 在 `sinceMs` 之後有沒有發通知。 */
    @Synchronized
    /** 最近的幾則通知（給「有什麼通知」這種查詢用）；格式「App：內容」。 */
    fun recent(limit: Int = 5): List<String> =
        notes.toList().takeLast(limit).map { note -> note.pkg + "：" + note.text.take(60) }

    fun size(): Int = notes.size

    fun latestFor(pkg: String, sinceMs: Long): Note? =
        notes.lastOrNull { it.pkg == pkg && it.atMs >= sinceMs }

    @Synchronized
    fun recentSince(sinceMs: Long): List<Note> = notes.filter { it.atMs >= sinceMs }

    @Synchronized
    fun clear() = notes.clear()
}
