package com.wae.agent.gen5

import android.content.Context
import com.wae.agent.core.nlp.MiniLmEncoder
import com.wae.agent.core.nlp.MiniLmPostProcess
import com.wae.core.match.Similarity

/**
 * 目標解析階梯的「語意階」（B8）：用內建 MiniLM 算文字相似度。
 *
 * 三條紀律：
 *  1. 模型沒載入就回 0（上層自然退回前兩階），不要假裝有分數。
 *  2. 任何錯誤都回 0，不讓 embedding 失敗中斷任務。
 *  3. full／lite 都能跑：沒有模型時的行為與「沒有這一階」完全一樣。
 */
class MiniLmSimilarity(
    private val context: Context,
    private val cacheSize: Int = DEFAULT_CACHE
) : Similarity {

    private val cache = object : LinkedHashMap<String, FloatArray>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>): Boolean =
            size > cacheSize
    }

    override fun score(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        return runCatching {
            if (!MiniLmEncoder.ensureLoaded(context)) return 0f
            val va = vectorOf(a) ?: return 0f
            val vb = vectorOf(b) ?: return 0f
            MiniLmPostProcess.toSimilarity01(MiniLmPostProcess.cosine(va, vb))
        }.getOrDefault(0f)
    }

    private fun vectorOf(text: String): FloatArray? {
        cache[text]?.let { return it }
        val v = MiniLmEncoder.encode(listOf(text)).firstOrNull() ?: return null
        cache[text] = v
        return v
    }

    private companion object {
        const val DEFAULT_CACHE = 128
    }
}
