package com.wae.agent.core.capability

/**
 * 掃描規劃器（純邏輯，可在 JVM 單元測試）。
 *
 * 職責：決定「這次要掃哪些、掃多少、是全量還是增量」，把成本控制在預算內。
 * 這是自動掃描能安全跑在別人的手機上的關鍵——不能每次啟動都全量枚舉 300 個 App。
 */
object CapabilityScanPlanner {

    enum class Mode { FULL_SCAN, INCREMENTAL_SCAN, SKIP }

    /**
     * @param previous 上次快照（null = 從未掃過）
     * @param currentVersionCodes 目前系統上各 App 的 versionCode
     * @param nowMs 現在時間
     * @param dirty 是否收到安裝/卸載/更新的廣播
     */
    fun decide(
        previous: CapabilitySnapshot?,
        currentVersionCodes: Map<String, Long>,
        nowMs: Long,
        options: ScanOptions,
        dirty: Boolean
    ): Mode {
        if (previous == null) return Mode.FULL_SCAN
        if (previous.schemaVersion != CapabilitySchema.VERSION) return Mode.FULL_SCAN

        val expired = nowMs - previous.builtAt > options.rescanTtlMs
        if (expired) return Mode.FULL_SCAN

        if (dirty) return Mode.INCREMENTAL_SCAN

        // 即使沒有廣播，版本號變了也要增量補掃（例如被系統靜默更新）。
        // 注意：若上次是「部分掃描」(partial)，快照裡本來就缺一些套件，
        // 不能把「快照裡沒有的套件」當成變動，否則會永遠判定為 incremental。
        val prevCodes = previous.versionCodes()
        val versionChanged = currentVersionCodes.any { (pkg, v) ->
            val old = prevCodes[pkg]
            old != null && old != v
        }
        val missingPackage = if (previous.partial) {
            false
        } else {
            prevCodes.keys.any { it !in currentVersionCodes }
        }
        return if (versionChanged || missingPackage) Mode.INCREMENTAL_SCAN else Mode.SKIP
    }

    /** 依選項過濾並裁切上限（先保留可啟動的，避免被系統 App 佔滿名額） */
    fun filterAndCap(raw: List<RawAppInfo>, options: ScanOptions): List<RawAppInfo> {
        val filtered = raw.asSequence()
            .filter { options.includeSystemApps || !it.isSystem }
            .filter { options.includeNonLaunchable || it.launchable }
            .sortedWith(compareByDescending<RawAppInfo> { it.launchable }.thenBy { it.pkg })
            .toList()
        return if (filtered.size <= options.maxApps) filtered else filtered.subList(0, options.maxApps)
    }

    /**
     * 增量掃描時，把新結果合併進舊快照：
     * 依 pkg 覆蓋，已卸載的移除。
     */
    fun merge(
        previous: CapabilitySnapshot?,
        rescanned: List<RawAppInfo>,
        currentPackages: Set<String>
    ): List<RawAppInfo> {
        val byPkg = LinkedHashMap<String, RawAppInfo>()
        previous?.apps?.forEach { if (it.pkg in currentPackages) byPkg[it.pkg] = it }
        rescanned.forEach { byPkg[it.pkg] = it }
        return byPkg.values.toList()
    }
}
