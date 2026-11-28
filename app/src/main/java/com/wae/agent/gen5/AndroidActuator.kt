package com.wae.agent.gen5

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.media.AudioManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.wae.core.exec.Actuator
import com.wae.core.exec.RawResult
import com.wae.core.model.GestureKind
import com.wae.core.model.Means

/**
 * Gen-5 的執行者（I1）。
 *
 * 這個類別只負責「把 Effect 的意圖翻成動作」；真正碰裝置的原始操作
 * （節點動作、手勢、意圖）都收在 `DeviceIO` —— 全 App 唯一的最低層。
 * 也就是說：這裡不再直接送出任何手勢或節點動作，只決定「要做什麼」。
 */
class AndroidActuator(
    private val service: AccessibilityService,
    private val context: Context
) : Actuator {

    /** 這個任務最近一次成功開啟的 App（搜尋意圖要用它，而不是「現在的前景」）。 */
    private var lastTargetPkg: String? = null

    override fun supports(means: Means): Boolean = when (means) {
        is Means.A11y -> NodeRegistry.get(means.key) != null
        is Means.Input -> true
        is Means.Gesture -> true
        is Means.SystemApi -> true
        is Means.Intent -> true
        is Means.Teach -> false
    }

    override fun run(means: Means): RawResult = when (means) {
        is Means.A11y -> runA11y(means)
        is Means.Input -> runInput(means)
        is Means.Gesture -> runGesture(means)
        is Means.SystemApi -> runSystem(means)
        is Means.Intent -> runIntent(means)
        is Means.Teach -> RawResult.noEffect("teach_not_implemented")
    }

    // ── 無障礙動作 ─────────────────────────────────────────────────────

    private fun runA11y(means: Means.A11y): RawResult {
        val node = NodeRegistry.get(means.key) ?: return RawResult.noEffect("node_gone")
        return when (means.action) {
            ACTION_CLICK -> clickNode(node)
            ACTION_FOCUS -> if (DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_FOCUS)) {
                RawResult.applied("focus")
            } else {
                RawResult.noEffect("focus_failed")
            }
            else -> RawResult.noEffect("unknown_action")
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): RawResult {
        if (node.isClickable && DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_CLICK)) {
            return RawResult.applied("click")
        }
        // 往上找可點的祖先（最多 CLICK_ANCESTOR_DEPTH 層）
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < CLICK_ANCESTOR_DEPTH) {
            if (parent.isClickable && DeviceIO.perform(parent, AccessibilityNodeInfo.ACTION_CLICK)) {
                return RawResult.applied("click_ancestor")
            }
            parent = parent.parent
            depth++
        }
        return RawResult.noEffect("not_clickable")
    }

    // ── 輸入 ─────────────────────────────────────────────────────────

    private fun runInput(means: Means.Input): RawResult {
        val focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: NodeRegistry.firstOrNull { it.isEditable }
            ?: return RawResult.noEffect("no_input_field")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, means.text)
        }
        if (DeviceIO.perform(focus, AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            // 讀回確認：有些欄位（例如設定的搜尋列）SET_TEXT 回 true 但根本沒吃字。
            // 沒有讀回就會變成「回報成功、畫面空白」的假成功。
            if (readBackContains(focus, means.text)) return RawResult.applied("set_text")
        }
        // 有些自繪欄位不吃 SET_TEXT：退回「剪貼簿 + 貼上」（B5）
        return pasteViaClipboard(focus, means.text)
    }

    /** 文字真的進去了才算數（設定搜尋列實測會靜默忽略 SET_TEXT）。 */
    private fun readBackContains(node: AccessibilityNodeInfo, expected: String): Boolean {
        val now = com.wae.core.util.Degrade.orElse("actuator:readNodeText", "") { node.text?.toString() ?: "" }
        if (now.isEmpty()) return false
        return now.contains(expected)
    }

    private fun pasteViaClipboard(focus: AccessibilityNodeInfo, text: String): RawResult {
        if (text.isEmpty()) return RawResult.noEffect("empty_text")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return RawResult.noEffect("no_clipboard")
        return com.wae.core.util.Degrade.orElse("actuator:paste", RawResult.noEffect("paste_error")) {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            if (DeviceIO.perform(focus, AccessibilityNodeInfo.ACTION_PASTE)) {
                if (readBackContains(focus, text)) {
                    RawResult.applied("paste")
                } else {
                    RawResult.noEffect("paste_ignored")
                }
            } else {
                RawResult.noEffect("paste_failed")
            }
        }
    }

    // ── 手勢 ─────────────────────────────────────────────────────────

    private fun runGesture(means: Means.Gesture): RawResult {
        // 座標保護：無障礙偶爾回負座標（例如 [-8,-8][1080,2340]），
        // GestureDescription 會直接丟 IllegalArgumentException 讓整個任務失敗。
        val x = means.at.x.coerceAtLeast(0)
        val y = means.at.y.coerceAtLeast(0)
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            if (means.gesture == GestureKind.SWIPE_UP) {
                lineTo(x.toFloat(), (y - SWIPE_PIXELS).coerceAtLeast(0f))
            }
            if (means.gesture == GestureKind.SWIPE_DOWN) {
                lineTo(x.toFloat(), y + SWIPE_PIXELS)
            }
        }
        val duration = if (means.gesture == GestureKind.LONG_PRESS) LONG_PRESS_MS else TAP_MS
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = DeviceIO.dispatch(service, gesture)
        return if (ok) RawResult.applied("gesture") else RawResult.noEffect("dispatch_failed")
    }

    // ── 系統 API ─────────────────────────────────────────────────────

    private fun openImagePicker(): RawResult {
        return com.wae.core.util.Degrade.orElse("actuator:picker", RawResult.noEffect("picker_failed")) {
            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            RawResult.applied("picker_opened")
        }
    }

    private fun runSystem(means: Means.SystemApi): RawResult {
        // 選檔器（D15）：只打開、不代選——選哪張照片是人的決定。
        if (means.api.startsWith("picker:")) {
            return openImagePicker()
        }
        val api = means.api
        return when {
            api.startsWith(API_VOLUME) -> adjustVolume(api.removePrefix(API_VOLUME))
            api == API_HOME -> {
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                if (ok) RawResult.applied("home") else RawResult.noEffect("home_failed")
            }
            api == API_BACK -> {
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                if (ok) RawResult.applied("back") else RawResult.noEffect("back_failed")
            }
            api == API_IME_ENTER -> {
                val focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                if (focus != null && DeviceIO.perform(focus, imeEnter.id)) {
                    RawResult.applied("ime_enter")
                } else {
                    RawResult.noEffect("ime_enter_failed")
                }
            }
            api.startsWith(API_MEDIA) -> RawResult.noEffect("media_not_implemented")
            api.startsWith(API_GLOBAL) -> {
                val key = api.removePrefix(API_GLOBAL)
                val action = when (key) {
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    else -> return RawResult.noEffect("unknown_global:$key")
                }
                return if (service.performGlobalAction(action)) RawResult.applied("global:$key")
                else RawResult.noEffect("global_failed:$key")
            }
            api.startsWith(API_PANEL) -> {
                val key = api.removePrefix(API_PANEL)
                if (SettingsActuator.openPanel(context, key)) RawResult.applied("panel:$key")
                else RawResult.noEffect("panel_failed:$key")
            }
            api.startsWith(API_BRIGHTNESS) -> {
                val direction = api.removePrefix(API_BRIGHTNESS)
                if (SettingsActuator.adjustBrightness(context, direction)) {
                    RawResult.applied("brightness:$direction")
                } else {
                    // 誠實：沒有 WRITE_SETTINGS 就不能假裝改好了（開設定頁是另一個動作）
                    RawResult.noEffect("brightness_needs_write_settings")
                }
            }
            else -> RawResult.noEffect("unknown_api")
        }
    }

    private fun adjustVolume(action: String): RawResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return RawResult.noEffect("no_audio_manager")
        val direction = when (action) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            else -> return RawResult.noEffect("unknown_volume")
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return RawResult.applied("volume_" + action)
    }

    // ── Intent（綠區：公開 API＋深鏈探測）────────────────────────────

    private fun runIntent(means: Means.Intent): RawResult {
        if (means.action == ACTION_VIEW) return openDeepLink(means)
        if (means.action == ACTION_SEND) return shareText(means)
        if (means.action == ACTION_SHARE_IMAGE) return shareScreenshot(means)
        if (means.action == ACTION_SHARE_PHOTO) return shareLatestPhoto(means)
        if (means.action == ACTION_SEARCH) return runSearchIntent(means)

        // 已經解析出套件名（Planner 透過 AppResolver 解析）就直接用它
        val explicitPkg = means.pkg
        val pkg = explicitPkg ?: AppCatalog.resolveLabel(context, means.data.orEmpty())
            ?: return RawResult.noEffect("app_not_found")
        val launch = AppCatalog.launchIntent(context, pkg)
            ?: return RawResult.noEffect("no_launch_intent:$pkg")
        return com.wae.core.util.Degrade.orElse("actuator:launch", RawResult.noEffect("launch_failed")) {
            context.startActivity(launch)
            lastTargetPkg = pkg
            RawResult.applied("launch:$pkg")
        }
    }

    /**
     * 帶查詢字串的搜尋意圖（B：搜尋走意圖，不必點搜尋列、不必叫輸入法）。
     *
     * 用「目前前景 App」查資料表；沒有對應的搜尋 Activity 就誠實回 noEffect，
     * 讓上層改用 UI 路徑（點搜尋列＋輸入）。
     */
    private fun runSearchIntent(means: Means.Intent): RawResult {
        val query = means.data.orEmpty()
        if (query.isEmpty()) return RawResult.noEffect("empty_query")
        // 先看「這個任務剛開的是哪個 App」：代理人跑在自己的畫面裡，
        // 只看前景會查到 com.wae.agent（然後永遠找不到搜尋 action）。
        val foreground = lastTargetPkg
            ?: com.wae.core.util.Degrade.orElse("actuator:foregroundPkg", "") { service.rootInActiveWindow?.packageName?.toString() ?: "" }
        val action = SearchActions.actionFor(context, foreground)
            ?: return RawResult.noEffect("no_search_intent")
        val intent = Intent(action).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return com.wae.core.util.Degrade.orElse("actuator:searchIntent", RawResult.noEffect("search_intent_failed")) {
            context.startActivity(intent)
            RawResult.applied("search_intent:" + foreground)
        }
    }

    /** 分享相簿裡最近一張照片（需要讀取媒體權限；沒有就誠實回失敗）。 */
    private fun shareLatestPhoto(means: Means.Intent): RawResult {
        if (!PhotoLibrary.hasPermission(context)) return RawResult.noEffect("no_media_permission")
        val photo = PhotoLibrary.latest(context) ?: return RawResult.noEffect("no_photo")
        android.util.Log.i("WAE_PHOTOS", "share photo id=${photo.id} uri=${photo.uri}")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, photo.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        means.pkg?.let { send.setPackage(it) }
        return com.wae.core.util.Degrade.orElse("actuator:sharePhoto", RawResult.noEffect("share_photo_failed")) {
            context.startActivity(send)
            RawResult.applied("share_photo:" + (means.pkg ?: "chooser"))
        }
    }

    /** 分享截圖（D11 延伸）：自己拍、自己存、用 content:// 交給目標 App，不需要任何媒體權限。 */
    private fun shareScreenshot(means: Means.Intent): RawResult {
        val shot = captureScreenshot() ?: return RawResult.noEffect("screenshot_failed")
        val dir = java.io.File(context.cacheDir, ShotProvider.DIR)
        dir.mkdirs()
        val name = "shot-" + System.currentTimeMillis() + ".png"
        val file = java.io.File(dir, name)
        val saved = com.wae.core.util.Degrade.orElse("actuator:saveScreenshot", false) {
            java.io.FileOutputStream(file).use { out ->
                shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        }
        com.wae.core.util.Degrade.orNull("actuator:recycleShot") { shot.recycle() }
        if (!saved) return RawResult.noEffect("screenshot_save_failed")

        val uri = android.net.Uri.parse("content://" + ShotProvider.authority(context.packageName) + "/" + name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        means.pkg?.let { send.setPackage(it) }
        return com.wae.core.util.Degrade.orElse("actuator:shareImage", RawResult.noEffect("share_image_failed")) {
            context.startActivity(send)
            RawResult.applied("share_image:" + (means.pkg ?: "chooser"))
        }
    }

    private fun captureScreenshot(): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        // 連續動作（換頁、動畫）時 takeScreenshot 會失敗；重試幾次比直接回失敗誠實。
        repeat(SCREENSHOT_ATTEMPTS) { attempt ->
            val shot = captureScreenshotOnce()
            if (shot != null) return shot
            if (attempt < SCREENSHOT_ATTEMPTS - 1) {
                com.wae.core.util.Degrade.orNull("actuator:screenshotRetryWait") {
                Thread.sleep(SCREENSHOT_RETRY_MS * (attempt + 1))
            }
            }
        }
        return null
    }

    private fun captureScreenshotOnce(): android.graphics.Bitmap? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var bitmap: android.graphics.Bitmap? = null
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        com.wae.core.util.Degrade.orNull("actuator:takeScreenshot") {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        com.wae.core.util.Degrade.orNull("actuator:wrapBuffer") {
                            bitmap = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
        } // 失敗時 latch 已在 onFailure 倒數；外層 wait 有逾時，不會卡死
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        com.wae.core.util.Degrade.orNull("actuator:shutdownExecutor") { executor.shutdown() }
        return bitmap
    }

    /** 分享文字（D11）：直接把內容丟給目標 App，不必先開它。 */
    private fun shareText(means: Means.Intent): RawResult {
        val text = means.data.orEmpty()
        if (text.isEmpty()) return RawResult.noEffect("no_share_text")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val pkg = means.pkg
        if (pkg != null) send.setPackage(pkg)
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return com.wae.core.util.Degrade.orElse("actuator:share", RawResult.noEffect("share_failed")) {
            context.startActivity(send)
            RawResult.applied("share:" + (pkg ?: "chooser"))
        }
    }

    /** 深鏈（B15）：直接問系統要誰來開，不走畫面導覽。 */
    private fun openDeepLink(means: Means.Intent): RawResult {
        val uri = means.data ?: return RawResult.noEffect("no_uri")
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        means.pkg?.let { intent.setPackage(it) }
        return com.wae.core.util.Degrade.orElse("actuator:deepLink", RawResult.noEffect("view_failed")) {
            context.startActivity(intent)
            RawResult.applied("view:" + uri.take(URL_LOG_LEN))
        }
    }

    private companion object {
        const val ACTION_CLICK = "click"
        const val ACTION_FOCUS = "focus"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SHARE_IMAGE = com.wae.core.effect.Planner.ACTION_SHARE_IMAGE
        const val ACTION_SEARCH = com.wae.core.effect.Planner.ACTION_SEARCH
        const val ACTION_SHARE_PHOTO = com.wae.core.effect.Planner.ACTION_SHARE_PHOTO
        private const val SCREENSHOT_ATTEMPTS = 3
        private const val SCREENSHOT_RETRY_MS = 160L
        const val URL_LOG_LEN = 40
        const val API_VOLUME = "volume:"
        const val API_MEDIA = "media:"
        const val API_HOME = "global_action:HOME"
        const val API_BACK = "global_action:BACK"
        const val API_IME_ENTER = "ime:enter"
        const val API_PANEL = "panel:"
        const val API_GLOBAL = "global:"
        const val API_BRIGHTNESS = "brightness:"
        const val CLIP_LABEL = "wae"
        const val CLICK_ANCESTOR_DEPTH = 3
        const val TAP_MS = 60L
        const val LONG_PRESS_MS = 600L
        const val SWIPE_PIXELS = 400f
    }
}
