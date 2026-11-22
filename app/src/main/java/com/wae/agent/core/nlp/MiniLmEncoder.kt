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
 * all-MiniLM-L6-v2（INT8 ONNX）真實推理封裝。
 *
 * 與舊版差別：舊版把模型位元組當雜訊索引（`wScale = 1 + w/512`），
 * 這裡是真的 BERT 前向：WordPiece tokenize → input_ids/attention_mask/token_type_ids
 * → last_hidden_state → mean pooling → L2 正規化。
 *
 * 模型與詞表都在 assets（`models/minilm_l6_v2_int8.onnx`、`tokenizer/vocab.txt`），
 * 首次啟動時複製到 filesDir（ORT 只吃檔案路徑或 byte[]），之後直接複用。
 */
object MiniLmEncoder {

    private const val TAG = "WAE_MINILM"
    private const val ASSET_MODEL = "models/minilm_l6_v2_int8.onnx"
    private const val ASSET_VOCAB = "tokenizer/vocab.txt"
    private const val MODEL_BYTES = 23_026_053L
    private const val VOCAB_BYTES = 231_508L

    /**
     * 序列長度上限。UI 候選文字絕大多數 < 40 token，128 → 64 可把前向時間腰斬；
     * 超長文字（例如抖音影片說明）截斷後仍然保留開頭關鍵詞，對排序影響可忽略。
     */
    private const val MAX_LEN = 64
    private const val MAX_CACHE = 512

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var lastLatencyMs: Long = 0L
        private set

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: BertWordPieceTokenizer? = null
    private var outputName: String = ""
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
            val vocabFile = ensureAssetCopy(context, ASSET_VOCAB, "vocab.txt", VOCAB_BYTES)
            val modelFile = ensureAssetCopy(context, ASSET_MODEL, "minilm_l6_v2_int8.onnx", MODEL_BYTES)

            val tk = BertWordPieceTokenizer.fromVocabularyStream(vocabFile.inputStream(), MAX_LEN)
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // 2 條執行緒在 A55 上實測利用率不足；4 條（大核數）明顯更快
                setIntraOpNumThreads(
                    Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                )
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val sess = ortEnv.createSession(modelFile.absolutePath, opts)

            tokenizer = tk
            env = ortEnv
            session = sess
            outputName = sess.outputNames.firstOrNull { it.contains("last_hidden", true) }
                ?: sess.outputNames.first()
            useTokenTypeIds = sess.inputNames.any { it.contains("token_type", true) }
            isReady = true
            lastError = ""
            Log.i(TAG, "MiniLM ready: inputs=${sess.inputNames} output=$outputName useTokenTypeIds=$useTokenTypeIds")
            true
        } catch (t: Throwable) {
            isReady = false
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "MiniLM init failed", t)
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

        val started = android.os.SystemClock.elapsedRealtime()
        // 先試整批一次前向（動態 batch），失敗則逐條（部分匯出圖把 batch 固定為 1）
        val batchOk = try {
            runBatch(pending.map { tk.encode(texts[it]) }, pending, texts, results)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "batch inference failed, fallback to per-item: ${t.message}")
            false
        }
        if (!batchOk) {
            for (idx in pending) {
                try {
                    val enc = tk.encode(texts[idx])
                    results[idx] = runSingle(enc)
                } catch (t: Throwable) {
                    Log.e(TAG, "encode failed for '${texts[idx].take(40)}'", t)
                    results[idx] = FloatArray(0)
                }
            }
        }
        for (i in results.indices) {
            val v = results[i]
            if (v != null && v.isNotEmpty()) cache[texts[i]] = v
        }
        lastLatencyMs = android.os.SystemClock.elapsedRealtime() - started
        Log.i(TAG, "encode n=${pending.size} len=$MAX_LEN cache=${texts.size - pending.size} total=${lastLatencyMs}ms")
        return results.map { it ?: FloatArray(0) }
    }

    private fun runBatch(
        encodings: List<BertWordPieceTokenizer.Encoding>,
        indices: List<Int>,
        texts: List<String>,
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
                    results[indices[b]] = MiniLmPostProcess.meanPoolAndNormalize(
                        data[b], encodings[b].attentionMask
                    )
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
                return MiniLmPostProcess.meanPoolAndNormalize(data[0], enc.attentionMask)
            }
        } finally {
            for (t in inputs.values) runCatching { t.close() }
        }
    }

    /**
     * 把 asset 複製到 filesDir 供 ORT 以檔案路徑載入。
     *
     * 注意：不能用 `assets.openFd()` 取大小 —— 只有 noCompress 的 asset 才能拿到 fd，
     * `vocab.txt` 是被壓縮的，openFd 會直接拋 "This file can not be opened as a file descriptor"。
     * 因此改用「已知位元組數」作為快取驗證（由打包腳本保證，測試會覆蓋）。
     */
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

    fun clearCache() {
        cache.clear()
    }
}
