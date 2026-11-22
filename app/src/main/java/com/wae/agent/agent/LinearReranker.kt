package com.wae.agent.agent

import android.content.Context
import org.json.JSONObject

/**
 * 訓練式 reranker（M3）：特徵式 logistic regression，權重來自
 * `assets/agent/reranker_linear.json`（由 tools/reranker/train_reranker.py 產生）。
 *
 * 特徵與 Python 端一致：exact / contains / char_overlap / len_ratio / bigram_jaccard /
 * clickable / editable / is_row / is_edit。
 */
object LinearReranker {

    private var weights: DoubleArray? = null
    private var bias: Double = 0.0
    private var features: List<String> = emptyList()
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return weights != null
        loaded = true
        return runCatching {
            val text = context.assets.open("agent/reranker_linear.json")
                .bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            if (obj.optString("type") != "logistic") return false
            val arr = obj.getJSONArray("features")
            val names = ArrayList<String>()
            for (i in 0 until arr.length()) names.add(arr.getString(i))
            val w = obj.getJSONObject("weights")
            val vec = DoubleArray(names.size) { i -> w.optDouble(names[i], 0.0) }
            features = names
            weights = vec
            bias = obj.optDouble("bias", 0.0)
            true
        }.getOrDefault(false)
    }

    /** 回傳 0..1 分數；未載入時回 null。 */
    fun score(target: String, element: ObservedElement): Float? {
        val w = weights ?: return null
        val f = featurize(target, element)
        var z = bias
        for (i in f.indices) z += f[i] * w.getOrElse(i) { 0.0 }
        val p = 1.0 / (1.0 + Math.exp(-z))
        return p.toFloat().coerceIn(0f, 1f)
    }

    fun isLoaded(): Boolean = weights != null

    private fun featurize(target: String, element: ObservedElement): DoubleArray {
        val t = normalize(target)
        val c = normalize(element.text)
        val exact = if (t == c && t.isNotEmpty()) 1.0 else 0.0
        val contains = if (t.isNotEmpty() && (c.contains(t) || t.contains(c))) 1.0 else 0.0
        val overlap = if (t.isEmpty()) 0.0 else t.count { c.contains(it) }.toDouble() / t.length
        val lenRatio = if (maxOf(t.length, c.length) == 0) 0.0 else
            minOf(t.length, c.length).toDouble() / maxOf(t.length, c.length)
        val jac = bigramJaccard(t, c)
        val cls = element.type.lowercase()
        return doubleArrayOf(
            exact,
            contains,
            overlap,
            lenRatio,
            jac,
            if (element.clickable) 1.0 else 0.0,
            if (element.editable) 1.0 else 0.0,
            if (cls.contains("row")) 1.0 else 0.0,
            if (cls.contains("edit")) 1.0 else 0.0
        )
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("\\s+"), "")

    private fun bigramJaccard(a: String, b: String): Double {
        val sa = bigrams(a)
        val sb = bigrams(b)
        if (sa.isEmpty() && sb.isEmpty()) return 0.0
        val inter = sa.count { sb.contains(it) }
        val union = sa.size + sb.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    private fun bigrams(s: String): Set<String> {
        if (s.length < 2) return if (s.isEmpty()) emptySet() else setOf(s)
        val out = HashSet<String>()
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }
}
