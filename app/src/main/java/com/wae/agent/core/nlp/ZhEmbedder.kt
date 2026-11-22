package com.wae.agent.core.nlp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.LongBuffer
import java.util.Collections

/**
 * 中文（簡繁混合）語意 embedding：BAAI/bge-small-zh-v1.5（INT8 ONNX，~22.8MB）。
 *
 * 用途：把「使用者目標」與「App 名稱 / 畫面元素」放進同一個語意空間，
 * 讓「我想看劇」「我想查天氣」能靠語意挑 App，而不是靠字表。
 *
 * 與 MiniLmEncoder 的差別：
 *  - 詞表是中文 BERT WordPiece（`tokenizer/bge_vocab.txt`）
 *  - 取 CLS（第一顆 token）做 L2 正規化，不做 mean pooling
 */
object ZhEmbedder {

    private const val TAG = "WAE_ZHEMB"
    private const val ASSET_MODEL = "models/bge-small-zh-v1.5-int8.onnx"
    private const val ASSET_VOCAB = "tokenizer/bge_vocab.txt"
    private const val MODEL_BYTES = 23_903_394L
    private const val VOCAB_BYTES = 109_540L
    private const val MAX_LEN = 64
    private const val MAX_CACHE = 512

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: BertWordPieceTokenizer? = null
    private var useTokenTypeIds = false

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, FloatArray>(MAX_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
                size > MAX_CACHE
        }
    )

    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (isReady) return true
        return try {
            val vocabFile = ensureAssetCopy(context, ASSET_VOCAB, "bge_vocab.txt", VOCAB_BYTES)
            val modelFile = ensureAssetCopy(
                context, ASSET_MODEL, "bge-small-zh-v1.5-int8.onnx", MODEL_BYTES
            )
            val tk = BertWordPieceTokenizer.fromVocabularyStream(vocabFile.inputStream(), MAX_LEN)
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val sess = ortEnv.createSession(modelFile.absolutePath, opts)
            tokenizer = tk
            env = ortEnv
            session = sess
            useTokenTypeIds = sess.inputNames.any { it.contains("token_type", true) }
            isReady = true
            lastError = ""
            Log.i(TAG, "ZhEmbedder ready: inputs=${sess.inputNames} outputs=${sess.outputNames}")
            true
        } catch (t: Throwable) {
            isReady = false
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "ZhEmbedder init failed", t)
            false
        }
    }

    fun encode(texts: List<String>): List<FloatArray> {
        if (!isReady) return texts.map { FloatArray(0) }
        val tk = tokenizer ?: return texts.map { FloatArray(0) }
        val results = arrayOfNulls<FloatArray>(texts.size)
        val pending = ArrayList<Int>()
        for (i in texts.indices) {
            val cached = cache[texts[i]]
            if (cached != null) results[i] = cached else pending.add(i)
        }
        if (pending.isEmpty()) return results.map { it ?: FloatArray(0) }

        val batchOk = try {
            runBatch(pending.map { tk.encode(texts[it]) }, pending, results)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "batch failed, per-item: ${t.message}")
            false
        }
        if (!batchOk) {
            for (idx in pending) {
                results[idx] = runCatching { runSingle(tk.encode(texts[idx])) }.getOrDefault(FloatArray(0))
            }
        }
        for (i in results.indices) {
            val v = results[i]
            if (v != null && v.isNotEmpty()) cache[texts[i]] = v
        }
        return results.map { it ?: FloatArray(0) }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0f || nb <= 0f) return 0f
        return (dot / (Math.sqrt(na.toDouble()) * Math.sqrt(nb.toDouble()))).toFloat()
    }

    private fun clsPoolAndNormalize(row: FloatArray): FloatArray {
        var sum = 0f
        for (v in row) sum += v * v
        val n = Math.sqrt(sum.toDouble()).toFloat()
        if (n <= 0f) return row
        return FloatArray(row.size) { row[it] / n }
    }

    private fun runBatch(
        encodings: List<BertWordPieceTokenizer.Encoding>,
        indices: List<Int>,
        results: Array<FloatArray?>
    ) {
        val sess = session ?: error("session not ready")
        val ortEnv = env ?: error("env not ready")
        val n = encodings.size
        val idsBuf = LongBuffer.allocate(n * MAX_LEN)
        val maskBuf = LongBuffer.allocate(n * MAX_LEN)
        val typeBuf = LongBuffer.allocate(n * MAX_LEN)
        for (enc in encodings) {
            idsBuf.put(enc.inputIds)
            maskBuf.put(enc.attentionMask)
            typeBuf.put(enc.tokenTypeIds)
        }
        idsBuf.rewind(); maskBuf.rewind(); typeBuf.rewind()
        val shape = longArrayOf(n.toLong(), MAX_LEN.toLong())
        val inputs = HashMap<String, OnnxTensor>()
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(ortEnv, idsBuf, shape)
            inputs["attention_mask"] = OnnxTensor.createTensor(ortEnv, maskBuf, shape)
            if (useTokenTypeIds) inputs["token_type_ids"] = OnnxTensor.createTensor(ortEnv, typeBuf, shape)
            sess.run(inputs).use { out ->
                val tensor = out.get(0) as OnnxTensor
                @Suppress("UNCHECKED_CAST")
                val data = tensor.value as Array<Array<FloatArray>>
                for (b in 0 until n) {
                    results[indices[b]] = clsPoolAndNormalize(data[b][0])
                }
            }
        } finally {
            for (t in inputs.values) runCatching { t.close() }
        }
    }

    private fun runSingle(enc: BertWordPieceTokenizer.Encoding): FloatArray {
        val sess = session ?: error("session not ready")
        val ortEnv = env ?: error("env not ready")
        val shape = longArrayOf(1L, MAX_LEN.toLong())
        val inputs = HashMap<String, OnnxTensor>()
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(enc.inputIds), shape)
            inputs["attention_mask"] = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(enc.attentionMask), shape)
            if (useTokenTypeIds) {
                inputs["token_type_ids"] = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(enc.tokenTypeIds), shape)
            }
            sess.run(inputs).use { out ->
                val tensor = out.get(0) as OnnxTensor
                @Suppress("UNCHECKED_CAST")
                val data = tensor.value as Array<Array<FloatArray>>
                return clsPoolAndNormalize(data[0][0])
            }
        } finally {
            for (t in inputs.values) runCatching { t.close() }
        }
    }

    private fun ensureAssetCopy(
        context: Context,
        assetPath: String,
        fileName: String,
        expectedSize: Long
    ): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, fileName)
        if (target.exists() && target.length() == expectedSize) return target
        val tmp = File(dir, "$fileName.tmp")
        context.assets.open(assetPath).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (tmp.length() != expectedSize) {
            val actual = tmp.length()
            tmp.delete()
            throw IllegalStateException("asset $assetPath size mismatch: expected=$expectedSize actual=$actual")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }
}
