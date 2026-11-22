package com.wae.agent.core.safety

import android.content.Context

/**
 * 系統安全套件清單（權限對話框、安裝器）。
 *
 * 為什麼要有這一層：這些套件名原本硬寫在程式裡（AOSP + Google 兩種），
 * 但各 OEM 的權限控制器套件名不同（MIUI / ColorOS / EMUI / One UI…），
 * 寫死會導致「該擋的沒擋」。依本專案政策，清單放在資料檔：
 * `assets/open_source_ai_databases/system_safety_packages.tsv`
 *
 * 安全設計：**失敗要往「更保守」的方向倒**。
 * 即使資料檔沒載入，仍以語意規則（套件名含 permissioncontroller / packageinstaller）擋住，
 * 不會因為載入失敗而放行。
 */
object SystemSafetyPackages {

    private const val DB_PATH = "open_source_ai_databases/system_safety_packages.tsv"

    /** 語意後備：資料檔不可用時仍能辨識的套件名特徵 */
    private val SEMANTIC_TOKENS = listOf("permissioncontroller", "packageinstaller")

    @Volatile
    private var explicit: Set<String> = emptySet()

    @Volatile
    private var loaded: Boolean = false

    fun isLoaded(): Boolean = loaded

    fun count(): Int = explicit.size

    /** 服務啟動時呼叫一次（失敗不影響流程，只會退回語意後備） */
    fun ensureLoaded(context: Context?) {
        if (loaded || context == null) return
        loaded = true
        try {
            val lines = context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            loadFromLines(lines)
        } catch (_: Throwable) {
            // 保持空集合，isBlocked 仍會用語意後備
        }
    }

    /** 由資料行載入（JVM 測試與 assets 共用同一條解析路徑） */
    fun loadFromLines(lines: List<String>) {
        val set = HashSet<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val pkg = line.split("\t").firstOrNull()?.trim().orEmpty()
            if (pkg.isNotEmpty()) set.add(pkg)
        }
        explicit = set
        loaded = true
    }

    fun resetForTest() {
        explicit = emptySet()
        loaded = false
    }

    /** 這個套件是否屬於「不可代操作」的系統畫面 */
    fun isBlocked(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (explicit.contains(pkg)) return true
        val p = pkg.lowercase()
        return SEMANTIC_TOKENS.any { p.contains(it) }
    }

    /** 供審計／除錯列出目前生效的清單 */
    fun all(): List<String> = explicit.sorted()
}
