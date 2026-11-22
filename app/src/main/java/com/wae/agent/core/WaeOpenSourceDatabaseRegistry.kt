package com.wae.agent.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * WAE 開源語料與 UI 資料庫動態載入索引器（零代碼預設、100% 讀取 assets/open_source_ai_databases/ 與系統 PackageManager）：
 * - command_lexicon.tsv (7,508 條指令詞彙標籤、動作碼 c/t/s/b/r/o、連接碼 1/2、+Q/+A 槽位條件、canonical 角色)
 * - global_tw_cn_app_metadata.tsv (App 套件與別名映射) + 手機 PackageManager 已安裝 App 動態檢索
 * - hit_cilin_synonyms_expanded.tsv (16,505 組哈工大同義詞詞林)
 * - google_material_icons_full_semantics.tsv (4,269 組 Material 圖標語義)
 */
object WaeOpenSourceDatabaseRegistry {

    data class LexiconEntry(
        val term: String,
        val tagExpr: String,
        val confidence: Float,
        val canonicalRole: String,
        val iconHints: List<String>
    ) {
        val primaryActionCode: Char
            get() = tagExpr.firstOrNull() ?: 'c'

        val isConnector: Boolean
            get() = tagExpr.startsWith("1") || tagExpr.startsWith("2") || canonicalRole.startsWith("CONN_")

        val expectsQueryPayload: Boolean
            get() = tagExpr.contains("+Q") || primaryActionCode == 't' || primaryActionCode == 's'

        val expectsAppTarget: Boolean
            get() = tagExpr.contains("+A") || primaryActionCode == 'o'
    }

    data class AppMetadataEntry(
        val packageName: String,
        val displayName: String,
        val category: String,
        val aliases: List<String>
    )

    data class MaterialIconEntry(
        val iconName: String,
        val roleTag: String,
        val terms: List<String>
    )

    data class ResolvedAppTarget(
        val packageName: String,
        val displayName: String,
        val matchedAlias: String
    )

    val lexiconMap = ConcurrentHashMap<String, LexiconEntry>()
    val appMetadataList = ArrayList<AppMetadataEntry>()
    val materialIconMap = ConcurrentHashMap<String, MaterialIconEntry>()
    private val cilinWordToClusterIds = ConcurrentHashMap<String, MutableSet<String>>()

    /** 中文量詞/代詞/數詞（資料驅動，來自 CC-CEDICT + jieba，不含任何 App 資料） */
    val singleCharMeasureWords = HashSet<String>()
    val multiCharMeasureWords = HashSet<String>()
    val pronouns = HashSet<String>()
    val numerals = HashSet<String>()

    /** 通用 UI 動作詞彙（多語系，來自 AOSP Settings 資源，跨 App 通用） */
    val uiActionTerms = HashSet<String>()

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context? = null) {
        if (loaded) return
        loaded = true
        loadCommandLexicon(context)
        loadAppMetadata(context)
        loadMaterialIcons(context)
        loadHitCilinSynonyms(context)
        loadClassifierLexicon(context)
        loadUiActionVocabulary(context)
        loadAppIntentCategory(context)
        loadRoleKeywords(context)
        loadFuzzyIntent(context)
        loadTextNormalizer(context)
    }

    /** 簡繁／ASR 同音正規化（App 名稱、意圖詞都比對前先正規化）。 */
    private fun loadTextNormalizer(context: Context?) {
        runCatching {
            com.wae.agent.core.app.TextNormalizer.loadFromLines(
                openAssetLines(context, "open_source_ai_databases/opencc_zh_tw_cn_lexicon.tsv"),
                openAssetLines(context, "open_source_ai_databases/stopwords_and_asr_homophones.tsv")
            )
        }
    }

    /** 模糊語義 → 意圖框與槽位（FuzzyIntent 用）。 */
    private fun loadFuzzyIntent(context: Context?) {
        runCatching {
            com.wae.agent.core.app.FuzzyIntent.loadFrames(
                openAssetLines(context, "open_source_ai_databases/ui_intent_frames.tsv")
            )
            com.wae.agent.core.app.FuzzyIntent.loadSlots(
                openAssetLines(context, "open_source_ai_databases/ui_intent_slots.tsv")
            )
        }
    }

    /** 通用角色 → 畫面關鍵詞（$role:xxx 的解析表）。 */
    private fun loadRoleKeywords(context: Context?) {
        runCatching {
            com.wae.agent.core.app.RoleResolver.loadFromLines(
                openAssetLines(context, "open_source_ai_databases/ui_role_keywords.tsv")
            )
        }
    }

    /** 開放式意圖 → App 類別（看劇／聽歌／導航…），給 AppSelector 用。 */
    private fun loadAppIntentCategory(context: Context?) {
        runCatching {
            com.wae.agent.core.app.AppIntentCategory.loadFromLines(
                openAssetLines(context, "open_source_ai_databases/ui_app_intent_category.tsv")
            )
        }
    }

    /**
     * 載入通用 UI 動作詞彙（確定/取消/下一頁/送出…的多語系寫法）。
     * 來源：AOSP Settings 的 strings.xml（Apache-2.0），屬平台標準用語。
     */
    private fun loadUiActionVocabulary(context: Context?) {
        openAssetLines(context, "open_source_ai_databases/ui_action_vocabulary.tsv").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split('\t')
            // key, en, zh_CN, zh_TW, ja, ko, 繁簡變體(|分隔)
            for (i in 1 until parts.size) {
                for (raw in parts[i].split('|')) {
                    val term = raw.trim().lowercase()
                    if (term.isNotEmpty() && term.length <= 16) uiActionTerms.add(term)
                }
            }
        }
    }

    /**
     * 該文字是否為「通用動作標籤」（例如 確定/取消/下一頁/Send/Continue）。
     * 用於區分「內容元素」與「動作按鈕」——這是跨 App 通用的判斷，不依賴任何 App 的資料。
     */
    fun isGenericActionLabel(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t.length > 20) return false
        if (uiActionTerms.contains(t)) return true
        // 允許前後有少量裝飾字（如「確定並繼續」）
        return uiActionTerms.any { term ->
            term.length >= 2 && (t.startsWith(term) || t.endsWith(term))
        }
    }

    /**
     * 載入語言層詞庫（量詞/代詞/數詞）。
     * 來源：CC-CEDICT 的 CL: 量詞標注 + jieba 的詞性標注（皆為離線資料檔）。
     */
    private fun loadClassifierLexicon(context: Context?) {
        openAssetLines(context, "open_source_ai_databases/zh_classifier_lexicon.tsv").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split('\t')
            if (parts.size >= 2) {
                val word = parts[0].trim()
                val type = parts[1].trim().uppercase()
                if (word.isEmpty()) return@forEach
                when (type) {
                    "MEASURE" -> singleCharMeasureWords.add(word)
                    "MEASURE_MULTI" -> multiCharMeasureWords.add(word)
                    "PRONOUN" -> pronouns.add(word)
                    "NUMERAL" -> numerals.add(word)
                }
            }
        }
    }

    private fun openAssetLines(context: Context?, relativeAssetPath: String): Sequence<String> {
        try {
            if (context != null) {
                val stream = context.assets.open(relativeAssetPath)
                return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).lineSequence()
            }
        } catch (_: Throwable) {
        }

        // 測試（JVM）沒有 Context，只能從工作目錄找檔案。Gradle 測試的工作目錄是模組根，
        // 所以三個都列；不再寫死舊的 E:/NewAndroidApp（專案已搬到 E:/WAE/NewAndroidApp）。
        val candidates = listOf(
            File("src/main/assets/$relativeAssetPath"),
            File("app/src/main/assets/$relativeAssetPath"),
            File("../app/src/main/assets/$relativeAssetPath")
        )
        for (f in candidates) {
            if (f.exists()) {
                return f.bufferedReader(Charsets.UTF_8).lineSequence()
            }
        }
        return emptySequence()
    }

    private fun loadCommandLexicon(context: Context?) {
        openAssetLines(context, "open_source_ai_databases/command_lexicon.tsv").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split('\t')
            if (parts.size >= 5) {
                val term = parts[0].trim().lowercase()
                val tagExpr = parts[1].trim()
                val conf = parts[3].toFloatOrNull() ?: 0.8f
                val canonical = parts[4].trim()
                val icons = if (parts.size >= 6 && parts[5].isNotBlank()) {
                    parts[5].split('|').map { it.trim().lowercase() }
                } else emptyList()
                if (term.isNotEmpty()) {
                    lexiconMap[term] = LexiconEntry(term, tagExpr, conf, canonical, icons)
                }
            }
        }
    }

    private fun loadAppMetadata(context: Context?) {
        openAssetLines(context, "open_source_ai_databases/global_tw_cn_app_metadata.tsv").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split('\t')
            if (parts.size >= 4) {
                val pkg = parts[0].trim()
                val name = parts[1].trim()
                val cat = parts[2].trim()
                val aliases = (parts[3].split('|') + listOf(name))
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                appMetadataList.add(AppMetadataEntry(pkg, name, cat, aliases))
            }
        }
    }

    private fun loadMaterialIcons(context: Context?) {
        openAssetLines(context, "open_source_ai_databases/google_material_icons_full_semantics.tsv").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split('\t')
            if (parts.size >= 6) {
                val iconName = parts[0].trim().lowercase()
                val role = parts[2].trim()
                val twTerms = parts[3].split('|')
                val cnTerms = parts[4].split('|')
                val enTerms = parts[5].split('|')
                val allTerms = (twTerms + cnTerms + enTerms).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                materialIconMap[iconName] = MaterialIconEntry(iconName, role, allTerms)
            }
        }
    }

    private fun loadHitCilinSynonyms(context: Context?) {
        openAssetLines(context, "open_source_ai_databases/hit_cilin_synonyms_expanded.tsv").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split('\t')
            if (parts.size >= 2) {
                val clusterCode = parts[0].trim()
                val words = parts[1].split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                for (w in words) {
                    cilinWordToClusterIds.getOrPut(w) { HashSet(2) }.add(clusterCode)
                }
            }
        }
    }

    /**
     * 查詢兩個詞在哈工大同義詞詞林 (16,505 組) 或 command_lexicon 中是否同屬一個語義簇
     */
    fun areWordsSynonymousInDatabase(wordA: String, wordB: String): Boolean {
        ensureLoaded()
        val a = wordA.trim().lowercase()
        val b = wordB.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b || a.contains(b) || b.contains(a)) return true

        val clustersA = cilinWordToClusterIds[a]
        val clustersB = cilinWordToClusterIds[b]
        if (clustersA != null && clustersB != null && clustersA.any { clustersB.contains(it) }) {
            return true
        }

        val lexA = lexiconMap[a]
        val lexB = lexiconMap[b]
        if (lexA != null && lexB != null && lexA.canonicalRole.isNotEmpty() && lexA.canonicalRole == lexB.canonicalRole) {
            return true
        }
        return false
    }

    /**
     * 結合手機本機 PackageManager 已安裝 App 列表與 global_tw_cn_app_metadata.tsv 解析目標 App
     */
    fun resolveTargetAppFromDatabaseOrDevice(context: Context?, text: String): ResolvedAppTarget? {
        ensureLoaded(context)
        val lower = text.lowercase()
        // 正規化版本（簡繁＋ASR 同音）：讓「打开红果短剧」也能對到「紅果短劇」
        val normLower = com.wae.agent.core.app.TextNormalizer.normalize(lower)

        // 1. 若有 Context，先直接比對本機已安裝之 Launcher App 真實名稱 (支援任何未在資料庫中的新 App)
        if (context != null) {
            try {
                val pm = context.packageManager
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                var bestInstalled: ResolvedAppTarget? = null
                for (ri in resolveInfos) {
                    val pkg = ri.activityInfo?.packageName.orEmpty()
                    if (pkg.isEmpty() || pkg == context.packageName) continue
                    val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
                    val labelLower = label.lowercase()
                    val labelNorm = com.wae.agent.core.app.TextNormalizer.normalize(labelLower)
                    if (labelLower.length >= 2 &&
                        (lower.contains(labelLower) || (labelNorm.isNotEmpty() && normLower.contains(labelNorm)))
                    ) {
                        if (bestInstalled == null || labelLower.length > bestInstalled.matchedAlias.length) {
                            bestInstalled = ResolvedAppTarget(pkg, label, labelLower)
                        }
                    }
                }
                if (bestInstalled != null) {
                    return bestInstalled
                }
            } catch (_: Throwable) {
            }
        }

        // 2. 比對 global_tw_cn_app_metadata.tsv 中的別名 (並優先選擇本機已安裝之對應套件)
        var bestDbMatch: ResolvedAppTarget? = null
        var bestInstalledDbMatch: ResolvedAppTarget? = null
        for (app in appMetadataList) {
            for (alias in app.aliases) {
                if (alias.isNotEmpty() &&
                    (lower.contains(alias) ||
                        normLower.contains(com.wae.agent.core.app.TextNormalizer.normalize(alias)))
                ) {
                    val candidate = ResolvedAppTarget(app.packageName, app.displayName, alias)
                    if (bestDbMatch == null || alias.length > bestDbMatch.matchedAlias.length) {
                        bestDbMatch = candidate
                    }
                    if (context != null && isPackageInstalled(context, app.packageName)) {
                        if (bestInstalledDbMatch == null || alias.length > bestInstalledDbMatch.matchedAlias.length) {
                            bestInstalledDbMatch = candidate
                        }
                    }
                }
            }
        }
        return bestInstalledDbMatch ?: bestDbMatch ?: fuzzyResolveInstalled(context, lower)
    }

    /**
     * 通用模糊解析（最後一道）：
     * 使用者只講一半、打錯字、或 ASR 聽錯時（「打開按管理」→「裝置管理」），
     * 精確/包含比對都會失敗。這裡先用**資料驅動**的方式剝掉開頭動詞（lexicon 裡 expectsAppTarget 的詞），
     * 再把剩下的片語跟所有已安裝 App 的 label 做字元 bigram 相似度，取最高分。
     *
     * 為什麼不是硬編碼前綴：動詞清單來自 command_lexicon.tsv，新增動詞只要加資料。
     */
    fun stripLeadingVerb(text: String): String {
        var best: String? = null
        synchronized(lexiconMap) {
            for ((term, entry) in lexiconMap) {
                if (!entry.expectsAppTarget) continue
                if (term.length < 2 || !text.startsWith(term)) continue
                if (best == null || term.length > best!!.length) best = term
            }
        }
        val verb = best ?: return text
        return text.removePrefix(verb).trim(' ', '\u3000', '的')
    }

    private fun fuzzyResolveInstalled(context: Context?, lower: String): ResolvedAppTarget? {
        if (context == null) return null
        val phrase = stripLeadingVerb(lower)
        if (phrase.length < 2) return null
        return try {
            val pm = context.packageManager
            val infos = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
            var best: ResolvedAppTarget? = null
            var bestScore = 0f
            var secondScore = 0f
            val seen = HashSet<String>()
            for (ri in infos) {
                val pkg = ri.activityInfo?.packageName.orEmpty()
                if (pkg.isEmpty() || pkg == context.packageName || !seen.add(pkg)) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.length < 2) continue
                val score = com.wae.agent.core.app.AppSelector.bigramSimilarity(phrase, label.lowercase())
                if (score > bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    best = ResolvedAppTarget(pkg, label, phrase)
                } else if (score > secondScore) {
                    secondScore = score
                }
            }
            // 門檻：0.34 約等於「一半以上的字元 bigram 對上」；同時要求跟第二名有差距，避免亂猜。
            if (best != null && bestScore >= 0.34f && bestScore - secondScore >= 0.05f) best else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 純開 App 時要開哪個 App：先精確/別名/模糊解析，再退回合 catalog 的 label 比對。
     * 回 null 代表「找不到」。
     *
     * 這是**解析**用的，不是拿來判斷句子種類的——路由一律以「拆解器有沒有拆出完整目標」為準。
     */
    fun resolvePureLaunchTarget(context: Context?, text: String): ResolvedAppTarget? {
        if (context == null) return null
        resolveTargetAppFromDatabaseOrDevice(context, text)?.let { return it }
        val lower = text.trim().lowercase()
        val phrase = stripLeadingVerb(lower)
        // 一定要有「開 App」的動詞開頭（資料驅動：lexicon 的 expectsAppTarget）。
        // 否則任何一句中文（閒聊、新聞、廣告詞）都可能被硬解析成某個 App。
        if (phrase == lower) return null
        if (phrase.length !in 2..20) return null
        val catalog = com.wae.agent.core.app.InstalledAppCatalog.load(context)
        val decision = com.wae.agent.core.app.AppSelector.select(phrase, catalog, emptyMap())
        // **必須有信心**：沒有門檻的話，任何一句中文（例如閒聊）都會被解析成某個 App，
        // 造成「指令閘門」把閒聊也當成指令。
        if (!decision.confident) return null
        return decision.best?.candidate?.let { ResolvedAppTarget(it.pkg, it.label, phrase) }
    }

    fun resolveTargetPackageFromDatabase(text: String, context: Context? = null): String {
        return resolveTargetAppFromDatabaseOrDevice(context, text)?.packageName.orEmpty()
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(pkg) != null ||
                context.packageManager.getPackageInfo(pkg, 0) != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 啟動目標 App 套件（支援 getLaunchIntentForPackage 與顯式 ComponentName 雙重兜底）
     */
    fun launchAppByPackageName(context: Context, targetPackage: String): Boolean {
        if (targetPackage.isEmpty()) return false
        val pm = context.packageManager
        try {
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                context.startActivity(launchIntent)
                return true
            }
        } catch (_: Throwable) {
        }

        // Fallback：查詢該 package 下所有 MAIN Activity
        try {
            val queryIntent = Intent(Intent.ACTION_MAIN, null).apply {
                setPackage(targetPackage)
            }
            val activities = pm.queryIntentActivities(queryIntent, 0)
            val firstAct = activities.firstOrNull()?.activityInfo
            if (firstAct != null) {
                val explicitIntent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(firstAct.packageName, firstAct.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                context.startActivity(explicitIntent)
                return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    /**
     * 從 google_material_icons_full_semantics.tsv 與 command_lexicon.tsv 解析元件之語義角色
     */
    fun resolveIconOrNodeRoleFromDatabase(
        viewId: String,
        text: String,
        contentDesc: String
    ): String {
        ensureLoaded()
        val resIdToken = viewId.substringAfterLast('/').lowercase()
        if (resIdToken.isNotEmpty()) {
            materialIconMap[resIdToken]?.let { return it.roleTag.lowercase() }
            for ((k, v) in materialIconMap) {
                if (k.length >= 4 && resIdToken.contains(k)) {
                    return v.roleTag.lowercase()
                }
            }
        }

        val combined = "$text $contentDesc".trim().lowercase()
        if (combined.isNotEmpty()) {
            lexiconMap[combined]?.let {
                if (it.canonicalRole.isNotEmpty()) return it.canonicalRole.lowercase()
            }
            for ((term, entry) in lexiconMap) {
                if (term.length >= 2 && combined.contains(term) && entry.canonicalRole.isNotEmpty()) {
                    return entry.canonicalRole.lowercase()
                }
            }
        }
        return "ui_element"
    }
}
