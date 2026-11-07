package com.example.newandroidapp.stability

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import com.example.newandroidapp.vision.RawVisionNode
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * 100% 原創自研：執行層與畫面感知抽象化暨高權限備援通道路由器 (PrivilegedExecutionChannel & ActionExecutionRouter)
 *
 * 核心職責：
 * 1. 將手勢執行 (`tap` / `swipe` / `inputText` / `keyEvent`) 與畫面感知 (`captureScreen` / `dumpUiHierarchy`) 介面抽象化。
 * 2. 預設採用無障礙服務 ([ExecutionChannelType.ACCESSIBILITY_DEFAULT])。
 * 3. 當偵測到以下任一異常阻擋狀況時，自動無縫降級切換至 [ExecutionChannelType.SHIZUKU_ADB_FALLBACK]（ADB 權限）
 *    或 [ExecutionChannelType.ROOT_SHELL_FALLBACK]（Root 底層通道）執行手勢注入與畫面/節點樹擷取：
 *    - (A) 目標畫面啟用防截圖保護 (`FLAG_SECURE`，如截圖錯誤碼或 C++ 零拷貝偵測全黑畫面)。
 *    - (B) 無障礙節點樹被刻意阻擋或回傳空樹 (`rootInActiveWindow == null` 或有效節點數為 0)。
 *    - (C) 無障礙手勢注入失效 (`dispatchGesture` 回傳 false 或觸發 `onCancelled`)。
 */
enum class ExecutionChannelType(val code: String, val displayLabel: String) {
    ACCESSIBILITY_DEFAULT("ACCESSIBILITY_DEFAULT", "預設無障礙服務通道"),
    SHIZUKU_ADB_FALLBACK("SHIZUKU_ADB_FALLBACK", "Shizuku (ADB 權限) 備援通道"),
    ROOT_SHELL_FALLBACK("ROOT_SHELL_FALLBACK", "Root 底層 Shell 備援通道")
}

enum class FallbackTriggerReason(val code: String, val description: String) {
    NONE("NONE", "常規無障礙通道正常運作"),
    FLAG_SECURE_PROTECTED("FLAG_SECURE_PROTECTED", "目標畫面啟用防截圖保護 (FLAG_SECURE)"),
    ACCESSIBILITY_TREE_BLOCKED("ACCESSIBILITY_TREE_BLOCKED", "無障礙節點樹被刻意阻擋或為空"),
    GESTURE_INJECTION_FAILED("GESTURE_INJECTION_FAILED", "無障礙手勢注入失效或被攔截")
}

interface ExecutionAndPerceptionChannel {
    val channelType: ExecutionChannelType
    fun isChannelAvailable(context: Context? = null): Boolean
    fun performTap(x: Int, y: Int, service: AccessibilityService? = null): Boolean
    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, service: AccessibilityService? = null): Boolean
    fun performInputText(text: String, service: AccessibilityService? = null): Boolean
    fun performGlobalKey(keyCode: Int, service: AccessibilityService? = null): Boolean
    fun dumpBlockedUiTreeFallback(screenWidth: Int, screenHeight: Int): List<RawVisionNode>
}

/**
 * 通道 1：預設無障礙服務執行通道 (AccessibilityService Default Channel)
 */
object AccessibilityDefaultChannel : ExecutionAndPerceptionChannel {
    override val channelType: ExecutionChannelType = ExecutionChannelType.ACCESSIBILITY_DEFAULT

    override fun isChannelAvailable(context: Context?): Boolean = true

    override fun performTap(x: Int, y: Int, service: AccessibilityService?): Boolean {
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return runCatching {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    override fun performSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
        service: AccessibilityService?
    ): Boolean {
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return runCatching {
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val safeDur = durationMs.coerceIn(80L, 1200L)
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDur)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    override fun performInputText(text: String, service: AccessibilityService?): Boolean {
        return service != null && text.isNotEmpty()
    }

    override fun performGlobalKey(keyCode: Int, service: AccessibilityService?): Boolean {
        if (service == null) return false
        return runCatching {
            service.performGlobalAction(keyCode)
        }.getOrDefault(false)
    }

    override fun dumpBlockedUiTreeFallback(screenWidth: Int, screenHeight: Int): List<RawVisionNode> {
        return emptyList()
    }
}

/**
 * 通道 2：Shizuku (ADB 權限) 備援執行與感知通道
 */
object ShizukuAdbFallbackChannel : ExecutionAndPerceptionChannel {
    override val channelType: ExecutionChannelType = ExecutionChannelType.SHIZUKU_ADB_FALLBACK

    @Volatile
    internal var mockAvailableForTest: Boolean? = null

    @Volatile
    internal var lastExecutedCommand: String = ""
        private set

    override fun isChannelAvailable(context: Context?): Boolean {
        mockAvailableForTest?.let { return it }
        // 1. 反射檢查 Shizuku Binder API 是否已授權且存活
        val shizukuAlive = runCatching {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val pingMethod = cls.getMethod("pingBinder")
            val alive = pingMethod.invoke(null) as? Boolean ?: false
            if (!alive) return@runCatching false
            val checkPerm = cls.getMethod("checkSelfPermission")
            val perm = checkPerm.invoke(null) as? Int ?: -1
            perm == 0
        }.getOrDefault(false)
        if (shizukuAlive) return true

        // 2. 檢查當前進程是否具備 shell / ADB 權限特徵
        return runCatching {
            val uid = android.os.Process.myUid()
            uid == 2000 || uid == 0
        }.getOrDefault(false)
    }

    override fun performTap(x: Int, y: Int, service: AccessibilityService?): Boolean {
        val cmd = "input tap $x $y"
        lastExecutedCommand = cmd
        if (mockAvailableForTest == true) return true
        return executeShizukuOrShellCommand(cmd)
    }

    override fun performSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
        service: AccessibilityService?
    ): Boolean {
        val cmd = "input swipe $x1 $y1 $x2 $y2 ${max(80L, durationMs)}"
        lastExecutedCommand = cmd
        if (mockAvailableForTest == true) return true
        return executeShizukuOrShellCommand(cmd)
    }

    override fun performInputText(text: String, service: AccessibilityService?): Boolean {
        val escaped = sanitizeShellInputText(text)
        val cmd = "input text '$escaped'"
        lastExecutedCommand = cmd
        if (mockAvailableForTest == true) return true
        return executeShizukuOrShellCommand(cmd)
    }

    override fun performGlobalKey(keyCode: Int, service: AccessibilityService?): Boolean {
        val cmd = "input keyevent $keyCode"
        lastExecutedCommand = cmd
        if (mockAvailableForTest == true) return true
        return executeShizukuOrShellCommand(cmd)
    }

    override fun dumpBlockedUiTreeFallback(screenWidth: Int, screenHeight: Int): List<RawVisionNode> {
        val cmd = "uiautomator dump /data/local/tmp/ai_shizuku_uidump.xml"
        lastExecutedCommand = cmd
        if (mockAvailableForTest == true) {
            return buildSyntheticFallbackNodes("shizuku_adb", screenWidth, screenHeight)
        }
        if (!executeShizukuOrShellCommand(cmd)) return emptyList()
        return parseUiAutomatorDumpXml(File("/data/local/tmp/ai_shizuku_uidump.xml"))
    }

    private fun executeShizukuOrShellCommand(command: String): Boolean {
        // 優先嘗試透過 Shizuku.newProcess 執行高權限指令
        val viaShizuku = runCatching {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val newProc = cls.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProc.isAccessible = true
            val proc = newProc.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
            val ok = proc?.waitFor(2500L, TimeUnit.MILLISECONDS) ?: false
            ok && (proc?.exitValue() == 0)
        }.getOrDefault(false)
        if (viaShizuku) return true

        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val ok = proc.waitFor(2000L, TimeUnit.MILLISECONDS)
            ok && proc.exitValue() == 0
        }.getOrDefault(false)
    }
}

/**
 * 通道 3：Root 底層 Shell (`su -c`) 備援執行與感知通道
 */
object RootShellFallbackChannel : ExecutionAndPerceptionChannel {
    override val channelType: ExecutionChannelType = ExecutionChannelType.ROOT_SHELL_FALLBACK

    @Volatile
    internal var mockAvailableForTest: Boolean? = null

    @Volatile
    internal var lastExecutedCommand: String = ""
        private set

    override fun isChannelAvailable(context: Context?): Boolean {
        mockAvailableForTest?.let { return it }
        return runCatching {
            val suPaths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/Magisk/su")
            suPaths.any { File(it).exists() }
        }.getOrDefault(false)
    }

    override fun performTap(x: Int, y: Int, service: AccessibilityService?): Boolean {
        val cmd = "input tap $x $y"
        lastExecutedCommand = cmd
        if (mockAvailableForTest == true) return true
        return executeRootCommand(cmd)
    }

    override fun performSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
        service: AccessibilityService?
    ): Boolean {
        val cmd = "input touchscreen swipe $x1 $y1 $x2 $y2 ${max(80L, durationMs)}"
        lastExecutedCommand = cmd
        if (mockAvailableForTest == true) return true
        return executeRootCommand(cmd)
    }

    override fun performInputText(text: String, service: AccessibilityService?): Boolean {
        val escaped = sanitizeShellInputText(text)
        val cmd = "input text '$escaped'"
        lastExecutedCommand = cmd
        if (mockAvailableForTest == true) return true
        return executeRootCommand(cmd)
    }

    override fun performGlobalKey(keyCode: Int, service: AccessibilityService?): Boolean {
        val cmd = "input keyevent $keyCode"
        lastExecutedCommand = cmd
        if (mockAvailableForTest == true) return true
        return executeRootCommand(cmd)
    }

    override fun dumpBlockedUiTreeFallback(screenWidth: Int, screenHeight: Int): List<RawVisionNode> {
        val cmd = "uiautomator dump /data/local/tmp/ai_root_uidump.xml"
        lastExecutedCommand = cmd
        if (mockAvailableForTest == true) {
            return buildSyntheticFallbackNodes("root_su", screenWidth, screenHeight)
        }
        if (!executeRootCommand(cmd)) return emptyList()
        return parseUiAutomatorDumpXml(File("/data/local/tmp/ai_root_uidump.xml"))
    }

    private fun executeRootCommand(command: String): Boolean {
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val finished = proc.waitFor(2500L, TimeUnit.MILLISECONDS)
            finished && proc.exitValue() == 0
        }.getOrDefault(false)
    }
}

/**
 * 執行與感知路由器 (ActionExecutionRouter)：
 * 預設走 AccessibilityService；當偵測到 FLAG_SECURE、空無障礙樹或手勢注入失敗時，自動切換至 Shizuku 或 Root 備援通道。
 */
object ActionExecutionRouter {

    @Volatile
    var activeChannelType: ExecutionChannelType = ExecutionChannelType.ACCESSIBILITY_DEFAULT
        private set

    @Volatile
    var lastFallbackReason: FallbackTriggerReason = FallbackTriggerReason.NONE
        private set

    private val fallbackSwitchCounter = AtomicInteger(0)

    val fallbackSwitchCount: Int
        get() = fallbackSwitchCounter.get()

    @Synchronized
    fun resetToDefault() {
        activeChannelType = ExecutionChannelType.ACCESSIBILITY_DEFAULT
        lastFallbackReason = FallbackTriggerReason.NONE
        fallbackSwitchCounter.set(0)
        ShizukuAdbFallbackChannel.mockAvailableForTest = null
        RootShellFallbackChannel.mockAvailableForTest = null
    }

    /**
     * 供單元測試配置 Shizuku / Root 模擬可用性
     */
    @Synchronized
    fun configureMockPrivilegedChannelsForTest(shizukuAvailable: Boolean?, rootAvailable: Boolean?) {
        ShizukuAdbFallbackChannel.mockAvailableForTest = shizukuAvailable
        RootShellFallbackChannel.mockAvailableForTest = rootAvailable
    }

    fun resolveActiveChannel(context: Context? = null): ExecutionAndPerceptionChannel {
        return when (activeChannelType) {
            ExecutionChannelType.SHIZUKU_ADB_FALLBACK ->
                if (ShizukuAdbFallbackChannel.isChannelAvailable(context)) ShizukuAdbFallbackChannel else AccessibilityDefaultChannel
            ExecutionChannelType.ROOT_SHELL_FALLBACK ->
                if (RootShellFallbackChannel.isChannelAvailable(context)) RootShellFallbackChannel else AccessibilityDefaultChannel
            ExecutionChannelType.ACCESSIBILITY_DEFAULT -> AccessibilityDefaultChannel
        }
    }

    /**
     * 當偵測到 FLAG_SECURE 防截圖保護、無障礙樹被阻擋、或手勢注入失敗時，自動無縫切換至高權限備援通道
     */
    @Synchronized
    fun onAnomalyDetectedSwitchToFallback(
        reason: FallbackTriggerReason,
        context: Context? = null
    ): ExecutionAndPerceptionChannel {
        lastFallbackReason = reason
        fallbackSwitchCounter.incrementAndGet()
        val targetChannel: ExecutionAndPerceptionChannel = when {
            ShizukuAdbFallbackChannel.isChannelAvailable(context) -> ShizukuAdbFallbackChannel
            RootShellFallbackChannel.isChannelAvailable(context) -> RootShellFallbackChannel
            else -> AccessibilityDefaultChannel
        }
        if (targetChannel.channelType != ExecutionChannelType.ACCESSIBILITY_DEFAULT) {
            activeChannelType = targetChannel.channelType
        }
        return targetChannel
    }

    /**
     * 當切換至正常畫面且無障礙服務恢復時，自動回歸預設無障礙通道
     */
    @Synchronized
    fun restoreDefaultAccessibilityChannelIfHealthy(hasValidAccessibilityNodes: Boolean, isSecureWindow: Boolean) {
        if (hasValidAccessibilityNodes && !isSecureWindow && activeChannelType != ExecutionChannelType.ACCESSIBILITY_DEFAULT) {
            activeChannelType = ExecutionChannelType.ACCESSIBILITY_DEFAULT
            lastFallbackReason = FallbackTriggerReason.NONE
        }
    }

    fun parseUiAutomatorDumpXml(
        rawXml: String,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): List<RawVisionNode> {
        return parseUiAutomatorDumpXmlText(rawXml, screenWidth, screenHeight)
    }

    /**
     * 執行點擊手勢：預設透過無障礙服務，若無障礙注入失敗或當前處於高權限備援模式則自動由 Shizuku / Root 執行
     */
    fun routeTap(
        x: Int,
        y: Int,
        service: AccessibilityService? = null,
        context: Context? = null,
        accessibilitySucceededHint: Boolean? = null
    ): Boolean {
        if (activeChannelType != ExecutionChannelType.ACCESSIBILITY_DEFAULT) {
            val ch = resolveActiveChannel(context)
            if (ch.performTap(x, y, service)) return true
        }
        val a11yOk = accessibilitySucceededHint ?: AccessibilityDefaultChannel.performTap(x, y, service)
        if (a11yOk) return true

        val fallback = onAnomalyDetectedSwitchToFallback(FallbackTriggerReason.GESTURE_INJECTION_FAILED, context)
        return fallback.performTap(x, y, service)
    }

    /**
     * 執行滑動手勢：預設透過無障礙服務，失敗時自動降級至 Shizuku / Root
     */
    fun routeSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
        service: AccessibilityService? = null,
        context: Context? = null,
        accessibilitySucceededHint: Boolean? = null
    ): Boolean {
        if (activeChannelType != ExecutionChannelType.ACCESSIBILITY_DEFAULT) {
            val ch = resolveActiveChannel(context)
            if (ch.performSwipe(x1, y1, x2, y2, durationMs, service)) return true
        }
        val a11yOk = accessibilitySucceededHint ?: AccessibilityDefaultChannel.performSwipe(x1, y1, x2, y2, durationMs, service)
        if (a11yOk) return true

        val fallback = onAnomalyDetectedSwitchToFallback(FallbackTriggerReason.GESTURE_INJECTION_FAILED, context)
        return fallback.performSwipe(x1, y1, x2, y2, durationMs, service)
    }

    /**
     * 當無障礙節點樹被刻意阻擋（空樹）或啟用 FLAG_SECURE 時，透過 Shizuku / Root 擷取備援節點樹
     */
    fun routeBlockedTreePerception(
        isFlagSecure: Boolean,
        isTreeEmptyOrBlocked: Boolean,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        context: Context? = null
    ): List<RawVisionNode> {
        if (!isFlagSecure && !isTreeEmptyOrBlocked) return emptyList()
        val reason = if (isFlagSecure) {
            FallbackTriggerReason.FLAG_SECURE_PROTECTED
        } else {
            FallbackTriggerReason.ACCESSIBILITY_TREE_BLOCKED
        }
        val fallback = onAnomalyDetectedSwitchToFallback(reason, context)
        return fallback.dumpBlockedUiTreeFallback(screenWidth, screenHeight)
    }

    fun getStatusBadge(): String {
        return "[執行通道:${activeChannelType.code}(備援切換=${fallbackSwitchCount}次, 原因=${lastFallbackReason.code})]"
    }
}

private fun sanitizeShellInputText(raw: String): String {
    return raw.replace("'", "")
        .replace("\"", "")
        .replace(";", "")
        .replace("&", "")
        .replace("|", "")
        .replace("`", "")
        .replace(" ", "%s")
        .trim()
}

private fun buildSyntheticFallbackNodes(
    sourceTag: String,
    screenWidth: Int,
    screenHeight: Int
): List<RawVisionNode> {
    val cx = screenWidth / 2
    val cy = screenHeight / 2
    return listOf(
        RawVisionNode(
            id = "${sourceTag}_fallback_search",
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 0,
            className = "android.widget.EditText",
            packageName = "privileged.fallback",
            bounds = Rect(80, 120, screenWidth - 80, 240),
            text = "搜尋",
            contentDescription = "高權限通道還原搜尋框",
            viewIdResourceName = "id/${sourceTag}_search_box",
            isClickable = true,
            isEditable = true,
            isScrollable = false,
            isCheckable = false,
            isChecked = false
        ),
        RawVisionNode(
            id = "${sourceTag}_fallback_confirm",
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 1,
            className = "android.widget.Button",
            packageName = "privileged.fallback",
            bounds = Rect(cx - 160, cy, cx + 160, cy + 120),
            text = "確認",
            contentDescription = "高權限通道還原確認鈕",
            viewIdResourceName = "id/${sourceTag}_confirm_btn",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isChecked = false
        )
    )
}

internal fun parseUiAutomatorDumpXml(xmlFile: File): List<RawVisionNode> {
    if (!xmlFile.exists() || !xmlFile.canRead()) return emptyList()
    return runCatching {
        parseUiAutomatorDumpXmlText(xmlFile.readText(Charsets.UTF_8))
    }.getOrDefault(emptyList())
}

internal fun parseUiAutomatorDumpXmlText(
    rawXml: String,
    @Suppress("UNUSED_PARAMETER") screenWidth: Int = 1080,
    @Suppress("UNUSED_PARAMETER") screenHeight: Int = 2400
): List<RawVisionNode> {
    if (rawXml.isBlank()) return emptyList()
    return runCatching {
        val nodeRegex = Regex("""<node\s+([^>]+?)/?>""")
        val attrRegex = Regex("""(\w[\w-]*)="([^"]*)"""")
        val boundsRegex = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
        val out = ArrayList<RawVisionNode>()
        var idx = 0

        for (match in nodeRegex.findAll(rawXml)) {
            val attrBlock = match.groupValues[1]
            val attrs = HashMap<String, String>()
            for (am in attrRegex.findAll(attrBlock)) {
                attrs[am.groupValues[1]] = am.groupValues[2]
            }
            val text = attrs["text"].orEmpty()
            val desc = attrs["content-desc"].orEmpty()
            val resId = attrs["resource-id"].orEmpty()
            val cls = attrs["class"].orEmpty().ifBlank { "android.view.View" }
            val pkg = attrs["package"].orEmpty()
            val clickable = attrs["clickable"] == "true"
            val checkable = attrs["checkable"] == "true"
            val checked = attrs["checked"] == "true"
            val scrollable = attrs["scrollable"] == "true"
            val boundsStr = attrs["bounds"].orEmpty()
            val bm = boundsRegex.find(boundsStr) ?: continue
            val l = bm.groupValues[1].toIntOrNull() ?: 0
            val t = bm.groupValues[2].toIntOrNull() ?: 0
            val r = bm.groupValues[3].toIntOrNull() ?: 0
            val b = bm.groupValues[4].toIntOrNull() ?: 0
            if (r <= l || b <= t) continue
            if (!clickable && !scrollable && !checkable && text.isBlank() && desc.isBlank()) continue

            idx++
            out.add(
                RawVisionNode(
                    id = "uidump_$idx",
                    windowId = 1,
                    windowLayer = 0,
                    zOrderRank = idx,
                    className = cls,
                    packageName = pkg,
                    bounds = Rect(l, t, r, b).apply {
                        left = l
                        top = t
                        right = r
                        bottom = b
                    },
                    text = text,
                    contentDescription = desc,
                    viewIdResourceName = resId,
                    isClickable = clickable,
                    isEditable = cls.contains("EditText", ignoreCase = true),
                    isScrollable = scrollable,
                    isCheckable = checkable,
                    isChecked = checked
                )
            )
        }
        out
    }.getOrDefault(emptyList())
}
