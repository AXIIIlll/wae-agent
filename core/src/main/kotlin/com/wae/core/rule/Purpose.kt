package com.wae.core.rule

/**
 * 目的（Purpose）＝分類法的葉節點（`docs/WAE_Purpose_Taxonomy.md`）。
 *
 * 鐵律：
 *  1. 沒有 `post` 的目的不准存在（無法驗證＝不存在的目的）。
 *  2. `risk=HIGH` 或 `reversible=false` → 只導覽、不執行最後一步（由規則引擎強制）。
 *  3. 目的只描述「要達成什麼」，不描述「怎麼做」——怎麼做是 `Method`。
 */
data class Purpose(
    val id: String,
    /** 所屬大類（A–J），用來分組與統計。 */
    val domain: String,
    /** 需要的槽位名稱，例：["app"]。 */
    val slots: List<String>,
    /** 必要槽位（沒有就必須反問）。 */
    val requiredSlots: List<String>,
    /** 可驗證的達成條件（token，例 `pkg_matches(app)`）。 */
    val post: String,
    val risk: Risk,
    val reversible: Boolean,
    /** 可逆時怎麼還原；不可逆留空。 */
    val undo: String = "",
    /** 允許的操作 id（對應 `ui/operations.tsv`）。 */
    val operations: List<String> = emptyList(),
    /** 高風險時是否「只導覽、不執行最後一步」。 */
    val navigateOnly: Boolean = false
) {
    val highRisk: Boolean get() = risk == Risk.HIGH || !reversible
}

enum class Risk { LOW, MEDIUM, HIGH }

/**
 * 解析 `ui/purposes.tsv`。
 *
 * 格式：`id<TAB>domain<TAB>slots(|)<TAB>required(|)<TAB>post<TAB>risk<TAB>reversible<TAB>undo<TAB>operations(|)<TAB>navigate_only`
 * 註解與空行跳過；欄位不足或非法值就整列跳過（不讓壞資料進來）。
 */
object PurposeTable {

    fun parse(lines: Sequence<String>): List<Purpose> {
        val out = ArrayList<Purpose>()
        for (raw in lines) {
            val line = raw.trimStart('\uFEFF').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val c = line.split('\t')
            if (c.size < 7) continue
            val id = c[0].trim()
            if (id.isEmpty()) continue
            val post = c[4].trim()
            if (post.isEmpty()) continue // 沒有驗證條件 → 不准進表
            val risk = runCatching { Risk.valueOf(c[5].trim().uppercase()) }.getOrNull() ?: continue
            val reversible = c[6].trim().equals("true", ignoreCase = true)
            out.add(
                Purpose(
                    id = id,
                    domain = c[1].trim(),
                    slots = Slots.canonical(splitList(c.getOrNull(2))),
                    requiredSlots = Slots.canonical(splitList(c.getOrNull(3))),
                    post = post,
                    risk = risk,
                    reversible = reversible,
                    undo = c.getOrNull(7)?.trim().orEmpty(),
                    operations = splitList(c.getOrNull(8)),
                    navigateOnly = c.getOrNull(9)?.trim()?.equals("true", true) ?: false
                )
            )
        }
        return out
    }

    private fun splitList(cell: String?): List<String> =
        cell?.trim()?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
