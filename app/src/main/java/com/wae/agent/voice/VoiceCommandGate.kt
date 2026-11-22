package com.wae.agent.voice

import android.content.Context
import android.util.Log

/**
 * 語音指令閘門（P0）。
 *
 * 問題：Vosk 對環境噪音也會吐出文字（實測吐過「素人 动用 如果 这个 这个」），
 * 若直接執行會在別人的 App 裡亂點。所以語音結果必須通過兩道檢查才交給管線：
 *  1. 長度合理（2–30 字）
 *  2. 含已知指令詞（來自 `command_lexicon.tsv` 的中文詞條 + `global_tw_cn_app_metadata.tsv` 的 App 別名）
 *
 * 全部資料驅動，不硬編碼詞表。
 */
object VoiceCommandGate {

    private const val TAG = "WAE_VOICE_GATE"
    private var keywords: List<String> = emptyList()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val set = HashSet<String>()
        // 1) 指令詞庫：第一欄是詞（含中文），過濾太短/含標籤字元
        runCatching {
            context.assets.open("open_source_ai_databases/command_lexicon.tsv").bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    val term = line.substringBefore('\t').trim()
                    if (term.length in 2..12 && term.any { it.code in 0x4E00..0x9FFF }) {
                        set.add(term)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "load command_lexicon failed: ${it.message}") }

        // 2) App 別名：第 4 欄（| 分隔）
        runCatching {
            context.assets.open("open_source_ai_databases/global_tw_cn_app_metadata.tsv")
                .bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        if (line.isBlank() || line.startsWith("#")) return@forEach
                        val parts = line.split('\t')
                        if (parts.size >= 4) {
                            parts[3].split('|').forEach { alias ->
                                val a = alias.trim()
                                if (a.length in 2..12 && a.any { it.code in 0x4E00..0x9FFF }) set.add(a)
                            }
                        }
                    }
                }
        }.onFailure { Log.w(TAG, "load app metadata failed: ${it.message}") }

        keywords = set.toList().sortedByDescending { it.length }
        loaded = true
        Log.i(TAG, "gate ready: ${keywords.size} keywords")
    }

    /**
     * 是否像一句可執行的中文指令。
     *
     * 規則（不是字表）：
     *  - 命令詞庫命中 → 可執行
     *  - 或「執行路由」判斷拆解器/Agent 能處理（結構完整目標或 Agent 可接手）→ 放行
     *  - 其餘（噪音、閒聊）→ 視為非指令
     */
    fun isCommandLike(context: Context, text: String): Boolean {
        ensureLoaded(context)
        if (isCommandLikeWith(text, keywords)) return true
        // 規則：拆得出結構完整的目標 → 可執行（例如「打 開 YouTube 搜尋貓咪」）
        val complete = runCatching {
            com.wae.agent.core.ExecutionRouter.hasCompleteGoal(context, text)
        }.getOrDefault(false)
        if (complete) {
            runCatching { Log.i(TAG, "accept (complete goal): $text") }
            return true
        }
        return false
    }

    /** 純函式版（供單元測試） */
    fun isCommandLikeWith(text: String, words: List<String>): Boolean {
        val normalized = text.replace(Regex("\\s+"), "").trim()
        if (normalized.length < 2 || normalized.length > 30) return false
        if (normalized.none { it.code in 0x4E00..0x9FFF }) return false
        val hit = words.firstOrNull { it.isNotBlank() && normalized.contains(it) }
        if (hit == null) {
            runCatching { Log.i(TAG, "reject (no keyword): $text") }
            return false
        }
        runCatching { Log.i(TAG, "accept (keyword=$hit): $text") }
        return true
    }
}
