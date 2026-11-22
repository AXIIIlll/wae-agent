package com.wae.agent.gen5

import android.content.Context
import com.wae.core.config.BundledData
import com.wae.core.rule.LexiconFamilyMap
import com.wae.core.rule.RoleLexicon

/**
 * 把「App 屬於哪一類」變成「該用哪一份詞庫」。
 *
 * 三層解析（越前面越可信）：
 *  1. 我們的 metadata（人工整理，含中文別名）
 *  2. `pkg_category.tsv`（19K 套件 → Play 分類，公開資料；只作 fallback）
 *  3. 名稱關鍵字（銀行／購物／影片…）→ 家族
 * 都不命中 → COMMON（只用統一詞庫，不亂套領域詞）。
 */
object AppCategoryResolver {

    private const val TAG = "WAE_LEXICON"

    /** 我們自己的分類代碼 → 詞庫家族。 */
    private val OWN_CATEGORY_FAMILY = mapOf(
        "VIDEO_MEDIA" to "MEDIA", "SHORT_DRAMA" to "MEDIA", "ENTERTAINMENT" to "MEDIA",
        "MUSIC_MEDIA" to "MUSIC", "MAPS_NAV" to "TRAVEL", "TRAVEL" to "TRAVEL",
        "SHOPPING" to "SHOPPING", "FOOD" to "FOOD", "FINANCE" to "FINANCE",
        "SOCIAL" to "SOCIAL", "COMMUNICATION" to "COMMS", "NEWS" to "NEWS",
        "PRODUCTIVITY" to "PRODUCTIVITY", "TOOLS" to "TOOLS", "HEALTH_FITNESS" to "HEALTH",
        "PHOTO" to "PHOTO", "CAMERA" to "PHOTO", "GAME" to "GAMES", "SYSTEM" to "TOOLS",
        "GOOGLE_BROWSER" to "TOOLS", "BROWSER" to "TOOLS", "SEARCH_ENGINE" to "TOOLS"
    )

    /** 名稱關鍵字 → 家族（最後一道）。 */
    private val NAME_HINTS = listOf(
        "銀行" to "FINANCE", "bank" to "FINANCE", "錢包" to "FINANCE", "wallet" to "FINANCE",
        "購物" to "SHOPPING", "shop" to "SHOPPING", "商城" to "SHOPPING", "買" to "SHOPPING",
        "影片" to "MEDIA", "video" to "MEDIA", "劇" to "MEDIA", "tv" to "MEDIA",
        "音樂" to "MUSIC", "music" to "MUSIC", "podcast" to "MUSIC",
        "地圖" to "TRAVEL", "map" to "TRAVEL", "導航" to "TRAVEL", "nav" to "TRAVEL",
        "相機" to "PHOTO", "camera" to "PHOTO", "相片" to "PHOTO", "photo" to "PHOTO",
        "訊" to "COMMS", "message" to "COMMS", "chat" to "COMMS", "mail" to "COMMS",
        "社群" to "SOCIAL", "social" to "SOCIAL", "交友" to "SOCIAL",
        "新聞" to "NEWS", "news" to "NEWS", "天氣" to "WEATHER", "weather" to "WEATHER",
        "健身" to "HEALTH", "health" to "HEALTH", "運動" to "HEALTH",
        "筆記" to "PRODUCTIVITY", "note" to "PRODUCTIVITY", "日曆" to "PRODUCTIVITY",
        "calendar" to "PRODUCTIVITY", "設定" to "TOOLS", "settings" to "TOOLS",
        "檔案" to "TOOLS", "file" to "TOOLS"
    )

    @Volatile
    private var pkgCategory: Map<String, String>? = null

    @Volatile
    private var commonLexicon: RoleLexicon? = null

    @Volatile
    private var familyMap: LexiconFamilyMap? = null

    private val familyCache = HashMap<String, RoleLexicon>()

    fun familyOf(context: Context, pkg: String, label: String = ""): String {
        metadataFamily(pkg)?.let { return it }
        playCategory(context, pkg)?.let { play ->
            familyMap(context).familyOf(play).let { fam ->
                if (fam != LexiconFamilyMap.COMMON) return fam
            }
        }
        val text = (label + " " + pkg).lowercase()
        for ((hint, fam) in NAME_HINTS) {
            if (text.contains(hint)) return fam
        }
        return LexiconFamilyMap.COMMON
    }

    /** 取得這個 App 應該用的詞庫（統一 + 家族）。 */
    fun lexiconFor(context: Context, pkg: String, label: String = ""): RoleLexicon {
        val common = common(context)
        val fam = familyOf(context, pkg, label)
        if (fam == LexiconFamilyMap.COMMON) return common
        synchronized(familyCache) {
            familyCache[fam]?.let { return RoleLexicon.merge(common, it) }
            val loaded = runCatching { RoleLexicon.parse(BundledData.lexiconFamilyLines(fam)) }
                .getOrDefault(RoleLexicon.empty())
            familyCache[fam] = loaded
            return RoleLexicon.merge(common, loaded)
        }
    }

    fun familyOfLabel(context: Context, label: String): String =
        familyOf(context, "", label)

    private fun common(context: Context): RoleLexicon {
        commonLexicon?.let { return it }
        synchronized(this) {
            commonLexicon?.let { return it }
            val loaded = runCatching { RoleLexicon.parse(BundledData.lexiconCommonLines()) }
                .getOrDefault(RoleLexicon.empty())
            android.util.Log.i(TAG, "common lexicon roles=" + loaded.roles().size)
            commonLexicon = loaded
            return loaded
        }
    }

    private fun familyMap(context: Context): LexiconFamilyMap {
        familyMap?.let { return it }
        synchronized(this) {
            familyMap?.let { return it }
            val loaded = runCatching { LexiconFamilyMap.parse(BundledData.lexiconFamilyMapLines()) }
                .getOrDefault(LexiconFamilyMap.parse(emptySequence()))
            familyMap = loaded
            return loaded
        }
    }

    private fun metadataFamily(pkg: String): String? {
        if (pkg.isBlank()) return null
        val entry = runCatching {
            com.wae.agent.core.WaeOpenSourceDatabaseRegistry.appMetadataList
                .firstOrNull { it.packageName == pkg }
        }.getOrNull() ?: return null
        return OWN_CATEGORY_FAMILY[entry.category] ?: entry.category.takeIf { it.isNotEmpty() }
    }

    private fun playCategory(context: Context, pkg: String): String? {
        if (pkg.isBlank()) return null
        val map = pkgCategory ?: loadPkgCategory()
        return map[pkg]
    }

    private fun loadPkgCategory(): Map<String, String> {
        synchronized(this) {
            pkgCategory?.let { return it }
            val map = HashMap<String, String>(20_000)
            runCatching {
                BundledData.pkgCategoryLines().forEach { line ->
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    val c = line.split('\t')
                    if (c.size >= 2) map[c[0].trim()] = c[1].trim()
                }
            }
            android.util.Log.i(TAG, "pkg_category loaded=" + map.size)
            pkgCategory = map
            return map
        }
    }
}
