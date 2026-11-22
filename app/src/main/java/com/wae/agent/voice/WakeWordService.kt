package com.wae.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 喚醒詞服務（P1 #15）— **預設關閉，使用者必須在設定裡手動開啟**。
 *
 * 隱私：音訊只在記憶體中處理，不落盤、不上傳（App 本身沒有 INTERNET 權限）。
 * 功耗：只在開啟時常駐麥克風；關閉即釋放。
 * 引擎：sherpa-onnx KeywordSpotter，內建中文 KWS 模型（約 3.5MB）。
 */
class WakeWordService : Service() {

    private var worker: Thread? = null
    @Volatile private var running = false
    private var spotter: KeywordSpotter? = null
    private var audioRecord: AudioRecord? = null
    private var lastTriggerAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("正在等待喚醒詞"))
        running = true
        worker = kotlin.concurrent.thread(name = "wae-wake-word") { loop() }
        VoiceLog.event(TAG, "WAKE_WORD_START")
    }

    override fun onDestroy() {
        running = false
        runCatching { audioRecord?.stop() }
        runCatching { worker?.join(500) }
        runCatching { audioRecord?.release() }
        runCatching { spotter?.release() }
        audioRecord = null
        spotter = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        VoiceLog.event(TAG, "WAKE_WORD_STOP")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun loop() {
        val sp = try {
            prepare()
        } catch (t: Throwable) {
            VoiceLog.error(TAG, "wake word init failed", t)
            stopSelf()
            return
        }
        spotter = sp
        notifyText("正在等待喚醒詞")
        Log.i(TAG, "WAKE_WORD_READY")
        val buffer = ShortArray(CHUNK)
        while (running) {
            val ar = audioRecord ?: createAudioRecord().also { audioRecord = it }
            if (ar == null) {
                Log.w(TAG, "audio record unavailable")
                break
            }
            try {
                ar.startRecording()
                val stream = sp.createStream()
                while (running) {
                    val n = ar.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (sp.isReady(stream)) sp.decode(stream)
                    val result = sp.getResult(stream)
                    val kw = result.keyword
                    if (kw.isNotBlank()) {
                        Log.i(TAG, "WAKE_WORD_DETECTED keyword=$kw")
                        VoiceLog.event(TAG, "WAKE_WORD_DETECTED")
                        sp.reset(stream)
                        trigger()
                    }
                }
            } catch (t: Throwable) {
                if (running) {
                    VoiceLog.error(TAG, "wake word loop failed", t)
                    runCatching { ar.stop() }
                    runCatching { ar.release() }
                    audioRecord = null
                    Thread.sleep(1500)
                }
            }
        }
    }

    private fun trigger() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < TRIGGER_COOLDOWN_MS) return
        lastTriggerAt = now
        runCatching {
            startActivity(
                Intent(this, AssistActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(AssistActivity.EXTRA_FROM_WAKE_WORD, true)
                }
            )
        }
    }

    private fun prepare(): KeywordSpotter {
        val dir = ensureModel()
        val config = KeywordSpotterConfig.builder()
            .setFeatureConfig(
                FeatureConfig.builder().setSampleRate(SAMPLE_RATE).setFeatureDim(80).build()
            )
            .setOnlineModelConfig(
                OnlineModelConfig.builder()
                    .setTransducer(
                        OnlineTransducerModelConfig.builder()
                            .setEncoder(File(dir, "encoder.int8.onnx").absolutePath)
                            .setDecoder(File(dir, "decoder.int8.onnx").absolutePath)
                            .setJoiner(File(dir, "joiner.int8.onnx").absolutePath)
                            .build()
                    )
                    .setTokens(File(dir, "tokens.txt").absolutePath)
                    .setNumThreads(2)
                    .setModelType("zipformer")
                    .build()
            )
            .setKeywordsFile(File(dir, "keywords.txt").absolutePath)
            .setKeywordsScore(1.5f)
            .setKeywordsThreshold(0.25f)
            .setNumTrailingBlanks(2)
            .setMaxActivePaths(4)
            .build()
        return KeywordSpotter(config)
    }

    private fun ensureModel(): File {
        val root = File(filesDir, MODEL_DIR)
        if (!File(root, "encoder.int8.onnx").isFile) {
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
            assets.open("sherpa/kws-zh.zip").use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val target = File(root, entry.name)
                        if (!target.canonicalPath.startsWith(root.canonicalPath)) {
                            entry = zis.nextEntry
                            continue
                        }
                        if (entry.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }
        val kw = File(root, "keywords.txt")
        if (!kw.isFile) {
            runCatching {
                assets.open("sherpa/kws-keywords.txt").use { input ->
                    FileOutputStream(kw).use { input.copyTo(it) }
                }
            }
        }
        return root
    }

    private fun createAudioRecord(): AudioRecord? = runCatching {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK * 2)
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            null
        } else ar
    }.getOrNull()

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "WAE 喚醒詞", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, AssistActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("WAE 正在等待喚醒詞")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notifyText(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification(text))
        }
    }

    companion object {
        private const val TAG = "WAE_WAKE"
        private const val CHANNEL = "wae_wake_word"
        private const val NOTIF_ID = 42
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600
        private const val MODEL_DIR = "kws-zh"
        private const val TRIGGER_COOLDOWN_MS = 3000L

        const val PREF = "wae_wake_word"
        const val KEY_ENABLED = "enabled"

        // H：預設開啟，下載完就能用「hey wae」；不想要的人可在設定頁關掉（不會背景收音）。
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).apply()
            val intent = Intent(context, WakeWordService::class.java)
            if (enabled) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }
}
