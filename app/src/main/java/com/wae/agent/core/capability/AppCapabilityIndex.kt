package com.wae.agent.core.capability

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自動掃描引擎（綠區）。
 *
 * 設計目標：**任何人都能拿這個 App 去掃他自己手機上的 App**，不需要我們在旁協助。
 * 因此它必須自我調度：
 *  - 首次啟動：全量掃描（背景執行，不阻塞 UI）
 *  - 之後：TTL 內且沒有變動 → 直接跳過（成本 0）
 *  - 安裝/卸載/更新（PackageChangeReceiver）→ 增量重掃
 *  - 版本號變了（靜默更新）→ 增量重掃
 *
 * 法律邊界見 docs/GREEN_ZONE_POLICY.md：只用 PackageManager / Intent 解析等公開 API。
 */
object AppCapabilityIndex : CapabilityQuery {

    private const val TAG = "WAE_CAPABILITY"
    private const val CACHE_FILE = "app_capability_index.json"
    private const val DIRTY_FILE = "app_capability_dirty"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wae-capability-scan").apply { priority = Thread.MIN_PRIORITY }
    }
    private val running = AtomicBoolean(false)

    /**
     * 是否有「執行期」偵測到的套件變動（安裝/卸載廣播）。
     * 初值必須是 false：程序重啟後要靠版本號比對（decide）判斷是否有變動，
     * 若初值為 true，每次重啟都會多做一次增量掃描。
     */
    private val dirty = AtomicBoolean(false)

    @Volatile
    private var snapshot: CapabilitySnapshot? = null

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var lastScanMs: Long = 0L
        private set

    @Volatile
    var lastMode: CapabilityScanPlanner.Mode = CapabilityScanPlanner.Mode.SKIP
        private set

    /** 由 PackageChangeReceiver 呼叫：標記「有變動，下次要增量掃」 */
    fun markDirty(context: Context? = null, reason: String = "manual") {
        dirty.set(true)
        // 持久化：package 變動廣播常在我們程序沒跑的時候發生（例如系統更新 App），
        // 只放記憶體會在進程重啟後遺失，導致下次啟動誤判為「無變動」。
        if (context != null) {
            try {
                File(context.filesDir, DIRTY_FILE).writeText(reason, Charsets.UTF_8)
            } catch (_: Throwable) {
            }
        }
        Log.i(TAG, "dirty: $reason")
    }

    /**
     * 產品入口：確保能力圖譜是新鮮的。
     * 非阻塞；多數情況下（快取有效且無變動）什麼都不做。
     */
    fun ensureFresh(
        context: Context,
        options: ScanOptions = ScanOptions(),
        force: Boolean = false,
        onDone: ((CapabilitySnapshot?) -> Unit)? = null
    ) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                scanInternal(context.applicationContext, options, force)
                onDone?.invoke(snapshot)
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "scan failed", t)
                onDone?.invoke(null)
            } finally {
                running.set(false)
            }
        }
    }

    private fun scanInternal(context: Context, options: ScanOptions, force: Boolean) {
        val prev = snapshot ?: loadSnapshot(context)
        snapshot = prev
        val pm = context.packageManager
        val currentCodes = readVersionCodes(pm)
        val now = System.currentTimeMillis()

        val mode = if (force) CapabilityScanPlanner.Mode.FULL_SCAN else
            CapabilityScanPlanner.decide(
                previous = prev,
                currentVersionCodes = currentCodes,
                nowMs = now,
                options = options,
                dirty = dirty.get() || File(context.filesDir, DIRTY_FILE).exists()
            )

        lastMode = mode
        if (mode == CapabilityScanPlanner.Mode.SKIP) {
            Log.i(TAG, "skip: cache fresh (apps=${prev?.apps?.size ?: 0})")
            dirty.set(false)
            return
        }

        val started = android.os.SystemClock.elapsedRealtime()
        val deadline = started + options.timeBudgetMs

        // 增量掃描：只重讀「版本變了或新安裝」的套件，其餘沿用舊快照。
        // 沒有這一步，incremental 只是「合併」而非「少做事」，成本仍然接近全量。
        val prevCodes = prev?.versionCodes() ?: emptyMap()
        val onlyPackages: Set<String>? = if (mode == CapabilityScanPlanner.Mode.INCREMENTAL_SCAN) {
            currentCodes.filter { (pkg, v) -> prevCodes[pkg] != v }.keys
        } else {
            null
        }

        val (loaded, partial) = readAppInfos(pm, options, deadline, onlyPackages)

        val merged = if (mode == CapabilityScanPlanner.Mode.FULL_SCAN) {
            CapabilityScanPlanner.filterAndCap(loaded, options)
        } else {
            // 關鍵：currentPackages 必須是「裝置上目前的全部套件」，
            // 不能只傳這次重掃到的（那會把沒變動的 App 全部當成已卸載而丟掉）。
            CapabilityScanPlanner.filterAndCap(
                CapabilityScanPlanner.merge(prev, loaded, currentCodes.keys),
                options
            )
        }

        val next = CapabilitySnapshot(
            schemaVersion = CapabilitySchema.VERSION,
            builtAt = now,
            partial = partial,
            apps = merged
        )
        snapshot = next
        dirty.set(false)
        lastScanMs = android.os.SystemClock.elapsedRealtime() - started
        lastError = ""
        dirty.set(false)
        try {
            File(context.filesDir, DIRTY_FILE).delete()
        } catch (_: Throwable) {
        }
        persist(context, next)
        Log.i(
            TAG,
            "scan ${mode.name}: apps=${next.apps.size} launchable=${next.apps.count { it.launchable }} " +
                "partial=$partial ${lastScanMs}ms"
        )
    }

    private fun readVersionCodes(pm: PackageManager): Map<String, Long> {
        return try {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0).associate { it.packageName to it.longVersionCode }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    /** 綠區讀取：只碰 PackageManager 的公開欄位 */
    private fun readAppInfos(
        pm: PackageManager,
        options: ScanOptions,
        deadlineMs: Long,
        onlyPackages: Set<String>? = null
    ): Pair<List<RawAppInfo>, Boolean> {
        val out = ArrayList<RawAppInfo>(options.maxApps)
        var partial = false
        try {
            @Suppress("DEPRECATION")
            val packages: List<PackageInfo> = pm.getInstalledPackages(
                PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PERMISSIONS
            )
            for (info in packages) {
                if (out.size >= options.maxApps) { partial = true; break }
                if (android.os.SystemClock.elapsedRealtime() > deadlineMs) { partial = true; break }
                val pkg = info.packageName ?: continue
                // 增量模式：未變動的套件直接跳過（連 getLaunchIntentForPackage 都不呼叫）
                if (onlyPackages != null && pkg !in onlyPackages) continue
                val appInfo: ApplicationInfo = info.applicationInfo ?: continue
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!options.includeSystemApps && isSystem) continue
                val launchable = pkg == "android" || pm.getLaunchIntentForPackage(pkg) != null
                if (!options.includeNonLaunchable && !launchable) continue

                val exported = info.activities
                    ?.filter { it.exported }
                    ?.mapNotNull { it.name }
                    ?: emptyList()
                out.add(
                    RawAppInfo(
                        pkg = pkg,
                        label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                            .getOrDefault(pkg),
                        versionCode = info.longVersionCode,
                        installer = runCatching {
                            pm.getInstallSourceInfo(pkg).installingPackageName.orEmpty()
                        }.getOrDefault(""),
                        category = runCatching { appInfo.category }.getOrDefault(0),
                        targetSdk = appInfo.targetSdkVersion,
                        launchable = launchable,
                        isSystem = isSystem,
                        permissions = info.requestedPermissions?.toList() ?: emptyList(),
                        exportedActivities = exported.take(40)
                    )
                )
            }
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "readAppInfos failed", t)
        }
        return Pair(out, partial)
    }

    /** 綠區探測：問系統「這個 URI 有沒有 App 能路由」（執行時用，不枚舉） */
    fun canRoute(context: Context, uriString: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    // ── CapabilityQuery ────────────────────────────────────────────────
    override fun all(): List<RawAppInfo> = snapshot?.apps ?: emptyList()

    override fun byPackage(pkg: String): RawAppInfo? = all().firstOrNull { it.pkg == pkg }

    override fun launchable(): List<RawAppInfo> = all().filter { it.launchable }

    override fun byCategory(category: Int): List<RawAppInfo> = all().filter { it.category == category }

    override fun requiringPermission(permission: String): List<RawAppInfo> =
        all().filter { it.permissions.contains(permission) }

    override fun statusLine(): String {
        val s = snapshot
        return if (s == null) {
            "未建立" + (if (lastError.isEmpty()) "" else " · $lastError")
        } else {
            "${s.apps.size} 個 App · ${s.apps.count { it.launchable }} 可啟動 · " +
                "${s.apps.sumOf { it.exportedActivities.size }} 個對外入口" +
                (if (s.partial) "（部分）" else "")
        }
    }

    fun currentSnapshot(): CapabilitySnapshot? = snapshot

    fun shouldScanNow(nowMs: Long = System.currentTimeMillis(), options: ScanOptions = ScanOptions()): Boolean {
        val s = snapshot
        if (s == null) return true
        return CapabilityScanPlanner.decide(
            s, s.versionCodes(), nowMs, options, dirty.get()
        ) != CapabilityScanPlanner.Mode.SKIP
    }

    private fun persist(context: Context, snap: CapabilitySnapshot) {
        try {
            File(context.filesDir, CACHE_FILE).writeText(snap.toJson(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "persist failed: ${t.message}")
        }
    }

    private fun loadSnapshot(context: Context): CapabilitySnapshot? {
        return try {
            val f = File(context.filesDir, CACHE_FILE)
            if (!f.exists()) return null
            CapabilitySnapshot.parse(f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    /** 使用者要求遺忘（也供第三方開發者清空） */
    fun clear(context: Context) {
        snapshot = null
        lastError = ""
        dirty.set(true)
        try {
            File(context.filesDir, CACHE_FILE).delete()
        } catch (_: Throwable) {
        }
    }
}
