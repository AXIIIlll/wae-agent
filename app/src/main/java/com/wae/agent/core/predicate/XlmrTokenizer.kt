package com.wae.agent.core.predicate

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * XLM-R（SentencePiece **Unigram**）分詞器——只為了餵 cross-encoder。
 *
 * 為什麼要自己寫：專案裡已有的 `BertWordPieceTokenizer` 是 WordPiece，
 * XLM-R 用的是 SentencePiece Unigram，兩者詞表與切法都不同。
 *
 * 為什麼不直接吃 `tokenizer.json`（16.3 MB）：手機上解析那棵 JSON 要 1–2 秒、
 * 峰值 ~50 MB。這裡用離線轉好的 `xlmr_vocab.tsv`（6.7 MB），一次線性掃描即可。
 *
 * 演算法：SentencePiece 的 Viterbi（最大分數切分），
 * 對應 `pre_tokenizer = WhitespaceSplit + Metaspace(add_prefix_space=true)`。
 */
object XlmrTokenizer {

    private const val ASSET_VOCAB = "tokenizer/xlmr_vocab.tsv"
    private const val MAX_PIECE = 16
    private const val UNK_PENALTY = -12f

    const val CLS = 0L
    const val PAD = 1L
    const val SEP = 2L
    const val UNK = 3L

    @Volatile private var idOf: HashMap<String, Int>? = null
    @Volatile private var scoreOf: FloatArray? = null
    @Volatile private var ready = false
    private val loading = AtomicBoolean(false)

    fun isReady(): Boolean = ready

    fun ensureLoaded(context: Context): Boolean {
        if (ready) return true
        if (!loading.compareAndSet(false, true)) return false
        return try {
            val f = ensureAssetCopy(context, ASSET_VOCAB, "xlmr_vocab.tsv")
            val map = HashMap<String, Int>(300_000)
            val scores = ArrayList<Float>(300_000)
            f.bufferedReader(Charsets.UTF_8).use { br: BufferedReader ->
                br.forEachLine { line ->
                    if (line.isEmpty()) return@forEachLine
                    val t1 = line.indexOf('\t')
                    if (t1 <= 0) return@forEachLine
                    val t2 = line.indexOf('\t', t1 + 1)
                    val piece = line.substring(0, t1)
                    val id = line.substring(t1 + 1, if (t2 > 0) t2 else line.length).toIntOrNull()
                        ?: return@forEachLine
                    val sc = if (t2 > 0) line.substring(t2 + 1).toFloatOrNull() ?: 0f else 0f
                    map[piece] = id
                    while (scores.size <= id) scores.add(0f)
                    scores[id] = sc
                }
            }
            idOf = map
            scoreOf = scores.toFloatArray()
            ready = true
            true
        } catch (t: Throwable) {
            android.util.Log.w("WAE_XLMR", "load failed: ${t.message}")
            false
        } finally {
            loading.set(false)
        }
    }

    /** 單句 → ids（已含 `<s>` 與 `</s>`）。 */
    fun encode(text: String, maxLen: Int = 48): LongArray {
        val pieces = segment(text)
        val body = pieces.take((maxLen - 2).coerceAtLeast(1))
        val out = LongArray(body.size + 2)
        out[0] = CLS
        body.forEachIndexed { i, id -> out[i + 1] = id.toLong() }
        out[out.size - 1] = SEP
        return out
    }

    /** 成對 → `<s> a </s></s> b </s>`（XLM-R 的 pair 格式）。 */
    fun encodePair(a: String, b: String, maxLen: Int = 64): LongArray {
        val pa = segment(a).take((maxLen - 4) / 2)
        val pb = segment(b).take((maxLen - 4) / 2)
        val out = ArrayList<Long>(pa.size + pb.size + 4)
        out.add(CLS)
        pa.forEach { out.add(it.toLong()) }
        out.add(SEP)
        out.add(SEP)
        pb.forEach { out.add(it.toLong()) }
        out.add(SEP)
        return out.toLongArray()
    }

    /** Viterbi 最大分數切分（回傳 vocab id 序列；未知字元給 `<unk>`）。 */
    fun segment(text: String): List<Int> {
        val map = idOf ?: return emptyList()
        val scores = scoreOf ?: return emptyList()
        if (text.isEmpty()) return emptyList()

        // Metaspace：空白 → ▁，並在最前面補一個 ▁
        val norm = buildString(text.length + 1) {
            append('▁')
            for (ch in text) append(if (ch == ' ') '▁' else ch)
        }

        val n = norm.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        val backId = IntArray(n + 1) { -1 }
        best[0] = 0f

        for (i in 1..n) {
            val lo = (i - MAX_PIECE).coerceAtLeast(0)
            for (j in lo until i) {
                if (best[j] == Float.NEGATIVE_INFINITY) continue
                val piece = norm.substring(j, i)
                val id = map[piece]
                val gain = if (id != null) scores.getOrElse(id) { 0f } else UNK_PENALTY
                val cand = best[j] + gain
                if (cand > best[i]) {
                    best[i] = cand
                    back[i] = j
                    backId[i] = id ?: UNK.toInt()
                }
            }
        }

        val out = ArrayList<Int>(n)
        var i = n
        while (i > 0) {
            val j = back[i]
            if (j < 0) { out.add(UNK.toInt()); i -= 1 } else {
                out.add(backId[i])
                i = j
            }
        }
        out.reverse()
        return out
    }

    private fun ensureAssetCopy(context: Context, asset: String, name: String): File {
        val out = File(context.filesDir, name)
        val expected = runCatching { context.assets.openFd(asset).length }.getOrDefault(-1L)
        if (out.exists() && (expected <= 0 || out.length() == expected)) return out
        context.assets.open(asset).use { input ->
            out.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
        }
        return out
    }
}
