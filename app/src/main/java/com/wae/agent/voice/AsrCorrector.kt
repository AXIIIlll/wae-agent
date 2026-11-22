package com.wae.agent.voice

import android.content.Context

/**
 * 離線語音常見誤認修正（資料驅動）。
 *
 * 來源：
 *  1. `assets/open_source_ai_databases/ui_asr_corrections.tsv`（wrong<TAB>right）
 *  2. `assets/open_source_ai_databases/stopwords_and_asr_homophones.tsv`
 *     的 `ASR` 行（type<TAB>wrong<TAB>right）
 */
object AsrCorrector {

    private var replacements: List<Pair<String, String>> = emptyList()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val list = ArrayList<Pair<String, String>>()
        runCatching {
            context.assets.open("open_source_ai_databases/ui_asr_corrections.tsv")
                .bufferedReader().useLines { seq ->
                    seq.forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        val p = line.split('\t')
                        if (p.size >= 2 && p[0].isNotBlank() && p[1].isNotBlank()) {
                            list.add(p[0].trim() to p[1].trim())
                        }
                    }
                }
        }
        runCatching {
            context.assets.open("open_source_ai_databases/stopwords_and_asr_homophones.tsv")
                .bufferedReader().useLines { seq ->
                    seq.forEach { raw ->
                        val line = raw.trim()
                        if (!line.startsWith("ASR\t")) return@forEach
                        val p = line.split('\t')
                        if (p.size >= 3 && p[1].isNotBlank() && p[2].isNotBlank()) {
                            list.add(p[1].trim() to p[2].trim())
                        }
                    }
                }
        }
        replacements = list.sortedByDescending { it.first.length }
        loaded = true
    }

    fun correct(context: Context, text: String): String {
        if (text.isBlank()) return text
        ensureLoaded(context)
        return correctWith(text, replacements)
    }

    /** 純函式版（供單元測試）：一次掃描、長條目優先，避免連鎖誤替換 */
    fun correctWith(text: String, list: List<Pair<String, String>>): String {
        if (text.isEmpty()) return text
        val entries = list.filter { it.first.isNotBlank() }.sortedByDescending { it.first.length }
        if (entries.isEmpty()) return text
        val map = entries.toMap()
        val pattern = Regex(entries.joinToString("|") { Regex.escape(it.first) })
        return pattern.replace(text) { m -> map[m.value] ?: m.value }
    }
}
