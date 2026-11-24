package com.wae.agent.gen5

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.wae.core.frame.KindDeriver
import com.wae.core.frame.KindFeatures
import com.wae.core.model.Channel
import com.wae.core.model.Control
import com.wae.core.model.ControlState
import com.wae.core.model.Evidence
import com.wae.core.model.Frame
import com.wae.core.model.FrameFingerprint
import com.wae.core.model.RectI
import com.wae.core.pipeline.FrameSource
import com.wae.core.util.TextHash

/**
 * 無障礙樹（＋稀疏時的 OCR）→ core `Frame`（S2/S3/B1）。
 *
 * 分工：節點走訪在這一層，型別判斷交給 `KindDeriver`（core，可測），
 * 指紋交給 `FrameFingerprint`（core，決定性）。
 */
class A11yFrameSource(
    private val service: AccessibilityService,
    private val context: Context,
    private val ocr: OcrChannel? = null,
    private val icons: IconChannel? = null,
    private val ocrA11yThreshold: Int = DEFAULT_OCR_THRESHOLD
) : FrameSource {

    override fun capture(): Frame {
        val started = System.currentTimeMillis()
        NodeRegistry.clear()

        val root = service.rootInActiveWindow ?: return Frame.EMPTY

        val pkg = root.packageName?.toString().orEmpty()
        val controls = ArrayList<Control>(64)
        // 先走「上層彈出視窗」（選單、對話框、下拉）：這些是獨立視窗，
        // rootInActiveWindow 只會拿到下面那個主視窗。
        // 實測（2026-11-23）：Chrome 的三點選單打開後，擷取只看得到工具列，
        // 「歷史紀錄」明明在畫面上卻找不到。
        val popupRoots = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
        com.wae.core.util.Degrade.orNull("frame:windows") {
            val activeId = root.windowId
            service.windows?.asReversed()?.forEach { w ->
                if (w.id != activeId && w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    w.root?.let { popupRoots.add(it) }
                }
            }
        }
        for (popup in popupRoots) {
            // layer=0：這一層是使用者眼前看到的彈出層（選單／對話框）
            walk(popup, controls, depth = 0, counter = intArrayOf(0), layer = 0)
        }
        val popupCount = controls.size
        walk(root, controls, depth = 0, counter = intArrayOf(0), layer = 1)
        if (popupCount > 0) {
            // 彈出視窗的元素排在前面：比對時 firstOrNull 會先看到選單項目
            android.util.Log.i("WAE_WINDOWS", "popup controls=$popupCount total=${controls.size}")
        }

        // 區域式 OCR：只在「缺資訊的地方」辨識，而不是整頁。
        // 觸發條件：節點太少（Canvas／Flutter），或這一幀有彈出視窗（選單項目常常不在 a11y 裡）。
        if ((controls.size < ocrA11yThreshold || popupCount > 0) && ocr != null && ocr.isAvailable()) {
            val dm = context.resources.displayMetrics
            val regions = com.wae.core.screen.OcrRegionPlanner.plan(
                controls = controls,
                popupBounds = popupRoots.mapNotNull { r ->
                    val rect = android.graphics.Rect()
                    r.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        com.wae.core.screen.OcrRegionPlanner.Region(
                            rect.left, rect.top, rect.right, rect.bottom
                        )
                    } else null
                },
                screenWidth = dm.widthPixels,
                screenHeight = dm.heightPixels,
                expandPx = com.wae.agent.core.runtime.Tunables.getInt(context, "ocr.region_expand_px", 48),
                minArea = com.wae.agent.core.runtime.Tunables.getInt(context, "ocr.region_min_area", 2000),
                maxRegions = com.wae.agent.core.runtime.Tunables.getInt(context, "ocr.region_max", 4)
            )
            var added = 0
            if (regions.isEmpty()) {
                // 沒有任何可疑區域 → 退化成整頁（保持舊行為，避免「什麼都沒看」）
                val ocrControls = ocr.capture()
                if (ocrControls.isNotEmpty()) { controls.addAll(ocrControls); added = ocrControls.size }
            } else {
                for (region in regions) {
                    val rect = android.graphics.Rect(region.left, region.top, region.right, region.bottom)
                    val ocrControls = ocr.capture(region = rect)
                    if (ocrControls.isNotEmpty()) { controls.addAll(ocrControls); added += ocrControls.size }
                }
                if (added > 0) {
                    android.util.Log.i("WAE_OCR", "region ocr added=$added regions=${regions.size}")
                }
            }
        }

        // A4：有節點但幾乎都沒有文字（相機、工具列）→ 補圖示候選
        val labeled = controls.count { it.label.isNotEmpty() }
        val forceIcons = runCatching {
            java.io.File(context.filesDir, "vision_debug_always").exists()
        }.getOrDefault(false)
        if (icons != null && icons.isAvailable() && (forceIcons || labeled < ICON_LABELED_THRESHOLD)) {
            val iconControls = icons.capture()
            if (iconControls.isNotEmpty()) {
                controls.addAll(iconControls)
            }
        }

        val version = versionCodeOf(pkg)
        val costMs = System.currentTimeMillis() - started
        // 1) 圖示 → 文字：把「已知圖示」的入口補上文字（設定頁的 Wi-Fi 就是一顆圖示）
        com.wae.agent.core.predicate.IconTextConverter.ensureLoaded(context)
        val withIcons = controls.map { com.wae.agent.core.predicate.IconTextConverter.convert(it) }
        // 2) 子元素文字繼承：圖示本身沒文字、但被同一列的文字包住時，把文字拉上來
        val withLabels = inheritLabelsFromChildren(withIcons)
        return Frame(
            fp = FrameFingerprint.of(pkg, version, withLabels),
            pkg = pkg,
            appVersion = version,
            controls = withLabels,
            evidence = Evidence(setOf(Channel.A11Y), costMs = costMs),
            atMs = started
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<Control>,
        depth: Int,
        counter: IntArray,
        layer: Int = 1
    ) {
        if (depth > MAX_DEPTH || counter[0] >= MAX_NODES) return
        counter[0]++

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val visible = node.isVisibleToUser && rect.width() > 0 && rect.height() > 0

        // 只收「完全在螢幕內」的元素：負座標或超出邊界的是別頁，點了只會亂跑
        val dm = context.resources.displayMetrics
        val onScreen = rect.left >= 0 && rect.top >= 0 &&
            rect.right <= dm.widthPixels && rect.bottom <= dm.heightPixels
        if (visible && onScreen) {
            val key = keyOf(node, rect)
            NodeRegistry.put(key, node)
            out.add(controlOf(node, key, rect, layer))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, depth + 1, counter)
        }
    }

    /**
     * 讓「沒有文字的元素」繼承**被它包住的文字**。
     *
     * 典型情況：一列可點的容器裡有「圖示 + 標題 + 說明」，容器本身 `text` 是空的。
     * 取面積最小的子文字（最貼近的那一個），才不會把整列的說明文字當成標題。
     */
    private fun inheritLabelsFromChildren(controls: List<Control>): List<Control> {
        if (controls.isEmpty()) return controls
        val labeled = controls.filter { it.label.isNotBlank() }
        if (labeled.isEmpty()) return controls
        return controls.map { c ->
            // 容器（FrameLayout/LinearLayout…）不繼承：否則整個畫面會變成「連接」並被點擊
            if (c.label.isNotBlank() || isContainer(c.classHint)) c
            else {
                val inner = labeled
                    .filter { o ->
                        o.key != c.key &&
                            o.bounds.left >= c.bounds.left && o.bounds.top >= c.bounds.top &&
                            o.bounds.right <= c.bounds.right && o.bounds.bottom <= c.bounds.bottom
                    }
                    .minByOrNull { it.bounds.area }
                if (inner == null) c
                else c.copy(
                    label = inner.label,
                    textHash = inner.textHash,
                    state = c.state.copy(text = inner.label)
                )
            }
        }
    }

    /** 這些是版面容器，不是可點的目標，不該從子元素繼承文字。 */
    private fun isContainer(classHint: String): Boolean {
        val c = classHint.substringAfterLast('.')
        return c == "FrameLayout" || c == "LinearLayout" || c == "RelativeLayout" ||
            c == "ViewGroup" || c == "RecyclerView" || c == "ViewPager" ||
            c == "ScrollView" || c == "NestedScrollView" || c == "ConstraintLayout"
    }

    /** 往上找 4 層，看有沒有可點的祖先（清單列很常見：文字在子節點、可點在父節點）。 */
    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var cur = node.parent
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun controlOf(node: AccessibilityNodeInfo, key: String, rect: Rect, layer: Int = 1): Control {
        val text = node.text?.toString().orEmpty()
            .ifEmpty { node.contentDescription?.toString().orEmpty() }
        val cls = node.className?.toString().orEmpty()

        val kind = KindDeriver.derive(
            KindFeatures(
                editable = node.isEditable,
                hasRange = node.rangeInfo != null,
                checkable = node.isCheckable,
                selected = node.isSelected,
                clickable = node.isClickable,
                scrollable = node.isScrollable,
                classHint = cls,
                label = text
            )
        )

        return Control(
            key = key,
            kind = kind,
            label = text,
            textHash = if (text.isEmpty()) "" else TextHash.of(text),
            bounds = RectI(rect.left, rect.top, rect.right, rect.bottom),
            enabled = node.isEnabled,
            // 「可互動」＝可點、或祖先可點、或可聚焦。
            // 為什麼要算 focusable：很多自訂頁籤是 clickable=false + focusable=true
            // （實測三星時鐘的「鬧鐘／世界時鐘」頁籤），只認 clickable 會讓頁籤整個消失。
            clickable = node.isClickable || hasClickableAncestor(node) || node.isFocusable,
            scrollable = node.isScrollable,
            classHint = cls,
            state = ControlState(
                on = if (node.isCheckable) node.isChecked else null,
                checked = if (node.isCheckable) node.isChecked else null,
                selected = if (node.isSelected) true else null,
                text = text.ifEmpty { null }
            ),
            source = Channel.A11Y,
            viewId = node.viewIdResourceName?.toString().orEmpty(),
            layer = layer,
            inWeb = isInWebContent(node)
        )
    }

    private fun isInWebContent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 12) {
            val cls = cur.className?.toString().orEmpty()
            if (cls.contains("WebView", ignoreCase = true) ||
                cls.contains("webview", ignoreCase = true)
            ) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    /**
     * 產生**同一畫面上唯一**的元素鍵。
     *
     * 為什麼不能只用 view id：清單裡每一列的 `viewIdResourceName` 都一樣
     * （例如設定頁每列都是 `android:id/title`），
     * `NodeRegistry.put(key, node)` 會後寫覆蓋前寫，之後 `get(key)` 取到的是**別的元素**。
     * 實測後果：說「開 WiFi」，卻去點了同一 key 的最後一筆（Wi-Fi 清單裡的電台按鈕）。
     *
     * 解法：view id + 左上角座標。同一個畫面內唯一，且同一畫面跨幀穩定
     * （不像序號會在元素增減時整體位移）。
     */
    private fun keyOf(node: AccessibilityNodeInfo, rect: Rect): String {
        val id = node.viewIdResourceName
        val pos = "${rect.left},${rect.top}"
        if (!id.isNullOrEmpty()) return "id:$id@$pos"
        val cls = node.className?.toString().orEmpty()
        return "cls:$cls|${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    private fun versionCodeOf(pkg: String): Long {
        if (pkg.isEmpty()) return 0L
        return runCatching {
            val info = context.packageManager.getPackageInfo(pkg, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)
    }

    private companion object {
        const val MAX_DEPTH = 24
        const val MAX_NODES = 300
        const val DEFAULT_OCR_THRESHOLD = 6
        /** 少於這個數量的「有文字元素」就補圖示（相機、純圖工具列）。 */
        const val ICON_LABELED_THRESHOLD = 5
    }
}
