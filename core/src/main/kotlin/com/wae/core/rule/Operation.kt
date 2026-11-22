package com.wae.core.rule

/**
 * 操作（Operation）＝手機真正能做的事（十幾種）。
 *
 * 為什麼要獨立一張表：目的（要什麼）與操作（怎麼做）是不同層。
 * 同一個目的可以用不同操作達成，選哪個看「前提是否成立 + 成本」。
 */
data class Operation(
    val id: String,
    /** 前提（token，例 `app_installed(app)`、`write_settings`）。 */
    val pre: String,
    /** 成本（越小越優先）：SYSTEM_API/INTENT=1、A11Y=3、INPUT=4、GESTURE=5。 */
    val cost: Int,
    /** 執行前要怎麼確認「現在做得到」。 */
    val probe: String = "",
    /** 失敗後可以換哪個操作（同目的）。 */
    val fallback: String = ""
)

object OperationTable {

    fun parse(lines: Sequence<String>): List<Operation> {
        val out = ArrayList<Operation>()
        for (raw in lines) {
            val line = raw.trimStart('\uFEFF').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val c = line.split('\t')
            if (c.size < 3) continue
            val id = c[0].trim()
            val cost = c[2].trim().toIntOrNull() ?: continue
            if (id.isEmpty()) continue
            out.add(
                Operation(
                    id = id,
                    pre = c[1].trim(),
                    cost = cost,
                    probe = c.getOrNull(3)?.trim().orEmpty(),
                    fallback = c.getOrNull(4)?.trim().orEmpty()
                )
            )
        }
        return out
    }
}
