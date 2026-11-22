package com.wae.agent.core.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 每個 App 的**額外別名**（衍生＋模型產生），存在 `filesDir/app_aliases.json`。
 *
 * 為什麼要落盤：模型衍生很貴（每個 App 一次生成），只做一次就快取；
 * App 增減時由 `AppCatalogWatcher` 觸發更新，不需要每次啟動重算。
 *
 * 檔案格式：{"pkg":["別名1","別名2"], ...}；只存 app 私有目錄，不上傳。
 */
object AppAliasStore {

    private const val FILE_NAME = "app_aliases.json"
    private val cache = HashMap<String, MutableList<String>>()
    private var loaded = false

    @Synchronized
    fun load(context: Context): Map<String, List<String>> {
        if (loaded) return cache
        loaded = true
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            if (f.isFile) {
                val obj = JSONObject(f.readText(Charsets.UTF_8))
                for (key in obj.keys()) {
                    val arr = obj.optJSONArray(key) ?: continue
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i).trim()
                        if (v.isNotEmpty()) list.add(v)
                    }
                    if (list.isNotEmpty()) cache[key] = list
                }
            }
        }
        return cache
    }

    fun aliasesOf(context: Context, pkg: String): List<String> = synchronized(this) {
        load(context)
        cache[pkg]?.toList() ?: emptyList()
    }

    @Synchronized
    fun put(context: Context, pkg: String, aliases: List<String>) {
        if (pkg.isBlank()) return
        load(context)
        val clean = aliases.map { it.trim() }.filter { it.length in 2..20 }.distinct()
        if (clean.isEmpty()) return
        cache.getOrPut(pkg) { ArrayList() }.apply {
            for (a in clean) if (!contains(a)) add(a)
        }
        save(context)
    }

    /** 移除已解除安裝 App 的別名（避免檔案無限成長）。 */
    @Synchronized
    fun prune(context: Context, installedPackages: Set<String>) {
        load(context)
        val before = cache.size
        cache.keys.removeAll { it !in installedPackages }
        if (cache.size != before) save(context)
    }

    private fun save(context: Context) {
        runCatching {
            val obj = JSONObject()
            for ((pkg, list) in cache) {
                if (list.isEmpty()) continue
                obj.put(pkg, org.json.JSONArray(list))
            }
            File(context.filesDir, FILE_NAME).writeText(obj.toString(), Charsets.UTF_8)
        }
    }

    fun resetForTest() {
        synchronized(this) {
            cache.clear()
            loaded = false
        }
    }
}
