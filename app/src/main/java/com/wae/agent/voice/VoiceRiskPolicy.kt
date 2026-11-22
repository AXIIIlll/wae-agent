package com.wae.agent.voice

import android.content.Context
import android.util.Log

/**
 * 語音意圖風險分級（Phase 0 / A5、D2）。
 *
 * 三級：
 *  - SAFE    ：可直接執行
 *  - CONFIRM ：必須先出現「我聽到：…」確認卡
 *  - BLOCK   ：語音不可自動執行（金流/不可逆），一律轉打字讓使用者自己按
 *
 * 詞表在 `assets/open_source_ai_databases/ui_voice_risk.tsv`（資料驅動）。
 */
object VoiceRiskPolicy {

    enum class Risk { SAFE, CONFIRM, BLOCK }

    private var confirmWords: List<String> = emptyList()
    private var blockWords: List<String> = emptyList()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val confirm = ArrayList<String>()
        val block = ArrayList<String>()
        runCatching {
            context.assets.open("open_source_ai_databases/ui_voice_risk.tsv")
                .bufferedReader().useLines { seq ->
                    seq.forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        val parts = line.split('\t')
                        if (parts.size < 2) return@forEach
                        val word = parts[0].trim()
                        if (word.isEmpty()) return@forEach
                        when (parts[1].trim().uppercase()) {
                            "BLOCK" -> block.add(word)
                            "CONFIRM" -> confirm.add(word)
                        }
                    }
                }
        }.onFailure { Log.w("WAE_RISK", "load voice risk failed: ${it.message}") }
        confirmWords = confirm.sortedByDescending { it.length }
        blockWords = block.sortedByDescending { it.length }
        loaded = true
    }

    fun classify(context: Context, text: String): Risk {
        ensureLoaded(context)
        return classifyWith(text, blockWords, confirmWords)
    }

    /** 純函式版（供單元測試）：詞表由呼叫端提供 */
    fun classifyWith(text: String, block: List<String>, confirm: List<String>): Risk {
        val norm = text.replace(Regex("\\s+"), "")
        if (norm.isEmpty()) return Risk.SAFE
        if (block.any { it.isNotBlank() && norm.contains(it) }) return Risk.BLOCK
        if (confirm.any { it.isNotBlank() && norm.contains(it) }) return Risk.CONFIRM
        return Risk.SAFE
    }

    /** 信心門檻：低於此值一律要求確認（Vosk 回報 <0 表示沒有信心資訊） */
    const val CONFIDENCE_THRESHOLD = 0.65f
}
