package com.wae.agent.dev

import android.content.Context
import android.util.Log

/**
 * 開發期內建評測（DEV ONLY）。
 *
 * 只應在 debug build 使用；正式發布前請把 `app/src/main/assets/dev/` 與本檔案一起刪除，
 * 並移除 MainActivity 裡的 `devBenchmarkRow` 區塊（搜尋 `DEV_BENCHMARK` 即可找到）。
 *
 * 功能：從 assets 讀取 100 任務，可選難度層與數量，逐一套進現有執行管線，
 * 統計每層成功率與平均耗時（判定方式：任務結束後狀態字串是否含「✓」）。
 */
object DevBenchmark {

    private const val TAG = "WAE_DEV_BENCH"
    private const val ASSET = "dev/benchmark_tasks.tsv"
    private const val POLL_MS = 800L

    data class Task(
        val id: String,
        val tier: String,
        val cmd: String,
        val minSteps: Int,
        val timeoutS: Int
    )

    data class Result(val task: Task, val ok: Boolean, val elapsedMs: Long, val skipped: Boolean = false)

    interface Host {
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

    fun load(context: Context): List<Task> {
        return try {
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).readLines()
                .mapNotNull { line ->
                    if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
                    val p = line.split("\t")
                    if (p.size < 6 || p[0] == "id") return@mapNotNull null
                    Task(
                        id = p[0],
                        tier = p[1],
                        cmd = p[3],
                        minSteps = p[4].toIntOrNull() ?: 1,
                        timeoutS = p[5].toIntOrNull() ?: 60
                    )
                }
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: ${t.message}")
            emptyList()
        }
    }

    /** tier 傳 null 或 "全部" 表示全部難度 */
    fun run(context: Context?, host: Host, tasks: List<Task>) {
        if (running || tasks.isEmpty()) return
        running = true
        Thread {
            val results = ArrayList<Result>()
            try {
                for ((index, task) in tasks.withIndex()) {
                    // 任務假設的 App 不在裝置上 → skip（不算 Agent 失敗）
                    if (context != null) {
                        val hints = com.wae.agent.core.WaeV26PipelineOrchestrator
                            .decomposeCommandIntoLocalGoals(task.cmd, context)
                            .map { it.targetPackageHint }.filter { it.isNotEmpty() }.distinct()
                        val check = com.wae.agent.core.app.AppInstallResolver.check(context, hints)
                        if (check.allMissing()) {
                            val names = com.wae.agent.core.app.AppInstallResolver.displayNames(context, check.missing)
                            results.add(Result(task, ok = false, elapsedMs = 0, skipped = true))
                            Log.i(TAG, "SKIP ${task.id} ${task.tier} 未安裝：${names.joinToString("、")}")
                            host.onProgress("[${index + 1}/${tasks.size}] ${task.id} SKIP（未安裝：${names.joinToString("、")}）")
                            continue
                        }
                    }
                    val startedAt = System.currentTimeMillis()
                    host.setCommand(task.cmd)
                    Thread.sleep(300)
                    val seqBefore = host.taskRunSeq()
                    host.startTask()
                    var ok = false
                    var done = false
                    val deadline = startedAt + task.timeoutS * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        Thread.sleep(POLL_MS)
                        // 確定性訊號：新任務序號已出現且服務已寫出成敗
                        val seq = host.taskRunSeq()
                        val result = host.lastTaskSuccess()
                        if (seq > seqBefore && result != null) {
                            ok = result
                            done = true
                            break
                        }
                    }
                    val elapsed = System.currentTimeMillis() - startedAt
                    val result = Result(task, ok, elapsed)
                    results.add(result)
                    Log.i(TAG, "RESULT ${task.id} ${task.tier} ok=$ok ${elapsed}ms steps>=${task.minSteps} '${task.cmd}'")
                    val passed = results.count { it.ok }
                    host.onProgress(
                        "[${index + 1}/${tasks.size}] ${task.id} ${task.tier} " +
                            "${if (ok) "PASS" else if (done) "FAIL" else "TIMEOUT"} " +
                            "${elapsed / 1000}s  累計 ${passed}/${results.size}"
                    )
                    Thread.sleep(800)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "benchmark crashed", t)
            } finally {
                running = false
                val summary = summarize(results)
                Log.i(TAG, "SUMMARY ${summary.replace("\n", " | ")}")
                runCatching {
                    context?.let { ctx ->
                        val f = java.io.File(ctx.filesDir, "dev_benchmark_last.tsv")
                        f.writeText(
                            results.joinToString("") { r ->
                                "${r.task.id}\t${r.task.tier}\t${r.ok}\t${r.elapsedMs}\t${r.task.minSteps}\t${r.task.cmd}\n"
                            }, Charsets.UTF_8
                        )
                    }
                }
                host.onFinished(summary)
            }
        }.start()
    }

    fun summarize(results: List<Result>): String {
        if (results.isEmpty()) return "（無結果）"
        val sb = StringBuilder("評測完成：")
        for (tier in listOf("EASY", "MEDIUM", "HARD", "EXTREME")) {
            val sub = results.filter { it.task.tier == tier }
            if (sub.isEmpty()) continue
            val rate = sub.count { it.ok } * 100 / sub.size
            val avg = sub.sumOf { it.elapsedMs } / sub.size / 1000
            sb.append("\n$tier ${sub.count { it.ok }}/${sub.size} ($rate%) 平均 ${avg}s")
        }
        return sb.toString()
    }
}
