package com.wae.agent.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 內建離線語音辨識（Sherpa-onnx 中文 streaming zipformer int8）。
 *
 * 模型隨 APK 附帶（assets/sherpa/zh-14m-int8.zip，約 20MB），首次使用解壓到
 * `filesDir/sherpa-zh-14m/`；使用者不需要額外下載任何東西，也不需要網路。
 */
class SherpaAsr(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var stream: OnlineStream? = null

    fun prepareAsync(onReady: () -> Unit, onError: (String) -> Unit) {
        if (recognizer != null) {
            onReady()
            return
        }
        Thread {
            try {
                val dir = ensureModel()
                val config = OnlineRecognizerConfig.builder()
                    .setOnlineModelConfig(
                        OnlineModelConfig.builder()
                            .setTransducer(
                                OnlineTransducerModelConfig.builder()
                                    .setEncoder(File(dir, "encoder.onnx").absolutePath)
                                    .setDecoder(File(dir, "decoder.onnx").absolutePath)
                                    .setJoiner(File(dir, "joiner.onnx").absolutePath)
                                    .build()
                            )
                            .setTokens(File(dir, "tokens.txt").absolutePath)
                            .setNumThreads(2)
                            .setDebug(false)
                            .setModelType("zipformer")
                            .build()
                    )
                    .setFeatureConfig(
                        FeatureConfig.builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setFeatureDim(80)
                            .setDither(0.0f)
                            .build()
                    )
                    .setDecodingMethod("greedy_search")
                    .setEnableEndpoint(true)
                    .build()
                recognizer = OnlineRecognizer(config)
                VoiceLog.event(TAG, "SHERPA_READY")
                onReady()
            } catch (t: Throwable) {
                VoiceLog.error(TAG, "sherpa init failed", t)
                onError(t.message ?: "離線語音引擎初始化失敗")
            }
        }.apply { name = "sherpa-prepare" }.start()
    }

    fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val rec = recognizer ?: run {
            onError("離線語音引擎尚未就緒")
            return
        }
        stop()
        running = true
        // 開發驗證：debug 版若外部有 mic_inject.wav，用它模擬麥克風串流（驗證 endpoint/送出路徑）
        val inject = if (isDebuggable()) {
            java.io.File(appContext.getExternalFilesDir(null), "mic_inject.wav")
        } else null
        if (inject != null && inject.isFile) {
            worker = kotlin.concurrent.thread(name = "sherpa-listen-file") {
                listenFromWav(rec, inject, onPartial, onFinal, onError)
            }
            return
        }
        worker = kotlin.concurrent.thread(name = "sherpa-listen") {
            val ar = com.wae.core.util.Degrade.orNull("asr:createAudioRecord") { createAudioRecord() }
            if (ar == null) {
                running = false
                onError("麥克風初始化失敗")
                return@thread
            }
            audioRecord = ar
            val s = com.wae.core.util.Degrade.orNull("asr:createStream") { rec.createStream() }
            if (s == null) {
                running = false
                onError("無法建立語音串流")
                return@thread
            }
            stream = s
            try {
                ar.startRecording()
                val buffer = ShortArray(READ_CHUNK)
                var voiced = 0
                var reads = 0
                var lastPartial = ""
                while (running) {
                    val n = ar.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    reads += 1
                    val samples = FloatArray(n) { buffer[it] / 32768.0f }
                    var sum = 0.0
                    for (v in samples) sum += v.toDouble() * v
                    if (kotlin.math.sqrt(sum / n) >= RMS_THRESHOLD) voiced += 1

                    s.acceptWaveform(samples, SAMPLE_RATE)
                    while (rec.isReady(s)) rec.decode(s)
                    val text = rec.getResult(s).text
                    if (text.isNotBlank() && text != lastPartial) {
                        lastPartial = text
                        onPartial(TradConverter.toTraditional(appContext, text))
                    }
                    if (rec.isEndpoint(s)) {
                        val finalText = rec.getResult(s).text
                        if (finalText.isNotBlank() && voiced >= MIN_VOICED_CHUNKS) {
                            onFinal(TradConverter.toTraditional(appContext, finalText))
                            return@thread
                        }
                        rec.reset(s)
                        lastPartial = ""
                        voiced = 0
                    }
                    if (reads > MAX_READS) {
                        if (lastPartial.isNotBlank() && voiced >= MIN_VOICED_CHUNKS) {
                            onFinal(TradConverter.toTraditional(appContext, lastPartial))
                        } else {
                            onError("沒有聽到語音")
                        }
                        return@thread
                    }
                }
            } catch (t: Throwable) {
                VoiceLog.error(TAG, "sherpa listen failed", t)
                if (running) onError(t.message ?: "語音辨識失敗")
            } finally {
                running = false
                com.wae.core.util.Degrade.orNull("asr:stopRecord") { ar.stop() }
                com.wae.core.util.Degrade.orNull("asr:releaseRecord") { ar.release() }
                audioRecord = null
                com.wae.core.util.Degrade.orNull("asr:releaseStream") { s.release() }
                stream = null
            }
        }
    }

    fun stop() {
        running = false
        com.wae.core.util.Degrade.orNull("asr:stopRecord2") { audioRecord?.stop() }
        com.wae.core.util.Degrade.orNull("asr:joinWorker") { worker?.join(300) }
        worker = null
    }

    fun close() {
        stop()
        com.wae.core.util.Degrade.orNull("asr:releaseStream2") { stream?.release() }
        com.wae.core.util.Degrade.orNull("asr:releaseRecognizer") { recognizer?.release() }
        recognizer = null
    }

    /** 開發驗證：直接辨識一個 16kHz / 單聲道 PCM16 WAV（驗證離線引擎是否正常） */
    fun recognizeWavFile(file: File): String? {
        val rec = recognizer ?: return null
        if (!file.isFile) return null
        val bytes = file.readBytes()
        var dataStart = 0
        for (i in 0 until bytes.size - 8) {
            if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'a'.code.toByte() &&
                bytes[i + 2] == 't'.code.toByte() && bytes[i + 3] == 'a'.code.toByte()
            ) {
                dataStart = i + 8
                break
            }
        }
        if (dataStart == 0) dataStart = 44
        if (bytes.size <= dataStart + 2) return null
        val s = rec.createStream()
        return try {
            var offset = dataStart
            val chunk = FloatArray(READ_CHUNK)
            while (offset + 1 < bytes.size) {
                var count = 0
                while (count < chunk.size && offset + 1 < bytes.size) {
                    val lo = bytes[offset].toInt() and 0xFF
                    val hi = bytes[offset + 1].toInt()
                    chunk[count] = ((hi shl 8) or lo).toShort() / 32768.0f
                    count += 1
                    offset += 2
                }
                if (count > 0) s.acceptWaveform(chunk.copyOf(count), SAMPLE_RATE)
                while (rec.isReady(s)) rec.decode(s)
            }
            // 串流模型需要尾端靜音才會吐出最後幾個字
            s.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
            while (rec.isReady(s)) rec.decode(s)
            s.inputFinished()
            while (rec.isReady(s)) rec.decode(s)
            rec.getResult(s).text.ifBlank { null }?.let { TradConverter.toTraditional(appContext, it) }
        } catch (t: Throwable) {
            VoiceLog.error(TAG, "sherpa wav failed", t)
            null
        } finally {
            com.wae.core.util.Degrade.orNull("asr:releaseStream") { s.release() }
        }
    }

    // ── 內部 ──────────────────────────────────────────────
    private fun isDebuggable(): Boolean =
        (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 開發驗證：用 WAV 模擬麥克風串流（與真麥克風走同一套 endpoint/partial/final 邏輯） */
    private fun listenFromWav(
        rec: OnlineRecognizer,
        file: java.io.File,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val s = com.wae.core.util.Degrade.orNull("asr:createStream") { rec.createStream() }
        if (s == null) {
            running = false
            onError("無法建立語音串流")
            return
        }
        stream = s
        try {
            val bytes = file.readBytes()
            var dataStart = 0
            for (i in 0 until bytes.size - 8) {
                if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'a'.code.toByte() &&
                    bytes[i + 2] == 't'.code.toByte() && bytes[i + 3] == 'a'.code.toByte()
                ) {
                    dataStart = i + 8
                    break
                }
            }
            if (dataStart == 0) dataStart = 44
            val pcm = FloatArray((bytes.size - dataStart) / 2)
            var off = dataStart
            for (i in pcm.indices) {
                val lo = bytes[off].toInt() and 0xFF
                val hi = bytes[off + 1].toInt()
                pcm[i] = ((hi shl 8) or lo).toShort() / 32768.0f
                off += 2
            }
            var idx = 0
            var voiced = 0
            var lastPartial = ""
            while (running && idx < pcm.size) {
                val n = minOf(READ_CHUNK, pcm.size - idx)
                val chunk = pcm.copyOfRange(idx, idx + n)
                idx += n
                var sum = 0.0
                for (v in chunk) sum += v.toDouble() * v
                if (kotlin.math.sqrt(sum / n) >= RMS_THRESHOLD) voiced += 1
                s.acceptWaveform(chunk, SAMPLE_RATE)
                while (rec.isReady(s)) rec.decode(s)
                val text = rec.getResult(s).text
                if (text.isNotBlank() && text != lastPartial) {
                    lastPartial = text
                    onPartial(TradConverter.toTraditional(appContext, text))
                }
                if (rec.isEndpoint(s)) {
                    val finalText = rec.getResult(s).text
                    if (finalText.isNotBlank() && voiced >= MIN_VOICED_CHUNKS) {
                        onFinal(TradConverter.toTraditional(appContext, finalText))
                        return
                    }
                    rec.reset(s)
                    lastPartial = ""
                    voiced = 0
                }
                Thread.sleep(100) // 模擬 0.1 秒音訊節奏
            }
            s.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
            while (rec.isReady(s)) rec.decode(s)
            val finalText = rec.getResult(s).text
            if (finalText.isNotBlank() && voiced >= MIN_VOICED_CHUNKS) {
                onFinal(TradConverter.toTraditional(appContext, finalText))
            } else {
                onError("沒有聽到語音")
            }
        } catch (t: Throwable) {
            if (running) onError(t.message ?: "語音辨識失敗")
        } finally {
            running = false
            com.wae.core.util.Degrade.orNull("asr:releaseStream") { s.release() }
            stream = null
        }
    }

    private fun createAudioRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(READ_CHUNK * 2)
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            com.wae.core.util.Degrade.orNull("asr:releaseRecord") { ar.release() }
            throw IllegalStateException("AudioRecord 無法初始化")
        }
        return ar
    }

    /** 解壓內建模型（首次），回傳模型目錄 */
    private fun ensureModel(): File {
        val root = File(appContext.filesDir, MODEL_DIR)
        val encoder = File(root, "encoder.onnx")
        if (!encoder.isFile) {
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
            appContext.assets.open("sherpa/zh-14m-int8.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val target = File(root, entry.name)
                        if (!target.canonicalPath.startsWith(root.canonicalPath)) {
                            entry = zis.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> zis.copyTo(out) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
        // hotwords：提升指令詞命中率（模型沒有熱詞功能時會被忽略）
        val hw = File(root, "hotwords.txt")
        if (!hw.isFile) {
            com.wae.core.util.Degrade.orNull("asr:hotwords") {
                appContext.assets.open("sherpa/hotwords.txt").use { input ->
                    FileOutputStream(hw).use { out -> input.copyTo(out) }
                }
            }
        }
        return root
    }

    companion object {
        private const val TAG = "WAE_SHERPA"
        private const val SAMPLE_RATE = 16000
        private const val READ_CHUNK = 1600 // 0.1 秒
        private const val MAX_READS = 120    // 約 12 秒
        private const val RMS_THRESHOLD = 0.008
        private const val MIN_VOICED_CHUNKS = 3
        private const val MODEL_DIR = "sherpa-zh-14m"
    }
}
