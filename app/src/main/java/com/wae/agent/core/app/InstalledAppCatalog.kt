package com.wae.agent.core.app

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.wae.agent.core.WaeOpenSourceDatabaseRegistry

/**
 * 已安裝 App 目錄：把 PackageManager + 專案 App metadata +（可選）使用量
 * 組成 `AppSelector` 需要的候選清單。
 *
 * 類別來源優先序：
 *   1. `global_tw_cn_app_metadata.tsv`（人工整理、有中文別名與細分類別）
 *   2. Android `ApplicationInfo.category`（粗略，很多 App 沒設）
 *   3. 空字串（只能靠名稱/別名）
 *
 * 使用量需要 `PACKAGE_USAGE_STATS`（AppOps 授權）；沒有授權時回空 map，功能自動退化。
 */
object InstalledAppCatalog {

    private val cache = ArrayList<AppSelector.AppCandidate>()
    private var cachedAtMs = 0L
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private val usageCache = HashMap<String, Long>()
    private var usageCachedAtMs = 0L
    private const val USAGE_TTL_MS = 60 * 1000L

    fun load(context: Context, force: Boolean = false): List<AppSelector.AppCandidate> {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            if (!force && cache.isNotEmpty() && now - cachedAtMs < CACHE_TTL_MS) return cache.toList()
        }
        WaeOpenSourceDatabaseRegistry.ensureLoaded(context)
        val pm = context.packageManager
        val meta = WaeOpenSourceDatabaseRegistry.appMetadataList.associateBy { it.packageName }
        val out = ArrayList<AppSelector.AppCandidate>()
        val seen = HashSet<String>()

        // 一次查「有啟動入口的 App」（比對每個 package 呼叫 getLaunchIntentForPackage 快很多）
        val launchables = runCatching {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
                0
            )
        }.getOrDefault(emptyList())

        for (ri in launchables) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm)?.toString()?.trim() }.getOrNull()
            if (label.isNullOrEmpty()) continue

            val known = meta[pkg]
            val androidCategory = runCatching {
                pm.getApplicationInfo(pkg, 0).category
            }.getOrDefault(0)
            val category = known?.category?.takeIf { it.isNotBlank() }
                ?: androidCategoryToOurs(androidCategory)
            // 別名 = metadata 的人工別名 + 衍生（簡繁/去後綴/大小寫）+ 落盤的模型別名
            val aliases = AppAliasHeuristics.withDerived(
                label,
                (known?.aliases ?: emptyList()) + AppAliasStore.aliasesOf(context, pkg)
            )
            out.add(AppSelector.AppCandidate(label, pkg, category, aliases))
        }
        synchronized(cache) {
            cache.clear()
            cache.addAll(out)
            cachedAtMs = now
        }
        return out
    }

    /** Android 的粗分類 → 專案 metadata 的類別名稱（只映常見的幾個）。 */
    fun androidCategoryToOurs(category: Int): String = when (category) {
        ApplicationInfo.CATEGORY_VIDEO -> "VIDEO_MEDIA"
        ApplicationInfo.CATEGORY_AUDIO -> "MUSIC_MEDIA"
        ApplicationInfo.CATEGORY_MAPS -> "MAPS_NAV"
        ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL"
        ApplicationInfo.CATEGORY_IMAGE -> "GALLERY"
        ApplicationInfo.CATEGORY_GAME -> "GAME"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
        ApplicationInfo.CATEGORY_NEWS -> "BROWSER"
        else -> ""
    }

    /** 近 N 天的前景使用時間（毫秒）。沒有 PACKAGE_USAGE_STATS 時回空 map。 */
    fun usageCounts(context: Context, days: Int = 30): Map<String, Long> {
        val now = System.currentTimeMillis()
        synchronized(usageCache) {
            if (usageCache.isNotEmpty() && now - usageCachedAtMs < USAGE_TTL_MS) return usageCache.toMap()
        }
        if (!hasUsageAccess(context)) return emptyMap()
        val result = runCatching {
            val mgr = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = now
            val start = end - days * 24L * 60L * 60L * 1000L
            val stats = mgr.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end) ?: return emptyMap()
            val out = HashMap<String, Long>()
            for (s in stats) {
                if (s.totalTimeInForeground <= 0L) continue
                out[s.packageName] = (out[s.packageName] ?: 0L) + s.totalTimeInForeground
            }
            out
        }.getOrDefault(emptyMap())
        synchronized(usageCache) {
            usageCache.clear()
            usageCache.putAll(result)
            usageCachedAtMs = now
        }
        return result
    }

    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun resetForTest() {
        synchronized(cache) {
            cache.clear()
            cachedAtMs = 0L
        }
    }

    /** App 增減／更新時呼叫，讓下一次解析重建目錄。 */
    fun invalidate() {
        synchronized(cache) {
            cache.clear()
            cachedAtMs = 0L
        }
        synchronized(usageCache) {
            usageCache.clear()
            usageCachedAtMs = 0L
        }
    }
}
