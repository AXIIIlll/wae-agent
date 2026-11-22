package com.wae.agent.core.capability

import org.json.JSONArray
import org.json.JSONObject

/**
 * 能力圖譜的對外資料契約。
 *
 * 給其他開發者使用時，schemaVersion 是相容性保證：
 * 讀取方必須先檢查版本，未知版本一律視為不可用（fail-safe）。
 */
object CapabilitySchema {
    const val VERSION = 1
}

/** 掃描參數——由呼叫方（本 App 或第三方開發者）決定 */
data class ScanOptions(
    /** 是否包含系統 App（預設含，但可關閉以降成本） */
    val includeSystemApps: Boolean = true,
    /** 是否包含無法啟動的 App（無 launcher 入口） */
    val includeNonLaunchable: Boolean = true,
    /** 單次掃描最多處理幾個 App（硬上限，避免極端裝置卡頓） */
    val maxApps: Int = 700,
    /** 單次掃描時間預算（毫秒），超過即停止並回報 partial */
    val timeBudgetMs: Long = 15_000,
    /** 快取有效期，過期後下次啟動做全量重掃 */
    val rescanTtlMs: Long = 7L * 24 * 60 * 60 * 1000
)

/** 單一 App 的綠區能力描述（只含系統公開元資料） */
data class RawAppInfo(
    val pkg: String,
    val label: String,
    val versionCode: Long,
    val installer: String,
    /** ApplicationInfo.category（0/-1 = 未分類） */
    val category: Int,
    val targetSdk: Int,
    val launchable: Boolean,
    val isSystem: Boolean,
    val permissions: List<String>,
    val exportedActivities: List<String>
)

/** 一次掃描的結果快照（可持久化、可版本化） */
data class CapabilitySnapshot(
    val schemaVersion: Int,
    val builtAt: Long,
    val partial: Boolean,
    val apps: List<RawAppInfo>
) {
    fun versionCodes(): Map<String, Long> = apps.associate { it.pkg to it.versionCode }

    fun toJson(): String {
        val arr = JSONArray()
        for (a in apps) {
            arr.put(
                JSONObject().apply {
                    put("pkg", a.pkg)
                    put("label", a.label)
                    put("versionCode", a.versionCode)
                    put("installer", a.installer)
                    put("category", a.category)
                    put("targetSdk", a.targetSdk)
                    put("launchable", a.launchable)
                    put("isSystem", a.isSystem)
                    put("permissions", JSONArray(a.permissions))
                    put("exportedActivities", JSONArray(a.exportedActivities))
                }
            )
        }
        return JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("builtAt", builtAt)
            put("partial", partial)
            put("apps", arr)
        }.toString()
    }

    companion object {
        fun parse(json: String): CapabilitySnapshot? {
            return try {
                val root = JSONObject(json)
                val version = root.optInt("schemaVersion", -1)
                if (version != CapabilitySchema.VERSION) return null
                val arr = root.optJSONArray("apps") ?: JSONArray()
                val apps = ArrayList<RawAppInfo>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    apps.add(
                        RawAppInfo(
                            pkg = o.getString("pkg"),
                            label = o.optString("label", ""),
                            versionCode = o.optLong("versionCode", 0L),
                            installer = o.optString("installer", ""),
                            category = o.optInt("category", 0),
                            targetSdk = o.optInt("targetSdk", 0),
                            launchable = o.optBoolean("launchable", false),
                            isSystem = o.optBoolean("isSystem", false),
                            permissions = o.optJSONArray("permissions").toStringList(),
                            exportedActivities = o.optJSONArray("exportedActivities").toStringList()
                        )
                    )
                }
                CapabilitySnapshot(
                    schemaVersion = version,
                    builtAt = root.optLong("builtAt", 0L),
                    partial = root.optBoolean("partial", false),
                    apps = apps
                )
            } catch (_: Throwable) {
                null
            }
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            val out = ArrayList<String>(length())
            for (i in 0 until length()) out.add(optString(i, ""))
            return out.filter { it.isNotEmpty() }
        }
    }
}

/** 對外查詢介面（其他開發者用這個，不必碰內部實作） */
interface CapabilityQuery {
    fun all(): List<RawAppInfo>
    fun byPackage(pkg: String): RawAppInfo?
    fun launchable(): List<RawAppInfo>
    fun byCategory(category: Int): List<RawAppInfo>
    fun requiringPermission(permission: String): List<RawAppInfo>
    fun statusLine(): String
}
