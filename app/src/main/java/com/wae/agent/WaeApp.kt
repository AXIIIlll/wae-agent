package com.wae.agent

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WAE 全域入口：安裝未捕捉例外守護，把最後一次崩潰寫入 filesDir/crash-last.txt。
 * 無障礙服務跑在其他 App 之上，崩潰時使用者只會看到「服務自己關掉」，
 * 沒有落盤紀錄幾乎無法回溯，因此這一層是後續排查的前提。
 */
class WaeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashGuard()
        // P1：套用使用者在設定裡選的 A/B 旗標（預設全關）
        runCatching { com.wae.agent.core.runtime.RuntimeFlags.applyFromPrefs(this) }
        // S57：移除 Vosk 後，清掉舊版解壓的 65MB 模型（升級後自動回收空間）
        runCatching {
            File(filesDir, "vosk-model-cn").let { if (it.exists()) it.deleteRecursively() }
        }
        // 真實語意模型初始化放背景執行緒：23MB ONNX 首次載入 + ORT session 建立約 300~800ms，
        // 不能卡住 Application 啟動。失敗時 WaeTextSemantics 會自動回退舊算子。
        Thread {
            try {
                com.wae.agent.core.nlp.WaeTextSemantics.init(this)
                kotlin.concurrent.thread(isDaemon = true) {
                    kotlin.runCatching { com.wae.agent.core.predicate.ModelCache.sweep(this) }
                }
                Log.i(
                    "WAE_MINILM",
                    "init done: real=${com.wae.agent.core.nlp.WaeTextSemantics.usingRealModel} " +
                        "err=${com.wae.agent.core.nlp.WaeTextSemantics.lastError}"
                )
            } catch (t: Throwable) {
                Log.e("WAE_MINILM", "init crashed", t)
            }
        }.start()

    }

    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val report = buildString {
                    append(stamp).append("  thread=").append(thread.name).append('\n')
                    append(Log.getStackTraceString(throwable))
                    append("\n")
                }
                File(filesDir, "crash-last.txt").writeText(report, Charsets.UTF_8)
                Log.e("WAE_CRASH", "Uncaught exception on ${thread.name}", throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
