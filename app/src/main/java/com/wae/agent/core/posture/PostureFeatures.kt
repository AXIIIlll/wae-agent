package com.wae.agent.core.posture

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.ComponentLibraryType
import com.wae.agent.core.PagePosture

/**
 * 姿態/組件庫模型的特徵抽取（+1 模型的第一步）。
 *
 * 12 維特徵，全部來自已在記憶體中的 a11y 候選與視窗資訊，
 * **不讀畫面像素、不含任何 App 資料**，因此可跨 App 通用、可在 JVM 測試。
 *
 * 特徵順序固定（訓練腳本依賴此順序）：
 *  0 LIST_FEED 密度      1 FORM_INPUT 可輸入比   2 MODAL_DIALOG 遮罩比
 *  3 BOTTOM_SHEET 佔比   4 DETAIL_PAGE 底欄密度 5 NAV_HOME 密度
 *  6 SEARCH_RESULTS 密度 7 WATERFALL_GRID 密度   8 Material 類名比
 *  9 Cupertino 類名比   10 Flutter 類名比        11 常數偏置
 */
object PostureFeatures {

    const val DIM = 12

    data class Raw(
        val windowCount: Int,
        val topWindowAreaRatio: Float,
        val candidates: List<CandidateOption>,
        val classNames: List<String>
    )

    fun from(raw: Raw): FloatArray {
        val total = raw.candidates.size.coerceAtLeast(1).toFloat()
        val editableRatio = raw.candidates.count { it.isEditable } / total
        val scrollableRatio = raw.candidates.count { it.isScrollable } / total
        val centerDensity = raw.candidates.count { it.gridZone == "CENTER_LIST" } / total
        val topDensity = raw.candidates.count { it.gridZone.startsWith("TOP") } / total
        val bottomDensity = raw.candidates.count { it.gridZone == "BOTTOM_BAR" } / total
        val hasOverlay = raw.windowCount >= 2

        val cn = raw.classNames.size.coerceAtLeast(1).toFloat()
        return floatArrayOf(
            centerDensity + scrollableRatio,                                   // 0
            editableRatio * 2.5f,                                              // 1
            if (hasOverlay) (1f - raw.topWindowAreaRatio) else 0f,              // 2
            if (hasOverlay) raw.topWindowAreaRatio else 0f,                     // 3
            bottomDensity * 1.5f,                                              // 4
            (bottomDensity + centerDensity) * 0.9f,                            // 5
            (topDensity + centerDensity + scrollableRatio) * 0.8f,             // 6
            centerDensity * 0.7f,                                              // 7
            raw.classNames.count { it.contains("material", true) || it.contains("androidx", true) } / cn, // 8
            raw.classNames.count { it.contains("cupertino", true) } / cn,      // 9
            raw.classNames.count { it.contains("flutter", true) } / cn,        // 10
            0.25f                                                              // 11
        )
    }

    /** 組件庫推斷（規則即可，屬於 UI 框架偵測，不是姿態） */
    fun componentLib(featureVec: FloatArray): ComponentLibraryType {
        if (featureVec.size < DIM) return ComponentLibraryType.ANDROID_NATIVE
        val flutter = featureVec[10]
        val cupertino = featureVec[9]
        val material = featureVec[8]
        return when {
            flutter >= 0.15f -> ComponentLibraryType.FLUTTER_OR_CUSTOM
            cupertino >= 0.15f -> ComponentLibraryType.CUPERTINO
            material >= 0.30f -> ComponentLibraryType.MATERIAL
            else -> ComponentLibraryType.ANDROID_NATIVE
        }
    }

    val POSTURE_CLASSES: List<PagePosture> = listOf(
        PagePosture.LIST_FEED,
        PagePosture.FORM_INPUT,
        PagePosture.MODAL_DIALOG,
        PagePosture.BOTTOM_SHEET,
        PagePosture.DETAIL_PAGE,
        PagePosture.NAV_HOME,
        PagePosture.SEARCH_RESULTS,
        PagePosture.WATERFALL_GRID,
        PagePosture.EMPTY_OR_UNKNOWN
    )
}
