package com.wae.agent.core.predicate

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.wae.agent.core.nlp.BertWordPieceTokenizer
import com.wae.core.predicate.TextFold
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 璆萄?銝剜?隤?蝺函Ⅳ?剁?`pai-bert-tiny-zh`嚗pache-2.0嚗? *
 * ?箔?暻潛摰?378 MB ???芋??箔???琿閰望撟孵??云瘚芾祥?? * ?? **3.18 M ? / 3.08 MB嚗nt8嚗?* ??encoder 撠梯雲隞伐?
 *  - ?璅???Ｘ?蝐斗?敶勗?????征??**?鋆捱**嚗? *  - ?????餈???憿?**?格???斗**嚗? *
 * 摰?捱蝑芋??頛?嚗??胯??芋???蹂誨?? */
object TinyBert {

    private const val ASSET_MODEL = "models/pai-bert-tiny-zh-int8.onnx"
    private const val ASSET_VOCAB = "tokenizer/bge_vocab.txt"
    private const val MAX_LEN = 48

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: BertWordPieceTokenizer? = null
    private val loading = AtomicBoolean(false)

    /** 璅∪??臬撌脣?剁?瘝?撠勗? false嚗?怎垢敹??芷???嚗?*/
    fun isReady(): Boolean = session != null

    fun ensureLoaded(context: Context): Boolean {
        if (session != null) return true
        if (!loading.compareAndSet(false, true)) return false
        return try {
            val modelFile = ensureAssetCopy(context, ASSET_MODEL, "pai_tiny_bert_int8.onnx")
            val vocabFile = ensureAssetCopy(context, ASSET_VOCAB, "bge_vocab.txt")
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env = e
            session = e.createSession(modelFile.absolutePath, opts)
            tokenizer = BertWordPieceTokenizer.fromVocabularyStream(vocabFile.inputStream(), MAX_LEN)
            true
        } catch (t: Throwable) {
            android.util.Log.w("WAE_TINY", "tiny bert load failed: ${t.message}")
            false
        } finally {
            loading.set(false)
        }
    }

    /** ?像??????????撌?L2 甇????嚗??舐?? null??*/
    fun embed(text: String): FloatArray? {
        val sess = session ?: return null
        val e = env ?: return null
        val tk = tokenizer ?: return null
        if (text.isBlank()) return null

        val enc = tk.encode(text)
        val ids = enc.inputIds
        if (ids.isEmpty()) return null
        val mask = enc.attentionMask
        val types = enc.tokenTypeIds
        val n = ids.size.toLong()

        return try {
            OnnxTensor.createTensor(e, LongBuffer.wrap(ids), longArrayOf(1, n)).use { idT ->
                OnnxTensor.createTensor(e, LongBuffer.wrap(mask), longArrayOf(1, n)).use { mkT ->
                    OnnxTensor.createTensor(e, LongBuffer.wrap(types), longArrayOf(1, n)).use { tpT ->
                        val input = mapOf("input_ids" to idT, "attention_mask" to mkT, "token_type_ids" to tpT)
                        sess.run(input).use { out ->
                            val hidden = out.get(0).value as Array<Array<FloatArray>>
                            meanPoolNormalize(hidden[0], mask)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WAE_TINY", "embed failed: ${t.message}")
            null
        }
    }

    /** 擗憐?訾撮摨佗??拚??賢歇甇????蝑?抒?嚗?*/
    fun similarity(a: String, b: String): Float {
        val va = embed(a) ?: return 0f
        val vb = embed(b) ?: return 0f
        var s = 0f
        for (i in va.indices) s += va[i] * vb[i]
        return s.coerceIn(-1f, 1f)
    }

    private fun meanPoolNormalize(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = hidden[0].size
        val acc = FloatArray(dim)
        var n = 0
        for (t in hidden.indices) {
            if (mask.getOrElse(t) { 0L } == 0L) continue
            val row = hidden[t]
            for (d in 0 until dim) acc[d] += row[d]
            n += 1
        }
        if (n > 0) for (d in 0 until dim) acc[d] /= n.toFloat()
        var norm = 0f
        for (v in acc) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-6f) for (d in 0 until dim) acc[d] /= norm
        return acc
    }

    private fun ensureAssetCopy(context: Context, asset: String, name: String): File {
        val out = File(context.filesDir, name)
        val expected = runCatching { context.assets.openFd(asset).length }.getOrDefault(-1L)
        if (out.exists() && (expected <= 0 || out.length() == expected)) return out
        context.assets.open(asset).use { input ->
            out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        return out
    }

    /**
     * 瘙箇?璅∪???蝐斤隡澆漲???Ｗ祕雿??格?閰?vs ?恍璅惜??     *
     * ??摮??瘥?嚗噶摰?蝎曄Ⅱ嚗?摮銝?撠??芋??     */
    fun labelSimilarity(goalLabel: String, candidateLabel: String): Float {
        if (goalLabel.isBlank() || candidateLabel.isBlank()) return 0f
        if (TextFold.containsFold(candidateLabel, goalLabel)) return 1f
        if (!isReady()) return 0f
        return similarity(goalLabel, candidateLabel)
    }
}
