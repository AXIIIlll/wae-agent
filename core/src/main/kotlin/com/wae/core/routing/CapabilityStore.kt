package com.wae.core.routing

import java.io.File

/**
 * 能力索引的落盤（B14）。
 *
 * 為什麼要落盤：路線的可靠度是「越用越準」的資產；
 * 重開 App 就歸零的話，效果路由永遠停在第一天。
 */
class CapabilityStore(private val file: File) {

    fun load(): List<Capability> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Capability>()
        runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trimStart('\uFEFF').trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    decode(line)?.let { out.add(it) }
                }
            }
        }
        return out
    }

    /** 原子寫入：先寫 tmp 再 rename。 */
    fun save(rows: List<Capability>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (c in rows) {
            sb.append(c.appId).append('\t')
                .append(c.goalKind.name).append('\t')
                .append(c.attempts).append('\t')
                .append(c.successes).append('\t')
                .append(c.avgSteps).append('\n')
        }
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun decode(line: String): Capability? {
        val c = line.split('\t')
        if (c.size < COLUMNS) return null
        val kind = runCatching { GoalKind.valueOf(c[1]) }.getOrNull() ?: return null
        return Capability(
            appId = c[0],
            goalKind = kind,
            attempts = c[2].toIntOrNull() ?: 0,
            successes = c[3].toIntOrNull() ?: 0,
            avgSteps = c[4].toIntOrNull() ?: 0
        )
    }

    companion object {
        const val COLUMNS = 5
        const val HEADER = "# app_id\tgoal_kind\tattempts\tsuccesses\tavg_steps"
    }
}
