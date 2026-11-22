package com.wae.agent.dev

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 提示詞套件執行器（開發期用）。
 *
 * **重要設計原則：本類別不做任何「成功/失敗」判定。**
 * 之前吃過的虧是「App 自己判自己成功」，但那只是它自己的判定，
 * 不代表使用者的目標真的達成。因此這裡只做兩件事：
 *   1. 依序把固定提示詞交給管線執行
 *   2. 把「原始事實」寫進 logcat（與檔案），包含：
 *      PROMPT_START / PROMPT_END / 耗時 / 內部自我回報（明確標示為不可信）
 *
 * 真正的成敗由外部（adb logcat + 截圖）判斷。
 */
object PromptSuiteRunner {

    private const val TAG = "WAE_PROMPTS"
    private const val ASSET = "dev/prompt_suite.tsv"
    private const val OUT_FILE = "prompt_suite_last.tsv"
    private const val POLL_MS = 700L

    data class Item(val id: String, val prompt: String, val timeoutS: Int, val note: String)

    interface Host {
        /** 回到桌面（每項之間重置，避免殘留頁面污染判定） */
        fun goHome()
        fun setCommand(cmd: String)
        fun startTask()
        fun isBusy(): Boolean
        fun taskRunSeq(): Long
        fun lastTaskSuccess(): Boolean?
        fun currentStatus(): String
        fun onProgress(text: String)
        fun onFinished(summary: String)
    }

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    fun load(context: Context): List<Item> = try {
        context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).readLines()
            .mapNotNull { line ->
                val raw = line.trimEnd('\r', '\n')
                if (raw.isBlank() || raw.startsWith("#")) return@mapNotNull null
                val p = raw.split("\t")
                if (p.size < 3) return@mapNotNull null
                Item(
                    id = p[0].trim(),
                    prompt = p[1].trim(),
                    timeoutS = p[2].trim().toIntOrNull() ?: 90,
                    note = p.getOrElse(3) { "" }.trim()
                )
            }
    } catch (t: Throwable) {
        Log.w(TAG, "load failed: ${t.message}")
        emptyList()
    }

    fun run(context: Context?, host: Host, items: List<Item>) {
        if (running || items.isEmpty()) return
        running = true
        Thread {
            val rows = ArrayList<String>()
            Log.i(TAG, "PROMPT_SUITE_START count=${items.size}")
            try {
                for ((index, item) in items.withIndex()) {
                    val t0 = System.currentTimeMillis()
                    if (index > 0) {
                        // 重置到首頁：每項都從乾淨狀態開始（否則前一項留下的頁面會害下一項誤判）
                        runCatching { host.goHome() }
                        Thread.sleep(900)
                    }
                    Log.i(TAG, "PROMPT_START id=${item.id} prompt='${item.prompt}' expect='${item.note}'")
                    host.onProgress("${item.id}: ${item.prompt}")

                    val seqBefore = host.taskRunSeq()
                    host.setCommand(item.prompt)
                    Thread.sleep(250)
                    host.startTask()

                    val deadline = t0 + item.timeoutS * 1000L
                    var finished = false
                    while (System.currentTimeMillis() < deadline) {
                        Thread.sleep(POLL_MS)
                        if (!host.isBusy() && host.taskRunSeq() > seqBefore) {
                            finished = true
                            break
                        }
                    }
                    Thread.sleep(600) // 讓最後幾行日誌落地
                    val elapsed = System.currentTimeMillis() - t0
                    val self = host.lastTaskSuccess()
                    val seq = host.taskRunSeq()
                    // 明確標示：這是 App 自己的回報，不可當作判定依據
                    Log.i(
                        TAG,
                        "PROMPT_END id=${item.id} ms=$elapsed finished=$finished " +
                            "APP_SELF_REPORT=${self?.toString() ?: "null"} seq=$seq status='${host.currentStatus().take(80)}'"
                    )
                    rows.add(
                        "${item.id}\t${elapsed}\t$finished\t${self?.toString() ?: "null"}\t${seq}\t${item.prompt}"
                    )
                    host.onProgress("${item.id} 完成 ${elapsed}ms（自我回報=${self?.toString() ?: "null"}）")
                    Thread.sleep(700)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "suite failed: ${t.message}")
            } finally {
                running = false
                Log.i(TAG, "PROMPT_SUITE_END items=${items.size}")
                try {
                    if (context != null) {
                        val f = File(context.filesDir, OUT_FILE)
                        f.writeText(
                            "id\tms\tfinished\tselfReport\tseq\tprompt\n" + rows.joinToString("\n") + "\n",
                            Charsets.UTF_8
                        )
                        Log.i(TAG, "PROMPT_SUITE_SAVED ${f.absolutePath}")
                    }
                } catch (_: Throwable) {
                }
                host.onFinished("提示詞套件完成：${items.size} 項（判定請看後端日誌）")
            }
        }.start()
    }
}
