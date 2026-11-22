package com.wae.core.rule

/**
 * 每 App 的能力表（從實際結果學來的「這支手機／這個 App 做得到什麼」）。
 *
 * 為什麼需要：大模型不知道手機上有什麼——它會推論出「點下載」，
 * 但這個 App 可能根本沒有下載功能。把「成功／失敗」記下來，
 * 之後就能：
 *  1. 告訴模型「這條路真的存在」（而不是讓它幻想）
 *  2. 連續失敗時記成「這個 App 不支援這個目的」，不要每次都重試
 *
 * 資料只存結構（pkg、目的、次數、時間），不含畫面內容。
 */
data class AppCapability(
    val pkg: String,
    val purpose: String,
    val success: Int = 0,
    val failure: Int = 0,
    val lastTs: Long = 0L
) {
    val total: Int get() = success + failure
    val rate: Float get() = if (total == 0) 0f else success.toFloat() / total
}

class AppCapabilityTable(rows: List<AppCapability> = emptyList()) {

    private val map = LinkedHashMap<String, AppCapability>()

    init {
        for (row in rows) map[key(row.pkg, row.purpose)] = row
    }

    private fun key(pkg: String, purpose: String) = pkg + "\u0001" + purpose

    fun get(pkg: String, purpose: String): AppCapability? = map[key(pkg, purpose)]

    /** true＝做過且成功；false＝連續失敗到不可信；null＝沒經驗（不要當成不行）。 */
    fun supports(pkg: String, purpose: String): Boolean? {
        val row = get(pkg, purpose) ?: return null
        return when {
            row.success > 0 -> true
            row.failure >= LACK_THRESHOLD -> false
            else -> null
        }
    }

    /** 連續失敗達門檻 → 視為「這個 App 不支援這個目的」。 */
    fun lacks(pkg: String, purpose: String): Boolean {
        val row = get(pkg, purpose) ?: return false
        return row.success == 0 && row.failure >= LACK_THRESHOLD
    }

    fun record(pkg: String, purpose: String, ok: Boolean, nowMs: Long = System.currentTimeMillis()): AppCapability {
        if (pkg.isBlank() || purpose.isBlank()) {
            return AppCapability(pkg, purpose)
        }
        val previous = get(pkg, purpose)
        val updated = if (ok) {
            AppCapability(pkg, purpose, (previous?.success ?: 0) + 1, previous?.failure ?: 0, nowMs)
        } else {
            AppCapability(pkg, purpose, previous?.success ?: 0, (previous?.failure ?: 0) + 1, nowMs)
        }
        map[key(pkg, purpose)] = updated
        return updated
    }

    fun rows(): List<AppCapability> = map.values.toList()

    /** 這個目的在這支手機上「已知可行」的 App。 */
    fun appsFor(purpose: String): List<String> =
        rows().filter { it.purpose == purpose && it.success > 0 }.map { it.pkg }

    fun toLines(): List<String> = rows().map {
        listOf(it.pkg, it.purpose, it.success, it.failure, it.lastTs).joinToString("\t")
    }

    companion object {
        /** 失敗幾次之後就當成「不支援」——太少會误判，太多會一直重試。 */
        const val LACK_THRESHOLD = 2

        fun parse(lines: Sequence<String>): AppCapabilityTable {
            val rows = ArrayList<AppCapability>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 4) continue
                val pkg = c[0].trim()
                val purpose = c[1].trim()
                if (pkg.isEmpty() || purpose.isEmpty()) continue
                rows.add(
                    AppCapability(
                        pkg = pkg,
                        purpose = purpose,
                        success = c[2].trim().toIntOrNull() ?: 0,
                        failure = c[3].trim().toIntOrNull() ?: 0,
                        lastTs = c.getOrNull(4)?.trim()?.toLongOrNull() ?: 0L
                    )
                )
            }
            return AppCapabilityTable(rows)
        }
    }
}

/**
 * 能力探測（裝置層）的介面：core 只定義「要問什麼」，實作在 App 層。
 *
 * 為什麼要介面：規則引擎要能問「意圖可解析嗎」「權限有嗎」「有這個硬體嗎」，
 * 但不能直接碰 Android API（core 要能 JVM 測試）。
 */
interface CapabilityProbe {
    /** 這個 intent action（可帶 package）在這台裝置上解析得到嗎？ */
    fun intentResolvable(action: String, pkg: String = ""): Boolean

    /** 這個權限目前有嗎？ */
    fun hasPermission(permission: String): Boolean

    /** 這台裝置有這個硬體功能嗎？（例 android.hardware.camera） */
    fun hasFeature(feature: String): Boolean

    /** 系統面板（快速設定）現在打得開嗎？ */
    fun canOpenPanel(): Boolean

    companion object {
        /** 沒有探測器時的安全預設：全部回 true（不要因為探測不到就什麼都不做）。 */
        val ALLOW_ALL = object : CapabilityProbe {
            override fun intentResolvable(action: String, pkg: String) = true
            override fun hasPermission(permission: String) = true
            override fun hasFeature(feature: String) = true
            override fun canOpenPanel() = true
        }
    }
}
