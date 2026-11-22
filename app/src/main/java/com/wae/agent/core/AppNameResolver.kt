package com.wae.agent.core

import android.content.Context
import android.content.pm.PackageManager

/**
 * App 名稱 → package 的通用解析（資料驅動，不寫死清單）。
 *
 * 來源：
 *  1. `global_tw_cn_app_metadata.tsv`（package / 名稱 / 分類 / 別名）
 *  2. PackageManager 的安裝 App 標籤（精確/包含比對）
 */
object AppNameResolver {

    private data class Entry(val pkg: String, val name: String, val aliases: List<String>)

    private var entries: List<Entry> = emptyList()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val list = ArrayList<Entry>()
        runCatching {
            context.assets.open("open_source_ai_databases/global_tw_cn_app_metadata.tsv")
                .bufferedReader().useLines { seq ->
                    seq.forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        val p = line.split('\t')
                        if (p.size >= 4) {
                            list.add(
                                Entry(
                                    pkg = p[0].trim(),
                                    name = p[1].trim(),
                                    aliases = p[3].split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                                )
                            )
                        }
                    }
                }
        }
        entries = list
        loaded = true
    }

    fun resolve(context: Context, spoken: String): String? {
        val q = spoken.lowercase().trim()
        if (q.isEmpty()) return null
        ensureLoaded(context)
        val pm = context.packageManager
        // 1) 先比對資料庫（別名 > 名稱）
        entries.forEach { e ->
            if (q == e.name.lowercase()) return e.pkg
        }
        entries.forEach { e ->
            if (e.aliases.any { a -> q == a || q.contains(a) || a.contains(q) }) return e.pkg
        }
        entries.forEach { e ->
            if (q.contains(e.name.lowercase()) || e.name.lowercase().contains(q)) return e.pkg
        }
        // 2) 比對已安裝 App 標籤
        return runCatching {
            pm.getInstalledApplications(0).firstOrNull { app ->
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
                label.equals(spoken, ignoreCase = true) || label.lowercase().contains(q) || q.contains(label.lowercase())
            }?.packageName
        }.getOrNull()
    }
}
