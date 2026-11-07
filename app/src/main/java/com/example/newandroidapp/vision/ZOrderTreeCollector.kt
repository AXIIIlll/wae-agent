package com.example.newandroidapp.vision

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.newandroidapp.ai.DeterminismHardeningEngine
import com.example.newandroidapp.stability.SystemBarBlockerGuard
import com.example.newandroidapp.vision.dataset.Phase1TreeImmunityConfig

/**
 * 第一階段收集器：多視窗與節點樹之嚴格 Z-Order 逆向遍歷（由最頂層視窗往底層、由後繪製的子樹往前繪製的子樹）
 *
 * 關鍵工程細節（父子同源豁免機制）：
 * 在逆向後序遍歷（Reverse Post-Order：先掃最上層子節點，再回到父容器）時，
 * 同一個可點擊父容器內部的子元件屬於「構成該父容器的視覺內容」，絕不能把自己的父容器當成被遮擋物剔除；
 * 只有「較高層級的視窗（Window Layer）」或「同層級中較晚繪製的兄弟浮層（如 FrameLayout / CoordinatorLayout 頂層的 BottomSheet、FAB、遮罩面板）」
 * 才會寫入 2D Occupancy Grid 遮擋下層兄弟與底層視窗。
 */
object ZOrderTreeCollector {

    const val DEFAULT_MAX_DEPTH = 45
    const val DEFAULT_MAX_NODES = 400

    /**
     * 節點階層抽象介面，支援實機 AccessibilityNodeInfo 與單元測試自訂節點樹
     */
    interface NodeHierarchySource {
        val isVisibleToUser: Boolean
        fun getBoundsInScreen(outBounds: Rect)
        val className: CharSequence?
        val packageName: CharSequence?
        val text: CharSequence?
        val contentDescription: CharSequence?
        val hintText: CharSequence?
        val viewIdResourceName: String?
        val isClickable: Boolean
        val isLongClickable: Boolean
        val isCheckable: Boolean
        val isChecked: Boolean
        val isSelected: Boolean
        val isFocused: Boolean get() = false
        val isEditable: Boolean
        val isScrollable: Boolean
        val isEnabled: Boolean
        val drawingOrder: Int
        val childCount: Int
        fun getChild(index: Int): NodeHierarchySource?
        val canScrollForwardOrBackward: Boolean
        val identityCode: Int
        val isPassword: Boolean get() = false
        val inputType: Int get() = 0
    }

    /**
     * 實體 AccessibilityNodeInfo 包裝器
     */
    class RealAccessibilityNodeSource(private val real: AccessibilityNodeInfo) : NodeHierarchySource {
        override val isVisibleToUser: Boolean get() = real.isVisibleToUser
        override fun getBoundsInScreen(outBounds: Rect) = real.getBoundsInScreen(outBounds)
        override val className: CharSequence? get() = real.className
        override val packageName: CharSequence? get() = real.packageName
        override val text: CharSequence? get() = real.text
        override val contentDescription: CharSequence? get() = real.contentDescription
        override val hintText: CharSequence? get() = real.hintText
        override val viewIdResourceName: String? get() = real.viewIdResourceName
        override val isClickable: Boolean get() = real.isClickable
        override val isLongClickable: Boolean get() = real.isLongClickable
        override val isCheckable: Boolean get() = real.isCheckable
        override val isChecked: Boolean get() = real.isChecked
        override val isSelected: Boolean get() = real.isSelected
        override val isFocused: Boolean get() = real.isFocused || real.isAccessibilityFocused
        override val isEditable: Boolean get() = real.isEditable
        override val isScrollable: Boolean get() = real.isScrollable
        override val isEnabled: Boolean get() = real.isEnabled
        override val drawingOrder: Int get() = real.drawingOrder
        override val childCount: Int get() = real.childCount
        override fun getChild(index: Int): NodeHierarchySource? {
            val c = runCatching { real.getChild(index) }.getOrNull() ?: return null
            return RealAccessibilityNodeSource(c)
        }
        override val canScrollForwardOrBackward: Boolean get() {
            val actions = runCatching { real.actionList }.getOrNull() ?: return false
            return actions.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id ||
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
            }
        }
        override val identityCode: Int get() {
            val b = Rect()
            runCatching { real.getBoundsInScreen(b) }
            return DeterminismHardeningEngine.computeDeterministicNodeCode(
                windowId = real.windowId,
                depth = 0,
                childIndex = real.drawingOrder,
                className = real.className?.toString() ?: "",
                viewIdResourceName = real.viewIdResourceName ?: "",
                rawBounds = b,
                extraSalt = (real.text?.toString().orEmpty().hashCode() * 31) xor
                    real.contentDescription?.toString().orEmpty().hashCode()
            )
        }
        override val isPassword: Boolean get() = real.isPassword
        override val inputType: Int get() = real.inputType
    }

    /**
     * 從無障礙多視窗列表（AccessibilityService.windows）進行嚴格 Z-Order 逆向收集
     *
     * 具備保護閥機制：
     * - 最大遞迴深度：maxDepth = 45（預設）
     * - 最大節點上限：maxNodes = 400（預設）
     */
    fun collectFromAccessibilityWindows(
        windows: List<AccessibilityWindowInfo>,
        rootFallback: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        ignorePackageName: String? = null,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxNodes: Int = DEFAULT_MAX_NODES
    ): List<RawVisionNode> {
        val result = ArrayList<RawVisionNode>(128)
        var zRankCounter = 0
        var blockedCount = 0
        val screenRect = Rect(0, 0, screenWidth, screenHeight)
        val statusBarH = SystemBarBlockerGuard.getStatusBarHeightPx(null, screenHeight)
        val navBarH = SystemBarBlockerGuard.getNavigationBarHeightPx(null, screenHeight)

        // 依照 Window Layer 由高到低排序（最頂層懸浮窗、Dialog 優先，並排除無障礙自身 Overlay 與頂底狀態列/導覽列視窗）
        val sortedWindows = windows
            .filter { win ->
                if (win.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@filter false
                if (SystemBarBlockerGuard.isSystemBarWindow(win, screenWidth, screenHeight, statusBarH, navBarH)) {
                    blockedCount++
                    return@filter false
                }
                val root = win.root
                val pkg = root?.packageName?.toString() ?: ""
                pkg.isNotEmpty() && pkg != ignorePackageName
            }
            .sortedByDescending { it.layer }

        val usedNodeIds = HashSet<String>(maxNodes * 2)
        if (sortedWindows.isNotEmpty()) {
            for (win in sortedWindows) {
                if (result.size >= maxNodes) break
                val root = runCatching { win.root }.getOrNull() ?: continue
                val winBounds = Rect()
                runCatching { win.getBoundsInScreen(winBounds) }
                if (!winBounds.intersect(screenRect)) continue

                val isDialogOrOverlayWin = win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (winBounds.width() < screenWidth * 0.95 || winBounds.height() < screenHeight * 0.85)

                zRankCounter = traverseNodeReverseZ(
                    node = RealAccessibilityNodeSource(root),
                    windowId = win.id,
                    windowLayer = win.layer,
                    parentId = null,
                    currentViewport = winBounds,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    forceOverlayWindow = isDialogOrOverlayWin,
                    startZRank = zRankCounter,
                    outList = result,
                    depth = 0,
                    maxDepth = maxDepth,
                    maxNodes = maxNodes,
                    assignedNodeId = null,
                    usedNodeIds = usedNodeIds
                )
            }
        } else if (rootFallback != null) {
            traverseNodeReverseZ(
                node = RealAccessibilityNodeSource(rootFallback),
                windowId = rootFallback.windowId,
                windowLayer = 0,
                parentId = null,
                currentViewport = screenRect,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                forceOverlayWindow = false,
                startZRank = 0,
                outList = result,
                depth = 0,
                maxDepth = maxDepth,
                maxNodes = maxNodes,
                assignedNodeId = null,
                usedNodeIds = usedNodeIds
            )
        }

        val rawOut = ArrayList<RawVisionNode>(result.size.coerceAtMost(maxNodes))
        val foregroundTargetPkg = DeterminismHardeningEngine.selectPrimaryForegroundPackage(result)
        val navBarFloorPx = screenHeight - navBarH

        for (n in result) {
            if (rawOut.size >= maxNodes) break
            // 若為葉節點或獨立互動元件且完全落入頂部狀態列 / 底部導覽列禁區，或屬於動態背景雜訊（非目標包名、時間、電量），予以攔截剔除
            val isFullScreenContainer = (n.bounds.bottom - n.bounds.top) > screenHeight * 0.4f
            if (!isFullScreenContainer &&
                (SystemBarBlockerGuard.isNodeInSystemBarZone(
                    bounds = n.bounds,
                    packageName = n.packageName,
                    screenHeight = screenHeight,
                    statusBarHeightPx = statusBarH,
                    navBarHeightPx = navBarH
                ) || DeterminismHardeningEngine.shouldStripRawVisionNode(
                    node = n,
                    screenHeightPx = screenHeight,
                    statusBarCeilingPx = statusBarH,
                    navBarFloorPx = navBarFloorPx,
                    foregroundAppPackage = foregroundTargetPkg
                ))
            ) {
                blockedCount++
                continue
            }
            rawOut.add(n)
        }
        SystemBarBlockerGuard.blockedNodeCountLastScan = blockedCount
        return rawOut
    }

    /**
     * 直接從抽象節點樹來源收集（供單元測試與自訂節點樹使用）
     */
    fun collectFromNodeSource(
        root: NodeHierarchySource,
        screenWidth: Int,
        screenHeight: Int,
        windowId: Int = 1,
        windowLayer: Int = 0,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxNodes: Int = DEFAULT_MAX_NODES
    ): List<RawVisionNode> {
        val result = ArrayList<RawVisionNode>(128)
        val screenRect = Rect(0, 0, screenWidth, screenHeight).apply {
            left = 0
            top = 0
            right = screenWidth
            bottom = screenHeight
        }
        val usedNodeIds = HashSet<String>(maxNodes * 2)
        traverseNodeReverseZ(
            node = root,
            windowId = windowId,
            windowLayer = windowLayer,
            parentId = null,
            currentViewport = screenRect,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            forceOverlayWindow = false,
            startZRank = 0,
            outList = result,
            depth = 0,
            maxDepth = maxDepth,
            maxNodes = maxNodes,
            assignedNodeId = null,
            usedNodeIds = usedNodeIds
        )
        return result.take(maxNodes)
    }

    private fun allocateUniqueDeterministicNodeId(
        node: NodeHierarchySource,
        windowId: Int,
        depth: Int,
        childSlot: Int,
        bounds: Rect,
        parentId: String?,
        usedNodeIds: MutableSet<String>
    ): String {
        val snappedLeft = DeterminismHardeningEngine.roundTo10PxGrid(bounds.left)
        val snappedTop = DeterminismHardeningEngine.roundTo10PxGrid(bounds.top)
        val treeHash = DeterminismHardeningEngine.computeDeterministicNodeCode(
            windowId = windowId,
            depth = depth,
            childIndex = childSlot,
            className = node.className?.toString() ?: "",
            viewIdResourceName = node.viewIdResourceName ?: "",
            rawBounds = bounds,
            extraSalt = node.identityCode xor (parentId?.hashCode() ?: 0)
        )
        val baseId = "w${windowId}_d${depth}_s${childSlot}_n${treeHash}_${snappedLeft}_${snappedTop}"
        if (baseId != parentId && usedNodeIds.add(baseId)) {
            return baseId
        }
        var dupIndex = 1
        while (true) {
            val candidate = "${baseId}_u$dupIndex"
            if (candidate != parentId && usedNodeIds.add(candidate)) {
                return candidate
            }
            dupIndex++
        }
    }

    private fun traverseNodeReverseZ(
        node: NodeHierarchySource,
        windowId: Int,
        windowLayer: Int,
        parentId: String?,
        currentViewport: Rect,
        screenWidth: Int,
        screenHeight: Int,
        forceOverlayWindow: Boolean,
        startZRank: Int,
        outList: MutableList<RawVisionNode>,
        depth: Int = 0,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxNodes: Int = DEFAULT_MAX_NODES,
        assignedNodeId: String? = null,
        usedNodeIds: MutableSet<String> = HashSet()
    ): Int {
        if (outList.size >= maxNodes) return startZRank
        if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return startZRank

        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val boundsW = bounds.right - bounds.left
        val boundsH = bounds.bottom - bounds.top
        if (boundsW <= 0 || boundsH <= 0) return startZRank

        val nodeId = assignedNodeId ?: allocateUniqueDeterministicNodeId(
            node = node,
            windowId = windowId,
            depth = depth,
            childSlot = 0,
            bounds = bounds,
            parentId = parentId,
            usedNodeIds = usedNodeIds
        )
        val clsName = node.className?.toString() ?: "android.view.View"
        val isScrollable = node.isScrollable

        // 若自身為可捲動容器（RecyclerView / ScrollView / ViewPager），子節點的可見視口必須與此容器取交集
        val childViewport = if (isScrollable) {
            val l = maxOf(currentViewport.left, bounds.left)
            val t = maxOf(currentViewport.top, bounds.top)
            val r = minOf(currentViewport.right, bounds.right)
            val b = minOf(currentViewport.bottom, bounds.bottom)
            Rect(l, t, r, b).apply {
                left = l
                top = t
                right = r
                bottom = b
            }
        } else {
            currentViewport
        }

        var zRank = startZRank
        val childIds = ArrayList<String>()

        // 遞迴深度與節點數量保護閥：僅在 depth < maxDepth 且 outList.size < maxNodes 時走訪子節點
        if (depth < maxDepth && outList.size < maxNodes) {
            val childCount = runCatching { node.childCount }.getOrDefault(0).coerceIn(0, (maxNodes * 2).coerceAtLeast(650))
            val childrenWithOrder = ArrayList<Pair<Int, NodeHierarchySource>>(childCount)
            for (i in 0 until childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                if (runCatching { child.isVisibleToUser }.getOrDefault(false)) {
                    val orderKey = runCatching { child.drawingOrder }.getOrDefault(0).takeIf { it > 0 } ?: i
                    childrenWithOrder.add(Pair(orderKey, child))
                }
            }
            // 逆序排列：索引/drawingOrder 越大代表越晚繪製（位於較高 Z 軸圖層）
            childrenWithOrder.sortByDescending { it.first }

            val validChildrenToTraverse = ArrayList<Pair<String, NodeHierarchySource>>(childrenWithOrder.size)
            for ((orderKey, child) in childrenWithOrder) {
                val cb = Rect()
                runCatching { child.getBoundsInScreen(cb) }
                val cbW = cb.right - cb.left
                val cbH = cb.bottom - cb.top
                if (cbW > 0 && cbH > 0) {
                    val childNodeId = allocateUniqueDeterministicNodeId(
                        node = child,
                        windowId = windowId,
                        depth = depth + 1,
                        childSlot = orderKey,
                        bounds = cb,
                        parentId = nodeId,
                        usedNodeIds = usedNodeIds
                    )
                    childIds.add(childNodeId)
                    validChildrenToTraverse.add(Pair(childNodeId, child))
                }
            }

            for ((childNodeId, child) in validChildrenToTraverse) {
                if (outList.size >= maxNodes) break
                zRank = traverseNodeReverseZ(
                    node = child,
                    windowId = windowId,
                    windowLayer = windowLayer,
                    parentId = nodeId,
                    currentViewport = childViewport,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    forceOverlayWindow = forceOverlayWindow,
                    startZRank = zRank,
                    outList = outList,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    maxNodes = maxNodes,
                    assignedNodeId = childNodeId,
                    usedNodeIds = usedNodeIds
                )
            }
        }

        if (outList.size >= maxNodes) return zRank

        val textStr = node.text?.toString()?.trim() ?: ""
        val descStr = node.contentDescription?.toString()?.trim() ?: ""
        val hintStr = node.hintText?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        val isLeaf = childIds.isEmpty()
        val isInteractiveSelf = node.isClickable || node.isLongClickable || node.isCheckable || node.isEditable
        val hasAnyTextOrDesc = textStr.isNotEmpty() || descStr.isNotEmpty() || hintStr.isNotEmpty()
        val screenArea = (screenWidth * screenHeight).toFloat().coerceAtLeast(1f)
        val nodeAreaRatio = (boundsW.toFloat() * boundsH.toFloat()) / screenArea

        // Phase 1.1: 鑑別 Jetpack Compose / 跨框架合成的無障礙 Role 空殼葉節點（不可互動、無文字、無 ID 的空殼 Button/CheckBox/Switch）
        val isControlClassLeaf = Phase1TreeImmunityConfig.isSyntheticRoleShimClass(clsName)
        val isValidControlLeaf = isControlClassLeaf && (isInteractiveSelf || hasAnyTextOrDesc || viewId.isNotEmpty())

        // 鑑別真正的圖示葉節點（排除大面積不可點擊的背景底圖 ImageView）
        val isImageClassLeaf = clsName.contains("Image", ignoreCase = true) ||
            clsName.contains("Icon", ignoreCase = true)
        val isValidImageIconLeaf = isImageClassLeaf && (
            isInteractiveSelf || hasAnyTextOrDesc || nodeAreaRatio < Phase1TreeImmunityConfig.maxIconLeafAreaRatio
        )

        val isImageOrIconLeaf = isLeaf && (isValidControlLeaf || isValidImageIconLeaf)
        val hasRenderedContent = hasAnyTextOrDesc || isImageOrIconLeaf

        // 判定是否為自繪黑盒或空洞候選（WebView / SurfaceView / Flutter / Canvas / 巨型無子節點區域）
        val isBlackBoxCandidate = (isLeaf && nodeAreaRatio >= 0.15f && textStr.isEmpty() && descStr.isEmpty()) ||
            clsName.contains("WebView", ignoreCase = true) ||
            clsName.contains("SurfaceView", ignoreCase = true) ||
            clsName.contains("TextureView", ignoreCase = true) ||
            clsName.contains("Flutter", ignoreCase = true) ||
            clsName.contains("Canvas", ignoreCase = true)

        // 判定可捲動方向向量
        val canScrollV = isScrollable && (
            clsName.contains("ScrollView", ignoreCase = true) ||
                clsName.contains("RecyclerView", ignoreCase = true) ||
                clsName.contains("ListView", ignoreCase = true) ||
                node.canScrollForwardOrBackward
            )
        val canScrollH = isScrollable && (
            clsName.contains("Horizontal", ignoreCase = true) ||
                clsName.contains("ViewPager", ignoreCase = true)
            )
        val scrollDir = when {
            canScrollV && canScrollH -> "VH"
            canScrollH -> "H"
            canScrollV -> "V"
            else -> ""
        }

        // 判斷是否為實體遮擋物（對話框卡片、底部選單、懸浮工具列、或實心互動按鈕）
        val isCardOrSheet = clsName.contains("CardView", ignoreCase = true) ||
            clsName.contains("BottomSheet", ignoreCase = true) ||
            clsName.contains("Dialog", ignoreCase = true) ||
            clsName.contains("Popup", ignoreCase = true) ||
            clsName.contains("Floating", ignoreCase = true) ||
            clsName.contains("Toolbar", ignoreCase = true) ||
            clsName.contains("AppBar", ignoreCase = true) ||
            viewId.contains("dialog", ignoreCase = true) ||
            viewId.contains("bottom_sheet", ignoreCase = true) ||
            viewId.contains("floating", ignoreCase = true)

        val isOpaque = forceOverlayWindow || isCardOrSheet || node.isClickable || hasRenderedContent

        // 列表結構簽章（供第 4 階段重複結構折疊使用）
        val listSignature = if (parentId != null && (node.isClickable || childIds.isNotEmpty())) {
            "${parentId}:${clsName.substringAfterLast('.')}:c${childIds.size}"
        } else {
            null
        }

        if (outList.size < maxNodes) {
            outList.add(
                RawVisionNode(
                    id = nodeId,
                    windowId = windowId,
                    windowLayer = windowLayer,
                    zOrderRank = zRank++,
                    className = clsName,
                    packageName = node.packageName?.toString() ?: "",
                    bounds = bounds,
                    viewportClipBounds = childViewport,
                    text = textStr,
                    contentDescription = descStr,
                    hintText = hintStr,
                    viewIdResourceName = viewId,
                    isClickable = node.isClickable,
                    isLongClickable = node.isLongClickable,
                    isCheckable = node.isCheckable,
                    isChecked = node.isChecked,
                    isSelected = node.isSelected,
                    isFocused = node.isFocused,
                    isEditable = node.isEditable,
                    isScrollable = isScrollable,
                    scrollDirections = scrollDir,
                    isEnabled = node.isEnabled,
                    isOpaqueOverlay = isOpaque,
                    isModalScrim = false,
                    isCanvasOrBlackBoxCandidate = isBlackBoxCandidate,
                    hasRenderedContent = hasRenderedContent,
                    parentId = parentId,
                    childIds = childIds,
                    listStructuralSignature = listSignature,
                    isPassword = node.isPassword,
                    inputType = node.inputType
                )
            )
        }

        return zRank
    }
}
