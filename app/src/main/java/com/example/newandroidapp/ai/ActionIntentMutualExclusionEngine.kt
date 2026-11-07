package com.example.newandroidapp.ai

import android.content.Context
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * 超大規模 15萬 × 15萬 (149,584 × 149,584 = 223億級詞對空間) 語義互斥、反義詞圖譜與五層本體樹決策引擎
 * (`ActionIntentMutualExclusionEngine`)
 *
 * 結合三大 GitHub 開源知識源實現 O(1) 毫秒級意圖共鳴與互斥抑制：
 * 1. **UI Action Ontology & Google Material Icons (4,267 圖標 × 74,758 標籤 × 28 角色)**：
 *    識別目標動作與候選按鈕之功能角色，對同排互斥動作（如目標為「規劃路線」，候選為「儲存地點 / 分享 / 通話 / 評論 / 網站」）
 *    施加 `-95.0f` 硬性互斥抑制，對目標共鳴角色給予 `+68.0f` 強效加分。
 * 2. **GitHub 開源中英反義詞圖譜 (`liuhuanyong/ChineseAntiword` + `mfaruqui/non-distributional` + `fong0124/chinese-dictionary`)**：
 *    38,271 條反義與對立關係（雙向展開逾 65,000 條反義邊），並透過同義詞群廣播（Synset Broadcast）覆蓋百萬級對立詞對。
 * 3. **哈工大詞林擴展版 5 層本體樹距離算法 (`One-sixth/HIT-IR-Lab-Tongyici-Cilin-Extended` + `ashengtx/CilinSimilarity` 田久樂樹距離公式)**：
 *    利用 32-bit 位元壓縮本體編碼 `(L1, L2, L3, L4, L5, Op)`，在 `< 1 us` 內計算任意兩詞在五層本體樹上的語義相似度与同級並列排斥度。
 */
object ActionIntentMutualExclusionEngine {

    enum class RelationType {
        EXACT_ROLE_RESONANCE,
        EXACT_SYNSET_RESONANCE,
        SIBLING_ACTION_MUTUAL_EXCLUSION,
        EXPLICIT_ANTONYM_PENALTY,
        ONTOLOGICAL_COORDINATE_DIVERGENCE,
        NEUTRAL_OR_UNRELATED
    }

    data class MutualExclusionEvaluation(
        val scoreDelta: Float,
        val treeSimilarity: Float,
        val relationType: RelationType,
        val targetResolvedRole: String?,
        val candidateResolvedRole: String?,
        val diagnosticReason: String
    )

    private val ACTION_VERB_PREFIX_REGEX = Regex(
        "^(?:請|幫我|麻煩|去|到|點擊|點選|點一下|按一下|按下|按|選擇|選取|打開|開啟|進入|查看|點)\\s*"
    )
    private val TRAILING_BUTTON_MODIFIER_REGEX = Regex(
        "(?:那個|這個|那顆|這顆|該)?(?:按鈕|按鍵|圖示|圖標|鍵|一下)+$"
    )

    /**
     * 抽取純淨之目標動作核心詞（例如 `"點擊 規劃路線"` -> `"規劃路線"`，`"點擊路線那個按鈕"` -> `"路線"`；若單獨為 `"開啟"` 則保留 `"開啟"`）
     */
    fun extractCleanTargetActionPhrase(rawGoalOrSubGoal: String): String {
        var s = rawGoalOrSubGoal.trim()
        repeat(2) {
            val stripped = s.replaceFirst(ACTION_VERB_PREFIX_REGEX, "").trim()
            if (stripped.isNotEmpty()) {
                s = stripped
            }
        }
        val withoutBtnSuffix = s.replace(TRAILING_BUTTON_MODIFIER_REGEX, "").trim()
        if (withoutBtnSuffix.isNotEmpty()) {
            s = withoutBtnSuffix
        }
        return s
    }

    /**
     * 計算兩個 32-bit 壓縮五層本體樹編碼之間的田久樂 (Tian Jiule 2010) 樹結構語義相似度 [0.0f .. 1.0f]
     *
     * 編碼格式：
     * Bit 30: Op (1='=', 0='#'/'@')
     * Bits [29..24]: L1 (大類)
     * Bits [23..18]: L2 (中類)
     * Bits [17..11]: L3 (小類)
     * Bits [10..5]:  L4 (詞群)
     * Bits [4..0]:   L5 (原子詞群)
     */
    fun compute5LevelOntologicalTreeSimilarity(codeA: Int, codeB: Int): Float {
        if (codeA == 0 || codeB == 0) return 0f
        if (codeA == codeB) {
            val isEqualSynset = ((codeA ushr 30) and 1) == 1
            return if (isEqualSynset) 1.0f else 0.72f
        }

        val l1A = (codeA ushr 24) and 0x3F
        val l1B = (codeB ushr 24) and 0x3F
        if (l1A != l1B) return 0.05f

        val l2A = (codeA ushr 18) and 0x3F
        val l2B = (codeB ushr 18) and 0x3F
        if (l2A != l2B) {
            val k = abs(l2A - l2B).toFloat()
            val n = 26f
            return (0.65f * cos(n * Math.PI / 180.0).toFloat() * ((n - k + 1f) / n)).coerceIn(0.08f, 0.65f)
        }

        val l3A = (codeA ushr 11) and 0x7F
        val l3B = (codeB ushr 11) and 0x7F
        if (l3A != l3B) {
            val k = abs(l3A - l3B).toFloat()
            val n = 40f
            return (0.80f * cos(n * Math.PI / 180.0).toFloat() * ((n - k + 1f) / n)).coerceIn(0.15f, 0.80f)
        }

        val l4A = (codeA ushr 5) and 0x3F
        val l4B = (codeB ushr 5) and 0x3F
        if (l4A != l4B) {
            val k = abs(l4A - l4B).toFloat()
            val n = 26f
            return (0.90f * cos(n * Math.PI / 180.0).toFloat() * ((n - k + 1f) / n)).coerceIn(0.25f, 0.90f)
        }

        val l5A = codeA and 0x1F
        val l5B = codeB and 0x1F
        val k = abs(l5A - l5B).toFloat()
        val n = 20f
        return (0.96f * cos(n * Math.PI / 180.0).toFloat() * ((n - k + 1f) / n)).coerceIn(0.35f, 0.96f)
    }

    /**
     * 判定兩個五層本體樹編碼是否屬於「同大類/中類但不同功能的同級並列競爭動作 (Coordinate Sibling Actions)」
     */
    private fun isCoordinateCompetingBranch(codeA: Int, codeB: Int): Boolean {
        if (codeA == 0 || codeB == 0 || codeA == codeB) return false
        val l1A = (codeA ushr 24) and 0x3F
        val l1B = (codeB ushr 24) and 0x3F
        val l2A = (codeA ushr 18) and 0x3F
        val l2B = (codeB ushr 18) and 0x3F
        if (l1A != l1B || l2A != l2B) return false
        // 同一中類 (例如 UI 動作 Ma.. 或詞林動詞 Hi/Fa/Gb..) 但不同小類/詞群/原子詞群
        val leafMask = 0x3FFFF
        return (codeA and leafMask) != (codeB and leafMask)
    }

    /**
     * 對單一候選按鈕節點執行 15萬 × 15萬 語義共鳴與互斥反義評估
     *
     * @param targetPhrase 使用者目標動作或子目標（如 `"規劃路線"`、`"路線"`、`"儲存地點"`、`"分享"`）
     * @param candidateLabel 候選節點之顯示文字或圖標語義標籤（如 `"路線"`、`"儲存地點"`、`"圖示:規劃路線"`）
     * @param candidatePredictedRoleCode Phase2ButtonFunctionalityClassifier 預測之角色代碼（可選）
     */
    fun evaluateCandidateActionAdjustment(
        targetPhrase: String,
        candidateLabel: String,
        candidatePredictedRoleCode: String? = null,
        context: Context? = null
    ): MutualExclusionEvaluation {
        val cleanTarget = extractCleanTargetActionPhrase(targetPhrase).lowercase()
        val cleanCandidate = candidateLabel
            .replace("圖示:", "")
            .replace("[", "")
            .replace("]", "")
            .trim()
            .lowercase()

        if (cleanTarget.isEmpty() || cleanCandidate.isEmpty()) {
            return MutualExclusionEvaluation(
                scoreDelta = 0f,
                treeSimilarity = 0f,
                relationType = RelationType.NEUTRAL_OR_UNRELATED,
                targetResolvedRole = null,
                candidateResolvedRole = null,
                diagnosticReason = "EMPTY_INPUT"
            )
        }

        val normTarget = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(cleanTarget, context)
        val normCandidate = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(cleanCandidate, context)

        // 1. 解析目標與候選節點之 UI 動作角色 (28 類動作本體 + 4,267 Material Icons)
        val targetAllRoles = OpenSourceAiDatabaseManager.resolveAllUiActionRoles(normTarget, context)
            .ifEmpty { OpenSourceAiDatabaseManager.resolveAllUiActionRoles(cleanTarget, context) }
        val candidateAllRoles = LinkedHashSet<String>().apply {
            addAll(OpenSourceAiDatabaseManager.resolveAllUiActionRoles(normCandidate, context))
            addAll(OpenSourceAiDatabaseManager.resolveAllUiActionRoles(cleanCandidate, context))
            candidatePredictedRoleCode?.let { mapClassifierRoleToOntologyRole(it) }?.let { add(it) }
        }

        val sharedRole = targetAllRoles.firstOrNull { it in candidateAllRoles }
        val targetRole = sharedRole
            ?: OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(normTarget, context)
            ?: OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(cleanTarget, context)

        val candidateRoleFromLexicon = sharedRole
            ?: OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(normCandidate, context)
            ?: OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(cleanCandidate, context)

        val candidateRole = candidateRoleFromLexicon ?: candidatePredictedRoleCode?.let { mapClassifierRoleToOntologyRole(it) }

        // 2. 若目標是明確的 UI 動作角色（例如 MAP_DIRECTIONS_ROUTE「規劃路線」或 LIKE_FAVORITE_BOOKMARK「儲存地點」）
        if (targetRole != null && candidateRole != null) {
            if (targetRole == candidateRole) {
                return MutualExclusionEvaluation(
                    scoreDelta = 68.0f,
                    treeSimilarity = 1.0f,
                    relationType = RelationType.EXACT_ROLE_RESONANCE,
                    targetResolvedRole = targetRole,
                    candidateResolvedRole = candidateRole,
                    diagnosticReason = "ROLE_RESONANCE($targetRole)"
                )
            } else {
                // 同排互斥動作抑制：例如目標是 MAP_DIRECTIONS_ROUTE (規劃路線)，候選是 LIKE_FAVORITE_BOOKMARK (儲存地點) / SHARE_FORWARD (分享) / CALL_PHONE_CONTACT (通話)
                val penalty = computeRoleToRoleMutualExclusionPenalty(targetRole, candidateRole)
                if (penalty < 0f) {
                    return MutualExclusionEvaluation(
                        scoreDelta = penalty,
                        treeSimilarity = 0.05f,
                        relationType = RelationType.SIBLING_ACTION_MUTUAL_EXCLUSION,
                        targetResolvedRole = targetRole,
                        candidateResolvedRole = candidateRole,
                        diagnosticReason = "ROLE_MUTEX($targetRole vs $candidateRole -> $penalty)"
                    )
                }
            }
        }

        // 3. 檢查 38,271 行中英開源反義詞圖譜 (Direct + Synset Broadcast Antonym Check)
        val targetAntonyms = OpenSourceAiDatabaseManager.getExplicitAntonyms(normTarget, context)
        if (targetAntonyms.isNotEmpty()) {
            for (ant in targetAntonyms) {
                val normAnt = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(ant, context)
                if (normCandidate == normAnt || cleanCandidate == ant ||
                    (normAnt.length >= 2 && normCandidate.contains(normAnt))
                ) {
                    return MutualExclusionEvaluation(
                        scoreDelta = -95.0f,
                        treeSimilarity = 0.0f,
                        relationType = RelationType.EXPLICIT_ANTONYM_PENALTY,
                        targetResolvedRole = targetRole,
                        candidateResolvedRole = candidateRole,
                        diagnosticReason = "EXPLICIT_ANTONYM($normTarget <-> $normAnt)"
                    )
                }
            }
        }

        // 4. 計算 15萬 × 15萬 五層本體樹距離 (HIT-IR-Lab Cilin 5-Level Tree + UI Action Ontology)
        val targetCodes = OpenSourceAiDatabaseManager.getPackedOntologicalCodes(normTarget, context)
        val candidateCodes = OpenSourceAiDatabaseManager.getPackedOntologicalCodes(normCandidate, context)

        if (targetCodes.isNotEmpty() && candidateCodes.isNotEmpty()) {
            var maxSim = 0f
            var exactSynsetHit = false
            var coordinateConflict = false

            for (tc in targetCodes) {
                for (cc in candidateCodes) {
                    val sim = compute5LevelOntologicalTreeSimilarity(tc, cc)
                    if (sim > maxSim) maxSim = sim
                    if (tc == cc && ((tc ushr 30) and 1) == 1) {
                        exactSynsetHit = true
                    } else if (isCoordinateCompetingBranch(tc, cc)) {
                        coordinateConflict = true
                    }
                }
            }

            if (exactSynsetHit) {
                return MutualExclusionEvaluation(
                    scoreDelta = 52.0f,
                    treeSimilarity = 1.0f,
                    relationType = RelationType.EXACT_SYNSET_RESONANCE,
                    targetResolvedRole = targetRole,
                    candidateResolvedRole = candidateRole,
                    diagnosticReason = "CILIN_SYNSET_EQUAL($normTarget == $normCandidate)"
                )
            }

            if (coordinateConflict && maxSim < 0.92f) {
                return MutualExclusionEvaluation(
                    scoreDelta = -55.0f,
                    treeSimilarity = maxSim,
                    relationType = RelationType.ONTOLOGICAL_COORDINATE_DIVERGENCE,
                    targetResolvedRole = targetRole,
                    candidateResolvedRole = candidateRole,
                    diagnosticReason = "ONTOLOGICAL_SIBLING_CONFLICT(sim=${"%.2f".format(maxSim)})"
                )
            }

            return MutualExclusionEvaluation(
                scoreDelta = if (maxSim >= 0.85f) 24.0f * maxSim else 0f,
                treeSimilarity = maxSim,
                relationType = RelationType.NEUTRAL_OR_UNRELATED,
                targetResolvedRole = targetRole,
                candidateResolvedRole = candidateRole,
                diagnosticReason = "TREE_SIM(${"%.2f".format(maxSim)})"
            )
        }

        return MutualExclusionEvaluation(
            scoreDelta = 0f,
            treeSimilarity = 0f,
            relationType = RelationType.NEUTRAL_OR_UNRELATED,
            targetResolvedRole = targetRole,
            candidateResolvedRole = candidateRole,
            diagnosticReason = "NO_ONTOLOGY_MATCH"
        )
    }

    private fun mapClassifierRoleToOntologyRole(classifierCode: String): String? {
        return when (classifierCode) {
            "MAP_DIRECTIONS_ROUTE" -> "MAP_DIRECTIONS_ROUTE"
            "MAP_START_NAVIGATION" -> "MAP_START_NAVIGATION"
            "LIKE_FAVORITE_BOOKMARK" -> "LIKE_FAVORITE_BOOKMARK"
            "SHARE_EXPORT", "SHARE_FORWARD" -> "SHARE_FORWARD"
            "CALL_PHONE_CONTACT" -> "CALL_PHONE_CONTACT"
            "OPEN_EXTERNAL_WEBSITE" -> "OPEN_EXTERNAL_WEBSITE"
            "ADD_TO_CART_BUY", "ADD_TO_CART" -> "ADD_TO_CART"
            "CHECKOUT_BUY_NOW" -> "CHECKOUT_BUY_NOW"
            "DELETE_REMOVE_TRASH", "DELETE_REMOVE_CLEAR" -> "DELETE_REMOVE_CLEAR"
            "CLOSE_DISMISS", "CLOSE_MODAL", "CANCEL_DISMISS_SKIP" -> "CANCEL_DISMISS_SKIP"
            "NAV_BACK", "BACK_UP_NAVIGATION" -> "BACK_UP_NAVIGATION"
            "SEND_SUBMIT_CONFIRM" -> "SEND_SUBMIT_CONFIRM"
            "FILTER_SORT_CHIP" -> "FILTER_SORT_CHIP"
            "SETTINGS_MENU_MORE", "SETTINGS_PREFERENCES" -> "SETTINGS_PREFERENCES"
            "MEDIA_PLAY_PAUSE", "PLAY_PAUSE_MEDIA" -> "PLAY_PAUSE_MEDIA"
            else -> null
        }
    }

    /**
     * 計算兩個不同 UI 功能角色之間的互斥懲罰強度
     * 例如：當使用者要求 `MAP_DIRECTIONS_ROUTE`（規劃路線）時，
     * 畫面上的 `LIKE_FAVORITE_BOOKMARK`（儲存地點）、`SHARE_FORWARD`（分享）、`CALL_PHONE_CONTACT`（通話）、
     * `OPEN_EXTERNAL_WEBSITE`（網站）、`COMMENT_REPLY`（評論）全部給予 `-95.0f` 絕對互斥重罰！
     */
    private fun computeRoleToRoleMutualExclusionPenalty(targetRole: String, candidateRole: String): Float {
        if (targetRole == candidateRole) return 0f

        // 路線規劃與開始導航：若目標明確是「規劃路線」，「開始導航」為次要相鄰動作 (-45f)，其餘如「儲存地點/分享/通話」為絕對互斥 (-95f)
        if (targetRole == "MAP_DIRECTIONS_ROUTE" && candidateRole == "MAP_START_NAVIGATION") {
            return -45.0f
        }
        if (targetRole == "MAP_START_NAVIGATION" && candidateRole == "MAP_DIRECTIONS_ROUTE") {
            return -45.0f
        }

        // 購物車與立即購買：若目標明確是其中之一，另一個給予 -55f 區隔，其餘無關動作給予 -95f
        if ((targetRole == "ADD_TO_CART" && candidateRole == "CHECKOUT_BUY_NOW") ||
            (targetRole == "CHECKOUT_BUY_NOW" && candidateRole == "ADD_TO_CART")
        ) {
            return -55.0f
        }

        // 其他任何明確功能角色不一致（例如 規劃路線 vs 儲存地點、儲存地點 vs 分享、撥打電話 vs 儲存地點、確認 vs 取消）
        return -95.0f
    }
}
