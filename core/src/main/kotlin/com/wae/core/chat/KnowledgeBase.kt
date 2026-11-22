package com.wae.core.chat

/**
 * 檢索語料（模型自己查的資料庫）。
 *
 * 設計原則：**管線每次都查，不靠模型記得查**。
 * 這裡只負責「文件 + 檢索」，不做意圖判斷——判斷交給模型（或規則快路徑）。
 */
data class KnowledgeDoc(
    val id: String,
    /** purpose / op / app / experience / correction */
    val kind: String,
    val text: String,
    /** 修正庫的權重比較高。 */
    val weight: Float = 1f
)

/**
 * 極簡檢索：字元 bigram TF-IDF + 餘弦。
 *
 * 為什麼不用 embedding：先驗證「每次查、強制引用」這條路是否有效；
 * 之後可換成 embedding，介面不變（`retrieve` 回同樣的 id 與分數）。
 */
class KnowledgeBase(docs: List<KnowledgeDoc> = emptyList()) {

    private val docs: List<KnowledgeDoc> = docs.filter { it.text.isNotBlank() }
    private val df = HashMap<String, Int>()
    private val docVectors: List<Map<String, Double>> = buildVectors()

    private fun buildVectors(): List<Map<String, Double>> {
        val vectors = ArrayList<Map<String, Double>>(docs.size)
        for (doc in docs) {
            val grams = grams(doc.text)
            vectors.add(grams)
            for (g in grams.keys) df[g] = (df[g] ?: 0) + 1
        }
        return vectors
    }

    /** 回傳前 k 篇（id、kind、text、分數），分數 0..1。 */
    fun retrieve(query: String, topK: Int = 3): List<Result> {
        if (docs.isEmpty()) return emptyList()
        val q = grams(query)
        if (q.isEmpty()) return emptyList()
        val qVec = weigh(q)
        val scored = ArrayList<Result>(docs.size)
        for ((index, doc) in docs.withIndex()) {
            val dVec = weigh(docVectors[index])
            val score = cosine(qVec, dVec)
            if (score > 0.0) scored.add(Result(doc.id, doc.kind, doc.text, (score * doc.weight).toFloat()))
        }
        return scored.sortedByDescending { it.score }.take(topK)
    }

    fun size(): Int = docs.size

    data class Result(val id: String, val kind: String, val text: String, val score: Float)

    private fun grams(text: String): Map<String, Double> {
        val cleaned = text.lowercase().replace(Regex("\\s+"), "")
        if (cleaned.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>()
        for (i in 0 until cleaned.length - 1) {
            val g = cleaned.substring(i, i + 2)
            out[g] = (out[g] ?: 0.0) + 1.0
        }
        // 單字也留一份，短句（例：讚）才檢索得到
        for (ch in cleaned) {
            val g = ch.toString()
            out[g] = (out[g] ?: 0.0) + 0.5
        }
        return out
    }

    private fun weigh(vec: Map<String, Double>): Map<String, Double> {
        val n = docs.size.toDouble().coerceAtLeast(1.0)
        val out = HashMap<String, Double>(vec.size)
        for ((gram, tf) in vec) {
            val docFreq = (df[gram] ?: 1).toDouble()
            val idf = Math.log((n + 1.0) / (docFreq + 0.5)) + 1.0
            out[gram] = (1.0 + Math.log(tf)) * idf
        }
        return out
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for ((gram, va) in a) {
            na += va * va
            b[gram]?.let { dot += va * it }
        }
        for (vb in b.values) nb += vb * vb
        if (na <= 0.0 || nb <= 0.0) return 0.0
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    companion object {
        /** 檢索結果給模型看的區塊（每篇截斷，避免吃 token）。 */
        fun render(results: List<Result>, limit: Int = 3, maxChars: Int = 180): String {
            if (results.isEmpty()) return ""
            val lines = results.take(limit).mapIndexed { index, r ->
                val tag = "P" + (index + 1)
                "$tag [${r.kind}] ${r.text.replace("\n", " ").take(maxChars)}"
            }
            return "可檢索到的相關資料：\n" + lines.joinToString("\n")
        }
    }
}
