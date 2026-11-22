package com.wae.core.memory

import java.io.File

/**
 * 路徑快取（C7）：把「這台機器上這句話成功的走法」記下來，下次優先走同一條。
 *
 * 記的是 **hint（偏好手段）**，不是整段腳本——所以 UI 改版時只會退化成重新規劃，
 * 不會像錄製腳本那樣整套失效。
 */
class ProgramCache(private val file: File) {

    data class Entry(
        val commandHash: String,
        val pkg: String,
        val appVersion: Long,
        val effectKey: String,
        val means: String,
        val hits: Int = 1
    )

    private val byKey = LinkedHashMap<String, Entry>()

    init {
        load()
    }

    fun hintsFor(commandHash: String, pkg: String, appVersion: Long): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (e in byKey.values) {
            if (e.commandHash == commandHash && e.pkg == pkg && e.appVersion == appVersion) {
                out[e.effectKey] = e.means
            }
        }
        return out
    }

    fun record(commandHash: String, pkg: String, appVersion: Long, effectKey: String, means: String) {
        val key = keyOf(commandHash, pkg, appVersion, effectKey)
        val prev = byKey[key]
        byKey[key] = Entry(commandHash, pkg, appVersion, effectKey, means, (prev?.hits ?: 0) + 1)
    }

    fun forget(commandHash: String, pkg: String, appVersion: Long, effectKey: String) {
        byKey.remove(keyOf(commandHash, pkg, appVersion, effectKey))
    }

    fun size(): Int = byKey.size

    fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val sb = StringBuilder(HEADER + "\n")
        for (e in byKey.values) {
            sb.append(e.commandHash).append('\t')
                .append(e.pkg).append('\t')
                .append(e.appVersion).append('\t')
                .append(e.effectKey).append('\t')
                .append(e.means).append('\t')
                .append(e.hits).append('\n')
        }
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    if (raw.isBlank() || raw.startsWith("#")) continue
                    val c = raw.split('\t')
                    if (c.size < COLUMNS) continue
                    val entry = Entry(c[0], c[1], c[2].toLongOrNull() ?: 0L, c[3], c[4], c[5].toIntOrNull() ?: 1)
                    byKey[keyOf(entry.commandHash, entry.pkg, entry.appVersion, entry.effectKey)] = entry
                }
            }
        }
    }

    private fun keyOf(commandHash: String, pkg: String, appVersion: Long, effectKey: String): String =
        commandHash + '\u0001' + pkg + '\u0001' + appVersion + '\u0001' + effectKey

    companion object {
        const val COLUMNS = 6
        const val HEADER = "# command_hash\tpkg\tapp_version\teffect\tmeans\thits"
    }
}
