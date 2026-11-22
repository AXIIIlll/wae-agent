package com.wae.agent.dev

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * 相容性掃描（開發期工具）。
 *
 * 目的：回答「台灣常用的 App 有哪些會擋我們」——用**實測**取代猜測。
 *
 * 流程（對每個已安裝的目標 App）：
 *   1. 啟動 App，等畫面穩定
 *   2. 抓目前畫面上的所有文字（無障礙樹）
 *   3. 比對阻擋訊號（資料檔）
 *   4. 記為可用 / 有警告 / 直接阻擋，然後回首頁
 *
 * **本工具不執行任何任務、不點擊任何元素**，只啟動 App 與讀畫面文字。
 * 若某 App 在啟動時就顯示風險提示，那是它對「無障礙服務已開啟」的反應，
 * 與我們是否操作它無關——這正是要量測的東西。
 */
object CompatibilityScanner {

    private const val TAG = "WAE_COMPAT"
    private const val TARGETS = "dev/compat_scan_targets.tsv"
    private const val SIGNALS = "dev/compat_block_signals.tsv"
    private const val OUT_FILE = "compat_matrix.tsv"
    private const val PER_APP_WAIT_MS = 3500L
    private const val MAX_APPS = 40

    enum class Verdict { OK, WARN, BLOCKED, SKIPPED }

    data class Target(val pkg: String, val label: String, val category: String)
    data class Signal(val pattern: String, val blocked: Boolean)
    data class Result(
        val target: Target,
        val verdict: Verdict,
        val evidence: String,
        val elapsedMs: Long
    )

    interface Host {
        fun installedPackages(): Set<String>
        /** 啟動指定 App（不操作內容）；回傳是否成功 */
        fun launch(pkg: String): Boolean
        /** 目前畫面上的所有文字 */
        fun screenTexts(): List<String>
        /** 目前前景套件 */
        fun foregroundPackage(): String
        fun goHome()
        fun onProgress(text: String)
    }

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    fun loadTargets(context: Context): List<Target> = try {
        context.assets.open(TARGETS).bufferedReader(Charsets.UTF_8).readLines()
            .mapNotNull { line ->
                val raw = line.trim()
                if (raw.isEmpty() || raw.startsWith("#")) return@mapNotNull null
                val p = raw.split("\t")
                if (p.size < 2) return@mapNotNull null
                Target(p[0].trim(), p[1].trim(), p.getOrElse(2) { "" }.trim())
            }
    } catch (t: Throwable) {
        Log.w(TAG, "load targets failed: ${t.message}")
        emptyList()
    }

    fun loadSignals(context: Context): List<Signal> = try {
        context.assets.open(SIGNALS).bufferedReader(Charsets.UTF_8).readLines()
            .mapNotNull { line ->
                val raw = line.trim()
                if (raw.isEmpty() || raw.startsWith("#")) return@mapNotNull null
                val p = raw.split("\t")
                if (p.size < 2) return@mapNotNull null
                Signal(p[0].trim(), p[1].trim().uppercase() == "BLOCK")
            }
    } catch (t: Throwable) {
        Log.w(TAG, "load signals failed: ${t.message}")
        emptyList()
    }

    /**
     * 判定單一 App 的相容性（純函式，可測試）。
     *
     * 規則：
     *  - 命中任何 BLOCK 訊號 → BLOCKED
     *  - 只命中 WARN 訊號 → WARN
     *  - 沒有任何訊號 → OK
     */
    fun classify(texts: List<String>, signals: List<Signal>): Pair<Verdict, String> {
        if (texts.isEmpty()) return Verdict.OK to ""
        val hay = texts.joinToString(" ").lowercase()
        val blockedHit = signals.filter { it.blocked && hay.contains(it.pattern.lowercase()) }
        if (blockedHit.isNotEmpty()) {
            return Verdict.BLOCKED to blockedHit.joinToString(",") { it.pattern }
        }
        val warnHit = signals.filter { hay.contains(it.pattern.lowercase()) }
        if (warnHit.isNotEmpty()) {
            return Verdict.WARN to warnHit.joinToString(",") { it.pattern }
        }
        return Verdict.OK to ""
    }

    /** 是否有啟動意圖（避免對沒有 UI 的套件做無效掃描） */
    fun launchable(context: Context, pkg: String): Boolean = try {
        context.packageManager.getLaunchIntentForPackage(pkg) != null
    } catch (_: Throwable) {
        false
    }

    fun run(context: Context, host: Host, maxApps: Int = MAX_APPS) {
        if (running) return
        running = true
        Thread {
            val results = ArrayList<Result>()
            try {
                val installed = host.installedPackages()
                val signals = loadSignals(context)
                val targets = loadTargets(context)
                    .filter { it.pkg in installed }
                    .filter { launchable(context, it.pkg) }
                    .filter { it.pkg != context.packageName }
                    .take(maxApps)
                Log.i(TAG, "SCAN_START installed_targets=${targets.size} signals=${signals.size}")
                for ((i, t) in targets.withIndex()) {
                    host.onProgress("${i + 1}/${targets.size} ${t.label}")
                    val t0 = System.currentTimeMillis()
                    val launched = runCatching { host.launch(t.pkg) }.getOrDefault(false)
                    if (!launched) {
                        results.add(Result(t, Verdict.SKIPPED, "launch failed", 0))
                        continue
                    }
                    Thread.sleep(PER_APP_WAIT_MS)
                    val fg = runCatching { host.foregroundPackage() }.getOrDefault("")
                    val texts = runCatching { host.screenTexts() }.getOrDefault(emptyList())
                    val (verdict, evidence) = classify(texts, signals)
                    val elapsed = System.currentTimeMillis() - t0
                    val row = Result(t, verdict, if (fg == t.pkg) evidence else "fg=$fg $evidence", elapsed)
                    results.add(row)
                    Log.i(
                        TAG,
                        "APP pkg=${t.pkg} label=${t.label} category=${t.category} " +
                            "verdict=$verdict evidence='${evidence.take(60)}' fg=$fg ms=$elapsed"
                    )
                    runCatching { host.goHome() }
                    Thread.sleep(700)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "scan failed: ${t.message}")
            } finally {
                running = false
                writeResults(context, results)
                Log.i(
                    TAG,
                    "SCAN_END apps=${results.size} ok=${results.count { it.verdict == Verdict.OK}} " +
                        "warn=${results.count { it.verdict == Verdict.WARN}} " +
                        "blocked=${results.count { it.verdict == Verdict.BLOCKED}} " +
                        "skipped=${results.count { it.verdict == Verdict.SKIPPED}}"
                )
            }
        }.start()
    }

    private fun writeResults(context: Context, results: List<Result>) {
        runCatching {
            val f = File(context.filesDir, OUT_FILE)
            val sb = StringBuilder("pkg\tlabel\tcategory\tverdict\tevidence\tms\n")
            for (r in results) {
                sb.append(r.target.pkg).append('\t')
                    .append(r.target.label).append('\t')
                    .append(r.target.category).append('\t')
                    .append(r.verdict.name).append('\t')
                    .append(r.evidence.replace('\n', ' ')).append('\t')
                    .append(r.elapsedMs).append('\n')
            }
            f.writeText(sb.toString(), Charsets.UTF_8)
            Log.i(TAG, "SCAN_SAVED ${f.absolutePath}")
        }
    }

    fun summarize(results: List<Result>): String {
        val ok = results.count { it.verdict == Verdict.OK }
        val warn = results.count { it.verdict == Verdict.WARN }
        val blocked = results.count { it.verdict == Verdict.BLOCKED }
        return "掃描完成：可用 $ok、警告 $warn、阻擋 $blocked（共 ${results.size}）"
    }
}
