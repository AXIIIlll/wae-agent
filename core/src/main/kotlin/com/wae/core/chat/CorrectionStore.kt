package com.wae.core.chat

/**
 * 修正庫：使用者改一次，就永久生效，而且會變成回歸測試。
 *
 * 一行一筆：`正規化句子<TAB>正確動作<TAB>次數`
 * 檢索時權重最高（預設 ×10），排在最前面。
 */
data class Correction(val utterance: String, val action: String, val hits: Int = 1)

class CorrectionStore(rows: List<Correction> = emptyList()) {

    private val map = LinkedHashMap<String, Correction>()

    init {
        for (row in rows) map[key(row.utterance)] = row
    }

    private fun key(text: String): String = normalize(text)

    fun add(utterance: String, action: String): Correction {
        val k = key(utterance)
        val previous = map[k]
        val updated = Correction(
            utterance = utterance.trim(),
            action = action.trim(),
            hits = (previous?.hits ?: 0) + 1
        )
        map[k] = updated
        return updated
    }

    fun lookup(utterance: String): Correction? = map[key(utterance)]

    fun rows(): List<Correction> = map.values.toList()

    fun toLines(): List<String> = rows().map { "${it.utterance}\t${it.action}\t${it.hits}" }

    /** 轉成檢索文件（權重高，讓它排在前面）。 */
    fun toDocs(weight: Float = 10f): List<KnowledgeDoc> = rows().map {
        KnowledgeDoc(
            id = "C-" + it.utterance.hashCode().toString(16),
            kind = "correction",
            text = "使用者曾說「${it.utterance}」，正確做法是：${it.action}",
            weight = weight
        )
    }

    companion object {
        fun normalize(text: String): String =
            text.lowercase().replace(Regex("\\s+"), "").trim('，', ',', '。', '.', '！', '!', '?', '？')

        fun parse(lines: Sequence<String>): CorrectionStore {
            val rows = ArrayList<Correction>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 2) continue
                val utterance = c[0].trim()
                val action = c[1].trim()
                if (utterance.isEmpty() || action.isEmpty()) continue
                rows.add(Correction(utterance, action, c.getOrNull(2)?.trim()?.toIntOrNull() ?: 1))
            }
            return CorrectionStore(rows)
        }
    }
}
