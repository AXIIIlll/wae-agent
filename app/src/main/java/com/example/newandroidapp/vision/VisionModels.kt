package com.example.newandroidapp.vision

import android.graphics.Point
import android.graphics.Rect

enum class NodeSourceType(val tag: String) {
    UI_TREE("UI樹"),
    SPATIAL_MERGED_ICON("拓樸吸附圖示"),
    GEOMETRIC_ICON_CLASSIFIED("16x16幾何圖示分類"),
    ROI_OCR_TEXT("ROI視覺OCR"),
    ROI_EDGE_BUTTON("ROI邊緣按鈕"),
    NANODET_UI_DETECT("NanoDet視覺偵測")
}

/**
 * 原始節點資料結構（支援真實多視窗 AccessibilityWindowInfo/NodeInfo 與內建沙盒模擬）
 */
data class RawVisionNode(
    val id: String,
    val windowId: Int,
    val windowLayer: Int,
    val zOrderRank: Int, // 0 為最頂層（最先繪製於視覺最前方）
    val className: String,
    val packageName: String = "",
    val bounds: Rect,
    val viewportClipBounds: Rect = bounds,
    val text: String = "",
    val contentDescription: String = "",
    val hintText: String = "",
    val viewIdResourceName: String = "",
    val isClickable: Boolean = false,
    val isLongClickable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isSelected: Boolean = false,
    val isFocused: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val scrollDirections: String = "", // 例如 "V", "H", "VH"
    val isEnabled: Boolean = true,
    val isOpaqueOverlay: Boolean = false, // 是否為不透明遮擋物（例如懸浮面板、Dialog、BottomSheet、實心按鈕）
    val isModalScrim: Boolean = false,    // 是否為模態背景遮罩（阻斷下層視窗點擊）
    val isCanvasOrBlackBoxCandidate: Boolean = false, // 是否為 WebView / SurfaceView / Canvas / 巨型無子節點容器
    val hasRenderedContent: Boolean = false, // 是否有實際渲染文字、圖片或圖示
    val parentId: String? = null,
    val childIds: List<String> = emptyList(),
    val listStructuralSignature: String? = null,
    val isPassword: Boolean = false,
    val inputType: Int = 0
)

/**
 * 第一階段：Z-Order 逆向光柵化與 2D 佔用格網裁切後的結果
 */
data class Stage1OccludedNode(
    val raw: RawVisionNode,
    val visibleRatio: Float,        // 剩餘可見比例 (0.0 ~ 1.0)，低於 0.20 (即被遮擋 > 80%) 則剔除
    val isCulled: Boolean,          // 是否因遮擋 > 80% 或完全不可見而被剔除
    val wasPartiallyOccluded: Boolean, // 是否曾被上層部分遮擋並重新計算安全落點
    val safeTapPointPx: Point,      // 透過曼哈頓距離變換（Pole of Inaccessibility）算出的最安全露出點擊中心
    val visibleBoundingBox: Rect,   // 扣除上層遮擋後的實際可見外框
    val overlayAlphaDesc: String = "" // 第六階段：透明穿透層 vs 半透明黑底像素鑑別結果
)

/**
 * 第二 & 三階段：觸控熱區收斂、空間拓樸關聯、16x16 幾何圖示分類與局部 ROI 雙軌補盲後的精準節點
 */
data class Stage2ConvergedNode(
    val nodeId: String,
    val shortMarkId: Int,           // Set-of-Marks 極簡短編號 (#1, #2...)
    val className: String,
    val shortTypeTag: String,       // 極簡類型代號：BTN, TXT, INP, ICO, CHK, SCR, ROI
    val label: String,              // 合併後的語義標籤（含空間吸附文字或 16x16 幾何圖示分類）
    val inlineStatePrefix: String,  // 狀態內嵌符號，如 [勾選], [未選], [已選], [輸入:提示], [V]
    val rawOriginalBounds: Rect,    // 原始外框（供視覺對比偏移量）
    val convergedBounds: Rect,      // 最小視覺外框收斂後的緊湊熱區
    val finalTapPx: Point,          // 最終 100% 絕對精準點擊像素座標 (px, py)
    val gridTapPoint: Point,        // 0~999 網格正規化座標 (gx, gy)
    val visibleRatio: Float,        // 可見比例
    val wasHotspotConverged: Boolean, // 是否經過子元件 Union Bounding Box 收斂
    val absorbedTextIds: List<String>, // 吸附合併的鄰近獨立純文字節點 ID
    val sourceType: NodeSourceType,
    val listGroupSignature: String? = null,
    val visualConfidence: Float = 1.0f, // 第 8 號模型 NanoDet-Plus-m 視覺偵測與多模態焊接信心度 (0.0 ~ 1.0)
    val nanoDetCategory: String = "",   // NanoDet 12 類行動端 UI 元件類別代號 (如 UI_CLOSE_X, UI_SEARCH_BAR)
    val predictedFunctionRole: String = "", // Phase 2: 通用按鈕功用分類角色 (如 SEARCH_INPUT_BOX, NAV_HOME_TAB)
    val functionRoleConfidence: Float = 0f, // Phase 2: 按鈕功用分類信心分數 (0.0 ~ 1.0)
    val packageName: String = ""            // 確定性加固：來源 App 包名（用於隔離非目標 App 背景與懸浮窗雜訊）
)

/**
 * 第三階段：偵測到的結構黑盒空洞（面積 > 15% 螢幕且無子節點語義的區域）
 */
data class BlackBoxRoiVoid(
    val voidNodeId: String,
    val className: String,
    val roiRect: Rect,
    val screenAreaRatio: Float,     // 佔全螢幕面積比例 (例如 0.28 = 28%)
    val dHash64: Long,              // 8x8 感知梯度雜湊，用於毫秒級幀間快取比對
    val cacheHit: Boolean,          // 是否命中上一幀快取
    val recoveredNodes: List<Stage2ConvergedNode>
)

/**
 * 四階段＋第 6 點深度優化管線完整輸出報告
 */
data class VisionPipelineReport(
    val timestampMs: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val gridCols: Int,
    val gridRows: Int,
    val occupancyGridSnapshot: ByteArray, // 記錄每個格點是由哪個 Z-Layer 佔用 (0=空白, >0=被遮擋)
    val totalRawNodes: Int,
    val culledByOcclusionCount: Int,
    val partiallyOccludedShiftedCount: Int,
    val transparentOverlayBypassedCount: Int, // 第 6 點：成功豁免的全螢幕透明穿透層數量
    val hotspotConvergedCount: Int,
    val spatialBoundIconCount: Int,
    val geometricIconClassifiedCount: Int,    // 第 6 點：透過 16x16 像素幾何拓樸成功識別的孤立無字圖示數
    val roiRecoveredCount: Int,
    val nanoDetDetectedCount: Int = 0,        // 第 8 號模型：NanoDet-Plus-m 視覺偵測與多模型焊接元件數
    val foldedListGroupCount: Int,
    val temporalStabilizedCount: Int,         // 第 6 點：幀間動畫/跑馬燈去抖動鎖定數量
    val stage1AllNodes: List<Stage1OccludedNode>,
    val detectedRoiVoids: List<BlackBoxRoiVoid>,
    val finalNodes: List<Stage2ConvergedNode>,
    val markIdToPixelMap: Map<Int, Point>, // #ID -> 真實像素座標雙向映射表
    val compressedPromptText: String,      // 階段四：100% 極限壓縮之本地 AI 視覺輸入
    val deltaPromptText: String,           // 階段四優化：與上一幀的差分輸出 (Delta Frame)
    val rawCharCount: Int,
    val compressedCharCount: Int,
    val compressionRatioPercent: Int,      // 節省的字元/Token 百分比
    val pipelineLatencyMs: Long,
    val screenPosture: String = "GENERAL_APP_SCREEN", // Phase 3: 全域畫面姿勢代號 (如 HOME_FEED_HUB, SEARCH_ENTRY_PORTAL)
    val screenPostureZh: String = "一般應用操作畫面", // Phase 3: 全域畫面姿勢中文名稱
    val screenPostureConfidence: Float = 0f,          // Phase 3: 全域畫面姿勢信心度 (0.0 ~ 1.0)
    val postureRecommendedRoles: List<String> = emptyList() // Phase 3: 當前畫面姿勢推薦優先互動角色
)
