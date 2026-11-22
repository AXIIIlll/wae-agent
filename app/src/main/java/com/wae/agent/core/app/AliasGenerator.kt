package com.wae.agent.core.app

import android.content.Context
import android.util.Log

/**
 * 用本機模型**衍生 App 別名**（增量、快取、有碰撞保護）。
 *
 * 為什麼要模型：手機上 ~150 個 App，人工別名只涵蓋幾十個；使用者講的是自己的說法
 * （「短句」「小紅書」「哀居」），這些寫法不在任何靜態清單裡。
 *
 * 設計取捨（避免拖慢啟動）：
 *  - **只在背景跑**，不阻塞 UI／語音指令。
 *  - 每次最多 N 個 App（預設 12），一天最多一輪；做完寫進 `AppAliasStore`，之後不再呼叫模型。
 *  - **碰撞保護**：模型的別名若已經屬於另一個 App 的 label/別名，直接丟掉
 *    （否則「看影片」可能被 Netflix 搶走）。
 *  - 只處理「別名很少」的 App（metadata 已有人工別名的優先跳過）。
 */
object AliasGenerator {

    private const val PREFS = "wae_alias_gen"
    private const val KEY_LAST_RUN = "last_run_ms"
    private const val MIN_INTERVAL_MS = 24L * 60L * 60L * 1000L

    /** 今天該不該跑（一天一次；App 有增減時 `AppCatalogWatcher` 會清目錄，但不會強制重跑）。 */
    fun isDue(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_RUN, 0L)
        return System.currentTimeMillis() - last >= MIN_INTERVAL_MS
    }

    private fun markRun(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_RUN, System.currentTimeMillis()).apply()
    }

    /** 找出「值得用模型衍生別名」的 App：別名少、且不是系統類 App。 */
    fun pickTargets(
        catalog: List<AppSelector.AppCandidate>,
        max: Int = 12
    ): List<AppSelector.AppCandidate> =
        catalog.filter { it.aliases.size < 2 }
            .filter { it.category != "SYSTEM" && it.category != "TOOLS" }
            .filter { it.label.length in 2..20 }
            .sortedBy { it.label.length }
            .take(max)

    /**
     * 背景執行一次。回傳實際寫入的 App 數。
     * 呼叫端請放在背景執行緒（例如 `WaeApp` 啟動時的 worker thread）。
     */
    fun runOnce(context: Context, maxApps: Int = 12): Int {
        if (!isDue(context)) return 0
        val catalog = runCatching { InstalledAppCatalog.load(context, force = true) }.getOrDefault(emptyList())
        if (catalog.isEmpty()) return 0

        // 碰撞保護：所有已存在的 label/別名（正規化後）
        val taken = HashSet<String>()
        for (c in catalog) {
            taken.add(TextNormalizer.normalize(c.label))
            for (a in c.aliases) taken.add(TextNormalizer.normalize(a))
        }

        val targets = pickTargets(catalog, maxApps)
        if (targets.isEmpty()) {
            markRun(context)
            return 0
        }

        var written = 0
        var planner: com.wae.agent.llm.LlmPlanner? = null
        try {
            targets.forEach { app ->
                val p = planner ?: com.wae.agent.llm.LlmPlanner(context).also { planner = it }
                val aliases = p.generateAliases(app.label)
                    .map { it.trim() }
                    .filter { it.length in 2..10 }
                    .filter { TextNormalizer.normalize(it) !in taken }
                    .distinct()
                if (aliases.isNotEmpty()) {
                    AppAliasStore.put(context, app.pkg, aliases)
                    for (a in aliases) taken.add(TextNormalizer.normalize(a))
                    written++
                    Log.i("WAE_ALIAS", "generated ${app.label} -> $aliases")
                }
            }
        } catch (t: Throwable) {
            Log.w("WAE_ALIAS", "alias generation failed: " + t.message)
        } finally {
            runCatching { planner?.unload() }
        }
        markRun(context)
        if (written > 0) InstalledAppCatalog.invalidate()
        Log.i("WAE_ALIAS", "run done: apps=$written")
        return written
    }

    fun resetForTest(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
