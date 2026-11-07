package com.example.newandroidapp.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * 全動態實機 App 探索與自動啟動器（零寫死清單，100% 動態掃描手機已安裝 App）
 *
 * 結合：
 * 1. Android PackageManager 實時查詢手機上所有可啟動之 Launcher Apps
 * 2. 安卓原生 AI 字串與套件名對齊（0ms 直接匹配）
 * 3. Needle 3 (`needle_embed` 1024 維神經向量 + `open_app` Tool-Call) 語義匹配最合適的已安裝 App 並自動啟動
 */
object InstalledAppLauncher {

    data class InstalledAppInfo(
        val label: String,
        val packageName: String,
        val packageTail: String
    )

    @Volatile
    private var cachedApps: List<InstalledAppInfo> = emptyList()

    @Volatile
    private var lastScanTimeMs: Long = 0L

    private val appLabelCache = ConcurrentHashMap<String, String>()

    private fun isMainThread(): Boolean {
        return runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(false)
    }

    /**
     * 實時掃描手機上所有可啟動的 App（排除本工具自身）
     */
    fun getInstalledLaunchableApps(context: Context, forceRefresh: Boolean = false): List<InstalledAppInfo> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedApps.isNotEmpty() && (isMainThread() || (now - lastScanTimeMs) < 120_000L)) {
            return cachedApps
        }
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = runCatching {
            pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())

        val myPkg = context.packageName
        val map = LinkedHashMap<String, InstalledAppInfo>()
        for (ri in resolveList) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == myPkg) continue
            val label = appLabelCache[pkg] ?: runCatching {
                ri.loadLabel(pm).toString().trim()
            }.getOrDefault(pkg).also { resolved ->
                if (resolved.isNotEmpty()) appLabelCache[pkg] = resolved
            }
            if (label.isNotEmpty() && !map.containsKey(pkg)) {
                val tail = pkg.substringAfterLast('.')
                map[pkg] = InstalledAppInfo(label = label, packageName = pkg, packageTail = tail)
            }
        }
        val list = map.values.toList()
        cachedApps = list
        lastScanTimeMs = now
        return list
    }

    /**
     * 根據使用者自然語言指令（與 Needle 3 提取之 appHint），從手機已安裝 App 中自動找出目標 App
     */
    fun resolveTargetAppForGoal(
        context: Context,
        rawUserGoal: String,
        needleAppHint: String? = null
    ): Pair<InstalledAppInfo, Float>? {
        val apps = getInstalledLaunchableApps(context)
        if (apps.isEmpty()) return null

        val goalNorm = rawUserGoal.lowercase().replace(" ", "")
        val hintNorm = needleAppHint?.lowercase()?.replace(" ", "").orEmpty()

        // 0. 查詢 GitHub 開源主流 App 包名與口語俗稱別名庫 (`global_tw_cn_app_metadata.tsv`)
        val aliasMeta = OpenSourceAiDatabaseManager.resolveAppByColloquialAlias(
            needleAppHint?.takeIf { it.isNotBlank() } ?: rawUserGoal,
            context
        )
        if (aliasMeta != null) {
            val matchedByPkgOrName = apps.firstOrNull { app ->
                app.packageName.equals(aliasMeta.packageName, ignoreCase = true) ||
                    app.label.equals(aliasMeta.canonicalLabel, ignoreCase = true) ||
                    aliasMeta.aliases.contains(app.label.lowercase())
            }
            if (matchedByPkgOrName != null) {
                return matchedByPkgOrName to 0.99f
            }
        }

        // 1. 第一階段（安卓原生 0ms 精確匹配）：檢查使用者指令中是否直接包含某個已安裝 App 的名稱或套件尾名
        val directMatches = apps.mapNotNull { app ->
            val labelNorm = app.label.lowercase().replace(" ", "")
            val tailNorm = app.packageTail.lowercase()
            when {
                hintNorm.isNotEmpty() && (labelNorm == hintNorm || tailNorm == hintNorm) -> app to 0.99f
                labelNorm.length >= 2 && goalNorm.contains(labelNorm) -> {
                    // 名稱越長匹配權重越高（避免單字元誤中）
                    val bonus = (labelNorm.length.coerceAtMost(8) * 0.01f)
                    app to (0.90f + bonus).coerceAtMost(0.99f)
                }
                tailNorm.length >= 3 && goalNorm.contains(tailNorm) -> app to 0.88f
                hintNorm.length >= 2 && (labelNorm.contains(hintNorm) || hintNorm.contains(labelNorm)) -> app to 0.86f
                else -> null
            }
        }.sortedByDescending { it.second }

        if (directMatches.isNotEmpty()) {
            return directMatches.first()
        }

        // 2. 第二階段（Needle 3 `needle_embed` 1024 維神經向量語義檢索）：
        // 若使用者未直接講出完整 App 名稱，利用字元交集初篩 Top-12 已安裝 App，再以 Needle 3 神經嵌入向量選出最相關 App
        val queryText = needleAppHint?.takeIf { it.isNotBlank() } ?: rawUserGoal
        val preFiltered = apps
            .map { app -> app to charOverlap(queryText.lowercase(), app.label.lowercase()) }
            .sortedByDescending { it.second }
            .take(15)

        val allowBlockingIpc = !isMainThread()
        val needleReady = allowBlockingIpc && NeedleNativeBridge.ensureLoaded(context)
        val qVec = if (needleReady) NeedleNativeBridge.embedText(queryText) else FloatArray(0)

        var bestApp: InstalledAppInfo? = null
        var bestScore = 0f

        for ((app, lexicalScore) in preFiltered) {
            val neuralSim = if (needleReady && qVec.isNotEmpty()) {
                val appVec = NeedleNativeBridge.embedText(app.label)
                if (appVec.isNotEmpty()) NeedleNativeBridge.cosineSimilarity(qVec, appVec) else 0f
            } else {
                0f
            }
            val combined = max(lexicalScore * 0.8f, neuralSim * 0.65f + lexicalScore * 0.35f)
            if (combined > bestScore) {
                bestScore = combined
                bestApp = app
            }
        }

        if (bestApp != null && bestScore >= 0.48f) {
            return bestApp to bestScore
        }
        return null
    }

    /**
     * 根據 PackageName 取得當前 App 的顯示名稱（供浮動窗即時顯示當前正在操作的 App）
     */
    fun getAppLabelByPackage(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return "當前畫面"
        if (packageName == context.packageName) return "AI 助手主程式"
        appLabelCache[packageName]?.let { return it }
        val fromCache = cachedApps.firstOrNull { it.packageName == packageName }
        if (fromCache != null) {
            appLabelCache[packageName] = fromCache.label
            return fromCache.label
        }
        return runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString().trim().ifEmpty { packageName.substringAfterLast('.') }
        }.getOrDefault(packageName.substringAfterLast('.')).also { resolved ->
            if (resolved.isNotEmpty()) {
                appLabelCache[packageName] = resolved
            }
        }
    }

    /**
     * 真實啟動目標 App
     */
    fun launchApp(context: Context, packageName: String): Boolean {
        return runCatching {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    private fun charOverlap(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val sa = a.filter { !it.isWhitespace() }.toSet()
        val sb = b.filter { !it.isWhitespace() }.toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0f
        val inter = sa.intersect(sb).size.toFloat()
        return inter / max(1, minOf(sa.size, sb.size)).toFloat()
    }
}
