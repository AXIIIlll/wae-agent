package com.example.newandroidapp.ai

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 三層標籤指令詞典與畫面最終裁決引擎 (`CommandLexiconEngine`)
 *
 * 實作《指令詞典打標籤計劃書》全套機制：
 * 1. **單字母縮寫代碼體系 (Compact Single-Char Tag Schema)**：
 *    - **動作類 (A 組)**：`c`=點擊, `t`=輸入文字, `s`=搜尋, `b`=返回, `r`=捲動/滑動, `w`=開關切換,
 *      `l`=長按, `d`=雙擊, `o`=開啟 App, `x`=擷取資訊, `g`=傳送/分享, `p`=播放/暫停
 *    - **連接詞 (J 組)**：`1`=依序接下一步, `2`=條件/結果
 *    - **目標名詞 (N 組)**：`B`=按鈕, `I`=輸入框, `A`=App 名, `P`=頁面/區域
 *    - **特殊 (S 組)**：`e`=語音錯字變體 (附正確詞), `z`=不使用/雜訊, `?`=待觀察
 *
 * 2. **多意思優先順序與條件裁決 (Multi-Sense Priority & Context Conditions)**：
 *    - 支援 `o+A>w+W>c+S` 格式（左邊優先，搭配條件 `+A` 後接 App 名、`+W` 後接開關項目、`+Q` 後接查詢內容、`+S` 畫面上有同名或同角色按鈕）。
 *    - 當條件都不成立時，自動退回優先順序第一個意思，並適度降低信心度。
 *
 * 3. **畫面最終裁決官 (Screen-as-Final-Arbiter)**：
 *    - 徹底根除「把點擊按鈕當成輸入文字」與「找不到按鈕就亂點一通再跑去搜尋框打字」的系統性問題：
 *    - 鐵律一：當子目標是點擊按鈕 (`c` / `TAP_TARGET` / `TerminalVerb.TAP_BUTTON`) 時，**100% 嚴禁觸發 `INPUT_TEXT`**，且對畫面上所有 `INP` 輸入框施加 `-200f` 硬性遮罩。
 *    - 鐵律二：當子目標是點擊特定按鈕（如「行車路線 / 路線」），若畫面上有同名或同本體角色 (`MAP_DIRECTIONS_ROUTE`) 的未點擊按鈕，精準點擊該按鈕；若該按鈕已經點過或畫面上只有不相關按鈕（如「開始 / 儲存 / 分享」），嚴禁亂點不相關按鈕，直接降低分數觸發滑動探索或完成停止！
 */
object CommandLexiconEngine {

    private const val ASSET_LEXICON_PATH = "open_source_ai_databases/command_lexicon.tsv"
    private val loaded = AtomicBoolean(false)

    /**
     * 單一意思與附加條件（例如 `o+A` -> code='o', condition="+A"）
     */
    data class SenseOption(
        val code: Char,
        val condition: String? = null
    ) {
        val codeDisplayName: String
            get() = getTagDisplayName(code)
    }

    /**
     * 詞典單筆記錄：對應 `詞 <TAB> 代碼(含優先順序與條件) <TAB> 來源(A/S/M) <TAB> 信心(0..1) <TAB> 備註/標準詞 <TAB> icon_hint`
     */
    data class LexiconEntry(
        val term: String,
        val rawTagExpr: String,
        val senses: List<SenseOption>,
        val source: Char,       // 'A'=L1自動, 'S'=L2半自動, 'M'=L3人工
        val confidence: Float,  // 0.0 .. 1.0
        val canonicalOrRole: String = "",
        val iconHints: List<String> = emptyList()
    ) {
        val primaryCode: Char
            get() = senses.firstOrNull()?.code ?: '?'

        fun hasCode(targetCode: Char): Boolean = senses.any { it.code == targetCode }
    }

    /**
     * 多意思動態消歧結果
     */
    data class SenseResolutionResult(
        val matchedTerm: String,
        val resolvedCode: Char,
        val resolvedDisplayName: String,
        val matchedCondition: String?,
        val effectiveConfidence: Float,
        val sourceTier: Char,
        val canonicalOrRole: String,
        val fallbackUsed: Boolean,
        val diagnosticTrace: String,
        val isTargetUiButton: Boolean = false,
        val iconHints: List<String> = emptyList()
    ) {
        val resolvedPrimaryCode: Char
            get() = resolvedCode
    }

    /**
     * 詞典品質與覆蓋率統計報告
     */
    data class LexiconQualityReport(
        val totalEntries: Int,
        val l1AutoCount: Int,
        val l2SemiAutoCount: Int,
        val l3ManualCount: Int,
        val multiSenseCount: Int,
        val conditionalSenseCount: Int,
        val asrHomophoneCount: Int,
        val iconHintEntryCount: Int = 0
    )

    private val entriesByTermLower = HashMap<String, LexiconEntry>(8192)
    private val roleToIconHints = HashMap<String, LinkedHashSet<String>>(64)
    private val iconToRoles = HashMap<String, LinkedHashSet<String>>(256)
    private val asrVariantToCanonical = LinkedHashMap<String, String>(128)
    private val connectorTermsSorted = ArrayList<Pair<String, Char>>(64)
    private val appTermsSorted = ArrayList<Pair<String, String>>(256)
    private val toggleFeatureTerms = HashSet<String>(128)

    private val BUILTIN_TOGGLE_FEATURES = setOf(
        "藍牙", "bluetooth", "wi-fi", "wifi", "wlan", "無線網路", "熱點", "個人熱點", "無線基地台",
        "深色模式", "暗色主題", "深色主題", "夜間模式", "護眼模式", "夜覽", "濾藍光",
        "勿擾", "勿擾模式", "飛航模式", "飛行模式", "省電模式", "省電", "自動旋轉",
        "定位", "位置資訊", "gps", "震動", "靜音", "自動調整亮度", "nfc", "行動數據", "漫遊"
    )

    /**
     * 單字母代碼對應之人類可讀全名（供單鍵標註工具與除錯日誌顯示）
     */
    fun getTagDisplayName(code: Char): String = when (code) {
        'c' -> "點擊"
        't' -> "輸入文字"
        's' -> "搜尋"
        'b' -> "返回"
        'r' -> "捲動／滑動"
        'w' -> "開關切換"
        'l' -> "長按"
        'd' -> "雙擊"
        'o' -> "開啟 App"
        'x' -> "擷取資訊"
        'g' -> "傳送／分享"
        'p' -> "播放／暫停"
        '1' -> "依序接下一步"
        '2' -> "條件／結果"
        'B' -> "按鈕"
        'I' -> "輸入框"
        'A' -> "App 名"
        'P' -> "頁面／區域"
        'e' -> "語音錯字變體"
        'z' -> "不使用／雜訊"
        '?' -> "待觀察"
        else -> "未知($code)"
    }

    @Synchronized
    fun ensureLoaded(context: Context? = null) {
        if (loaded.get()) return
        toggleFeatureTerms.addAll(BUILTIN_TOGGLE_FEATURES)
        try {
            openLexiconStream(context)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 32768).useLines { lines ->
                    for (raw in lines) {
                        if (raw.isEmpty() || raw[0] == '#') continue
                        val parts = raw.split('\t')
                        if (parts.size < 4) continue
                        val term = parts[0].trim()
                        val tagExpr = parts[1].trim()
                        val src = parts[2].trim().firstOrNull() ?: 'A'
                        val conf = parts[3].trim().toFloatOrNull() ?: 0.85f
                        val meta = if (parts.size >= 5) parts[4].trim() else ""
                        val iconHintRaw = if (parts.size >= 6) parts[5].trim() else ""
                        if (term.isEmpty() || tagExpr.isEmpty()) continue

                        val senses = parseTagExpression(tagExpr)
                        if (senses.isEmpty()) continue

                        val iconHints = if (iconHintRaw.isNotEmpty()) {
                            iconHintRaw.split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        } else {
                            emptyList()
                        }

                        val entry = LexiconEntry(
                            term = term,
                            rawTagExpr = tagExpr,
                            senses = senses,
                            source = src,
                            confidence = conf,
                            canonicalOrRole = meta,
                            iconHints = iconHints
                        )
                        val lowerKey = term.lowercase()
                        val existing = entriesByTermLower[lowerKey]
                        if (existing == null || sourcePriority(src) <= sourcePriority(existing.source)) {
                            entriesByTermLower[lowerKey] = entry
                        }

                        if (meta.isNotEmpty() && iconHints.isNotEmpty()) {
                            val rSet = roleToIconHints.getOrPut(meta) { LinkedHashSet() }
                            for (ic in iconHints) {
                                rSet.add(ic)
                                iconToRoles.getOrPut(ic) { LinkedHashSet() }.add(meta)
                            }
                        }

                        if (entry.primaryCode == 'e' && meta.isNotEmpty()) {
                            asrVariantToCanonical[lowerKey] = meta
                        }
                        if (entry.primaryCode == '1' || entry.primaryCode == '2') {
                            connectorTermsSorted.add(term to entry.primaryCode)
                        }
                        if (entry.primaryCode == 'A' && meta.isNotEmpty()) {
                            appTermsSorted.add(lowerKey to meta)
                        }
                        if (meta.startsWith("AOSP:NETWORK") || meta.startsWith("AOSP:DISPLAY") ||
                            meta.startsWith("AOSP:SOUND") || meta.startsWith("AOSP:BATTERY")
                        ) {
                            if (entry.primaryCode == 'P') {
                                toggleFeatureTerms.add(lowerKey)
                            }
                        }
                    }
                }
            }
            connectorTermsSorted.sortByDescending { it.first.length }
            appTermsSorted.sortByDescending { it.first.length }
        } catch (_: Throwable) {
        } finally {
            loaded.set(true)
        }
    }

    private fun sourcePriority(src: Char): Int = when (src) {
        'M' -> 0
        'A' -> 1
        'S' -> 2
        else -> 3
    }

    fun parseTagExpression(expr: String): List<SenseOption> {
        val rawParts = expr.split('>')
        val out = ArrayList<SenseOption>(rawParts.size)
        for (p in rawParts) {
            val clean = p.trim()
            if (clean.isEmpty()) continue
            val code = clean[0]
            val plusIdx = clean.indexOf('+')
            val cond = if (plusIdx >= 1 && plusIdx + 1 < clean.length) {
                clean.substring(plusIdx, minOf(plusIdx + 2, clean.length)).uppercase()
            } else {
                null
            }
            out.add(SenseOption(code = code, condition = cond))
        }
        return out
    }

    private fun openLexiconStream(context: Context?): InputStream? {
        if (context != null) {
            runCatching { return context.assets.open(ASSET_LEXICON_PATH) }
        }
        val candidatePaths = arrayOf(
            "app/src/main/assets/$ASSET_LEXICON_PATH",
            "src/main/assets/$ASSET_LEXICON_PATH",
            "e:/NewAndroidApp/app/src/main/assets/$ASSET_LEXICON_PATH"
        )
        for (p in candidatePaths) {
            val f = File(p)
            if (f.exists() && f.isFile) {
                return runCatching { f.inputStream() }.getOrNull()
            }
        }
        return null
    }

    /**
     * 查詢單詞在指令詞典中的條目（自動支援繁簡正規化）
     */
    fun lookupEntry(term: String, context: Context? = null): LexiconEntry? {
        if (term.isBlank()) return null
        ensureLoaded(context)
        val clean = term.trim().lowercase()
        entriesByTermLower[clean]?.let {
            if (it.primaryCode != 'z') return it
        }
        val normTw = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(clean, context)
        entriesByTermLower[normTw]?.let {
            if (it.primaryCode != 'z') return it
        }
        return null
    }

    /**
     * 利用詞典中的 `e`（語音錯字變體）標籤進行優先糾錯（如「變點極 -> 並點擊」、「行車錄線 -> 行車路線」）
     */
    fun normalizeAsrVariantsWithLexicon(rawText: String, context: Context? = null): String {
        if (rawText.isBlank()) return rawText
        ensureLoaded(context)
        var working = rawText
        for ((wrong, right) in asrVariantToCanonical) {
            if (working.lowercase().contains(wrong)) {
                working = working.replace(Regex(Regex.escape(wrong), RegexOption.IGNORE_CASE), right)
            }
        }
        return working
    }

    /**
     * 核心：多意思條件動態裁決器 (`resolveMultiSenseVerbOrTerm`)
     *
     * 根據《指令詞典打標籤計劃書》「二之一、多意思標籤」規則：
     * - 依序檢查 `代碼>代碼>代碼`（左邊優先）：
     *   - `+A`：後面接 App 名（如「開啟 LINE」-> `o` 開啟 App）
     *   - `+W`：後面接開關項目（如「開啟 藍牙」-> `w` 開關切換）
     *   - `+Q`：後面接要查的內容（如「查 臺北101」-> `s` 搜尋）
     *   - `+S`：畫面上有同名或同角色按鈕（如「查看路線」且畫面有「路線」按鈕 -> `c` 點擊）
     * - 條件都不成立時，退回優先順序第一個，並降低信心度 (`* 0.82f`)。
     */
    fun resolveSenseInContext(
        term: String,
        followingObjectText: String = "",
        report: VisionPipelineReport? = null,
        context: Context? = null
    ): SenseResolutionResult? {
        val entry = lookupEntry(term, context) ?: return null
        if (entry.senses.isEmpty()) return null

        val cleanFollow = followingObjectText.trim()
        val hasAppFollowing = isAppNameObject(cleanFollow, context)
        val hasToggleFollowing = isToggleFeatureObject(cleanFollow, context)
        val hasScreenButtonMatch = isTargetButtonOnScreen(
            targetPhrase = cleanFollow.ifEmpty { term },
            report = report,
            context = context
        ) || isKnownUiButtonNoun(cleanFollow, context)

        // 若後面接的是明確的 UI 按鈕名詞（如「行車路線」、「規劃路線」、「儲存地點」），則它不是自由搜尋詞 (+Q 為 false，+S 為 true)
        val isExplicitButtonObject = isKnownUiButtonNoun(cleanFollow, context)
        val verbHasAppSense = entry.senses.any { it.condition == "+A" }
        val verbHasToggleSense = entry.senses.any { it.condition == "+W" }
        val hasQueryFollowing = cleanFollow.isNotEmpty() &&
            (!verbHasAppSense || !hasAppFollowing) &&
            (!verbHasToggleSense || !hasToggleFollowing) &&
            !isExplicitButtonObject

        for (sense in entry.senses) {
            val cond = sense.condition
            val condMet = when (cond) {
                "+A" -> hasAppFollowing
                "+W" -> hasToggleFollowing
                "+Q" -> hasQueryFollowing
                "+S" -> hasScreenButtonMatch
                null, "" -> true
                else -> false
            }
            if (condMet) {
                val followEntry = lookupEntry(cleanFollow, context)
                val mergedIcons = (entry.iconHints + (followEntry?.iconHints ?: emptyList())).distinct()
                return SenseResolutionResult(
                    matchedTerm = entry.term,
                    resolvedCode = sense.code,
                    resolvedDisplayName = sense.codeDisplayName,
                    matchedCondition = cond,
                    effectiveConfidence = entry.confidence,
                    sourceTier = entry.source,
                    canonicalOrRole = entry.canonicalOrRole,
                    fallbackUsed = false,
                    diagnosticTrace = "詞典命中「${entry.term}」[${entry.rawTagExpr}] -> 條件 ${cond ?: "預設"} 成立 -> 採用 ${sense.code}(${sense.codeDisplayName})",
                    isTargetUiButton = isExplicitButtonObject || sense.code == 'B',
                    iconHints = mergedIcons
                )
            }
        }

        // 條件都不成立時，退回優先順序第一個，並降低信心
        val first = entry.senses.first()
        val discountedConf = (entry.confidence * 0.82f).coerceAtLeast(0.45f)
        val followEntry = lookupEntry(cleanFollow, context)
        val mergedIcons = (entry.iconHints + (followEntry?.iconHints ?: emptyList())).distinct()
        return SenseResolutionResult(
            matchedTerm = entry.term,
            resolvedCode = first.code,
            resolvedDisplayName = first.codeDisplayName,
            matchedCondition = null,
            effectiveConfidence = discountedConf,
            sourceTier = entry.source,
            canonicalOrRole = entry.canonicalOrRole,
            fallbackUsed = true,
            diagnosticTrace = "詞典命中「${entry.term}」[${entry.rawTagExpr}] -> 條件未完全滿足，退回第一優先 ${first.code}(${first.codeDisplayName}, 信心=${"%.2f".format(discountedConf)})",
            isTargetUiButton = isExplicitButtonObject || first.code == 'B',
            iconHints = mergedIcons
        )
    }

    /**
     * 檢查受詞是否為詞典中的 App 名稱 (`A`)
     */
    fun isAppNameObject(text: String, context: Context? = null): Boolean {
        val clean = text.trim().lowercase()
        if (clean.isEmpty()) return false
        ensureLoaded(context)
        val entry = lookupEntry(clean, context)
        if (entry != null && entry.primaryCode == 'A') return true
        for ((appAlias, _) in appTermsSorted) {
            if (appAlias.length >= 2 && clean.startsWith(appAlias)) {
                return true
            }
        }
        return OpenSourceAiDatabaseManager.resolveAppByColloquialAlias(clean, context) != null
    }

    /**
     * 檢查受詞是否為系統設定/開關項目 (`+W`)
     */
    fun isToggleFeatureObject(text: String, context: Context? = null): Boolean {
        val clean = text.trim().lowercase()
        if (clean.isEmpty()) return false
        ensureLoaded(context)
        val normTw = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(clean, context)
        return toggleFeatureTerms.any { kw ->
            clean == kw || normTw == kw || (kw.length >= 2 && (clean.contains(kw) || normTw.contains(kw)))
        }
    }

    /**
     * 檢查某個詞是否為詞典中明確標記為 `B`（按鈕目標名詞，如「行車路線、路線、規劃路線、開始導航、儲存地點、分享、按讚」）的 UI 元件名詞
     */
    fun isKnownUiButtonNoun(text: String, context: Context? = null): Boolean {
        val clean = text.trim().lowercase()
        if (clean.isEmpty()) return false
        if (clean.startsWith("搜尋") || clean.startsWith("搜索") || clean.startsWith("查找") ||
            clean.startsWith("查詢") || clean.startsWith("輸入")
        ) {
            return false
        }
        ensureLoaded(context)
        val entry = lookupEntry(clean, context)
        if (entry != null && entry.primaryCode == 'B') {
            return true
        }
        val explicitButtonRoles = setOf(
            "MAP_DIRECTIONS_ROUTE",
            "MAP_START_NAVIGATION",
            "LIKE_FAVORITE_BOOKMARK",
            "SHARE_FORWARD",
            "SHARE_EXPORT",
            "CALL_PHONE_CONTACT",
            "OPEN_EXTERNAL_WEBSITE",
            "ADD_TO_CART",
            "ADD_TO_CART_BUY",
            "CHECKOUT_BUY_NOW",
            "SWIPE_UP_EXPAND_SHEET",
            "SWIPE_DOWN_COLLAPSE_SHEET",
            "EXPAND_FULL_CONTENT",
            "SWIPE_HORIZONTAL_CAROUSEL",
            "TOGGLE_SWITCH_CHECKBOX"
        )
        val role = OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(clean, context)
        return role != null && role in explicitButtonRoles
    }

    /**
     * 檢查當前畫面 (`VisionPipelineReport`) 是否存在與目標詞同名或同 `UI_ONTOLOGY` 角色的按鈕 (`+S` 條件)
     */
    fun isTargetButtonOnScreen(
        targetPhrase: String,
        report: VisionPipelineReport?,
        context: Context? = null
    ): Boolean {
        if (report == null || report.finalNodes.isEmpty()) return false
        val clean = ActionIntentMutualExclusionEngine.extractCleanTargetActionPhrase(targetPhrase).trim().lowercase()
        if (clean.isEmpty()) return false
        val normTarget = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(clean, context)
        val targetRole = OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(normTarget, context)
            ?: OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(clean, context)

        for (node in report.finalNodes) {
            if (node.shortTypeTag == "INP" || InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)) continue
            val lbl = node.label.replace("圖示:", "").trim().lowercase()
            if (lbl.isEmpty()) continue
            val normLbl = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(lbl, context)
            if (lbl == clean || normLbl == normTarget ||
                (clean.length >= 2 && (lbl.contains(clean) || normLbl.contains(normTarget))) ||
                (lbl.length >= 2 && (clean.contains(lbl) || normTarget.contains(normLbl)))
            ) {
                return true
            }
            if (targetRole != null) {
                val candRole = OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(normLbl, context)
                    ?: node.predictedFunctionRole.takeIf { it.isNotBlank() }
                if (candRole == targetRole) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 解析單一子句的「主動作代碼 (`c/t/s/b/r/w/l/d/o/x/g/p`)」與「純淨目標受詞」
     */
    @Suppress("UNUSED_PARAMETER")
    fun analyzeClauseActionAndTarget(
        clause: String,
        appHint: String? = null,
        report: VisionPipelineReport? = null,
        context: Context? = null
    ): SenseResolutionResult? {
        val clean = normalizeAsrVariantsWithLexicon(clause.trim(), context)
            .replace(Regex("^(?:請幫我|麻煩幫我|可以幫我|幫我|我想|我要|請|先|再|然後|接著|並|并)\\s*"), "")
            .trim()
        if (clean.isEmpty()) return null

        // 1. 檢查「把 <目標> 打開/關閉/點開」句型
        val baMatch = Regex("^(?:把|將|将)\\s*(.+?)\\s*(?:給|给)?(打開|開啟|打开|开启|啟用|關閉|關掉|关闭|关掉|停用|點開|按一下|點一下)$").find(clean)
        if (baMatch != null) {
            val obj = baMatch.groupValues[1].trim()
            val verb = baMatch.groupValues[2].trim()
            return resolveSenseInContext(verb, obj, report, context)
        }

        // 2. 由長到短比對開頭動詞
        val candidateVerbLengths = intArrayOf(4, 3, 2, 1)
        for (len in candidateVerbLengths) {
            if (clean.length >= len) {
                val prefix = clean.substring(0, len)
                val entry = lookupEntry(prefix, context)
                if (entry != null && entry.primaryCode in setOf('c', 't', 's', 'b', 'r', 'w', 'l', 'd', 'o', 'x', 'g', 'p')) {
                    val rest = clean.substring(len).trim()
                    return resolveSenseInContext(prefix, rest, report, context)
                }
            }
        }

        // 3. 若整句本身就是一個已知按鈕名詞（例如「行車路線」、「規劃路線」、「開始導航」），當成點擊該按鈕 (`c`)
        val wholeEntry = lookupEntry(clean, context)
        if (wholeEntry != null) {
            if (wholeEntry.primaryCode == 'B') {
                return SenseResolutionResult(
                    matchedTerm = wholeEntry.term,
                    resolvedCode = 'c',
                    resolvedDisplayName = "點擊",
                    matchedCondition = "+S",
                    effectiveConfidence = wholeEntry.confidence,
                    sourceTier = wholeEntry.source,
                    canonicalOrRole = wholeEntry.canonicalOrRole,
                    fallbackUsed = false,
                    diagnosticTrace = "詞典命中目標按鈕名詞「${wholeEntry.term}」[B] -> 自動轉為點擊動作 (c)",
                    isTargetUiButton = true,
                    iconHints = wholeEntry.iconHints
                )
            }
            return resolveSenseInContext(clean, "", report, context)
        }
        return null
    }

    // =========================================================================
    // P2：位置與文字脈絡判斷（圖標 + 所在位置 + 周圍文字 -> 實際功用與動作標籤）
    // =========================================================================

    /**
     * 畫面空間分區（搭配優先級解決同圖標多義衝突）
     */
    enum class ScreenRegionZone(val displayNameZh: String) {
        INSIDE_INPUT_BOX("輸入框內部或右側"),
        SHEET_TOP_HANDLE("底部抽屜面板頂端"),
        BOTTOM_RIGHT_FAB("右下角懸浮按鈕區"),
        MODAL_CORNER("彈窗或對話框角落"),
        TOP_BAR("畫面頂欄"),
        BOTTOM_BAR("畫面底部導覽列"),
        MAIN_CONTENT("主內容視口區")
    }

    /**
     * 圖標結合位置與周圍文字後的最終功用裁決結果
     */
    data class IconContextResolution(
        val iconNameOrLabel: String,
        val regionZone: ScreenRegionZone,
        val resolvedFunctionalRole: String,
        val primaryActionCode: Char,     // 'c'=點擊, 'r'=滑動展開/收合, 'b'=返回/關閉, 'w'=開關/勾選
        val fallbackActionCode: Char,
        val meaningZh: String,
        val rulePriority: Int,
        val confidence: Float
    )

    /**
     * P2 核心：輸入圖標名稱/幾何標籤、座標、父容器類型與鄰近文字，裁決該圖標在當下脈絡的真實功用與動作代碼。
     * 所有規則皆附帶顯式優先級 (`rulePriority`)，避免規則衝突。
     */
    fun resolveIconInContext(
        iconNameOrLabel: String,
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
        parentContainerType: String = "",
        neighboringText: String = "",
        allNodes: List<Stage2ConvergedNode> = emptyList(),
        context: Context? = null
    ): IconContextResolution {
        ensureLoaded(context)
        val sw = screenWidth.coerceAtLeast(320).toFloat()
        val sh = screenHeight.coerceAtLeast(480).toFloat()
        val bw = (bounds.right - bounds.left).coerceAtLeast(1).toFloat()
        val bh = (bounds.bottom - bounds.top).coerceAtLeast(1).toFloat()
        val aspect = bw / bh
        val normCx = (((bounds.left + bounds.right) * 0.5f) / sw).coerceIn(0f, 1f)
        val normCy = (((bounds.top + bounds.bottom) * 0.5f) / sh).coerceIn(0f, 1f)

        val cleanIcon = iconNameOrLabel.removePrefix("圖示:").trim().lowercase()
        val parentLower = parentContainerType.lowercase()
        val neighborLower = neighboringText.lowercase()

        // 判斷是否處於輸入框右側或內部
        val hasAdjacentInput = allNodes.any { n ->
            n.shortTypeTag == "INP" &&
                abs((n.finalTapPx.y / sh) - normCy) <= 0.055f &&
                normCx >= (n.convergedBounds.left / sw)
        } || parentLower.contains("input") || parentLower.contains("edittext") ||
            parentLower.contains("search_bar") || parentLower.contains("search_plate") ||
            neighborLower.contains("搜尋框") || neighborLower.contains("輸入框") || neighborLower.contains("清除查詢")

        // 判斷是否處於底部抽屜頂端把手區
        val isSheetTopHandleZone = (normCx in 0.25f..0.75f && normCy in 0.26f..0.88f &&
            (aspect >= 3.6f || parentLower.contains("sheet") || parentLower.contains("drawer") ||
                parentLower.contains("panel") || neighborLower.contains("抽屜") || neighborLower.contains("路線") ||
                neighborLower.contains("詳情") || neighborLower.contains("評論")))

        val isBottomRightFabZone = normCx >= 0.72f && normCy >= 0.68f && aspect < 2.2f
        val isModalCornerZone = (normCx >= 0.76f || normCx <= 0.22f) && normCy in 0.04f..0.75f && !hasAdjacentInput

        val inferredZone = when {
            hasAdjacentInput -> ScreenRegionZone.INSIDE_INPUT_BOX
            isSheetTopHandleZone -> ScreenRegionZone.SHEET_TOP_HANDLE
            isBottomRightFabZone -> ScreenRegionZone.BOTTOM_RIGHT_FAB
            isModalCornerZone && (cleanIcon.contains("close") || cleanIcon.contains("關閉") || cleanIcon == "X" || cleanIcon == "x") -> ScreenRegionZone.MODAL_CORNER
            normCy <= 0.14f -> ScreenRegionZone.TOP_BAR
            normCy >= 0.86f -> ScreenRegionZone.BOTTOM_BAR
            else -> ScreenRegionZone.MAIN_CONTENT
        }

        // =====================================================================
        // 規則組 1：`X`（關閉 vs 清除輸入框文字）— 優先級 100 vs 90
        // =====================================================================
        val isCloseOrClearGlyph = cleanIcon in setOf("關閉", "close", "cancel", "clear", "dismiss", "highlight_off", "X", "x", "ui_close_x", "清除") ||
            cleanIcon.contains("close") || cleanIcon.contains("clear") || cleanIcon.contains("關閉")
        if (isCloseOrClearGlyph) {
            if (hasAdjacentInput || cleanIcon.contains("clear") || cleanIcon.contains("清除")) {
                return IconContextResolution(
                    iconNameOrLabel = iconNameOrLabel,
                    regionZone = ScreenRegionZone.INSIDE_INPUT_BOX,
                    resolvedFunctionalRole = "CLEAR_INPUT",
                    primaryActionCode = 'c',
                    fallbackActionCode = 'c',
                    meaningZh = "清除輸入框文字",
                    rulePriority = 100,
                    confidence = 0.97f
                )
            }
            return IconContextResolution(
                iconNameOrLabel = iconNameOrLabel,
                regionZone = ScreenRegionZone.MODAL_CORNER,
                resolvedFunctionalRole = "CLOSE_MODAL",
                primaryActionCode = 'b',
                fallbackActionCode = 'c',
                meaningZh = "關閉彈窗或視窗",
                rulePriority = 90,
                confidence = 0.94f
            )
        }

        // =====================================================================
        // 規則組 2：`^` / 扁平橢圓把手（底部面板頂端上滑展開 vs 右下角浮動鈕回到頂部）— 優先級 100 vs 90
        // =====================================================================
        val isUpOrSheetHandleGlyph = cleanIcon in setOf(
            "上滑展開抽屜", "向上箭頭", "bottom_sheets", "drag_handle", "bottom_panel_open",
            "expand_less", "keyboard_arrow_up", "keyboard_double_arrow_up", "chevron_line_up",
            "swipe_up", "swipe_up_alt", "horizontal_rule", "minimize", "arrow_upward", "^"
        ) || cleanIcon.contains("上滑展開") || cleanIcon.contains("向上箭頭") || cleanIcon.contains("drag_handle") || cleanIcon.contains("bottom_sheet")
        if (isUpOrSheetHandleGlyph) {
            if (isBottomRightFabZone && aspect < 2.2f && !cleanIcon.contains("抽屜") && !cleanIcon.contains("bottom_sheet") && !cleanIcon.contains("drag_handle")) {
                return IconContextResolution(
                    iconNameOrLabel = iconNameOrLabel,
                    regionZone = ScreenRegionZone.BOTTOM_RIGHT_FAB,
                    resolvedFunctionalRole = "SCROLL_TO_TOP",
                    primaryActionCode = 'c',
                    fallbackActionCode = 'r',
                    meaningZh = "點擊快速回到頂部",
                    rulePriority = 90,
                    confidence = 0.93f
                )
            }
            return IconContextResolution(
                iconNameOrLabel = iconNameOrLabel,
                regionZone = ScreenRegionZone.SHEET_TOP_HANDLE,
                resolvedFunctionalRole = "SWIPE_UP_EXPAND_SHEET",
                primaryActionCode = 'r',
                fallbackActionCode = 'c',
                meaningZh = "上滑展開底部抽屜面板",
                rulePriority = 100,
                confidence = 0.96f
            )
        }

        // =====================================================================
        // 規則組 3：`v`（面板頂端下滑收合 vs 表單欄位旁展開下拉選單）— 優先級 98 vs 88
        // =====================================================================
        val isDownGlyph = cleanIcon in setOf(
            "向下箭頭", "expand_more", "keyboard_arrow_down", "keyboard_double_arrow_down",
            "arrow_drop_down", "arrow_downward", "swipe_down", "bottom_panel_close",
            "collapse_all", "unfold_less", "v"
        ) || cleanIcon.contains("向下箭頭") || cleanIcon.contains("下滑收合") || cleanIcon.contains("下拉選單")
        if (isDownGlyph) {
            val isCollapseSheetContext = (normCx in 0.25f..0.75f &&
                (parentLower.contains("sheet") || parentLower.contains("drawer") || parentLower.contains("panel") ||
                    neighborLower.contains("收合") || neighborLower.contains("收起") || cleanIcon.contains("swipe_down") ||
                    cleanIcon.contains("bottom_panel_close")))
            if (isCollapseSheetContext) {
                return IconContextResolution(
                    iconNameOrLabel = iconNameOrLabel,
                    regionZone = ScreenRegionZone.SHEET_TOP_HANDLE,
                    resolvedFunctionalRole = "SWIPE_DOWN_COLLAPSE_SHEET",
                    primaryActionCode = 'r',
                    fallbackActionCode = 'c',
                    meaningZh = "下滑收合底部抽屜面板",
                    rulePriority = 98,
                    confidence = 0.94f
                )
            }
            return IconContextResolution(
                iconNameOrLabel = iconNameOrLabel,
                regionZone = inferredZone,
                resolvedFunctionalRole = "SWIPE_DOWN_COLLAPSE_SHEET",
                primaryActionCode = 'c',
                fallbackActionCode = 'r',
                meaningZh = "點擊展開下拉選單",
                rulePriority = 88,
                confidence = 0.91f
            )
        }

        // =====================================================================
        // 規則組 4：`<` / `>`（水平輪播滑動 vs 清單子項進入）— 優先級 95 vs 85
        // =====================================================================
        val isHorizontalChevron = cleanIcon in setOf(
            "向左箭頭", "向右箭頭", "chevron_left", "chevron_right", "keyboard_arrow_left",
            "keyboard_arrow_right", "swipe_left", "swipe_right", "arrow_forward_ios", "<", ">"
        ) || cleanIcon.contains("向左箭頭") || cleanIcon.contains("向右箭頭")
        if (isHorizontalChevron) {
            if (parentLower.contains("carousel") || parentLower.contains("pager") || cleanIcon.contains("swipe_")) {
                return IconContextResolution(
                    iconNameOrLabel = iconNameOrLabel,
                    regionZone = inferredZone,
                    resolvedFunctionalRole = "SWIPE_HORIZONTAL_CAROUSEL",
                    primaryActionCode = 'r',
                    fallbackActionCode = 'c',
                    meaningZh = "左右滑動切換輪播或分頁",
                    rulePriority = 95,
                    confidence = 0.92f
                )
            }
            return IconContextResolution(
                iconNameOrLabel = iconNameOrLabel,
                regionZone = inferredZone,
                resolvedFunctionalRole = "SWIPE_HORIZONTAL_CAROUSEL",
                primaryActionCode = 'c',
                fallbackActionCode = 'r',
                meaningZh = "點擊翻頁或進入子項詳情",
                rulePriority = 85,
                confidence = 0.90f
            )
        }

        // =====================================================================
        // 規則組 5：切換開關 vs 勾選方框 — 優先級 95
        // =====================================================================
        if (cleanIcon.contains("切換開關") || cleanIcon.contains("toggle") || cleanIcon.contains("switch") ||
            cleanIcon.contains("勾選") || cleanIcon.contains("方框") || cleanIcon.contains("check_box")
        ) {
            val isCheckbox = cleanIcon.contains("勾選") || cleanIcon.contains("方框") || cleanIcon.contains("check_box")
            return IconContextResolution(
                iconNameOrLabel = iconNameOrLabel,
                regionZone = inferredZone,
                resolvedFunctionalRole = "TOGGLE_SWITCH_CHECKBOX",
                primaryActionCode = 'w',
                fallbackActionCode = 'c',
                meaningZh = if (isCheckbox) "點擊勾選或取消勾選核取方塊" else "點擊切換開關狀態",
                rulePriority = 95,
                confidence = 0.95f
            )
        }

        // =====================================================================
        // 規則組 6：一般圖標查表映射
        // =====================================================================
        val dbRole = OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(cleanIcon, context)
            ?: iconToRoles[cleanIcon]?.firstOrNull()
            ?: "UNKNOWN_ICON"
        val primaryAct = when (dbRole) {
            "SWIPE_UP_EXPAND_SHEET" -> 'r'
            "BACK_UP_NAVIGATION", "CLOSE_MODAL" -> 'b'
            "SEARCH_SUBMIT" -> 's'
            "SHARE_FORWARD" -> 'g'
            "PLAY_PAUSE_MEDIA" -> 'p'
            "COPY_PASTE_SELECT" -> 'x'
            "TOGGLE_SWITCH_CHECKBOX" -> 'w'
            else -> 'c'
        }
        return IconContextResolution(
            iconNameOrLabel = iconNameOrLabel,
            regionZone = inferredZone,
            resolvedFunctionalRole = dbRole,
            primaryActionCode = primaryAct,
            fallbackActionCode = if (primaryAct == 'r') 'c' else 'r',
            meaningZh = getTagDisplayName(primaryAct),
            rulePriority = 50,
            confidence = if (dbRole == "UNKNOWN_ICON") 0.35f else 0.85f
        )
    }

    // =========================================================================
    // P5：指令詞庫與圖標標籤雙向打通（指令詞 <-> icon_hint 集合）
    // =========================================================================

    /**
     * 根據使用者指令詞或子句，解析出對應之目標圖標集合 (`icon_hint`)
     */
    fun resolveIconHintsForCommand(clauseOrGoal: String, context: Context? = null): List<String> {
        if (clauseOrGoal.isBlank()) return emptyList()
        ensureLoaded(context)
        val clean = normalizeAsrVariantsWithLexicon(clauseOrGoal.trim(), context).lowercase()
        val normTw = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(clean, context)

        val hints = LinkedHashSet<String>()
        lookupEntry(clean, context)?.iconHints?.let { hints.addAll(it) }
        lookupEntry(normTw, context)?.iconHints?.let { hints.addAll(it) }

        val clauseRes = analyzeClauseActionAndTarget(clauseOrGoal, null, null, context)
        if (clauseRes != null) {
            hints.addAll(clauseRes.iconHints)
            if (clauseRes.canonicalOrRole.isNotEmpty()) {
                roleToIconHints[clauseRes.canonicalOrRole]?.let { hints.addAll(it) }
            }
        }

        val role = OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(normTw, context)
        if (role != null) {
            roleToIconHints[role]?.let { hints.addAll(it) }
        }

        // 針對常見複合口語指令補充雙向對照
        when {
            normTw.contains("展開詳情") || normTw.contains("展開抽屜") || normTw.contains("上滑展開") || normTw.contains("拉開") -> {
                hints.addAll(listOf("bottom_sheets", "drag_handle", "bottom_panel_open", "expand_less", "keyboard_arrow_up", "swipe_up", "上滑展開抽屜", "向上箭頭", "展開全文"))
            }
            normTw.contains("回到頂部") -> {
                hints.addAll(listOf("expand_less", "keyboard_arrow_up", "arrow_upward", "vertical_align_top", "向上箭頭"))
            }
            normTw.contains("收合") || normTw.contains("收起") || normTw.contains("下拉") -> {
                hints.addAll(listOf("expand_more", "keyboard_arrow_down", "arrow_drop_down", "swipe_down", "向下箭頭"))
            }
            normTw.contains("清除") || normTw.contains("清空") -> {
                hints.addAll(listOf("clear", "close", "cancel", "關閉", "刪除"))
            }
            normTw.contains("關閉") || normTw.contains("關掉") -> {
                hints.addAll(listOf("close", "cancel", "highlight_off", "關閉"))
            }
            normTw.contains("路線") -> {
                hints.addAll(listOf("directions", "alt_route", "directions_car", "規劃路線"))
            }
            normTw.contains("導航") || normTw.contains("出發") -> {
                hints.addAll(listOf("navigation", "near_me", "開始導航"))
            }
        }
        return hints.toList()
    }

    /**
     * 反向查詢：給定圖標名稱，回傳其在詞典中關聯的所有 UI 功用角色
     */
    fun resolveRolesForIconName(iconName: String, context: Context? = null): Set<String> {
        ensureLoaded(context)
        val clean = iconName.removePrefix("圖示:").trim().lowercase()
        val out = LinkedHashSet<String>()
        iconToRoles[clean]?.let { out.addAll(it) }
        OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(clean, context)?.let { out.add(it) }
        return out
    }

    /**
     * P5 規劃器支援：當畫面上找不到文字按鈕時，改用 `icon_hint` 與位置脈絡 (`resolveIconInContext`) 尋找最匹配的圖標節點！
     */
    fun findBestNodeByIconHint(
        clauseOrGoal: String,
        nodes: List<Stage2ConvergedNode>,
        screenWidth: Int,
        screenHeight: Int,
        context: Context? = null
    ): Stage2ConvergedNode? {
        if (nodes.isEmpty() || clauseOrGoal.isBlank()) return null
        ensureLoaded(context)
        val normGoal = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(clauseOrGoal.trim().lowercase(), context)
        val targetHints = resolveIconHintsForCommand(normGoal, context).map { it.lowercase() }.toSet()
        if (targetHints.isEmpty()) return null

        val wantsClearInput = normGoal.contains("清除") || normGoal.contains("清空")
        val wantsCloseModal = (normGoal.contains("關閉") || normGoal.contains("關掉")) && !wantsClearInput
        val wantsScrollToTop = normGoal.contains("回到頂部")
        val wantsExpandSheet = normGoal.contains("展開") || normGoal.contains("抽屜") || normGoal.contains("上滑") || normGoal.contains("拉開")

        var bestNode: Stage2ConvergedNode? = null
        var bestScore = -999f

        for (node in nodes) {
            if (node.shortTypeTag == "INP" || InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)) continue
            val cleanLbl = node.label.removePrefix("圖示:").trim().lowercase()
            val roleCode = node.predictedFunctionRole.lowercase()

            val iconResolution = resolveIconInContext(
                iconNameOrLabel = node.label,
                bounds = node.convergedBounds,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                allNodes = nodes,
                context = context
            )

            var score = 0f
            if (cleanLbl in targetHints || targetHints.any { h -> h.length >= 2 && cleanLbl.contains(h) }) {
                score += 40f
            }
            val matEntry = OpenSourceAiDatabaseManager.lookupMaterialIconByNameOrTag(cleanLbl, context)
            if (matEntry != null && matEntry.iconName in targetHints) {
                score += 45f
            }

            // P2 + P5 位置消歧加成：例如指令說「關閉」時優先選 MODAL_CORNER 的 X，指令說「清除文字」時優先選 INSIDE_INPUT_BOX 的 X
            if (wantsClearInput) {
                if (iconResolution.resolvedFunctionalRole == "CLEAR_INPUT") score += 60f
                else if (iconResolution.resolvedFunctionalRole == "CLOSE_MODAL") score -= 80f
            } else if (wantsCloseModal) {
                if (iconResolution.resolvedFunctionalRole == "CLOSE_MODAL") score += 60f
                else if (iconResolution.resolvedFunctionalRole == "CLEAR_INPUT") score -= 80f
            }

            if (wantsScrollToTop) {
                if (iconResolution.resolvedFunctionalRole == "SCROLL_TO_TOP") score += 55f
                else if (iconResolution.regionZone == ScreenRegionZone.SHEET_TOP_HANDLE) score -= 45f
            } else if (wantsExpandSheet) {
                if (iconResolution.resolvedFunctionalRole == "SWIPE_UP_EXPAND_SHEET" &&
                    iconResolution.regionZone == ScreenRegionZone.SHEET_TOP_HANDLE
                ) {
                    score += 60f
                } else if (iconResolution.resolvedFunctionalRole == "SCROLL_TO_TOP") {
                    score -= 45f
                }
            }

            if (roleCode.isNotEmpty() && targetHints.any { h -> roleToIconHints[node.predictedFunctionRole]?.contains(h) == true }) {
                score += 25f
            }

            if (score > bestScore && score >= 25f) {
                bestScore = score
                bestNode = node
            }
        }
        return bestNode
    }

    // =========================================================================
    // P6：動作後驗證與自救（Post-Action Verification & Self-Rescue）
    // =========================================================================

    enum class IconRescueStrategy(val descriptionZh: String) {
        NONE_SUCCESS("畫面已成功變化，無需自救"),
        SWITCH_ACTION_MODE("第1步自救：切換動作模式（上滑 ↔ 點擊）"),
        APPLY_POSITION_OFFSET("第2步自救：微調作用點座標偏移並重試"),
        SWITCH_CANDIDATE_ICON("第3步自救：放棄失效圖標，改換下一個候選圖標並記錄失敗")
    }

    data class IconPostActionVerificationResult(
        val screenChanged: Boolean,
        val panelHeightDeltaPx: Int,
        val nodeCountDelta: Int,
        val attemptCountForIcon: Int,
        val rescueStrategy: IconRescueStrategy,
        val recommendedActionCode: Char,
        val recommendedPointPx: Point,
        val alternateCandidateNode: Stage2ConvergedNode?,
        val markedAsFailedInApp: Boolean,
        val diagnosticMessage: String
    )

    private val iconAttemptCounter = ConcurrentHashMap<String, Int>()
    private val appFailedIconActions = ConcurrentHashMap<String, Boolean>()

    fun clearIconRescueMemory() {
        iconAttemptCounter.clear()
        appFailedIconActions.clear()
    }

    fun isIconActionMarkedFailed(packageName: String, iconKey: String, actionCode: Char): Boolean {
        return appFailedIconActions["$packageName|$iconKey|$actionCode"] == true
    }

    /**
     * P6 核心：每次執行圖標動作後，比對前後畫面（面板高度變化、新增元件、節點數量、開關狀態）。
     * 若畫面沒變化，依序啟動三步自救：
     * 1. 換動作（上滑 `r` ↔ 點擊 `c`）
     * 2. 換位置偏移（座標偏移重試）
     * 3. 換候選圖標（同一圖標、同一動作最多嘗試 2 次，超過即記錄該 App 的失敗圖標避免無限重試）
     */
    fun verifyIconActionAndRecover(
        packageName: String,
        targetIconNode: Stage2ConvergedNode,
        executedActionCode: Char,
        beforeNodes: List<Stage2ConvergedNode>,
        afterNodes: List<Stage2ConvergedNode>,
        beforeSheetTopY: Int = -1,
        afterSheetTopY: Int = -1
    ): IconPostActionVerificationResult {
        val panelHeightDelta = if (beforeSheetTopY >= 0 && afterSheetTopY >= 0) {
            abs(beforeSheetTopY - afterSheetTopY)
        } else {
            val beforeMinY = beforeNodes.minOfOrNull { it.convergedBounds.top } ?: 0
            val afterMinY = afterNodes.minOfOrNull { it.convergedBounds.top } ?: 0
            abs(beforeMinY - afterMinY)
        }
        val nodeCountDelta = abs(afterNodes.size - beforeNodes.size)
        val beforeLabels = beforeNodes.map { "${it.label}|${it.inlineStatePrefix}" }.toSet()
        val afterLabels = afterNodes.map { "${it.label}|${it.inlineStatePrefix}" }.toSet()
        val labelsChanged = beforeLabels != afterLabels

        val screenChanged = panelHeightDelta >= 32 || nodeCountDelta >= 1 || labelsChanged
        val cx = (targetIconNode.convergedBounds.left + targetIconNode.convergedBounds.right) / 2
        val cy = (targetIconNode.convergedBounds.top + targetIconNode.convergedBounds.bottom) / 2
        val iconKey = "${targetIconNode.label}|$cx,$cy"
        val attemptKey = "$packageName|$iconKey|$executedActionCode"

        if (screenChanged) {
            iconAttemptCounter.remove(attemptKey)
            return IconPostActionVerificationResult(
                screenChanged = true,
                panelHeightDeltaPx = panelHeightDelta,
                nodeCountDelta = nodeCountDelta,
                attemptCountForIcon = 0,
                rescueStrategy = IconRescueStrategy.NONE_SUCCESS,
                recommendedActionCode = executedActionCode,
                recommendedPointPx = targetIconNode.finalTapPx,
                alternateCandidateNode = null,
                markedAsFailedInApp = false,
                diagnosticMessage = "動作後畫面已成功變化 (高度差=${panelHeightDelta}px, 節點差=$nodeCountDelta)"
            )
        }

        val currentAttempts = (iconAttemptCounter[attemptKey] ?: 0) + 1
        iconAttemptCounter[attemptKey] = currentAttempts

        // 同一圖標、同一動作最多嘗試 2 次
        if (currentAttempts == 1) {
            val flippedAction = when (executedActionCode) {
                'r' -> 'c'
                'c' -> 'r'
                'b' -> 'c'
                else -> 'c'
            }
            val ox = targetIconNode.finalTapPx.x
            val oy = (targetIconNode.finalTapPx.y + if (flippedAction == 'r') -48 else 16).coerceAtLeast(10)
            val offsetPoint = Point(ox, oy).apply {
                x = ox
                y = oy
            }
            return IconPostActionVerificationResult(
                screenChanged = false,
                panelHeightDeltaPx = 0,
                nodeCountDelta = 0,
                attemptCountForIcon = currentAttempts,
                rescueStrategy = IconRescueStrategy.SWITCH_ACTION_MODE,
                recommendedActionCode = flippedAction,
                recommendedPointPx = offsetPoint,
                alternateCandidateNode = null,
                markedAsFailedInApp = false,
                diagnosticMessage = "第 1 次動作無畫面變化 -> 啟動自救：切換動作 ($executedActionCode -> $flippedAction) 並微調座標至 (${offsetPoint.x}, ${offsetPoint.y})"
            )
        }

        // 第 2 次仍無變化 -> 標記該 App 此圖標動作失敗，並切換至下一個候選圖標
        appFailedIconActions[attemptKey] = true
        val alternate = afterNodes.firstOrNull { cand ->
            val candCx = (cand.convergedBounds.left + cand.convergedBounds.right) / 2
            val candCy = (cand.convergedBounds.top + cand.convergedBounds.bottom) / 2
            cand.nodeId != targetIconNode.nodeId &&
                cand.shortTypeTag != "INP" &&
                !InAppOperationPlanner.isForbiddenVoiceOrCameraNode(cand) &&
                !isIconActionMarkedFailed(packageName, "${cand.label}|$candCx,$candCy", 'c')
        }
        val fallbackX = targetIconNode.finalTapPx.x
        val fallbackY = (targetIconNode.finalTapPx.y - 96).coerceAtLeast(20)
        val altPoint = alternate?.finalTapPx ?: Point(fallbackX, fallbackY).apply {
            x = fallbackX
            y = fallbackY
        }
        val strategy = if (alternate != null) {
            IconRescueStrategy.SWITCH_CANDIDATE_ICON
        } else {
            IconRescueStrategy.APPLY_POSITION_OFFSET
        }
        return IconPostActionVerificationResult(
            screenChanged = false,
            panelHeightDeltaPx = 0,
            nodeCountDelta = 0,
            attemptCountForIcon = currentAttempts,
            rescueStrategy = strategy,
            recommendedActionCode = 'c',
            recommendedPointPx = altPoint,
            alternateCandidateNode = alternate,
            markedAsFailedInApp = true,
            diagnosticMessage = "連續 $currentAttempts 次無畫面變化 -> 已記錄該 App 失敗圖標 [$attemptKey]，改換候選圖標「${alternate?.label ?: "偏移點"}」"
        )
    }

    fun lookup(term: String, context: Context? = null): LexiconEntry? = lookupEntry(term, context)

    fun getLexiconSize(context: Context? = null): Int {
        ensureLoaded(context)
        return entriesByTermLower.size
    }

    fun buildQualitySummary(context: Context? = null): String {
        val r = getQualityReport(context)
        return "總計=${r.totalEntries} (L1自動(A)=${r.l1AutoCount}, L2半自動(S)=${r.l2SemiAutoCount}, L3人工精標(M)=${r.l3ManualCount}, 多義詞=${r.multiSenseCount}, 條件標籤=${r.conditionalSenseCount}, ASR糾錯=${r.asrHomophoneCount}, 圖標提示=${r.iconHintEntryCount})"
    }

    /**
     * 取得詞典品質統計報告
     */
    fun getQualityReport(context: Context? = null): LexiconQualityReport {
        ensureLoaded(context)
        var l1 = 0
        var l2 = 0
        var l3 = 0
        var multi = 0
        var cond = 0
        var asr = 0
        var iconHintsCnt = 0
        for (e in entriesByTermLower.values) {
            when (e.source) {
                'A' -> l1++
                'S' -> l2++
                'M' -> l3++
            }
            if (e.senses.size >= 2) multi++
            if (e.senses.any { !it.condition.isNullOrEmpty() }) cond++
            if (e.primaryCode == 'e') asr++
            if (e.iconHints.isNotEmpty()) iconHintsCnt++
        }
        return LexiconQualityReport(
            totalEntries = entriesByTermLower.size,
            l1AutoCount = l1,
            l2SemiAutoCount = l2,
            l3ManualCount = l3,
            multiSenseCount = multi,
            conditionalSenseCount = cond,
            asrHomophoneCount = asr,
            iconHintEntryCount = iconHintsCnt
        )
    }
}
