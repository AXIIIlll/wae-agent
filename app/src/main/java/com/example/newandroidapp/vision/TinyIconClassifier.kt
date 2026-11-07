package com.example.newandroidapp.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 孤立無字純圖示之三層語義與 16×16 幾何拓樸分類器（`TinyIconClassifier`）
 *
 * 實作《圖標標籤與功用辨識計畫（含 7 項補強）》之 **P3（三層辨識與未知圖標處理）** 與 **P4（擴充幾何規則）**：
 * 1. **第一層（無障礙標籤與未混淆 Resource-ID）**：優先檢查 `contentDescription`、`text` 與 `viewIdResourceName` 詞根。
 * 2. **第二層（16×16 二值化幾何拓樸辨識，支援深色與淺色模式）**：
 *    - 既有 8 種：`[圖示:選單]` (`≡`)、`[圖示:關閉]` (`X`)、`[圖示:新增]` (`+`)、`[圖示:更多]` (`⋮`)、
 *      `[圖示:搜尋]`、`[圖示:儲存地點]`、`[圖示:規劃路線]`、`[圖示:開始導航]`
 *    - P4 新增 7 種：`[圖示:上滑展開抽屜]`（框頂扁平橢圓橫條，寬高比 ≥ 4:1，或雙橫線拖曳把手 `=`）、
 *      `[圖示:向上箭頭]` (`^`)、`[圖示:向下箭頭]` (`v`)、`[圖示:向左箭頭]` (`<`)、`[圖示:向右箭頭]` (`>`)、
 *      `[圖示:切換開關]`（左右圓頭膠囊開關）、`[圖示:已勾選方框]` / `[圖示:未勾選方框]`（核取勾選框）
 * 3. **第三層（比對 4,267 個 Google Material 圖標語義庫）**：查閱 `OpenSourceAiDatabaseManager.lookupMaterialIconByNameOrTag`。
 * 4. **未知自訂圖標安全保底（`UNKNOWN_ICON`）**：三層皆未命中時，不硬猜具體功能，標記為 `UNKNOWN_ICON` 並記錄於未知圖標登錄表（供後續半自動補標），同時提供空間位置給脈絡引擎消歧。
 */
object TinyIconClassifier {

    /**
     * P3：三層圖標辨識完整結構化結果
     */
    data class IconRecognitionResult(
        val label: String,
        val functionalRole: String,
        val tierMatched: Int, // 1=無障礙/Resource-ID, 2=16x16幾何拓樸, 3=Material圖標庫, 0=未知圖標(UNKNOWN_ICON)
        val confidence: Float
    )

    /**
     * P3：未知自訂圖標記錄項目（供累積後半自動補標）
     */
    data class UnknownIconSample(
        val packageName: String,
        val viewIdResourceName: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val screenshotHash: Int,
        val recordedAtMs: Long = System.currentTimeMillis()
    )

    private val unknownIconRegistry = ConcurrentHashMap<String, UnknownIconSample>()

    fun getRecordedUnknownIcons(): List<UnknownIconSample> = unknownIconRegistry.values.toList()

    fun clearRecordedUnknownIcons() {
        unknownIconRegistry.clear()
    }

    private fun recordUnknownIcon(
        packageName: String,
        viewIdResourceName: String,
        bounds: Rect,
        screenshotHash: Int
    ) {
        if (unknownIconRegistry.size > 512) {
            unknownIconRegistry.clear()
        }
        val bw = bounds.right - bounds.left
        val bh = bounds.bottom - bounds.top
        val key = "$packageName|${viewIdResourceName.substringAfterLast('/')}|${bw}x${bh}|$screenshotHash"
        unknownIconRegistry[key] = UnknownIconSample(
            packageName = packageName,
            viewIdResourceName = viewIdResourceName,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            screenshotHash = screenshotHash
        )
    }

    /**
     * P3：執行嚴格三層順序辨識（1. 無障礙標籤/ID -> 2. 16x16 幾何拓樸 -> 3. Material 圖標庫 -> UNKNOWN_ICON）
     */
    fun classifyIconThreeTier(
        raw: RawVisionNode,
        iconBounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
        screenBitmap: Bitmap?,
        packageName: String = ""
    ): IconRecognitionResult {
        val descText = "${raw.text} ${raw.contentDescription}".trim()
        val idLower = raw.viewIdResourceName.substringAfterLast('/').lowercase()
        val isUnobfuscatedId = idLower.isNotEmpty() &&
            !idLower.matches(Regex("^[a-z0-9]{1,3}$")) &&
            !idLower.startsWith("0x")

        // =====================================================================
        // 第一層 (Tier 1)：無障礙標籤 (contentDescription / text) 與未混淆 Resource-ID
        // =====================================================================
        if (descText.isNotEmpty()) {
            val roleFromDesc = com.example.newandroidapp.ai.OpenSourceAiDatabaseManager
                .resolveUiActionRoleByTermOrIcon(descText)
            if (roleFromDesc != null) {
                return IconRecognitionResult(
                    label = "圖示:$descText",
                    functionalRole = roleFromDesc,
                    tierMatched = 1,
                    confidence = 0.96f
                )
            }
        }

        if (isUnobfuscatedId) {
            val tier1LabelAndRole: Pair<String, String>? = when {
                idLower.contains("drag_handle") || idLower.contains("bottom_sheet") ||
                    idLower.contains("pill_handle") || idLower.contains("grabber") ||
                    idLower.contains("sheet_handle") -> "圖示:上滑展開抽屜" to "SWIPE_UP_EXPAND_SHEET"
                idLower.contains("expand_less") || idLower.contains("arrow_up") ||
                    idLower.contains("chevron_up") || idLower.contains("swipe_up") -> "圖示:向上箭頭" to "SWIPE_UP_EXPAND_SHEET"
                idLower.contains("expand_more") || idLower.contains("arrow_down") ||
                    idLower.contains("chevron_down") || idLower.contains("swipe_down") ||
                    idLower.contains("drop_down") || idLower.contains("dropdown") -> "圖示:向下箭頭" to "SWIPE_DOWN_COLLAPSE_SHEET"
                idLower.contains("unfold_more") || idLower.contains("expand_content") ||
                    idLower.contains("open_in_full") || idLower.contains("fullscreen") -> "圖示:展開全文" to "EXPAND_FULL_CONTENT"
                idLower.contains("chevron_left") || idLower.contains("arrow_left") ||
                    idLower.contains("swipe_left") -> "圖示:向左箭頭" to "SWIPE_HORIZONTAL_CAROUSEL"
                idLower.contains("chevron_right") || idLower.contains("arrow_right") ||
                    idLower.contains("swipe_right") || idLower.contains("forward_ios") -> "圖示:向右箭頭" to "SWIPE_HORIZONTAL_CAROUSEL"
                idLower.contains("toggle") || idLower.contains("switch") -> "圖示:切換開關" to "TOGGLE_SWITCH_CHECKBOX"
                idLower.contains("checkbox") || idLower.contains("check_box") -> "圖示:勾選框" to "TOGGLE_SWITCH_CHECKBOX"
                idLower.contains("direction") || idLower.contains("route") || idLower.contains("wayfind") -> "圖示:規劃路線" to "MAP_DIRECTIONS_ROUTE"
                idLower.contains("navigate") || idLower.contains("navigation") || idLower.contains("compass") -> "圖示:開始導航" to "MAP_START_NAVIGATION"
                idLower.contains("bookmark") || idLower.contains("save_place") || idLower.contains("saved") ||
                    idLower.contains("favorite") || idLower.contains("wishlist") -> "圖示:儲存地點" to "LIKE_FAVORITE_BOOKMARK"
                idLower.contains("call") || idLower.contains("phone") || idLower.contains("dial") -> "圖示:撥打電話" to "CALL_PHONE_CONTACT"
                idLower.contains("website") || idLower.contains("globe") || idLower.contains("browser") || idLower.contains("web") -> "圖示:官方網站" to "OPEN_EXTERNAL_WEBSITE"
                idLower.contains("back") || idLower.contains("up") || idLower.contains("return") -> "圖示:返回" to "BACK_UP_NAVIGATION"
                idLower.contains("close") || idLower.contains("dismiss") || idLower.contains("cancel") || idLower.contains("clear") -> "圖示:關閉" to "CLOSE_MODAL"
                idLower.contains("menu") || idLower.contains("drawer") -> "圖示:選單" to "MORE_OVERFLOW_MENU"
                idLower.contains("search") || idLower.contains("find") -> "圖示:搜尋" to "SEARCH_SUBMIT"
                idLower.contains("add") || idLower.contains("plus") || idLower.contains("create") || idLower.contains("fab") -> "圖示:新增" to "ADD_CREATE_NEW"
                idLower.contains("more") || idLower.contains("overflow") || idLower.contains("option") -> "圖示:更多" to "MORE_OVERFLOW_MENU"
                idLower.contains("setting") || idLower.contains("config") || idLower.contains("gear") -> "圖示:設定" to "SETTINGS_PREFERENCES"
                idLower.contains("share") || idLower.contains("forward") -> "圖示:分享" to "SHARE_FORWARD"
                idLower.contains("delete") || idLower.contains("trash") || idLower.contains("remove") -> "圖示:刪除" to "DELETE_REMOVE_CLEAR"
                idLower.contains("filter") || idLower.contains("tune") || idLower.contains("sort") -> "圖示:篩選" to "FILTER_SORT_CHIP"
                idLower.contains("cart") || idLower.contains("shopping") || idLower.contains("bag") -> "圖示:購物車" to "ADD_TO_CART"
                idLower.contains("edit") || idLower.contains("pencil") || idLower.contains("compose") -> "圖示:編輯" to "EDIT_COMPOSE_POST"
                idLower.contains("copy") || idLower.contains("paste") -> "圖示:複製" to "COPY_PASTE_SELECT"
                idLower.contains("download") -> "圖示:下載" to "DOWNLOAD_SAVE_OFFLINE"
                idLower.contains("upload") || idLower.contains("camera") -> "圖示:上傳" to "ATTACH_UPLOAD_CAMERA"
                idLower.contains("refresh") || idLower.contains("reload") || idLower.contains("sync") -> "圖示:重新整理" to "REFRESH_SYNC_RELOAD"
                else -> null
            }
            if (tier1LabelAndRole != null) {
                return IconRecognitionResult(
                    label = tier1LabelAndRole.first,
                    functionalRole = tier1LabelAndRole.second,
                    tierMatched = 1,
                    confidence = 0.94f
                )
            }
        }

        // =====================================================================
        // 第二層 (Tier 2)：16x16 像素幾何拓樸辨識（含深淺色模式與扁平橢圓把手）
        // =====================================================================
        val iconW = iconBounds.right - iconBounds.left
        val iconH = iconBounds.bottom - iconBounds.top
        val aspect = if (iconH > 0) iconW.toFloat() / iconH.toFloat() else 1f
        val canRunGeometric = screenBitmap != null && !screenBitmap.isRecycled &&
            iconW >= 16 && (iconH >= 16 || (iconW >= 24 && iconH >= 4 && aspect >= 3.8f))
        if (canRunGeometric && screenBitmap != null) {
            val glyphResult = classifyBy16x16GeometricTopology(
                bounds = iconBounds,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                bitmap = screenBitmap
            )
            if (glyphResult != null) {
                return IconRecognitionResult(
                    label = glyphResult,
                    functionalRole = mapGeometricLabelToRole(glyphResult),
                    tierMatched = 2,
                    confidence = 0.88f
                )
            }
        }

        // =====================================================================
        // 第三層 (Tier 3)：比對 4,267 個 Google Material 圖標語義庫
        // =====================================================================
        if (isUnobfuscatedId) {
            val matEntry = com.example.newandroidapp.ai.OpenSourceAiDatabaseManager
                .lookupMaterialIconByNameOrTag(idLower)
            if (matEntry != null && matEntry.zhTwTerms.isNotEmpty() && matEntry.buttonFunctionRole != "GENERAL_UI_ICON") {
                return IconRecognitionResult(
                    label = "圖示:${matEntry.zhTwTerms.first()}",
                    functionalRole = matEntry.buttonFunctionRole,
                    tierMatched = 3,
                    confidence = 0.85f
                )
            }
        }

        // =====================================================================
        // 三層皆未命中 -> 標為 UNKNOWN_ICON（不硬猜），並記錄未知圖標供後續半自動補標
        // =====================================================================
        val rectHash = ((iconBounds.left * 31 + iconBounds.top) * 31 + iconBounds.right) * 31 + iconBounds.bottom
        val bmpHash = if (screenBitmap != null && !screenBitmap.isRecycled) {
            (screenBitmap.width * 31 + screenBitmap.height) xor rectHash
        } else {
            rectHash
        }
        recordUnknownIcon(
            packageName = packageName,
            viewIdResourceName = raw.viewIdResourceName,
            bounds = iconBounds,
            screenshotHash = bmpHash
        )
        return IconRecognitionResult(
            label = "圖示:未知自訂圖標",
            functionalRole = "UNKNOWN_ICON",
            tierMatched = 0,
            confidence = 0.35f
        )
    }

    /**
     * 對孤立無字圖示進行語義推論（向下相容既有管線呼叫介面）
     */
    fun classifyIsolatedIcon(
        raw: RawVisionNode,
        iconBounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
        screenBitmap: Bitmap?
    ): String {
        val threeTier = classifyIconThreeTier(
            raw = raw,
            iconBounds = iconBounds,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            screenBitmap = screenBitmap
        )
        if (threeTier.tierMatched in 1..3) {
            return threeTier.label
        }

        // 當三層皆為 UNKNOWN_ICON 時，僅提供空間方位描述供脈絡引擎參考，不妄猜具體圖標名稱
        val cxRatio = ((iconBounds.left + iconBounds.right) * 0.5f) / screenWidth.coerceAtLeast(1)
        val cyRatio = ((iconBounds.top + iconBounds.bottom) * 0.5f) / screenHeight.coerceAtLeast(1)
        return when {
            cyRatio < 0.12f && cxRatio < 0.18f -> "圖示:左上導覽/返回"
            cyRatio < 0.12f && cxRatio > 0.82f -> "圖示:右上功能/關閉"
            cyRatio > 0.78f && cxRatio > 0.75f -> "圖示:右下懸浮鈕(FAB)"
            else -> "圖示:互動按鈕"
        }
    }

    private fun mapGeometricLabelToRole(label: String): String = when (label) {
        "圖示:上滑展開抽屜" -> "SWIPE_UP_EXPAND_SHEET"
        "圖示:向上箭頭" -> "SWIPE_UP_EXPAND_SHEET"
        "圖示:向下箭頭" -> "SWIPE_DOWN_COLLAPSE_SHEET"
        "圖示:向左箭頭", "圖示:向右箭頭" -> "SWIPE_HORIZONTAL_CAROUSEL"
        "圖示:切換開關", "圖示:已勾選方框", "圖示:未勾選方框" -> "TOGGLE_SWITCH_CHECKBOX"
        "圖示:選單", "圖示:更多" -> "MORE_OVERFLOW_MENU"
        "圖示:關閉" -> "CLOSE_MODAL"
        "圖示:新增" -> "ADD_CREATE_NEW"
        "圖示:搜尋" -> "SEARCH_SUBMIT"
        "圖示:儲存地點" -> "LIKE_FAVORITE_BOOKMARK"
        "圖示:規劃路線" -> "MAP_DIRECTIONS_ROUTE"
        "圖示:開始導航" -> "MAP_START_NAVIGATION"
        else -> "UNKNOWN_ICON"
    }

    /**
     * 擷取圖示內部 16x16 前景二值化矩陣，分析投影波峰、正反對角線、尖角方向、膠囊開關與中心十字結構。
     * 支援深色模式（深底亮字）與淺色模式（亮底深字），因採用 `abs(luma - bgLuma) > threshold` 對稱對比度。
     */
    fun classifyBy16x16GeometricTopology(
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
        bitmap: Bitmap
    ): String? {
        val scaleX = bitmap.width.toFloat() / screenWidth.toFloat().coerceAtLeast(1f)
        val scaleY = bitmap.height.toFloat() / screenHeight.toFloat().coerceAtLeast(1f)

        val bw = (bounds.right - bounds.left).coerceAtLeast(1).toFloat()
        val bh = (bounds.bottom - bounds.top).coerceAtLeast(1).toFloat()
        val aspect = bw / bh
        val normCx = ((bounds.left + bounds.right) * 0.5f) / screenWidth.coerceAtLeast(1).toFloat()
        val normCy = ((bounds.top + bounds.bottom) * 0.5f) / screenHeight.coerceAtLeast(1).toFloat()

        // 針對極扁平的抽屜頂端橢圓橫條 (aspect >= 3.8)，縮小垂直內縮比例以保留完整橫桿與上下背景
        val padRatioX = if (aspect >= 3.8f) 0.06f else 0.15f
        val padRatioY = if (aspect >= 3.8f) 0.05f else 0.15f
        val padX = bw * padRatioX
        val padY = bh * padRatioY
        val innerLeft = (bounds.left + padX) * scaleX
        val innerTop = (bounds.top + padY) * scaleY
        val innerW = (bw * (1f - 2f * padRatioX)) * scaleX
        val innerH = (bh * (1f - 2f * padRatioY)) * scaleY
        if (innerW < 8f || (innerH < 8f && aspect < 3.8f) || innerH < 3f) return null

        // 取內框四角與外圍邊緣像素作為按鈕底色基準（深色模式與淺色模式皆適用）
        val bgSamples = listOf(
            sampleLuma(bitmap, innerLeft.toInt(), innerTop.toInt()),
            sampleLuma(bitmap, (innerLeft + innerW - 1).toInt(), innerTop.toInt()),
            sampleLuma(bitmap, innerLeft.toInt(), (innerTop + innerH - 1).toInt()),
            sampleLuma(bitmap, (innerLeft + innerW - 1).toInt(), (innerTop + innerH - 1).toInt())
        )
        val bgLuma = bgSamples.sorted()[1]

        val fg = Array(16) { BooleanArray(16) }
        for (r in 0 until 16) {
            val py = (innerTop + ((r + 0.5f) / 16f) * innerH).toInt().coerceIn(0, bitmap.height - 1)
            for (c in 0 until 16) {
                val px = (innerLeft + ((c + 0.5f) / 16f) * innerW).toInt().coerceIn(0, bitmap.width - 1)
                val luma = sampleLuma(bitmap, px, py)
                if (abs(luma - bgLuma) > 36) {
                    fg[r][c] = true
                }
            }
        }

        return classify16x16MatrixDirectly(
            fg = fg,
            aspectRatio = aspect,
            normCx = normCx,
            normCy = normCy
        )
    }

    /**
     * P4：直接對 16x16 二值化矩陣與長寬比執行幾何拓樸分類（可獨立供單元測試與深淺色合成測試呼叫）
     */
    @Suppress("UNUSED_PARAMETER")
    fun classify16x16MatrixDirectly(
        fg: Array<BooleanArray>,
        aspectRatio: Float = 1.0f,
        normCx: Float = 0.5f,
        normCy: Float = 0.5f
    ): String? {
        if (fg.size < 16 || fg[0].size < 16) return null

        val rowCounts = IntArray(16)
        val colCounts = IntArray(16)
        var totalFg = 0
        for (r in 0 until 16) {
            for (c in 0 until 16) {
                if (fg[r][c]) {
                    rowCounts[r]++
                    colCounts[c]++
                    totalFg++
                }
            }
        }

        if (totalFg < 8 || totalFg > 224) return null

        // 統計水平投影獨立波峰數
        var horizontalBands = 0
        var inBand = false
        for (r in 0 until 16) {
            if (rowCounts[r] >= 6) {
                if (!inBand) {
                    horizontalBands++
                    inBand = true
                }
            } else if (rowCounts[r] <= 2) {
                inBand = false
            }
        }

        // =====================================================================
        // P4 特徵 A：扁平橢圓橫條 / 底部抽屜拖曳把手 (SWIPE_UP_PILL_HANDLE)
        // 條件：寬高比 >= 3.8:1（且位於中軸附近 cx in 0.22..0.78），中央水平帶飽滿、上下邊界空白，
        // 或雙橫條拖曳把手 (horizontalBands == 2 且長寬比 >= 1.8)
        // =====================================================================
        val topBottomQuiet = (rowCounts[0] + rowCounts[1] + rowCounts[14] + rowCounts[15]) <= 4
        val midRowsStrong = (4..11).count { rowCounts[it] >= 8 } >= 2
        if (aspectRatio >= 3.8f && normCx in 0.20f..0.80f && horizontalBands in 1..2 && midRowsStrong && topBottomQuiet) {
            return "圖示:上滑展開抽屜"
        }
        if (horizontalBands == 2 && aspectRatio >= 1.6f && midRowsStrong && topBottomQuiet) {
            return "圖示:上滑展開抽屜"
        }

        // 特徵 1：漢堡選單（3 條水平橫線，中間夾著空白列）
        if (horizontalBands >= 3 && aspectRatio < 3.2f) {
            return "圖示:選單"
        }

        // =====================================================================
        // P4 特徵 B：切換開關 (TOGGLE_SWITCH — 左右圓頭膠囊，左右半部密度明顯不對稱)
        // =====================================================================
        if (aspectRatio in 1.55f..3.4f && horizontalBands <= 2) {
            var leftKnobFg = 0
            var rightKnobFg = 0
            for (r in 4..11) {
                for (c in 2..6) if (fg[r][c]) leftKnobFg++
                for (c in 9..13) if (fg[r][c]) rightKnobFg++
            }
            val topBottomCapsule = (rowCounts[2] >= 6 || rowCounts[3] >= 6) && (rowCounts[12] >= 6 || rowCounts[13] >= 6)
            if (topBottomCapsule && abs(leftKnobFg - rightKnobFg) >= 12 && (leftKnobFg >= 16 || rightKnobFg >= 16)) {
                return "圖示:切換開關"
            }
        }

        // 特徵 2：正對角線 (r == c) 與反對角線 (r + c == 15) 密度
        var mainDiag = 0
        var antiDiag = 0
        for (i in 2..13) {
            if (fg[i][i] || (i + 1 < 16 && fg[i][i + 1]) || (i - 1 >= 0 && fg[i][i - 1])) mainDiag++
            val ac = 15 - i
            if (fg[i][ac] || (ac + 1 < 16 && fg[i][ac + 1]) || (ac - 1 >= 0 && fg[i][ac - 1])) antiDiag++
        }

        // 特徵 3：中心十字（row 7..8 與 col 7..8）vs 四個外角（偵測新增按鈕 `+`）
        val centerRowFill = (rowCounts[7] + rowCounts[8]) / 2f
        val centerColFill = (colCounts[7] + colCounts[8]) / 2f
        var cornerFg = 0
        for (r in listOf(2, 3, 12, 13)) {
            for (c in listOf(2, 3, 12, 13)) {
                if (fg[r][c]) cornerFg++
            }
        }

        if (centerRowFill >= 9f && centerColFill >= 9f && cornerFg <= 3) {
            return "圖示:新增"
        }

        val edgeMidFg = (if (fg[2][7] || fg[2][8]) 1 else 0) +
            (if (fg[13][7] || fg[13][8]) 1 else 0) +
            (if (fg[7][2] || fg[8][2]) 1 else 0) +
            (if (fg[7][13] || fg[8][13]) 1 else 0)

        if (mainDiag >= 9 && antiDiag >= 9 && cornerFg >= 4 && edgeMidFg <= 1) {
            return "圖示:關閉"
        }

        // 特徵 4：偵測更多選單 `⋮`（垂直中軸線上有 3 個短小的孤立點）
        var verticalIslands = 0
        var inVIsland = false
        for (r in 0 until 16) {
            val midActive = fg[r][7] || fg[r][8]
            if (midActive && rowCounts[r] <= 5) {
                if (!inVIsland) {
                    verticalIslands++
                    inVIsland = true
                }
            } else if (!midActive) {
                inVIsland = false
            }
        }
        if (verticalIslands >= 3) {
            return "圖示:更多"
        }

        // =====================================================================
        // P4 特徵 C：勾選框 (CHECKBOX — 空心與已勾選方框) vs 書籤儲存緞帶
        // =====================================================================
        val topBar = rowCounts[2] >= 7 || rowCounts[3] >= 7
        val bottomBar = rowCounts[12] >= 7 || rowCounts[13] >= 7
        val leftRightRibbons = (colCounts[2] >= 8 || colCounts[3] >= 8) && (colCounts[12] >= 8 || colCounts[13] >= 8)
        val bottomVNotch = !fg[13][7] && !fg[13][8] && (fg[13][3] || fg[13][4]) && (fg[13][11] || fg[13][12])

        // 書籤儲存緞帶：頂部平齊 + 左右垂直邊 + 底部中央 V 字缺口
        if (topBar && leftRightRibbons && bottomVNotch && !bottomBar) {
            return "圖示:儲存地點"
        }

        // 正方形封閉外框：頂、底、左、右四邊皆連續，且底部中央封閉（非書籤缺口）
        val bottomCenterClosed = fg[12][7] || fg[12][8] || fg[13][7] || fg[13][8]
        if (aspectRatio in 0.72f..1.38f && topBar && bottomBar && leftRightRibbons && bottomCenterClosed) {
            var innerCoreFg = 0
            for (r in 5..10) {
                for (c in 5..10) {
                    if (fg[r][c]) innerCoreFg++
                }
            }
            if (innerCoreFg <= 3) {
                return "圖示:未勾選方框"
            } else if (innerCoreFg in 5..26) {
                return "圖示:已勾選方框"
            }
        }

        // 特徵 5：偵測搜尋放大鏡（左上方空心圓環 + 右下角 r>=11, c>=11 握柄）
        val centerHollow = !fg[6][6] && !fg[5][6] && !fg[6][5]
        val bottomRightTail = (fg[12][12] || fg[13][13] || fg[14][14]) && !fg[2][13] && !fg[13][2]
        if (centerHollow && bottomRightTail && mainDiag >= 5) {
            return "圖示:搜尋"
        }

        // 特徵 7：偵測路線規劃菱形（四邊中點突出呈 45° 菱形對稱、四角空白，內部具右轉箭頭特徵）
        if (edgeMidFg >= 3 && cornerFg <= 1 && centerRowFill in 4f..11f && (fg[7][9] || fg[7][10] || fg[8][9])) {
            return "圖示:規劃路線"
        }

        // 特徵 8：偵測開始導航上指實心箭頭（頂部中央尖角、中下部漸寬且厚實、底部中央內凹）
        val topApex = (fg[2][7] || fg[2][8] || fg[3][7] || fg[3][8]) && rowCounts[2] <= 4
        val wideningBody = rowCounts[10] > rowCounts[5] && rowCounts[11] >= 6
        if (topApex && wideningBody && bottomVNotch && totalFg >= 36) {
            return "圖示:開始導航"
        }

        // =====================================================================
        // P4 特徵 D：四向折線箭頭 `^` (向上)、`v` (向下)、`<` (向左)、`>` (向右)
        // =====================================================================
        // 1. `^` 向上山字箭頭：頂部中央尖角 (r in 3..6, c in 7..8)，左翼往左下、右翼往右下對稱展開，下半部中央鏤空
        val chevronUpApex = (3..6).any { r -> (fg[r][7] || fg[r][8]) && rowCounts[r] <= 5 }
        val chevronUpLeftWing = (6..11).count { r -> (2..6).any { c -> fg[r][c] } } >= 3
        val chevronUpRightWing = (6..11).count { r -> (9..13).any { c -> fg[r][c] } } >= 3
        val chevronUpBottomHollow = !fg[11][7] && !fg[11][8] && !fg[12][7] && !fg[12][8]
        if (chevronUpApex && chevronUpLeftWing && chevronUpRightWing && chevronUpBottomHollow && totalFg in 10..64) {
            return "圖示:向上箭頭"
        }

        // 2. `v` 向下山字箭頭：底部中央尖角 (r in 9..13, c in 7..8)，左翼往左上、右翼往右上對稱展開，上半部中央鏤空
        val chevronDownApex = (9..13).any { r -> (fg[r][7] || fg[r][8]) && rowCounts[r] <= 5 }
        val chevronDownLeftWing = (4..9).count { r -> (2..6).any { c -> fg[r][c] } } >= 3
        val chevronDownRightWing = (4..9).count { r -> (9..13).any { c -> fg[r][c] } } >= 3
        val chevronDownTopHollow = !fg[3][7] && !fg[3][8] && !fg[4][7] && !fg[4][8]
        if (chevronDownApex && chevronDownLeftWing && chevronDownRightWing && chevronDownTopHollow && totalFg in 10..64) {
            return "圖示:向下箭頭"
        }

        // 3. `<` 向左折線箭頭：左側中央尖角 (c in 3..6, r in 7..8)，上下翼往右上與右下對稱展開，右側中央鏤空
        val chevronLeftApex = (3..6).any { c -> (fg[7][c] || fg[8][c]) && colCounts[c] <= 5 }
        val chevronLeftTopWing = (6..11).count { c -> (2..6).any { r -> fg[r][c] } } >= 3
        val chevronLeftBottomWing = (6..11).count { c -> (9..13).any { r -> fg[r][c] } } >= 3
        val chevronLeftRightHollow = !fg[7][11] && !fg[8][11] && !fg[7][12] && !fg[8][12]
        if (chevronLeftApex && chevronLeftTopWing && chevronLeftBottomWing && chevronLeftRightHollow && totalFg in 10..64) {
            return "圖示:向左箭頭"
        }

        // 4. `>` 向右折線箭頭：右側中央尖角 (c in 9..12, r in 7..8)，上下翼往左上與左下對稱展開，左側中央鏤空
        val chevronRightApex = (9..12).any { c -> (fg[7][c] || fg[8][c]) && colCounts[c] <= 5 }
        val chevronRightTopWing = (4..9).count { c -> (2..6).any { r -> fg[r][c] } } >= 3
        val chevronRightBottomWing = (4..9).count { c -> (9..13).any { r -> fg[r][c] } } >= 3
        val chevronRightLeftHollow = !fg[7][3] && !fg[8][3] && !fg[7][4] && !fg[8][4]
        if (chevronRightApex && chevronRightTopWing && chevronRightBottomWing && chevronRightLeftHollow && totalFg in 10..64) {
            return "圖示:向右箭頭"
        }

        return null
    }

    private fun sampleLuma(bmp: Bitmap, x: Int, y: Int): Int {
        val px = x.coerceIn(0, bmp.width - 1)
        val py = y.coerceIn(0, bmp.height - 1)
        val c = bmp.getPixel(px, py)
        return (Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) shr 8
    }
}
