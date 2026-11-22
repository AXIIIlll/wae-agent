package com.wae.agent.gen5

import android.content.Context
import java.io.File

/**
 * 多輪補槽（B11）：反問之後，把使用者的回答接回原本那句話。
 *
 * 為什麼需要：只反問而不記答案，等於「問了白問」——
 * 使用者回答「小明」，系統還是不知道要傳給誰。
 *
 * 邊界：
 *  - 只在**同一個缺口**（例如「傳訊息」缺對象）成立；不是通用對話記憶。
 *  - 逾時就忘（預設 5 分鐘），避免「上次的問題吃掉這次的指令」。
 */
object FollowUp {

    private const val FILE = "followup_pending.tsv"
    private const val TTL_MS = 5 * 60 * 1000L

    data class Pending(val template: String, val question: String, val createdAt: Long)

    private fun file(context: Context): File = File(context.filesDir, FILE)

    fun remember(context: Context, template: String, question: String) {
        runCatching {
            file(context).writeText(
                "$template\u0001$question\u0001${System.currentTimeMillis()}",
                Charsets.UTF_8
            )
        }
    }

    fun take(context: Context): Pending? {
        val f = file(context)
        if (!f.exists()) return null
        val pending = runCatching {
            val cells = f.readText(Charsets.UTF_8).split('\u0001')
            if (cells.size < 3) return@runCatching null
            Pending(cells[0], cells[1], cells[2].toLongOrNull() ?: 0L)
        }.getOrNull()
        f.delete()
        if (pending == null) return null
        if (System.currentTimeMillis() - pending.createdAt > TTL_MS) return null
        return pending
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * 這句話看起來像「回答」還是「新指令」？
     *
     * 判準刻意保守：**短、且不含任何動作詞**才算回答。
     * 寧可把回答當新指令（會失敗，使用者重講一次），
     * 也不要把新指令當回答（會做出使用者沒要求的事）。
     */
    fun looksLikeAnswer(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 10) return false
        // 「給小明」「傳給小明」也是在回答「要傳給誰」
        if (ANSWER_PREFIXES.any { t.startsWith(it) } && t.length <= 10) return true
        return ANSWER_BLOCKERS.none { t.contains(it) }
    }

    /** 把回答正規化成純值：「給小明」→「小明」，「傳給小明」→「小明」。 */
    fun normalizeAnswer(text: String): String {
        var t = text.trim()
        for (prefix in ANSWER_PREFIXES) {
            if (t.startsWith(prefix)) {
                t = t.removePrefix(prefix).trim()
                break
            }
        }
        return t
    }

    private val ANSWER_PREFIXES = listOf("傳給", "送給", "發給", "給")

    private val ANSWER_BLOCKERS = listOf(
        "打開", "開啟", "關閉", "搜尋", "搜", "傳", "分享", "調", "回", "拍照", "照相",
        "設定", "音量", "亮度", "Wi-Fi", "藍牙", "天氣"
    )
}
