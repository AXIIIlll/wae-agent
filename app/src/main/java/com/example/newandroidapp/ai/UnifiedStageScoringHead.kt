package com.example.newandroidapp.ai

import android.content.Context
import com.example.newandroidapp.stability.BackwardChainingPlanner
import com.example.newandroidapp.stability.EpisodicAppMemory
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppOperationStage
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.stability.UiKeywordLexicon
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import com.example.newandroidapp.vision.dataset.Phase2ButtonFunctionalityClassifier
import com.example.newandroidapp.vision.dataset.Phase3ScreenPostureClassifier
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 統一階段條件式特徵權重評分頭 + 軟性階段機率閘門 + 語義殘差向量投射 + 離線成對排名蒸餾引擎
 * (UnifiedStageScoringHead v2.0 — 方案 C + 方案 B + 方案 E 三合一核心)
 *
 * 1. 【方法 1：16 維結構化特徵權重矩陣 W_stage 與雙線性交互項 M_stage】
 *    S(node_i | r) = b_r + w_r^T x_i + sum_{(u,v)} M_r(u, v) * x_i[u] * x_i[v]
 *
 * 2. 【方案 B：軟性階段機率閘門 (Soft Stage Probability Gating)】
 *    根據 8 維全局畫面狀態向量 s_screen in [0, 1]^8 透過閘門權重矩陣 W_gate in R^{5 x 8} 計算 Softmax 階段分佈：
 *    p_stage = Softmax(W_gate * s_screen + b_gate)
 *    並以期望值加權合成最終候選節點分數：
 *    S_soft(node_i) = sum_{r in Regimes} p_stage[r] * S(node_i | r)
 *
 * 3. 【方案 C：語義殘差向量消去投射 (Residual Intent Vector Projection)】
 *    當複合指令（如「查一下台灣高鐵時刻表順便幫我看官網」、「去YouTube找周杰倫的晴天打開來聽」）已完成前半段搜尋時，
 *    不依賴死板的連接詞 Regex，直接以 Gram-Schmidt 正交投影從整句目標向量 v_goal 中消去已完成動作向量 v_done：
 *    v_residual = Normalize(v_goal - proj_{v_done}(v_goal))
 *    並自動提取殘差意圖片段與候選節點計算餘弦對齊度注入 f2 特徵。
 *
 * 4. 【方案 E：離線黃金場景成對排名權重蒸餾 (Offline Pairwise Margin Ranking Distillation)】
 *    以 Margin Ranking Loss 自動校準正負樣本對 (x^+, x^-) 之最優權重並固化於 `assets/ui_stage_scoring_weights.json`，
 *    確保任何手機安裝 APK 後立即享有完全一致的最優邏輯決策。
 */
object UnifiedStageScoringHead {

    const val FEATURE_DIM = 16
    const val GATE_STATE_DIM = 8
    const val WEIGHTS_ASSET_FILE = "ui_stage_scoring_weights.json"
    private const val OVERRIDE_WEIGHTS_FILE = "ui_stage_scoring_weights_override.json"

    enum class ScoringRegime {
        STAGE_A_EXPAND_SEARCH,
        STAGE_B_INPUT_TYPE,
        STAGE_B_SUBMIT_QUERY,
        STAGE_C_SELECT_RESULT,
        IN_APP_NAVIGATION
    }

    data class BilinearTerm(
        val i: Int,
        val j: Int,
        val weight: Float
    )

    data class StageWeightProfile(
        val regime: ScoringRegime,
        val bias: Float,
        val weights: FloatArray,
        val bilinearTerms: List<BilinearTerm>
    )

    data class SoftGateProfile(
        val regime: ScoringRegime,
        val bias: Float,
        val weights: FloatArray
    )

    data class ResidualIntentProjection(
        val residualTextPhrase: String,
        val residualSemanticVector: FloatArray,
        val hasWebsiteOrLinkIntent: Boolean,
        val hasPlayOrWatchIntent: Boolean,
        val hasFirstItemOrOpenIntent: Boolean,
        val residualMagnitude: Float
    )

    data class NodeScoringBreakdown(
        val nodeId: String,
        val regime: ScoringRegime,
        val features: FloatArray,
        val linearScore: Float,
        val bilinearScore: Float,
        val totalLogitScore: Float,
        val softGatedLogitScore: Float,
        val stageProbabilities: Map<ScoringRegime, Float>,
        val normalizedProbability: Float
    )

    data class PairwiseTrainingSample(
        val regime: ScoringRegime,
        val positiveFeatures: FloatArray,
        val negativeFeatures: FloatArray,
        val targetMargin: Float = 45.0f
    )

    private val SEARCH_FILTER_TAB_WORDS = setOf(
        "全部", "圖片", "新聞", "影片", "地圖", "購物", "書籍", "航班", "財經", "搜尋工具",
        "all", "images", "news", "videos", "maps", "shopping"
    )

    private val RESIDUAL_STRIP_PREFIXES = listOf(
        "幫我", "請幫我", "請", "去", "到", "打開", "開啟", "用", "使用",
        "查詢", "搜尋", "搜索", "查找", "查一下", "搜一下", "找一下", "查", "搜", "找",
        "並", "并", "並且", "并且", "然後", "然后", "接著", "接着", "順便", "再幫我", "再"
    )

    @Volatile
    private var loadedProfiles: MutableMap<ScoringRegime, StageWeightProfile> = createDefaultProfiles()

    @Volatile
    private var loadedGateProfiles: MutableMap<ScoringRegime, SoftGateProfile> = createDefaultGateProfiles()

    @Volatile
    private var isAssetLoaded = false

    private fun createDefaultGateProfiles(): MutableMap<ScoringRegime, SoftGateProfile> {
        val map = LinkedHashMap<ScoringRegime, SoftGateProfile>()
        // s0: has_query, s1: is_submitted_mem, s2: has_inp, s3: inp_has_query, s4: vp_result_density, s5: is_results_posture, s6: top_inp_focused, s7: direct_nav
        map[ScoringRegime.STAGE_A_EXPAND_SEARCH] = SoftGateProfile(
            regime = ScoringRegime.STAGE_A_EXPAND_SEARCH,
            bias = -1.2f,
            weights = floatArrayOf(3.8f, -4.5f, -5.0f, -3.5f, -2.5f, -2.0f, -3.0f, -4.0f)
        )
        map[ScoringRegime.STAGE_B_INPUT_TYPE] = SoftGateProfile(
            regime = ScoringRegime.STAGE_B_INPUT_TYPE,
            bias = -1.5f,
            weights = floatArrayOf(3.2f, -4.2f, 4.5f, -4.8f, -2.2f, -1.8f, 2.4f, -4.0f)
        )
        map[ScoringRegime.STAGE_B_SUBMIT_QUERY] = SoftGateProfile(
            regime = ScoringRegime.STAGE_B_SUBMIT_QUERY,
            bias = -2.0f,
            weights = floatArrayOf(3.0f, -3.8f, 3.6f, 5.2f, -1.5f, -1.2f, 1.8f, -4.0f)
        )
        map[ScoringRegime.STAGE_C_SELECT_RESULT] = SoftGateProfile(
            regime = ScoringRegime.STAGE_C_SELECT_RESULT,
            bias = -1.8f,
            weights = floatArrayOf(3.4f, 5.4f, -0.4f, 1.2f, 3.8f, 3.2f, -3.5f, -4.0f)
        )
        map[ScoringRegime.IN_APP_NAVIGATION] = SoftGateProfile(
            regime = ScoringRegime.IN_APP_NAVIGATION,
            bias = -0.5f,
            weights = floatArrayOf(-3.5f, -2.0f, -1.0f, -2.0f, 0.8f, 0.5f, -1.5f, 5.8f)
        )
        return map
    }

    private fun createDefaultProfiles(): MutableMap<ScoringRegime, StageWeightProfile> {
        val map = LinkedHashMap<ScoringRegime, StageWeightProfile>()

        map[ScoringRegime.STAGE_A_EXPAND_SEARCH] = StageWeightProfile(
            regime = ScoringRegime.STAGE_A_EXPAND_SEARCH,
            bias = 5.5f,
            weights = floatArrayOf(
                8.5f,   // f0: exact_or_norm_query_match
                4.5f,   // f1: subword_bigram_semantic_sim
                0.0f,   // f2: residual_intent_and_website_affinity
                96.0f,  // f3: is_search_entry_role
                46.0f,  // f4: is_editable_input_box
                10.0f,  // f5: is_search_submit_or_suggestion
                -14.0f, // f6: is_content_card_in_viewport
                -38.0f, // f7: is_top_search_echo_or_filter_tab
                34.0f,  // f8: phase2_goal_role_alignment
                22.0f,  // f9: phase3_posture_transition_prior
                30.0f,  // f10: nanodet_visual_intent_align
                36.0f,  // f11: episodic_self_healing_memory
                -68.0f, // f12: clicked_loop_penalty
                -250.0f,// f13: forbidden_safety_mask
                30.0f,  // f14: spatial_top_bar_prior
                8.5f    // f15: pole_visibility_margin
            ),
            bilinearTerms = listOf(
                BilinearTerm(3, 14, 28.0f),
                BilinearTerm(3, 11, 24.0f)
            )
        )

        map[ScoringRegime.STAGE_B_INPUT_TYPE] = StageWeightProfile(
            regime = ScoringRegime.STAGE_B_INPUT_TYPE,
            bias = 5.5f,
            weights = floatArrayOf(
                6.0f,   // f0
                4.0f,   // f1
                0.0f,   // f2
                12.0f,  // f3
                118.0f, // f4: is_editable_input_box
                -16.0f, // f5
                -22.0f, // f6
                -42.0f, // f7
                28.0f,  // f8
                18.0f,  // f9
                24.0f,  // f10
                26.0f,  // f11
                -48.0f, // f12
                -250.0f,// f13
                25.0f,  // f14
                10.0f   // f15
            ),
            bilinearTerms = listOf(
                BilinearTerm(4, 14, 26.0f)
            )
        )

        map[ScoringRegime.STAGE_B_SUBMIT_QUERY] = StageWeightProfile(
            regime = ScoringRegime.STAGE_B_SUBMIT_QUERY,
            bias = 5.5f,
            weights = floatArrayOf(
                40.0f,  // f0
                25.0f,  // f1
                12.0f,  // f2
                -26.0f, // f3
                -78.0f, // f4: penalize re-tapping input box when query already typed
                98.0f,  // f5: is_search_submit_or_suggestion
                26.0f,  // f6
                -62.0f, // f7
                28.0f,  // f8
                18.0f,  // f9
                22.0f,  // f10
                28.0f,  // f11
                -68.0f, // f12
                -250.0f,// f13
                10.0f,  // f14
                8.5f    // f15
            ),
            bilinearTerms = listOf(
                BilinearTerm(5, 0, 34.0f)
            )
        )

        map[ScoringRegime.STAGE_C_SELECT_RESULT] = StageWeightProfile(
            regime = ScoringRegime.STAGE_C_SELECT_RESULT,
            bias = 10.5f,
            weights = floatArrayOf(
                72.0f,  // f0: exact_or_norm_query_match
                48.0f,  // f1: subword_bigram_semantic_sim
                44.0f,  // f2: residual_intent_and_website_affinity
                -98.0f, // f3: suppress search entry icons in Stage C
                -138.0f,// f4: suppress editable input box in Stage C
                -42.0f, // f5
                56.0f,  // f6: boost main viewport content cards
                -135.0f,// f7: suppress top search echo & filter tabs (全部/圖片/新聞)
                30.0f,  // f8
                24.0f,  // f9
                25.0f,  // f10
                35.0f,  // f11
                -78.0f, // f12
                -250.0f,// f13
                -38.0f, // f14: penalize top bar area during result selection
                12.5f   // f15
            ),
            bilinearTerms = listOf(
                BilinearTerm(6, 0, 36.0f),
                BilinearTerm(6, 1, 30.0f),
                BilinearTerm(6, 2, 34.0f),
                BilinearTerm(7, 14, -58.0f)
            )
        )

        map[ScoringRegime.IN_APP_NAVIGATION] = StageWeightProfile(
            regime = ScoringRegime.IN_APP_NAVIGATION,
            bias = 8.5f,
            weights = floatArrayOf(
                64.0f,  // f0
                40.0f,  // f1
                32.0f,  // f2
                14.0f,  // f3
                -32.0f, // f4
                12.0f,  // f5
                32.0f,  // f6
                -52.0f, // f7
                38.0f,  // f8
                30.0f,  // f9
                32.0f,  // f10
                38.0f,  // f11
                -68.0f, // f12
                -250.0f,// f13
                0.0f,   // f14
                10.5f   // f15
            ),
            bilinearTerms = listOf(
                BilinearTerm(0, 6, 30.0f),
                BilinearTerm(8, 9, 22.0f)
            )
        )

        return map
    }

    /**
     * 從 `assets/ui_stage_scoring_weights.json` 載入固化之最優權重矩陣。
     */
    fun ensureWeightsLoaded(context: Context?) {
        if (isAssetLoaded || context == null) return
        synchronized(this) {
            if (isAssetLoaded) return
            try {
                val overrideFile = File(context.filesDir, OVERRIDE_WEIGHTS_FILE)
                val jsonStr = if (overrideFile.exists() && overrideFile.length() > 32) {
                    overrideFile.readText(Charsets.UTF_8)
                } else {
                    context.assets.open(WEIGHTS_ASSET_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                parseAndApplyJsonWeights(jsonStr)
                isAssetLoaded = true
            } catch (_: Throwable) {
                isAssetLoaded = true
            }
        }
    }

    fun parseAndApplyJsonWeights(jsonStr: String) {
        val root = JSONObject(jsonStr)
        val stageWeightsObj = root.optJSONObject("stageWeights")
        if (stageWeightsObj != null) {
            val updated = createDefaultProfiles()
            for (regime in ScoringRegime.entries) {
                val regObj = stageWeightsObj.optJSONObject(regime.name) ?: continue
                val bias = regObj.optDouble("bias", 5.5).toFloat()
                val wArr = regObj.optJSONArray("weights")
                val weights = FloatArray(FEATURE_DIM)
                val fallbackW = updated[regime]?.weights ?: FloatArray(FEATURE_DIM)
                for (k in 0 until FEATURE_DIM) {
                    weights[k] = if (wArr != null && k < wArr.length()) {
                        wArr.optDouble(k, fallbackW[k].toDouble()).toFloat()
                    } else {
                        fallbackW[k]
                    }
                }
                val biArr = regObj.optJSONArray("bilinearInteractions")
                val biList = ArrayList<BilinearTerm>()
                if (biArr != null) {
                    for (m in 0 until biArr.length()) {
                        val item = biArr.optJSONObject(m) ?: continue
                        val i = item.optInt("i", -1)
                        val j = item.optInt("j", -1)
                        val w = item.optDouble("weight", 0.0).toFloat()
                        if (i in 0 until FEATURE_DIM && j in 0 until FEATURE_DIM && w != 0f) {
                            biList.add(BilinearTerm(i, j, w))
                        }
                    }
                }
                updated[regime] = StageWeightProfile(regime, bias, weights, biList)
            }
            loadedProfiles = updated
        }

        val gateObj = root.optJSONObject("softStageGateWeights")
        if (gateObj != null) {
            val updatedGates = createDefaultGateProfiles()
            for (regime in ScoringRegime.entries) {
                val gItem = gateObj.optJSONObject(regime.name) ?: continue
                val bias = gItem.optDouble("bias", -1.0).toFloat()
                val wArr = gItem.optJSONArray("weights")
                val weights = FloatArray(GATE_STATE_DIM)
                val fallbackW = updatedGates[regime]?.weights ?: FloatArray(GATE_STATE_DIM)
                for (k in 0 until GATE_STATE_DIM) {
                    weights[k] = if (wArr != null && k < wArr.length()) {
                        wArr.optDouble(k, fallbackW[k].toDouble()).toFloat()
                    } else {
                        fallbackW[k]
                    }
                }
                updatedGates[regime] = SoftGateProfile(regime, bias, weights)
            }
            loadedGateProfiles = updatedGates
        }
    }

    fun updateFeatureWeight(regime: ScoringRegime, featureIndex: Int, newWeight: Float) {
        if (featureIndex !in 0 until FEATURE_DIM) return
        val current = loadedProfiles[regime] ?: return
        val copyW = current.weights.copyOf()
        copyW[featureIndex] = newWeight
        loadedProfiles[regime] = current.copy(weights = copyW)
    }

    fun getStageWeightProfile(regime: ScoringRegime): StageWeightProfile {
        return loadedProfiles[regime] ?: createDefaultProfiles()[regime]!!
    }

    fun resetToDefaultWeights() {
        loadedProfiles = createDefaultProfiles()
        loadedGateProfiles = createDefaultGateProfiles()
        isAssetLoaded = false
    }

    // =========================================================================
    // 【方案 C：語義殘差向量消去投射 (Residual Intent Vector Projection)】
    // =========================================================================

    /**
     * 從整句使用者目標 `fullGoal` 中減去已執行或正在執行的搜尋查詢 `completedQuery`（與 App 名稱），
     * 計算 Gram-Schmidt 正交殘差語義向量 v_residual = Normalize(v_goal - proj_{v_done}(v_goal))，
     * 讓未透過標準連接詞連接的後半段意圖（如「順便幫我看官網」、「打開來聽」、「挑第一個點進去」）自然浮現。
     */
    fun computeResidualIntentProjection(
        fullGoal: String,
        completedQuery: String,
        explicitFollowUp: String = "",
        appHint: String = ""
    ): ResidualIntentProjection {
        val normGoal = fullGoal.trim().replace('臺', '台')
        val normQuery = completedQuery.trim().replace('臺', '台')
        val normFollow = explicitFollowUp.trim().replace('臺', '台')

        // 1. 剝離已完成之搜尋詞與口語前綴，提取殘差意圖片語
        var residualPhrase = if (normFollow.isNotEmpty()) {
            normFollow
        } else if (normQuery.isNotEmpty() && normGoal.contains(normQuery, ignoreCase = true)) {
            val afterQuery = normGoal.substringAfter(normQuery, "").trim()
            afterQuery
        } else {
            ""
        }

        if (appHint.isNotBlank()) {
            residualPhrase = residualPhrase.replace(appHint, "", ignoreCase = true).trim()
        }
        var stripped = true
        while (stripped && residualPhrase.isNotEmpty()) {
            stripped = false
            for (p in RESIDUAL_STRIP_PREFIXES) {
                if (residualPhrase.startsWith(p, ignoreCase = true)) {
                    residualPhrase = residualPhrase.substring(p.length).trim()
                    stripped = true
                    break
                }
            }
        }

        // 2. 計算整句目標向量 v_goal 與已完成搜尋動作向量 v_done 的正交投影殘差
        val vGoal = EpisodicAppMemory.buildSemanticFeatureVector(
            label = normGoal,
            intentKey = normGoal,
            shortTypeTag = "BTN",
            functionRoleCode = "CONTENT_CARD_ITEM",
            semanticTags = emptyList(),
            widthRatio = 0.5f,
            heightRatio = 0.1f,
            aspectRatio = 2.8f,
            nanoDetCategory = ""
        )
        val doneText = if (normQuery.isNotEmpty()) "搜尋 查找 輸入 $normQuery $appHint" else appHint
        val vDone = EpisodicAppMemory.buildSemanticFeatureVector(
            label = doneText,
            intentKey = normQuery,
            shortTypeTag = "INP",
            functionRoleCode = "SEARCH_INPUT_BOX",
            semanticTags = emptyList(),
            widthRatio = 0.6f,
            heightRatio = 0.08f,
            aspectRatio = 5.0f,
            nanoDetCategory = ""
        )

        var dot = 0f
        var normDoneSq = 0f
        for (i in vGoal.indices) {
            dot += vGoal[i] * vDone[i]
            normDoneSq += vDone[i] * vDone[i]
        }
        val projScale = if (normDoneSq > 1e-6f) (dot / normDoneSq).coerceIn(0f, 0.92f) else 0f

        val vRes = FloatArray(vGoal.size)
        var resNormSq = 0f
        for (i in vGoal.indices) {
            vRes[i] = vGoal[i] - projScale * vDone[i]
            resNormSq += vRes[i] * vRes[i]
        }
        val resMag = sqrt(resNormSq)
        if (resMag > 1e-6f) {
            for (i in vRes.indices) {
                vRes[i] /= resMag
            }
        }

        // 若殘差片語非空，將殘差片語本身的明確語義向量與正交殘差向量融合強化
        if (residualPhrase.isNotEmpty()) {
            val vExplicitRes = EpisodicAppMemory.buildSemanticFeatureVector(
                label = residualPhrase,
                intentKey = residualPhrase,
                shortTypeTag = "BTN",
                functionRoleCode = "CONTENT_CARD_ITEM",
                semanticTags = emptyList(),
                widthRatio = 0.5f,
                heightRatio = 0.1f,
                aspectRatio = 2.8f,
                nanoDetCategory = ""
            )
            var fusedSq = 0f
            for (i in vRes.indices) {
                vRes[i] = 0.45f * vRes[i] + 0.55f * vExplicitRes[i]
                fusedSq += vRes[i] * vRes[i]
            }
            val fusedNorm = sqrt(fusedSq)
            if (fusedNorm > 1e-6f) {
                for (i in vRes.indices) vRes[i] /= fusedNorm
            }
        }

        val combinedCheck = "$residualPhrase $normFollow".lowercase()
        val hasWebsiteIntent = combinedCheck.contains("網站") ||
            combinedCheck.contains("官網") ||
            combinedCheck.contains("網頁") ||
            combinedCheck.contains("點進") ||
            combinedCheck.contains("進入") ||
            combinedCheck.contains("官方")
        val hasPlayIntent = combinedCheck.contains("播放") ||
            combinedCheck.contains("聽") ||
            combinedCheck.contains("看") ||
            combinedCheck.contains("放來") ||
            combinedCheck.contains("打開來")
        val hasFirstOrOpenIntent = combinedCheck.contains("第一") ||
            combinedCheck.contains("首個") ||
            combinedCheck.contains("最上面") ||
            combinedCheck.contains("點開") ||
            combinedCheck.contains("點選") ||
            combinedCheck.contains("戳進") ||
            hasWebsiteIntent ||
            hasPlayIntent

        return ResidualIntentProjection(
            residualTextPhrase = residualPhrase,
            residualSemanticVector = vRes,
            hasWebsiteOrLinkIntent = hasWebsiteIntent,
            hasPlayOrWatchIntent = hasPlayIntent,
            hasFirstItemOrOpenIntent = hasFirstOrOpenIntent,
            residualMagnitude = resMag
        )
    }

    // =========================================================================
    // 【方案 B：軟性階段機率閘門 (Soft Stage Probability Gating)】
    // =========================================================================

    /**
     * 萃取 8 維全局畫面狀態特徵向量 s_screen in [0, 1]^8，並透過 W_gate 計算 5 大階段的 Softmax 機率分佈。
     */
    fun computeSoftStageProbabilities(
        report: VisionPipelineReport,
        query: String,
        isQuerySubmitted: Boolean,
        inputNodesCount: Int,
        inpAlreadyContainsQuery: Boolean,
        matchingViewportResultCount: Int
    ): Map<ScoringRegime, Float> {
        val s = FloatArray(GATE_STATE_DIM)
        val hasQuery = query.isNotBlank()
        s[0] = if (hasQuery) 1.0f else 0.0f
        s[1] = if (isQuerySubmitted) 1.0f else 0.0f
        s[2] = if (inputNodesCount > 0) 1.0f else 0.0f
        s[3] = if (inpAlreadyContainsQuery) 1.0f else 0.0f
        s[4] = (matchingViewportResultCount.toFloat() / 3.0f).coerceIn(0f, 1f)

        val isResultsPosture = report.screenPosture == "SEARCH_RESULTS_LIST" ||
            report.finalNodes.any { n -> n.label.trim() in UiKeywordLexicon.SEARCH_RESULTS_PAGE_MARKERS }
        s[5] = if (isResultsPosture) 1.0f else 0.0f

        val topInputFocused = report.finalNodes.any { n ->
            n.shortTypeTag == "INP" && n.gridTapPoint.y <= 240 && n.inlineStatePrefix.contains("焦點")
        }
        s[6] = if (topInputFocused) 1.0f else 0.0f
        s[7] = if (!hasQuery) 1.0f else 0.0f

        val logits = FloatArray(ScoringRegime.entries.size)
        var maxLogit = -999f
        for ((idx, regime) in ScoringRegime.entries.withIndex()) {
            val gateProf = loadedGateProfiles[regime] ?: createDefaultGateProfiles()[regime]!!
            var z = gateProf.bias
            for (k in 0 until min(GATE_STATE_DIM, gateProf.weights.size)) {
                z += gateProf.weights[k] * s[k]
            }
            logits[idx] = z
            if (z > maxLogit) maxLogit = z
        }

        val expVals = FloatArray(logits.size)
        var sumExp = 1e-6f
        for (i in logits.indices) {
            val e = exp(logits[i] - maxLogit)
            expVals[i] = e
            sumExp += e
        }

        val probMap = LinkedHashMap<ScoringRegime, Float>()
        for ((idx, regime) in ScoringRegime.entries.withIndex()) {
            probMap[regime] = (expVals[idx] / sumExp).coerceIn(0.001f, 0.999f)
        }
        return probMap
    }

    /**
     * 根據當前畫面狀態與搜尋進度判定主導之權重評分階段 (ScoringRegime)。
     */
    fun resolveScoringRegime(
        preliminaryStage: InAppOperationStage,
        alreadyTypedInInput: Boolean
    ): ScoringRegime {
        return when (preliminaryStage) {
            InAppOperationStage.STAGE_A_EXPAND_SEARCH -> ScoringRegime.STAGE_A_EXPAND_SEARCH
            InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT -> {
                if (alreadyTypedInInput) ScoringRegime.STAGE_B_SUBMIT_QUERY
                else ScoringRegime.STAGE_B_INPUT_TYPE
            }
            InAppOperationStage.STAGE_C_SELECT_RESULT -> ScoringRegime.STAGE_C_SELECT_RESULT
            else -> ScoringRegime.IN_APP_NAVIGATION
        }
    }

    /**
     * 萃取單一候選節點之 16 維正規化特徵向量 x_i in [-1, 1]^16
     */
    fun extractNodeFeatures16D(
        node: Stage2ConvergedNode,
        report: VisionPipelineReport,
        userGoal: String,
        currentSubGoal: String,
        query: String,
        followUp: String,
        regime: ScoringRegime,
        primaryInputNode: Stage2ConvergedNode?,
        viewportRankIndex: Int,
        episodicDelta: Float,
        screenHash: String,
        explicitSearchIntent: Boolean,
        residualProjection: ResidualIntentProjection? = null
    ): FloatArray {
        val x = FloatArray(FEATURE_DIM)
        val cleanLbl = node.label.trim()
        val labelLower = cleanLbl.lowercase()
        val labelNorm = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(labelLower.replace('臺', '台'))
        val fullTextLower = "${node.inlineStatePrefix}$cleanLbl".lowercase()

        val effectiveTarget = query.ifBlank { currentSubGoal.ifBlank { userGoal } }.trim()
        val targetNorm = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(effectiveTarget.lowercase().replace('臺', '台'))
        val followUpNorm = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(followUp.trim().lowercase().replace('臺', '台'))

        val resProj = residualProjection ?: computeResidualIntentProjection(
            fullGoal = userGoal,
            completedQuery = query,
            explicitFollowUp = followUp
        )

        // f0: exact_or_norm_query_match [0..1]（整合 OpenCC 繁簡兩岸用語對齊 + 哈工大同義詞詞林 Synsets）
        if (targetNorm.isNotEmpty() && labelNorm.isNotEmpty()) {
            x[0] = when {
                labelNorm == targetNorm -> 1.0f
                labelNorm.contains(targetNorm) -> {
                    val qScore = InAppOperationPlanner.scoreQueryMatchQuality(labelNorm, targetNorm)
                    (qScore / 100f).coerceIn(0.55f, 0.95f)
                }
                targetNorm.contains(labelNorm) && labelNorm.length >= 2 -> 0.52f
                else -> {
                    val cilinSyns = if (targetNorm.length in 2..8) {
                        OpenSourceAiDatabaseManager.expandSynonymsFromOpenSourceDatabases(targetNorm, maxSynonyms = 16)
                    } else emptySet()
                    if (cilinSyns.any { syn -> syn.length >= 2 && (labelNorm == syn || labelNorm.contains(syn)) }) {
                        0.78f
                    } else {
                        0.0f
                    }
                }
            }
        }

        // f1: subword_bigram_semantic_sim [0..1]
        if (targetNorm.length >= 2 && labelNorm.isNotEmpty()) {
            val bigramHits = if (targetNorm.length >= 4) {
                (0..targetNorm.length - 2).count { idx -> labelNorm.contains(targetNorm.substring(idx, idx + 2)) }
            } else if (labelNorm.contains(targetNorm)) 1 else 0
            val charOverlap = targetNorm.count { ch -> !ch.isWhitespace() && labelNorm.contains(ch) }.toFloat() /
                targetNorm.count { !it.isWhitespace() }.coerceAtLeast(1).toFloat()
            x[1] = (charOverlap * 0.55f + (bigramHits * 0.25f).coerceAtMost(0.60f)).coerceIn(0f, 1f)
        }

        // f2: residual_intent_and_website_affinity [0..1] (方案 C 語義殘差向量注入)
        val isWebsiteFollowUp = resProj.hasWebsiteOrLinkIntent ||
            followUpNorm.contains("網站") ||
            followUpNorm.contains("官網") ||
            followUpNorm.contains("網頁") ||
            followUpNorm.contains("點進") ||
            followUpNorm.contains("進入")
        val wantsFirstItem = resProj.hasFirstItemOrOpenIntent ||
            followUpNorm.contains("第一") ||
            followUpNorm.contains("首個") ||
            followUpNorm.contains("最上面") ||
            followUpNorm.contains("播放") ||
            isWebsiteFollowUp ||
            (regime == ScoringRegime.STAGE_C_SELECT_RESULT && followUpNorm.isEmpty())

        var affinity = 0f
        if (isWebsiteFollowUp && (labelNorm.contains("http") || labelNorm.contains(".tw") || labelNorm.contains(".com") || labelNorm.contains("官網") || labelNorm.contains("官方") || cleanLbl.length >= 6)) {
            affinity += 0.65f
        } else if (resProj.residualTextPhrase.length >= 2 && !isWebsiteFollowUp && labelNorm.contains(resProj.residualTextPhrase.lowercase())) {
            affinity += 0.85f
        }
        // 計算節點語義向量與方案 C 殘差意圖向量 v_residual 的餘弦相似度（僅於存在字元交集或處於結果選擇階段時啟用）
        val resPhraseNorm = resProj.residualTextPhrase.lowercase().replace('臺', '台')
        val hasResCharOverlap = resPhraseNorm.isNotEmpty() && resPhraseNorm.any { ch -> !ch.isWhitespace() && labelNorm.contains(ch) }
        if (cleanLbl.length >= 2 && resProj.residualMagnitude > 0.1f && (hasResCharOverlap || regime == ScoringRegime.STAGE_C_SELECT_RESULT)) {
            val vNode = EpisodicAppMemory.buildSemanticFeatureVector(
                label = labelNorm,
                intentKey = labelNorm,
                shortTypeTag = node.shortTypeTag,
                functionRoleCode = node.predictedFunctionRole,
                semanticTags = emptyList(),
                widthRatio = 0.4f,
                heightRatio = 0.08f,
                aspectRatio = 2.8f,
                nanoDetCategory = node.nanoDetCategory
            )
            val resCos = EpisodicAppMemory.cosineSimilarity(vNode, resProj.residualSemanticVector).coerceIn(0f, 1f)
            affinity += resCos * 0.25f
        }
        if (wantsFirstItem && viewportRankIndex == 0 && cleanLbl.length >= 2) {
            affinity += 0.55f
        } else if ((wantsFirstItem || regime == ScoringRegime.STAGE_C_SELECT_RESULT) && viewportRankIndex in 1..2 && cleanLbl.length >= 4) {
            affinity += (0.32f - viewportRankIndex * 0.10f)
        }
        x[2] = affinity.coerceIn(0f, 1f)

        // f3: is_search_entry_role [0..1]
        val isExactSearchWord = UiKeywordLexicon.isExactSearchWord(cleanLbl)
        val isSearchRole = node.predictedFunctionRole == "SEARCH_ENTRY" || node.predictedFunctionRole == "SEARCH_INPUT_BOX"
        val hasSearchKw = InAppOperationPlanner.SEARCH_ENTRY_KEYWORDS.any { kw -> fullTextLower.contains(kw) }
        x[3] = when {
            node.predictedFunctionRole == "FILTER_SORT_CHIP" || node.shortTypeTag == "CHK" -> 0f
            isExactSearchWord -> 1.0f
            isSearchRole -> (0.65f + 0.35f * node.functionRoleConfidence).coerceIn(0f, 1f)
            hasSearchKw -> 0.78f
            else -> 0f
        }

        // f4: is_editable_input_box [0..1]
        x[4] = if (node.shortTypeTag == "INP") {
            val inpQuality = BackwardChainingPlanner.scoreInputBoxCandidate(node)
            (inpQuality / 120f).coerceIn(0.65f, 1.0f)
        } else 0f

        // f5: is_search_submit_or_suggestion [0..1]
        if (primaryInputNode != null && node.nodeId != primaryInputNode.nodeId) {
            val isSubmitBtn = InAppOperationPlanner.isTrueSearchSubmitButton(node, primaryInputNode)
            val isSuggestionRow = node.shortTypeTag != "INP" &&
                node.gridTapPoint.x < 820 &&
                node.gridTapPoint.y in (primaryInputNode.gridTapPoint.y + 35)..560 &&
                (labelNorm.contains(targetNorm) || cleanLbl.length >= 2)
            x[5] = when {
                isSubmitBtn && isExactSearchWord -> 1.0f
                isSubmitBtn -> 0.88f
                isSuggestionRow -> (0.55f + 0.35f * x[0]).coerceIn(0.55f, 0.90f)
                else -> 0f
            }
        }

        // f6: is_content_card_in_viewport [0..1]
        val isInMainViewport = node.shortTypeTag != "INP" &&
            node.shortTypeTag != "SCR" &&
            node.gridTapPoint.y in 180..860 &&
            cleanLbl.isNotEmpty()
        val isFilterTab = labelLower in SEARCH_FILTER_TAB_WORDS && node.gridTapPoint.y <= 320
        if (isInMainViewport && !isFilterTab) {
            val rankFactor = if (viewportRankIndex >= 0) {
                (1.0f - (viewportRankIndex * 0.08f)).coerceIn(0.35f, 1.0f)
            } else 0.65f
            x[6] = rankFactor
        }

        // f7: is_top_search_echo_or_filter_tab [0..1]
        val isTopWebSearchEcho = targetNorm.isNotEmpty() && node.gridTapPoint.y <= 265 && labelNorm == targetNorm
        val isGoogleSearchBarPrompt = fullTextLower.contains("輕觸兩下即可在 google 搜尋") ||
            fullTextLower.contains("google.com/search?q=")
        val isTopSearchIconInStageC = regime == ScoringRegime.STAGE_C_SELECT_RESULT &&
            node.gridTapPoint.y < 260 && hasSearchKw
        x[7] = when {
            isTopWebSearchEcho || isGoogleSearchBarPrompt || isTopSearchIconInStageC -> 1.0f
            isFilterTab -> 0.85f
            !explicitSearchIntent && node.gridTapPoint.y <= 240 && (node.shortTypeTag == "INP" || hasSearchKw) -> 0.80f
            else -> 0f
        }

        // f8: phase2_goal_role_alignment [-1..1]
        val phase2Boost = Phase2ButtonFunctionalityClassifier.computeGoalRoleMatchBoost(
            userGoal = userGoal,
            currentSubGoal = currentSubGoal,
            nodeRoleCode = node.predictedFunctionRole,
            roleConfidence = node.functionRoleConfidence
        )
        x[8] = (phase2Boost / 40f).coerceIn(-1f, 1f)

        // f9: phase3_posture_transition_prior [-1..1]
        val (phase3Bonus, _) = Phase3ScreenPostureClassifier.computePostureTacticalBonus(
            screenPostureCode = report.screenPosture,
            candidateNode = node,
            hasExplicitSearchIntent = explicitSearchIntent,
            hasExplicitCloseIntent = effectiveTarget.contains("關閉") || effectiveTarget.contains("略過") || effectiveTarget.contains("跳過"),
            hasExplicitBuyIntent = effectiveTarget.contains("購買") || effectiveTarget.contains("購物車") || effectiveTarget.contains("結帳") || effectiveTarget.contains("買"),
            hasExplicitPlayIntent = effectiveTarget.contains("播放") || effectiveTarget.contains("聽") || effectiveTarget.contains("看")
        )
        x[9] = (phase3Bonus / 35f).coerceIn(-1f, 1f)

        // f10: nanodet_visual_intent_align [0..1]
        if (node.sourceType == NodeSourceType.NANODET_UI_DETECT && effectiveTarget.isNotEmpty()) {
            val t = effectiveTarget.lowercase()
            val catMatch = when (node.nanoDetCategory) {
                "UI_CLOSE_X" -> t.contains("關閉") || t.contains("略過") || t.contains("跳過") || t.contains("叉")
                "UI_SEARCH_BAR" -> explicitSearchIntent || t.contains("搜尋") || t.contains("搜索") || t.contains("查找") || t.contains("輸入")
                "UI_BUTTON_CTA" -> t.contains("確認") || t.contains("確定") || t.contains("送出") || t.contains("同意") || t.contains("下一步")
                "UI_CHECK_SWITCH" -> t.contains("開關") || t.contains("勾選") || t.contains("開啟") || t.contains("啟用")
                "UI_FAB_ADD" -> t.contains("新增") || t.contains("建立") || t.contains("加入")
                "UI_MEDIA_PLAY" -> t.contains("播放") || t.contains("暫停")
                "UI_ICON_BACK" -> t.contains("返回") || t.contains("上一頁")
                "UI_ICON_MENU_MORE" -> t.contains("選單") || t.contains("更多") || t.contains("設定")
                else -> false
            }
            if (catMatch) {
                x[10] = node.visualConfidence.coerceIn(0.35f, 1.0f)
            }
        }

        // f11: episodic_self_healing_memory [-1..1]
        x[11] = (episodicDelta / 30f).coerceIn(-1f, 1f)

        // f12: clicked_loop_penalty [0..1]
        x[12] = if (InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)) 1.0f else 0.0f

        // f13: forbidden_safety_mask [0..1]
        x[13] = if (InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node) ||
            InAppOperationPlanner.isForbiddenBrowserToolbarNode(node, userGoal)
        ) 1.0f else 0.0f

        // f14: spatial_top_bar_prior [0..1]
        x[14] = when {
            node.gridTapPoint.y <= 180 -> 1.0f
            node.gridTapPoint.y <= 260 -> 0.65f
            node.gridTapPoint.y <= 340 -> 0.25f
            else -> 0.0f
        }

        // f15: pole_visibility_margin [0..1]
        val bw = (node.convergedBounds.right - node.convergedBounds.left).coerceAtLeast(1).toFloat()
        val bh = (node.convergedBounds.bottom - node.convergedBounds.top).coerceAtLeast(1).toFloat()
        x[15] = ((min(bw, bh) / 120f).coerceIn(0.15f, 1.0f) * node.visibleRatio.coerceIn(0.1f, 1.0f)).coerceIn(0f, 1f)

        return x
    }

    private fun computeSingleRegimeLogit(
        features: FloatArray,
        regime: ScoringRegime,
        basePriorScore: Float
    ): Pair<Float, Float> {
        val profile = getStageWeightProfile(regime)
        var linear = profile.bias + (basePriorScore * 2.0f)
        val w = profile.weights
        val len = min(features.size, w.size)
        for (k in 0 until len) {
            linear += w[k] * features[k]
        }
        var bilinear = 0f
        for (term in profile.bilinearTerms) {
            if (term.i in 0 until len && term.j in 0 until len) {
                bilinear += term.weight * features[term.i] * features[term.j]
            }
        }
        return linear to bilinear
    }

    /**
     * 以當前階段之權重矩陣 W_stage、雙線性交互項 M_stage 以及軟性階段機率閘門 p_stage 計算單一節點之統一評分。
     */
    fun scoreCandidateWithWeights(
        nodeId: String,
        features: FloatArray,
        regime: ScoringRegime,
        basePriorScore: Float = 0.2f,
        stageProbabilities: Map<ScoringRegime, Float>? = null
    ): NodeScoringBreakdown {
        val (linear, bilinear) = computeSingleRegimeLogit(features, regime, basePriorScore)
        val hardTotalLogit = linear + bilinear

        val probs = stageProbabilities ?: mapOf(regime to 1.0f)
        var softGatedLogit = 0f
        if (stageProbabilities != null && stageProbabilities.size > 1) {
            for ((r, p) in stageProbabilities) {
                val (linR, biR) = computeSingleRegimeLogit(features, r, basePriorScore)
                softGatedLogit += p * (linR + biR)
            }
        } else {
            softGatedLogit = hardTotalLogit
        }

        // 融合主導階段分數與軟性機率閘門期望值 (65% 主導階段 + 35% 軟性閘門期望值)
        val effectiveLogit = if (stageProbabilities != null) {
            0.65f * hardTotalLogit + 0.35f * softGatedLogit
        } else {
            hardTotalLogit
        }
        val prob = (1.0f / (1.0f + exp(-(effectiveLogit - 25.0f) / 18.0f))).coerceIn(0.01f, 0.99f)

        return NodeScoringBreakdown(
            nodeId = nodeId,
            regime = regime,
            features = features,
            linearScore = linear,
            bilinearScore = bilinear,
            totalLogitScore = effectiveLogit,
            softGatedLogitScore = softGatedLogit,
            stageProbabilities = probs,
            normalizedProbability = prob
        )
    }

    // =========================================================================
    // 【方案 E：離線黃金場景成對排名權重蒸餾 (Pairwise Margin Ranking Distillation)】
    // =========================================================================

    /**
     * 對給定的正負樣本對集合 {(x^+, x^-)} 執行確定性 Margin Ranking Loss 梯度最佳化：
     * L(w) = sum max(0, margin - (S(x^+) - S(x^-)))
     * 確保每一階段的正樣本得分均穩定超越負樣本至少 `targetMargin`。
     */
    fun distillOptimalWeightsPairwiseMargin(
        samples: List<PairwiseTrainingSample>,
        epochs: Int = 40,
        learningRate: Float = 2.5f
    ): Map<ScoringRegime, FloatArray> {
        for (epoch in 0 until epochs) {
            var violations = 0
            for (sample in samples) {
                val prof = getStageWeightProfile(sample.regime)
                val posScore = scoreCandidateWithWeights("pos", sample.positiveFeatures, sample.regime).totalLogitScore
                val negScore = scoreCandidateWithWeights("neg", sample.negativeFeatures, sample.regime).totalLogitScore
                val diff = posScore - negScore
                if (diff < sample.targetMargin) {
                    violations++
                    val newW = prof.weights.copyOf()
                    for (k in 0 until min(FEATURE_DIM, newW.size)) {
                        // f13 為安全硬禁令遮罩 (-250f)，保持鎖定不變
                        if (k == 13) continue
                        val grad = sample.positiveFeatures[k] - sample.negativeFeatures[k]
                        newW[k] = (newW[k] + learningRate * grad).coerceIn(-180f, 180f)
                    }
                    loadedProfiles[sample.regime] = prof.copy(weights = newW)
                }
            }
            if (violations == 0) break
        }
        return loadedProfiles.mapValues { it.value.weights.copyOf() }
    }
}
