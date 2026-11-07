package com.example.newandroidapp.vision.dataset

import android.graphics.Rect
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.vision.AiVisionPipeline
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.VisionPipelineReport
import com.example.newandroidapp.vision.ZOrderTreeCollector
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 外部大型手機 GUI 數據庫雙向接入橋接器（ExternalGuiDatasetBridge）
 *
 * 完整接入兩大開源手機 UI 與操作軌跡數據庫：
 * 1. MobileViews (mllmTeam/MobileViews, 600K+ 現代 Android 螢幕截圖與 View Hierarchy 數據集)：
 *    - 支援解析 `state_*.json` (DroidBot / MobileViews 扁平索引式 View Hierarchy JSON)
 *    - 支援解析 `window_dump_*.xml` (UIAutomator 標準無障礙節點樹 XML)
 *    - 支援解析 `actions.csv` / `screenshot_state_mapping.csv` / `AppMetadata.csv` 狀態轉移軌跡與應用元資料
 *    - 直接轉換為 `ZOrderTreeCollector.NodeHierarchySource` 並送入 `AiVisionPipeline.execute(...)`
 *
 * 2. Android in the Wild / AitW (google-research/android_in_the_wild, 715K+ 真人操作軌跡數據集)：
 *    - 完整對應官方 `action_type.py` (`TYPE=3`, `DUAL_POINT=4`, `PRESS_BACK=5`, `PRESS_HOME=6`, `PRESS_ENTER=7`, `STATUS_TASK_COMPLETE=10`, `STATUS_TASK_IMPOSSIBLE=11`)
 *    - 完整實作官方 `action_matching.py` 評估演算法（14% 螢幕距離閾值、1.4 倍外框擴張命中判定、0.04 點擊/滑動分界與主軸方向比對）
 *    - 支援解析 `parsed_episodes.jsonl` 分片紀錄並將 `ui_annotations` 轉為 `RawVisionNode` 供決策層與行動層實測驗證
 */
object ExternalGuiDatasetBridge {

    private fun makeRect(l: Int, t: Int, r: Int, b: Int): Rect {
        return Rect(l, t, r, b).apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    // =========================================================================
    // Part 1: MobileViews 數據集接入 (JSON View Hierarchy / XML / UTG Actions)
    // =========================================================================

    data class MobileViewsStateMetadata(
        val stateStr: String,
        val structureStr: String,
        val foregroundActivity: String,
        val width: Int,
        val height: Int,
        val rootNode: ZOrderTreeCollector.NodeHierarchySource?,
        val totalViewCount: Int
    )

    data class MobileViewsTransitionRecord(
        val fromState: String,
        val toState: String,
        val rawAction: String,
        val actionVerb: String,
        val targetBounds: Rect?,
        val targetLabel: String
    )

    data class MobileViewsAppMetadata(
        val title: String,
        val appId: String,
        val installs: String,
        val genre: String,
        val genreId: String
    )

    private class DatasetTreeNode(
        override val identityCode: Int,
        override val className: CharSequence?,
        override val packageName: CharSequence?,
        override val text: CharSequence?,
        override val contentDescription: CharSequence?,
        override val hintText: CharSequence? = null,
        override val viewIdResourceName: String?,
        override val isVisibleToUser: Boolean,
        override val isClickable: Boolean,
        override val isLongClickable: Boolean,
        override val isCheckable: Boolean,
        override val isChecked: Boolean,
        override val isSelected: Boolean,
        override val isEditable: Boolean,
        override val isScrollable: Boolean,
        override val isEnabled: Boolean,
        override val isPassword: Boolean,
        override val drawingOrder: Int,
        private val nodeBounds: Rect,
        val childrenNodes: MutableList<DatasetTreeNode> = ArrayList()
    ) : ZOrderTreeCollector.NodeHierarchySource {
        override fun getBoundsInScreen(outBounds: Rect) {
            outBounds.left = nodeBounds.left
            outBounds.top = nodeBounds.top
            outBounds.right = nodeBounds.right
            outBounds.bottom = nodeBounds.bottom
        }
        override val childCount: Int get() = childrenNodes.size
        override fun getChild(index: Int): ZOrderTreeCollector.NodeHierarchySource? {
            return childrenNodes.getOrNull(index)
        }
        override val canScrollForwardOrBackward: Boolean get() = isScrollable
    }

    /**
     * 解析 MobileViews 的 `state_*.json` 檔案內容，構建 `NodeHierarchySource` 樹結構
     */
    @Suppress("UNCHECKED_CAST")
    fun parseMobileViewsStateJson(jsonContent: String): MobileViewsStateMetadata {
        val rootObj = MiniJsonParser.parseObject(jsonContent)
        val stateStr = rootObj["state_str"] as? String ?: ""
        val structureStr = rootObj["state_str_content_free"] as? String ?: ""
        val fgActivity = rootObj["foreground_activity"] as? String ?: ""
        val viewsArr = rootObj["views"] as? List<*> ?: emptyList<Any?>()

        var maxRight = 1080
        var maxBottom = 2340
        val nodeMap = HashMap<Int, DatasetTreeNode>(viewsArr.size)
        val childrenIndexMap = HashMap<Int, List<Int>>(viewsArr.size)
        val hasParentSet = HashSet<Int>()

        for (i in viewsArr.indices) {
            val v = viewsArr[i] as? Map<String, Any?> ?: continue
            val tempId = (v["temp_id"] as? Number)?.toInt() ?: i
            val boundsArr = v["bounds"] as? List<*>
            val rect = parseMobileViewsBoundsList(boundsArr)
            if (rect.right > maxRight) maxRight = rect.right
            if (rect.bottom > maxBottom) maxBottom = rect.bottom

            val childIds = ArrayList<Int>()
            val cArr = v["children"] as? List<*>
            if (cArr != null) {
                for (cItem in cArr) {
                    val cid = (cItem as? Number)?.toInt() ?: -1
                    if (cid >= 0) {
                        childIds.add(cid)
                        hasParentSet.add(cid)
                    }
                }
            }
            childrenIndexMap[tempId] = childIds

            val rawText = v["text"] as? String
            val rawDesc = v["content_description"] as? String
            val rawResId = v["resource_id"] as? String
            val clsName = v["class"] as? String ?: "android.view.View"
            val pkgName = v["package"] as? String ?: ""

            nodeMap[tempId] = DatasetTreeNode(
                identityCode = tempId + 1000,
                className = clsName,
                packageName = pkgName,
                text = rawText,
                contentDescription = rawDesc,
                viewIdResourceName = rawResId,
                isVisibleToUser = (v["visible"] as? Boolean) ?: true,
                isClickable = (v["clickable"] as? Boolean) ?: false,
                isLongClickable = (v["long_clickable"] as? Boolean) ?: false,
                isCheckable = (v["checkable"] as? Boolean) ?: false,
                isChecked = (v["checked"] as? Boolean) ?: false,
                isSelected = (v["selected"] as? Boolean) ?: false,
                isEditable = ((v["editable"] as? Boolean) ?: false) || clsName.contains("EditText", ignoreCase = true),
                isScrollable = (v["scrollable"] as? Boolean) ?: false,
                isEnabled = (v["enabled"] as? Boolean) ?: true,
                isPassword = (v["is_password"] as? Boolean) ?: false,
                drawingOrder = i,
                nodeBounds = rect
            )
        }

        for ((id, node) in nodeMap) {
            val childIds = childrenIndexMap[id].orEmpty()
            for (cid in childIds) {
                val childNode = nodeMap[cid]
                if (childNode != null && cid != id) {
                    node.childrenNodes.add(childNode)
                }
            }
        }

        val rootCandidate = nodeMap[0]
            ?: nodeMap.entries.firstOrNull { it.key !in hasParentSet }?.value
            ?: nodeMap.values.firstOrNull()

        return MobileViewsStateMetadata(
            stateStr = stateStr,
            structureStr = structureStr,
            foregroundActivity = fgActivity,
            width = maxRight.coerceAtLeast(320),
            height = maxBottom.coerceAtLeast(480),
            rootNode = rootCandidate,
            totalViewCount = nodeMap.size
        )
    }

    /**
     * 解析 MobileViews / UIAutomator 的 `window_dump_*.xml` 內容並構建 `NodeHierarchySource`
     */
    fun parseMobileViewsWindowDumpXml(xmlContent: String): MobileViewsStateMetadata {
        val noBom = xmlContent.removePrefix("\uFEFF").trim()
        val startIdx = noBom.indexOf("<?xml").takeIf { it >= 0 }
            ?: noBom.indexOf("<hierarchy").takeIf { it >= 0 }
            ?: noBom.indexOf("<node").takeIf { it >= 0 }
            ?: 0
        val endTag = "</hierarchy>"
        val endIdx = noBom.lastIndexOf(endTag)
        val cleanXml = if (endIdx >= startIdx) {
            noBom.substring(startIdx, endIdx + endTag.length)
        } else {
            noBom.substring(startIdx)
        }
        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(InputSource(StringReader(cleanXml)))
        doc.documentElement.normalize()

        var nodeCounter = 0
        var maxRight = 1080
        var maxBottom = 2340

        fun buildDomTree(elem: Element): DatasetTreeNode {
            nodeCounter++
            val boundsStr = elem.getAttribute("bounds").ifEmpty { "[0,0][1080,2340]" }
            val rect = parseUiAutomatorBoundsString(boundsStr)
            if (rect.right > maxRight) maxRight = rect.right
            if (rect.bottom > maxBottom) maxBottom = rect.bottom

            val clsName = elem.getAttribute("class").ifEmpty { "android.view.View" }
            val pkgName = elem.getAttribute("package")
            val textVal = elem.getAttribute("text")
            val descVal = elem.getAttribute("content-desc")
            val resId = elem.getAttribute("resource-id")
            val clickable = elem.getAttribute("clickable") == "true"
            val longClickable = elem.getAttribute("long-clickable") == "true"
            val checkable = elem.getAttribute("checkable") == "true"
            val checked = elem.getAttribute("checked") == "true"
            val selected = elem.getAttribute("selected") == "true"
            val scrollable = elem.getAttribute("scrollable") == "true"
            val enabled = elem.getAttribute("enabled") != "false"
            val password = elem.getAttribute("password") == "true"
            val visible = elem.getAttribute("visible-to-user") != "false"
            val drawOrder = elem.getAttribute("drawing-order").toIntOrNull() ?: nodeCounter

            val current = DatasetTreeNode(
                identityCode = nodeCounter + 5000,
                className = clsName,
                packageName = pkgName,
                text = textVal,
                contentDescription = descVal,
                viewIdResourceName = resId,
                isVisibleToUser = visible,
                isClickable = clickable,
                isLongClickable = longClickable,
                isCheckable = checkable,
                isChecked = checked,
                isSelected = selected,
                isEditable = clsName.contains("EditText", ignoreCase = true),
                isScrollable = scrollable,
                isEnabled = enabled,
                isPassword = password,
                drawingOrder = drawOrder,
                nodeBounds = rect
            )

            val childList = elem.childNodes
            for (i in 0 until childList.length) {
                val item = childList.item(i)
                if (item is Element && item.tagName == "node") {
                    current.childrenNodes.add(buildDomTree(item))
                }
            }
            return current
        }

        val rootElem = doc.documentElement
        val firstNodeElem: Element? = if (rootElem.tagName == "node") {
            rootElem
        } else {
            val nl = rootElem.childNodes
            var found: Element? = null
            for (i in 0 until nl.length) {
                val n = nl.item(i)
                if (n is Element && n.tagName == "node") {
                    found = n
                    break
                }
            }
            found
        }

        val rootNode = firstNodeElem?.let { buildDomTree(it) }

        return MobileViewsStateMetadata(
            stateStr = "",
            structureStr = "",
            foregroundActivity = "",
            width = maxRight.coerceAtLeast(320),
            height = maxBottom.coerceAtLeast(480),
            rootNode = rootNode,
            totalViewCount = nodeCounter
        )
    }

    /**
     * 將 MobileViews 狀態（JSON 或 XML）直接送入 ZOrderTreeCollector 與 AiVisionPipeline 執行完整四階段視覺感知
     */
    fun runVisionPipelineOnMobileViewsState(state: MobileViewsStateMetadata): VisionPipelineReport {
        val root = state.rootNode ?: return AiVisionPipeline.execute(
            rawNodes = emptyList(),
            screenWidth = state.width,
            screenHeight = state.height
        )
        val rawNodes = ZOrderTreeCollector.collectFromNodeSource(
            root = root,
            screenWidth = state.width,
            screenHeight = state.height
        )
        return AiVisionPipeline.execute(
            rawNodes = rawNodes,
            screenWidth = state.width,
            screenHeight = state.height,
            screenBitmap = null,
            useNormalizedGrid = true
        )
    }

    /**
     * 解析 MobileViews 的 `actions.csv` 狀態轉移軌跡
     */
    fun parseMobileViewsActionsCsv(csvFile: File): List<MobileViewsTransitionRecord> {
        if (!csvFile.exists()) return emptyList()
        val lines = csvFile.readLines(Charsets.UTF_8)
        if (lines.size <= 1) return emptyList()

        val bboxRegex = Regex("bound_box=(\\d+),(\\d+),(\\d+),(\\d+)")
        val labelRegex = Regex(">([^<]*)</")
        val result = ArrayList<MobileViewsTransitionRecord>(lines.size - 1)

        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val firstComma = line.indexOf(',')
            if (firstComma <= 0) continue
            val secondComma = line.indexOf(',', firstComma + 1)
            if (secondComma <= firstComma) continue

            val fromState = line.substring(0, firstComma).trim()
            val toState = line.substring(firstComma + 1, secondComma).trim()
            val rawAction = line.substring(secondComma + 1).trim().removeSurrounding("\"")
            val verb = rawAction.substringBefore(' ').trim()

            val bboxMatch = bboxRegex.find(rawAction)
            val bounds = if (bboxMatch != null) {
                makeRect(
                    bboxMatch.groupValues[1].toInt(),
                    bboxMatch.groupValues[2].toInt(),
                    bboxMatch.groupValues[3].toInt(),
                    bboxMatch.groupValues[4].toInt()
                )
            } else {
                null
            }
            val label = labelRegex.find(rawAction)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            result.add(
                MobileViewsTransitionRecord(
                    fromState = fromState,
                    toState = toState,
                    rawAction = rawAction,
                    actionVerb = verb,
                    targetBounds = bounds,
                    targetLabel = label
                )
            )
        }
        return result
    }

    // =========================================================================
    // Part 2: Android in the Wild (AitW) 數據集與官方評估演算法接入
    // =========================================================================

    /**
     * 嚴格對應 Google Research `android_in_the_wild/action_type.py`
     */
    enum class AitwActionType(val code: Int) {
        UNUSED_0(0),
        UNUSED_1(1),
        UNUSED_2(2),
        TYPE(3),
        DUAL_POINT(4),
        PRESS_BACK(5),
        PRESS_HOME(6),
        PRESS_ENTER(7),
        UNUSED_8(8),
        UNUSED_9(9),
        STATUS_TASK_COMPLETE(10),
        STATUS_TASK_IMPOSSIBLE(11);

        companion object {
            fun fromCode(code: Int): AitwActionType {
                return entries.firstOrNull { it.code == code } ?: UNUSED_0
            }
        }
    }

    /**
     * AitW 單一 UI 標註框（正規化座標 yTop, xLeft, height, width 範圍 0.0~1.0）
     */
    data class AitwUiAnnotation(
        val yTop: Float,
        val xLeft: Float,
        val height: Float,
        val width: Float,
        val text: String,
        val uiType: String
    ) {
        fun toPixelRect(screenWidth: Int, screenHeight: Int): Rect {
            val l = (xLeft * screenWidth).toInt().coerceIn(0, screenWidth)
            val t = (yTop * screenHeight).toInt().coerceIn(0, screenHeight)
            val r = ((xLeft + width) * screenWidth).toInt().coerceIn(l + 1, screenWidth.coerceAtLeast(l + 1))
            val b = ((yTop + height) * screenHeight).toInt().coerceIn(t + 1, screenHeight.coerceAtLeast(t + 1))
            return makeRect(l, t, r, b)
        }
    }

    /**
     * AitW 單步操作軌跡紀錄（解析自 `parsed_episodes.jsonl`）
     */
    data class AitwEpisodeStep(
        val episodeId: String,
        val stepId: Int,
        val episodeLength: Int,
        val goalInfo: String,
        val currentActivity: String,
        val deviceType: String,
        val androidApiLevel: Int,
        val imageWidth: Int,
        val imageHeight: Int,
        val actionType: AitwActionType,
        val touchY: Float,
        val touchX: Float,
        val liftY: Float,
        val liftX: Float,
        val typedText: String,
        val annotations: List<AitwUiAnnotation>
    ) {
        /**
         * 對應 `action_matching.py` 的 `is_tap_action`（歐氏距離 <= 0.04 為點擊，否則為滑動）
         */
        fun isTapGesture(): Boolean {
            return actionType == AitwActionType.DUAL_POINT &&
                isTapAction(touchY, touchX, liftY, liftX)
        }

        /**
         * 將 AitW 動作轉換為本系統 `AiActionType`
         */
        fun toAiActionType(): AiActionType {
            return when (actionType) {
                AitwActionType.TYPE -> AiActionType.INPUT_TEXT
                AitwActionType.PRESS_BACK -> AiActionType.NAVIGATE_BACK
                AitwActionType.PRESS_HOME -> AiActionType.NAVIGATE_BACK
                AitwActionType.PRESS_ENTER -> AiActionType.TAP
                AitwActionType.STATUS_TASK_COMPLETE -> AiActionType.FINISH_DONE
                AitwActionType.STATUS_TASK_IMPOSSIBLE -> AiActionType.FINISH_DONE
                AitwActionType.DUAL_POINT -> {
                    if (isTapGesture()) {
                        AiActionType.TAP
                    } else {
                        val dy = liftY - touchY
                        val dx = liftX - touchX
                        if (abs(dy) >= abs(dx)) {
                            // 手指由下往上滑 (liftY < touchY) 代表畫面往下捲動 (SCROLL_DOWN)
                            if (dy < 0f) AiActionType.SCROLL_DOWN else AiActionType.SCROLL_UP
                        } else {
                            if (dx < 0f) AiActionType.SWIPE_LEFT else AiActionType.SWIPE_RIGHT
                        }
                    }
                }
                else -> AiActionType.FINISH_DONE
            }
        }

        /**
         * 將 AitW 步驟的 `ui_annotations` 轉為 `RawVisionNode` 列表，可直接送入 `AiVisionPipeline.execute`
         */
        fun toRawVisionNodes(): List<RawVisionNode> {
            val w = imageWidth.coerceAtLeast(360)
            val h = imageHeight.coerceAtLeast(640)
            val screenRect = makeRect(0, 0, w, h)
            return annotations.mapIndexed { idx, ann ->
                val rect = ann.toPixelRect(w, h)
                val isIcon = ann.uiType.startsWith("ICON_", ignoreCase = true)
                val cls = if (isIcon) "android.widget.ImageButton" else "android.widget.TextView"
                val iconDesc = if (isIcon && ann.text.isBlank()) {
                    ann.uiType.removePrefix("ICON_").lowercase().replace('_', ' ')
                } else {
                    ""
                }
                RawVisionNode(
                    id = "aitw_${episodeId}_${stepId}_$idx",
                    windowId = 1,
                    windowLayer = 0,
                    zOrderRank = idx,
                    className = cls,
                    packageName = currentActivity.substringBefore('/'),
                    bounds = rect,
                    viewportClipBounds = screenRect,
                    text = ann.text,
                    contentDescription = iconDesc,
                    hintText = "",
                    viewIdResourceName = ann.uiType.lowercase(),
                    isClickable = true,
                    isLongClickable = false,
                    isCheckable = false,
                    isChecked = false,
                    isSelected = false,
                    isEditable = false,
                    isScrollable = false,
                    scrollDirections = "",
                    isEnabled = true,
                    isOpaqueOverlay = true,
                    isModalScrim = false,
                    isCanvasOrBlackBoxCandidate = false,
                    hasRenderedContent = true,
                    parentId = null,
                    childIds = emptyList(),
                    listStructuralSignature = null,
                    isPassword = false,
                    inputType = 0
                )
            }
        }
    }

    // 官方 `action_matching.py` 常數
    const val TAP_DISTANCE_THRESHOLD = 0.14f
    const val ANNOTATION_WIDTH_AUGMENT_FRACTION = 1.4f
    const val ANNOTATION_HEIGHT_AUGMENT_FRACTION = 1.4f
    const val SWIPE_DISTANCE_THRESHOLD = 0.04f

    /**
     * 嚴格對應 `action_matching.py` 之 `is_tap_action(normalized_start_yx, normalized_end_yx)`
     */
    fun isTapAction(startY: Float, startX: Float, endY: Float, endX: Float): Boolean {
        val dist = hypot(startY - endY, startX - endX)
        return dist <= SWIPE_DISTANCE_THRESHOLD
    }

    /**
     * 嚴格對應 `action_matching.py` 之 `check_actions_match(...)`
     * 判斷兩個動作（例如 AI 預測動作 vs. AitW 真人標註動作）在語義與元件邊界上是否一致
     */
    fun checkAitwActionsMatch(
        action1TouchY: Float,
        action1TouchX: Float,
        action1LiftY: Float,
        action1LiftX: Float,
        action1Type: AitwActionType,
        action2TouchY: Float,
        action2TouchX: Float,
        action2LiftY: Float,
        action2LiftX: Float,
        action2Type: AitwActionType,
        annotations: List<AitwUiAnnotation>,
        tapDistanceThreshold: Float = TAP_DISTANCE_THRESHOLD,
        widthAugmentFraction: Float = ANNOTATION_WIDTH_AUGMENT_FRACTION,
        heightAugmentFraction: Float = ANNOTATION_HEIGHT_AUGMENT_FRACTION
    ): Boolean {
        val hasNonDualPoint = action1Type != AitwActionType.DUAL_POINT || action2Type != AitwActionType.DUAL_POINT
        if (hasNonDualPoint) {
            return action1Type == action2Type
        }

        val isTap1 = isTapAction(action1TouchY, action1TouchX, action1LiftY, action1LiftX)
        val isTap2 = isTapAction(action2TouchY, action2TouchX, action2LiftY, action2LiftX)
        if (isTap1 != isTap2) return false

        if (isTap1) {
            for (ann in annotations) {
                val hChange = heightAugmentFraction * ann.height
                val wChange = widthAugmentFraction * ann.width
                val top = max(0f, ann.yTop - hChange / 2f)
                val left = max(0f, ann.xLeft - wChange / 2f)
                val height = min(1f, ann.height + hChange)
                val width = min(1f, ann.width + wChange)
                val bottom = top + height
                val right = left + width

                val tap1InBox = action1TouchY in top..bottom && action1TouchX in left..right
                val tap2InBox = action2TouchY in top..bottom && action2TouchX in left..right
                if (tap1InBox && tap2InBox) return true
            }
            val dist = hypot(action1TouchY - action2TouchY, action1TouchX - action2TouchX)
            return dist <= tapDistanceThreshold
        } else {
            val dy1 = abs(action1LiftY - action1TouchY)
            val dx1 = abs(action1LiftX - action1TouchX)
            val mainAxis1 = if (dy1 >= dx1) 0 else 1

            val dy2 = abs(action2LiftY - action2TouchY)
            val dx2 = abs(action2LiftX - action2TouchX)
            val mainAxis2 = if (dy2 >= dx2) 0 else 1
            return mainAxis1 == mainAxis2
        }
    }

    /**
     * 解析 `parsed_episodes.jsonl` 單行 JSON 為 `AitwEpisodeStep`
     */
    fun parseAitwEpisodeJsonLine(jsonLine: String): AitwEpisodeStep {
        val obj = MiniJsonParser.parseObject(jsonLine)
        val episodeId = obj["episode_id"] as? String ?: ""
        val stepId = optFirstInt(obj, "step_id", 0)
        val episodeLength = optFirstInt(obj, "episode_length", 1)
        val goalInfo = obj["goal_info"] as? String ?: ""
        val currentActivity = obj["current_activity"] as? String ?: ""
        val deviceType = obj["device_type"] as? String ?: ""
        val apiLevel = optFirstInt(obj, "android_api_level", 30)
        val imgW = optFirstInt(obj, "image/width", 1080)
        val imgH = optFirstInt(obj, "image/height", 2340)
        val actionCode = optFirstInt(obj, "results/action_type", 4)
        val typedText = obj["results/type_action"] as? String ?: ""

        val touchArr = obj["results/yx_touch"] as? List<*>
        val touchY = (touchArr?.getOrNull(0) as? Number)?.toFloat() ?: 0f
        val touchX = (touchArr?.getOrNull(1) as? Number)?.toFloat() ?: 0f

        val liftArr = obj["results/yx_lift"] as? List<*>
        val liftY = (liftArr?.getOrNull(0) as? Number)?.toFloat() ?: 0f
        val liftX = (liftArr?.getOrNull(1) as? Number)?.toFloat() ?: 0f

        val posArr = obj["image/ui_annotations_positions"] as? List<*> ?: emptyList<Any?>()
        val textsList = optStringList(obj, "image/ui_annotations_text")
        val typesList = optStringList(obj, "image/ui_annotations_ui_types")

        val boxCount = posArr.size / 4
        val annotations = ArrayList<AitwUiAnnotation>(boxCount)
        for (i in 0 until boxCount) {
            val base = i * 4
            annotations.add(
                AitwUiAnnotation(
                    yTop = (posArr[base] as? Number)?.toFloat() ?: 0f,
                    xLeft = (posArr[base + 1] as? Number)?.toFloat() ?: 0f,
                    height = (posArr[base + 2] as? Number)?.toFloat() ?: 0f,
                    width = (posArr[base + 3] as? Number)?.toFloat() ?: 0f,
                    text = textsList.getOrElse(i) { "" },
                    uiType = typesList.getOrElse(i) { "TEXT" }
                )
            )
        }

        return AitwEpisodeStep(
            episodeId = episodeId,
            stepId = stepId,
            episodeLength = episodeLength,
            goalInfo = goalInfo,
            currentActivity = currentActivity,
            deviceType = deviceType,
            androidApiLevel = apiLevel,
            imageWidth = imgW,
            imageHeight = imgH,
            actionType = AitwActionType.fromCode(actionCode),
            touchY = touchY,
            touchX = touchX,
            liftY = liftY,
            liftX = liftX,
            typedText = typedText,
            annotations = annotations
        )
    }

    /**
     * 批次載入 `parsed_episodes.jsonl` 檔案
     */
    fun loadAitwEpisodesFromJsonl(jsonlFile: File, maxSteps: Int = 500): List<AitwEpisodeStep> {
        if (!jsonlFile.exists()) return emptyList()
        val list = ArrayList<AitwEpisodeStep>()
        jsonlFile.useLines(Charsets.UTF_8) { seq ->
            for (line in seq) {
                if (list.size >= maxSteps) break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    list.add(parseAitwEpisodeJsonLine(trimmed))
                }
            }
        }
        return list
    }

    // =========================================================================
    // Part 3: AMEX (Android Multi-annotation EXpo, 102,439 張真實畫面與按鈕功能標註庫) 接入
    // =========================================================================

    data class AmexElementFunctionality(
        val index: Int,
        val bounds: Rect,
        val xmlDescriptions: List<String>,
        val elementType: String,
        val functionality: String,
        val isScrollable: Boolean
    )

    data class AmexScreenKnowledgeRecord(
        val imagePath: String,
        val pageCaption: String,
        val clickableElements: List<AmexElementFunctionality>,
        val scrollableElements: List<AmexElementFunctionality>
    ) {
        /**
         * 將 AMEX 標註畫面轉換為 `RawVisionNode` 列表，可直接送入 `AiVisionPipeline.execute`
         */
        fun toRawVisionNodes(screenWidth: Int = 1440, screenHeight: Int = 2960): List<RawVisionNode> {
            val screenRect = makeRect(0, 0, screenWidth, screenHeight)
            val result = ArrayList<RawVisionNode>(clickableElements.size + scrollableElements.size)
            var zRank = 0

            for (el in clickableElements) {
                val primaryDesc = el.xmlDescriptions.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                val isIcon = el.elementType.startsWith("ICON_", ignoreCase = true)
                val cls = if (isIcon) "android.widget.ImageButton" else "android.widget.Button"
                result.add(
                    RawVisionNode(
                        id = "amex_click_${imagePath}_${el.index}_$zRank",
                        windowId = 1,
                        windowLayer = 0,
                        zOrderRank = zRank++,
                        className = cls,
                        packageName = "amex.screen",
                        bounds = el.bounds,
                        viewportClipBounds = screenRect,
                        text = if (!isIcon) primaryDesc else "",
                        contentDescription = if (isIcon) primaryDesc.ifEmpty { el.elementType } else el.functionality,
                        hintText = el.functionality,
                        viewIdResourceName = el.elementType,
                        isClickable = true,
                        isLongClickable = false,
                        isCheckable = false,
                        isChecked = false,
                        isSelected = false,
                        isEditable = el.functionality.contains("type", ignoreCase = true) ||
                            el.functionality.contains("input", ignoreCase = true),
                        isScrollable = false,
                        scrollDirections = "",
                        isEnabled = true,
                        isOpaqueOverlay = true,
                        isModalScrim = false,
                        isCanvasOrBlackBoxCandidate = false,
                        hasRenderedContent = true,
                        parentId = null,
                        childIds = emptyList(),
                        listStructuralSignature = null,
                        isPassword = false,
                        inputType = 0
                    )
                )
            }

            for (el in scrollableElements) {
                val primaryDesc = el.xmlDescriptions.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                result.add(
                    RawVisionNode(
                        id = "amex_scroll_${imagePath}_${el.index}_$zRank",
                        windowId = 1,
                        windowLayer = 0,
                        zOrderRank = zRank++,
                        className = "androidx.recyclerview.widget.RecyclerView",
                        packageName = "amex.screen",
                        bounds = el.bounds,
                        viewportClipBounds = screenRect,
                        text = "",
                        contentDescription = primaryDesc,
                        hintText = el.functionality,
                        viewIdResourceName = el.elementType,
                        isClickable = false,
                        isLongClickable = false,
                        isCheckable = false,
                        isChecked = false,
                        isSelected = false,
                        isEditable = false,
                        isScrollable = true,
                        scrollDirections = "V",
                        isEnabled = true,
                        isOpaqueOverlay = false,
                        isModalScrim = false,
                        isCanvasOrBlackBoxCandidate = false,
                        hasRenderedContent = primaryDesc.isNotEmpty(),
                        parentId = null,
                        childIds = emptyList(),
                        listStructuralSignature = null,
                        isPassword = false,
                        inputType = 0
                    )
                )
            }
            return result
        }
    }

    /**
     * 解析 AMEX 的 `element_anno/<id>.json` 單一畫面與按鈕功能標註檔
     */
    fun parseAmexElementAnnoJson(jsonContent: String): AmexScreenKnowledgeRecord {
        val obj = MiniJsonParser.parseObject(jsonContent)
        val imagePath = obj["image_path"] as? String ?: ""
        val pageCaption = obj["page_caption"] as? String ?: ""

        fun parseElementList(rawList: List<*>?, isScrollable: Boolean): List<AmexElementFunctionality> {
            if (rawList == null) return emptyList()
            val out = ArrayList<AmexElementFunctionality>(rawList.size)
            for (i in rawList.indices) {
                val item = rawList[i] as? Map<*, *> ?: continue
                val bbox = item["bbox"] as? List<*>
                val rect = if (bbox != null && bbox.size >= 4) {
                    makeRect(
                        (bbox[0] as? Number)?.toInt() ?: 0,
                        (bbox[1] as? Number)?.toInt() ?: 0,
                        (bbox[2] as? Number)?.toInt() ?: 0,
                        (bbox[3] as? Number)?.toInt() ?: 0
                    )
                } else {
                    makeRect(0, 0, 0, 0)
                }
                val xmlDescList = (item["xml_desc"] as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    ?: emptyList()
                val elemType = item["type"] as? String ?: ""
                val func = item["functionality"] as? String ?: ""
                val idx = (item["idx"] as? Number)?.toInt() ?: i

                out.add(
                    AmexElementFunctionality(
                        index = idx,
                        bounds = rect,
                        xmlDescriptions = xmlDescList,
                        elementType = elemType,
                        functionality = func,
                        isScrollable = isScrollable
                    )
                )
            }
            return out
        }

        val clickables = parseElementList(obj["clickable_elements"] as? List<*>, isScrollable = false)
        val scrollables = parseElementList(obj["scrollable_elements"] as? List<*>, isScrollable = true)

        return AmexScreenKnowledgeRecord(
            imagePath = imagePath,
            pageCaption = pageCaption,
            clickableElements = clickables,
            scrollableElements = scrollables
        )
    }

    // =========================================================================
    // 內部解析輔助函式與 100% 純自研零依賴 JSON 解析器 (MiniJsonParser)
    // =========================================================================

    private fun parseMobileViewsBoundsList(boundsArr: List<*>?): Rect {
        if (boundsArr == null || boundsArr.size < 2) return makeRect(0, 0, 0, 0)
        val p1 = boundsArr[0] as? List<*>
        val p2 = boundsArr[1] as? List<*>
        if (p1 == null || p2 == null || p1.size < 2 || p2.size < 2) return makeRect(0, 0, 0, 0)
        val x1 = (p1[0] as? Number)?.toInt() ?: 0
        val y1 = (p1[1] as? Number)?.toInt() ?: 0
        val x2 = (p2[0] as? Number)?.toInt() ?: 0
        val y2 = (p2[1] as? Number)?.toInt() ?: 0
        val left = min(x1, x2)
        val top = min(y1, y2)
        val right = max(x1, x2)
        val bottom = max(y1, y2)
        return makeRect(left, top, right, bottom)
    }

    private fun parseUiAutomatorBoundsString(boundsStr: String): Rect {
        val m = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(boundsStr)
            ?: return makeRect(0, 0, 0, 0)
        return makeRect(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
            m.groupValues[4].toInt()
        )
    }

    private fun optFirstInt(obj: Map<String, Any?>, key: String, defaultVal: Int): Int {
        val v = obj[key] ?: return defaultVal
        return when (v) {
            is Number -> v.toInt()
            is List<*> -> (v.firstOrNull() as? Number)?.toInt() ?: defaultVal
            is String -> v.toIntOrNull() ?: defaultVal
            else -> defaultVal
        }
    }

    private fun optStringList(obj: Map<String, Any?>, key: String): List<String> {
        val v = obj[key] ?: return emptyList()
        return when (v) {
            is List<*> -> v.map { it?.toString() ?: "" }
            is String -> listOf(v)
            else -> emptyList()
        }
    }

    /**
     * 100% 純自研零第三方依賴之遞迴下降 JSON 解析器（支援 Android 執行期與純 JVM 單元測試）
     */
    internal object MiniJsonParser {
        fun parseObject(s: String): Map<String, Any?> {
            val state = ParserState(s)
            val v = state.parseValue()
            @Suppress("UNCHECKED_CAST")
            return v as? Map<String, Any?> ?: emptyMap()
        }

        private class ParserState(val src: String) {
            var pos = 0
            val len = src.length

            fun skipWhitespace() {
                while (pos < len) {
                    val c = src[pos]
                    if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++ else break
                }
            }

            fun parseValue(): Any? {
                skipWhitespace()
                if (pos >= len) return null
                return when (src[pos]) {
                    '{' -> parseMap()
                    '[' -> parseList()
                    '"' -> parseString()
                    't', 'f' -> parseBoolean()
                    'n' -> parseNull()
                    else -> parseNumber()
                }
            }

            fun parseMap(): Map<String, Any?> {
                val map = LinkedHashMap<String, Any?>()
                pos++ // skip '{'
                skipWhitespace()
                if (pos < len && src[pos] == '}') {
                    pos++
                    return map
                }
                while (pos < len) {
                    skipWhitespace()
                    if (pos >= len || src[pos] == '}') {
                        pos++
                        break
                    }
                    val key = if (src[pos] == '"') parseString() else ""
                    skipWhitespace()
                    if (pos < len && src[pos] == ':') pos++
                    val value = parseValue()
                    map[key] = value
                    skipWhitespace()
                    if (pos < len && src[pos] == ',') {
                        pos++
                    } else if (pos < len && src[pos] == '}') {
                        pos++
                        break
                    }
                }
                return map
            }

            fun parseList(): List<Any?> {
                val list = ArrayList<Any?>()
                pos++ // skip '['
                skipWhitespace()
                if (pos < len && src[pos] == ']') {
                    pos++
                    return list
                }
                while (pos < len) {
                    skipWhitespace()
                    if (pos >= len || src[pos] == ']') {
                        pos++
                        break
                    }
                    list.add(parseValue())
                    skipWhitespace()
                    if (pos < len && src[pos] == ',') {
                        pos++
                    } else if (pos < len && src[pos] == ']') {
                        pos++
                        break
                    }
                }
                return list
            }

            fun parseString(): String {
                pos++ // skip '"'
                val sb = StringBuilder()
                while (pos < len) {
                    val c = src[pos++]
                    if (c == '"') break
                    if (c == '\\' && pos < len) {
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= len) {
                                    val hex = src.substring(pos, pos + 4)
                                    sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(c)
                    }
                }
                return sb.toString()
            }

            fun parseBoolean(): Boolean {
                if (src.startsWith("true", pos)) {
                    pos += 4
                    return true
                }
                if (src.startsWith("false", pos)) {
                    pos += 5
                    return false
                }
                pos++
                return false
            }

            fun parseNull(): Any? {
                if (src.startsWith("null", pos)) {
                    pos += 4
                } else {
                    pos++
                }
                return null
            }

            fun parseNumber(): Number {
                val start = pos
                var isFloat = false
                while (pos < len) {
                    val c = src[pos]
                    if (c in '0'..'9' || c == '-' || c == '+') {
                        pos++
                    } else if (c == '.' || c == 'e' || c == 'E') {
                        isFloat = true
                        pos++
                    } else {
                        break
                    }
                }
                val token = src.substring(start, pos)
                return if (isFloat) {
                    token.toDoubleOrNull() ?: 0.0
                } else {
                    token.toLongOrNull() ?: 0L
                }
            }
        }
    }
}
