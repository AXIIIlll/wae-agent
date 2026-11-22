package com.wae.agent.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * WAE V2.6 通用 UI 模式覆蓋與自動規則挖掘引擎
 * - 零內建預設模式（Zero Seeded Patterns）：patternStore 初始為空，所有模式必須由真實執行軌跡挖掘產生
 * - 角色識別完全查詢 `assets/open_source_ai_databases/` (google_material_icons_full_semantics.tsv & command_lexicon.tsv)
 *   與幾何結構特徵，不在代碼中寫死特定關鍵字
 */
object WaePatternMiningEngine {

    enum class PatternTier {
        PROVISIONAL,
        APP_LOCAL,
        GLOBAL_VERIFIED
    }

    data class MinedActionPattern(
        val patternKey: String,
        val posture: PagePosture,
        val canonicalIntentTag: String,
        val targetZone: String,
        val targetRoleTag: String,
        val minRequiredSim: Float = 0.45f,
        var successCount: Int = 0,
        var totalCount: Int = 0,
        val verifiedPackages: MutableSet<String> = mutableSetOf(),
        var tier: PatternTier = PatternTier.PROVISIONAL
    ) {
        val confidence: Float
            get() = if (totalCount <= 0) 0f else successCount.toFloat() / totalCount.toFloat()
    }

    private const val LOCAL_PATTERNS_FILE = "local_patterns.json"
    private val patternStore = ConcurrentHashMap<String, MinedActionPattern>()

    /**
     * 第一層 & 第二層：透過 C++ 端側模型 (posture_mobilenetv3_small_int8.bin modelId=701 & component_lib_cls_int8.bin modelId=702)
     * 推理識別頁面雙層姿態 (primaryPosture + overlayPosture) 與組件庫類型
     */
    fun classifyDualPostureAndComponentLib(
        screenW: Int,
        screenH: Int,
        appWindowCount: Int,
        topWindowBoundsAreaRatio: Float,
        candidates: List<CandidateOption>,
        classNames: List<String>,
        dynamicScore: Float,
        /** 用於載入可訓練姿態模型（可為 null，測試時不需要） */
        context: android.content.Context? = null
    ): DualPostureState {
        if (candidates.isEmpty()) {
            return DualPostureState(
                primaryPosture = PagePosture.EMPTY_OR_UNKNOWN,
                overlayPosture = PagePosture.EMPTY_OR_UNKNOWN,
                componentLib = ComponentLibraryType.ANDROID_NATIVE,
                dynamicScore = dynamicScore
            )
        }

        // 特徵抽取集中到 PostureFeatures（可單測、與訓練腳本共用同一順序）
        val featureVec = com.wae.agent.core.posture.PostureFeatures.from(
            com.wae.agent.core.posture.PostureFeatures.Raw(
                windowCount = appWindowCount,
                topWindowAreaRatio = topWindowBoundsAreaRatio,
                candidates = candidates,
                classNames = classNames
            )
        )

        // +1 模型：可訓練的線性 softmax（權重可由離線訓練覆蓋）
        context?.let { com.wae.agent.core.posture.PostureModel.ensureLoaded(it) }
        val prediction = com.wae.agent.core.posture.PostureModel.classify(featureVec)

        // A/B：舊啟發式（各維取最大）作為對照，便於評估模型是否真的更好
        val heuristicIdx = run {
            var best = 0
            var bestVal = -1e9f
            for (cls in 0 until 8) {
                if (featureVec[cls] > bestVal) {
                    bestVal = featureVec[cls]
                    best = cls
                }
            }
            best
        }
        val heuristicPosture = PagePosture.values().getOrElse(heuristicIdx) { PagePosture.LIST_FEED }
        if (heuristicPosture != prediction.primary || prediction.confidence < 0.5f) {
            runCatching { Log.i(
                "WAE_POSTURE",
                "heur=${heuristicPosture.name} model=${prediction.primary.name} " +
                    "conf=${"%.2f".format(prediction.confidence)} src=${com.wae.agent.core.posture.PostureModel.source()}"
                )
            }
        }
        val postureValues = PagePosture.values()
        val primary = prediction.primary
        val overlay = prediction.overlay
        val compLib = com.wae.agent.core.posture.PostureFeatures.componentLib(featureVec)

        return DualPostureState(
            primaryPosture = primary,
            overlayPosture = overlay,
            componentLib = compLib,
            dynamicScore = dynamicScore
        )
    }

    /**
     * 第二層：100% 查詢開源資料庫 (google_material_icons_full_semantics.tsv + command_lexicon.tsv) 解析角色，零硬編碼預設
     */
    fun inferComponentUniversalRole(
        screenW: Int,
        screenH: Int,
        bounds: IntArray,
        className: String,
        viewId: String,
        text: String,
        contentDesc: String
    ): String {
        return WaeOpenSourceDatabaseRegistry.resolveIconOrNodeRoleFromDatabase(
            viewId = viewId,
            text = text,
            contentDesc = contentDesc
        )
    }

    /**
     * 第三層：檢索運行期挖掘出之模式（強制執行 S_sim >= minRequiredSim 語意鎖）
     */
    fun matchVerifiedPatternCandidate(
        packageName: String,
        postureState: DualPostureState,
        goal: LocalGoal,
        options: List<CandidateOption>
    ): CandidateOption? {
        val posture = postureState.effectivePosture
        val key = "${posture.name}|${goal.canonicalIntentTag}"
        val pattern = patternStore[key] ?: return null

        val scopeAllowed = (pattern.tier == PatternTier.GLOBAL_VERIFIED) ||
            (pattern.tier == PatternTier.APP_LOCAL && pattern.verifiedPackages.contains(packageName))
        if (!scopeAllowed || pattern.confidence < 0.75f) return null

        return options.firstOrNull { cand ->
            val roleMatched = cand.roleTag == pattern.targetRoleTag ||
                cand.gridZone == pattern.targetZone
            val semanticGuardPassed = cand.hybridSimScore >= pattern.minRequiredSim
            roleMatched && semanticGuardPassed
        }
    }

    /**
     * 第三層：動作經 L5 驗證因果有效 (VALID_CAUSAL) 後，自動更新序列挖掘庫與升格狀態
     */
    fun recordTrajectoryOutcome(
        context: Context?,
        packageName: String,
        posture: PagePosture,
        goal: LocalGoal,
        selectedOption: CandidateOption,
        causalState: CausalAttributionState
    ) {
        if (causalState == CausalAttributionState.UNCERTAIN_CROSS_CONTAINER) {
            return
        }

        val key = "${posture.name}|${goal.canonicalIntentTag}"
        val existing = patternStore.getOrPut(key) {
            MinedActionPattern(
                patternKey = key,
                posture = posture,
                canonicalIntentTag = goal.canonicalIntentTag,
                targetZone = selectedOption.gridZone,
                targetRoleTag = selectedOption.roleTag
            )
        }

        existing.totalCount += 1
        if (causalState == CausalAttributionState.VALID_CAUSAL) {
            existing.successCount += 1
            if (packageName.isNotEmpty()) {
                existing.verifiedPackages.add(packageName)
            }
        }

        existing.tier = when {
            existing.verifiedPackages.size >= 3 && existing.confidence >= 0.85f ->
                PatternTier.GLOBAL_VERIFIED
            existing.successCount >= 2 && existing.confidence >= 0.75f ->
                PatternTier.APP_LOCAL
            else -> PatternTier.PROVISIONAL
        }

        if (context != null) {
            persistPatternsToDisk(context)
        }
    }

    fun getMinedPatternsCount(): Int = patternStore.size

    private fun persistPatternsToDisk(context: Context) {
        try {
            val arr = JSONArray()
            for (p in patternStore.values) {
                arr.put(
                    JSONObject().apply {
                        put("patternKey", p.patternKey)
                        put("posture", p.posture.name)
                        put("canonicalIntentTag", p.canonicalIntentTag)
                        put("targetZone", p.targetZone)
                        put("targetRoleTag", p.targetRoleTag)
                        put("minRequiredSim", p.minRequiredSim.toDouble())
                        put("successCount", p.successCount)
                        put("totalCount", p.totalCount)
                        put("verifiedPackages", JSONArray(p.verifiedPackages.toList()))
                        put("tier", p.tier.name)
                    }
                )
            }
            File(context.filesDir, LOCAL_PATTERNS_FILE).writeText(arr.toString(2), Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }
}
