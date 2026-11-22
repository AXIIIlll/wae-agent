package com.wae.agent.core.omega

import android.content.Context
import java.io.File

/**
 * WAE-Ω 第 2 層（續）：App Model 的內容定址儲存（ModelStore）。
 *
 * 「內容定址」的意義：
 *  - 檔名 = `<pkg>-<sha256 前 12 碼>.model`，同一份模型永遠得到同一個位址
 *  - 讀回時重算雜湊驗證（`verifyIntegrity()`）→ 壞檔/被改過的模型直接拒用
 *  - 模型本身**不含使用者內容**（只有狀態/轉移/欄位 schema），因此可以離線分享
 *
 * 儲存位置：`filesDir/appmodel/`；Context 為 null 時走記憶體模式（JVM 測試用）。
 */
object ModelStore {

    private const val MAX_FILES = 100

    /** pkg -> 最新模型（記憶體索引；也是 null-context 模式的唯一儲存） */
    private val index = LinkedHashMap<String, AppModel>()

    private var appContext: Context? = null

    fun ensureLoaded(context: Context?) {
        if (context == null) return
        if (appContext == null) appContext = context.applicationContext
        if (index.isNotEmpty()) return
        runCatching {
            val dir = dir() ?: return
            if (!dir.exists()) return
            val latest = HashMap<String, AppModel>()
            for (f in dir.listFiles() ?: emptyArray()) {
                if (!f.name.endsWith(".model")) continue
                val model = runCatching { AppModel.fromLineFormat(f.readText(Charsets.UTF_8)) }
                    .getOrNull() ?: continue
                if (!model.verifyIntegrity()) continue
                val prev = latest[model.pkg]
                if (prev == null || model.version >= prev.version) latest[model.pkg] = model
            }
            synchronized(index) {
                index.clear()
                index.putAll(latest)
            }
        }
    }

    fun resetForTest() {
        synchronized(index) { index.clear() }
        appContext = null
    }

    fun count(): Int = synchronized(index) { index.size }

    fun all(): List<AppModel> = synchronized(index) { index.values.toList() }

    fun dir(): File? = appContext?.let { File(it.filesDir, "appmodel") }

    /**
     * 存檔（自動補 hash）。回傳是否成功；同 pkg 以 index 內最新版為準。
     */
    fun save(input: AppModel): Boolean {
        if (input.pkg.isBlank()) return false
        val model = input.withComputedHash()
        synchronized(index) {
            index[model.pkg] = model
            while (index.size > MAX_FILES) {
                val first = index.keys.firstOrNull() ?: break
                index.remove(first)
            }
        }
        val d = dir() ?: return true
        return runCatching {
            d.mkdirs()
            File(d, fileName(model)).writeText(model.toLineFormat(), Charsets.UTF_8)
            prune(d)
            true
        }.getOrDefault(false)
    }

    /** 讀取（記憶體索引優先；否則掃目錄找最新且雜湊正確的） */
    fun load(pkg: String): AppModel? {
        if (pkg.isBlank()) return null
        synchronized(index) { index[pkg] }?.let { return it }
        val d = dir() ?: return null
        return runCatching {
            val candidates = (d.listFiles() ?: emptyArray())
                .filter { it.name.startsWith(sanitize(pkg) + "-") && it.name.endsWith(".model") }
                .sortedByDescending { it.lastModified() }
            for (f in candidates) {
                val m = AppModel.fromLineFormat(f.readText(Charsets.UTF_8)) ?: continue
                if (m.pkg != pkg || !m.verifyIntegrity()) continue
                synchronized(index) { index[pkg] = m }
                return m
            }
            null
        }.getOrNull()
    }

    /** 匯入文字模型（驗證雜湊後存入） */
    fun importModel(text: String): AppModel? {
        val model = AppModel.fromLineFormat(text) ?: return null
        if (!model.verifyIntegrity()) return null
        if (!save(model)) return null
        return model
    }

    /** 匯出（可分享的純文字模型；不含任何使用者內容） */
    fun exportModel(pkg: String): String? = load(pkg)?.toLineFormat()

    fun clear(pkg: String): Boolean {
        synchronized(index) { index.remove(pkg) }
        val d = dir() ?: return true
        return runCatching {
            (d.listFiles() ?: emptyArray())
                .filter { it.name.startsWith(sanitize(pkg) + "-") }
                .forEach { it.delete() }
            true
        }.getOrDefault(false)
    }

    fun clearAll(): Boolean {
        synchronized(index) { index.clear() }
        val d = dir() ?: return true
        return runCatching {
            (d.listFiles() ?: emptyArray()).forEach { it.delete() }
            true
        }.getOrDefault(false)
    }

    private fun fileName(model: AppModel): String =
        sanitize(model.pkg) + "-" + model.hash.take(12) + ".model"

    private fun sanitize(raw: String): String {
        val sb = StringBuilder()
        for (c in raw) {
            sb.append(if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_')
        }
        return if (sb.isEmpty()) "app" else sb.toString()
    }

    private fun prune(d: File) {
        val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_FILES) return
        files.take(files.size - MAX_FILES).forEach { it.delete() }
    }
}
