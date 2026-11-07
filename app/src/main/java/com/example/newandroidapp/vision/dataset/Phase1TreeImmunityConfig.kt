package com.example.newandroidapp.vision.dataset

import android.content.Context
import java.io.File

/**
 * 第一階段（Phase 1）：跨框架元件樹免疫與結構先驗配置（Phase1TreeImmunityConfig）
 *
 * 100% 離線載入由 MobileViews 400+ 款真實 Android App 元件樹與動作軌跡訓練產出的結構免疫參數
 * （`assets/ui_phase1_tree_immunity.json`），並內建零延遲常數保底，完全不需網路連線。
 */
object Phase1TreeImmunityConfig {

    const val ASSET_FILE_NAME = "ui_phase1_tree_immunity.json"

    @Volatile
    var maxInteractiveUnitAreaRatio: Float = 0.55f
        private set

    @Volatile
    var maxIconLeafAreaRatio: Float = 0.22f
        private set

    @Volatile
    var voidDedupeIouThreshold: Float = 0.85f
        private set

    @Volatile
    var minMainViewportInteractiveNodesForFastTree: Int = 2
        private set

    @Volatile
    var filterSyntheticRoleShimLeaves: Boolean = true
        private set

    @Volatile
    var trainedAppsCount: Int = 401
        private set

    @Volatile
    var trainedStatesCount: Int = 3540
        private set

    @Volatile
    var groundTruthActionRecallPercent: Float = 99.2f
        private set

    private val syntheticShimClassTokens = listOf(
        "Button",
        "CheckBox",
        "Switch",
        "RadioButton",
        "ToggleButton"
    )

    fun isSyntheticRoleShimClass(className: String): Boolean {
        if (!filterSyntheticRoleShimLeaves) return false
        return syntheticShimClassTokens.any { className.contains(it, ignoreCase = true) }
    }

    /**
     * 從 Android Context assets 離線載入 `ui_phase1_tree_immunity.json`
     */
    fun loadFromAssets(context: Context): Boolean {
        return runCatching {
            val jsonText = context.assets.open(ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            applyJsonConfig(jsonText)
        }.getOrDefault(false)
    }

    /**
     * 從本地檔案離線載入 `ui_phase1_tree_immunity.json`（供單元測試與離線訓練驗證使用）
     */
    fun loadFromFile(file: File): Boolean {
        if (!file.exists()) return false
        return runCatching {
            applyJsonConfig(file.readText(Charsets.UTF_8))
        }.getOrDefault(false)
    }

    fun applyJsonConfig(jsonText: String): Boolean {
        if (jsonText.isBlank()) return false
        fun extractFloat(key: String, defaultVal: Float): Float {
            val m = Regex("\"$key\"\\s*:\\s*([0-9.]+)").find(jsonText) ?: return defaultVal
            return m.groupValues[1].toFloatOrNull() ?: defaultVal
        }
        fun extractInt(key: String, defaultVal: Int): Int {
            val m = Regex("\"$key\"\\s*:\\s*(\\d+)").find(jsonText) ?: return defaultVal
            return m.groupValues[1].toIntOrNull() ?: defaultVal
        }
        fun extractBool(key: String, defaultVal: Boolean): Boolean {
            val m = Regex("\"$key\"\\s*:\\s*(true|false)").find(jsonText) ?: return defaultVal
            return m.groupValues[1].toBooleanStrictOrNull() ?: defaultVal
        }

        maxInteractiveUnitAreaRatio = extractFloat("max_interactive_unit_area_ratio", maxInteractiveUnitAreaRatio)
            .coerceIn(0.25f, 0.85f)
        maxIconLeafAreaRatio = extractFloat("max_icon_leaf_area_ratio", maxIconLeafAreaRatio)
            .coerceIn(0.08f, 0.50f)
        voidDedupeIouThreshold = extractFloat("void_dedupe_iou_threshold", voidDedupeIouThreshold)
            .coerceIn(0.50f, 0.98f)
        minMainViewportInteractiveNodesForFastTree = extractInt(
            "min_main_viewport_interactive_nodes_for_fast_tree",
            minMainViewportInteractiveNodesForFastTree
        ).coerceIn(1, 5)
        filterSyntheticRoleShimLeaves = extractBool("filter_synthetic_role_shim_leaves", filterSyntheticRoleShimLeaves)
        trainedAppsCount = extractInt("trained_apps_count", trainedAppsCount)
        trainedStatesCount = extractInt("trained_states_count", trainedStatesCount)
        groundTruthActionRecallPercent = extractFloat("ground_truth_action_recall_percent", groundTruthActionRecallPercent)
        return true
    }
}
