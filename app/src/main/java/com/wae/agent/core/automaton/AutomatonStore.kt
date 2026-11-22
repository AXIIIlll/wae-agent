package com.wae.agent.core.automaton

import android.content.Context
import java.io.File

/**
 * 自動機儲存：每個 App 一張圖，落在 `filesDir/automaton/<pkg>.json`。
 *
 * 兩個上限：
 *  - 單 App 由 [GraphBudget] 控制（複雜度自適應）
 *  - 全域總量（預設 2MB）超過時，刪除最久沒更新的 App 圖（可重建）
 *
 * 持久化格式就是 [Automaton.canonicalJson]，因此檔案的 sha256 可直接當版本指紋。
 */
object AutomatonStore {

    private const val DIR = "automaton"
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024

    @Volatile
    private var loadAttempted = false

    fun ensureLoaded(context: Context?) {
        if (loadAttempted || context == null) return
        loadAttempted = true
        GraphBudget.ensureLoaded(context)
    }

    fun resetForTest() {
        loadAttempted = false
    }

    /** 檔名安全化（套件名只含字母數字與點，但仍做防禦） */
    private fun fileNameOf(pkg: String): String =
        pkg.map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '_' }.joinToString("") + ".json"

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun fileOf(context: Context, pkg: String): File = File(dir(context), fileNameOf(pkg))

    fun load(context: Context?, pkg: String): Automaton? {
        if (context == null || pkg.isEmpty()) return null
        return try {
            val f = fileOf(context, pkg)
            if (!f.exists()) null else Automaton.fromJson(f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    fun save(context: Context?, pkg: String, automaton: Automaton) {
        if (context == null || pkg.isEmpty()) return
        try {
            fileOf(context, pkg).writeText(automaton.toJson(), Charsets.UTF_8)
            enforceGlobalCap(context)
        } catch (_: Throwable) {
        }
    }

    fun listPackages(context: Context?): List<String> {
        if (context == null) return emptyList()
        return dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }?.sorted().orEmpty()
    }

    fun delete(context: Context?, pkg: String) {
        if (context == null) return
        runCatching { fileOf(context, pkg).delete() }
    }

    fun clearAll(context: Context?) {
        if (context == null) return
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    fun totalBytes(context: Context?): Long {
        if (context == null) return 0L
        return dir(context).listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    /** 全域上限：超過就刪最久沒更新的（可重建，成本低） */
    private fun enforceGlobalCap(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_TOTAL_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /** 給測試與除錯：統計 */
    fun stats(context: Context?): String {
        val pkgs = listPackages(context)
        return "apps=${pkgs.size} bytes=${totalBytes(context)}"
    }
}
