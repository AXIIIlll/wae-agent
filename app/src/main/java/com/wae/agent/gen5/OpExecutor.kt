package com.wae.agent.gen5

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.wae.core.rule.OpCall
import com.wae.core.rule.PostSpec
import com.wae.core.rule.Purpose

/**
 * 操作層執行器（v1）：把 `op:` 序列真的做出來，並用目的的 `post` 驗證。
 *
 * 三條紀律：
 *  1. **只走既有手段**（Intent／A11y／Input／SystemApi／Gesture），不新增第二條裝置路徑。
 *  2. **每個 op 都回報「做了／做不了」**；做不了就換下一個 op（規則層的退路）。
 *  3. **驗證是誠實的**：`post` 算不出來就回 null（不假裝成功）。
 */
object OpExecutor {

    data class Result(val ok: Boolean, val verified: Boolean?, val detail: String)

    /** 執行單一 op（給「混合計畫」用：cmd 與 op 交錯）。 */
    fun runSingle(context: Context, service: AccessibilityService?, op: OpCall): Boolean =
        runOne(context, service, op)

    fun run(
        context: Context,
        service: AccessibilityService?,
        purpose: Purpose,
        ops: List<OpCall>
    ): Result {
        var anyExecuted = false
        for (op in ops) {
            val executed = runOne(context, service, op)
            if (!executed) {
                return Result(false, false, "op_failed:" + op.id)
            }
            anyExecuted = true
            // WAIT 這種純時間操作不需要驗證
            if (op.id == "WAIT") SystemClock.sleep(op.args.firstOrNull()?.toLongOrNull() ?: 300L)
        }
        if (!anyExecuted) return Result(false, null, "no_ops")

        val verified = verify(context, service, purpose)
        return Result(true, verified, "ops=" + ops.size + " verified=" + verified)
    }

    // ── 單一操作 ────────────────────────────────────────────────

    private fun runOne(context: Context, service: AccessibilityService?, op: OpCall): Boolean =
        runCatching {
            when (op.id) {
                "INTENT_LAUNCH" -> launchApp(context, op.args.getOrNull(0).orEmpty())
                "INTENT_SEARCH" -> launchApp(context, op.args.getOrNull(0).orEmpty()) &&
                    typeIntoSearch(context, service, op.args.getOrNull(1).orEmpty())
                "INTENT_SEND" -> sendTo(context, op.args.getOrNull(0).orEmpty())
                "INTENT_PICK" -> pick(context, op.args.getOrNull(0).orEmpty())
                "INTENT_CAPTURE" -> capture(context)
                "A11Y_CLICK", "A11Y_SUBMIT" -> clickByText(context, service, op.args.getOrNull(0).orEmpty())
                "A11Y_SET_TEXT" -> setText(service, op.args.getOrNull(0).orEmpty())
                "PANEL" -> openPanel(service)
                "SYSTEM_VALUE" -> systemValue(context, op.args.getOrNull(0).orEmpty(), op.args.getOrNull(1).orEmpty())
                "BACK" -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
                "HOME" -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
                "URI_SEARCH" -> uriSearch(context, service, op.args.getOrNull(0).orEmpty())
                "IN_APP_SEARCH" -> service != null &&
                    StepActor.searchHere(service, op.args.getOrNull(0).orEmpty(), context)
                "CLIPBOARD_COPY" -> copyLastResult(context)
                "WAIT" -> true
                "WAIT_FOR" -> waitFor(service, op.args.getOrNull(0).orEmpty(), op.args.getOrNull(1)?.toIntOrNull() ?: 2000)
                else -> false
            }
        }.getOrDefault(false)

    /**
     * 用前景 App 的 URI scheme 搜尋（`uri_search.tsv`）。
     *
     * 為什麼比「在畫面找搜尋框」可靠：不用猜哪個元素是搜尋列，
     * 也不會誤點語音搜尋；URI 由 App 自己處理。
     */
    /** 直接用套件名啟動（不經舊管線，不會有重試／overlay）。 */
    fun launchPkg(context: Context, pkg: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 用「指定的 App」做 URI 搜尋（呼叫端已知要交給誰，不要靠前景猜）。 */
    fun uriSearchFor(context: Context, pkg: String, query: String): Boolean {
        if (pkg.isBlank() || query.isBlank()) return false
        val template = runCatching {
            com.wae.core.config.BundledData.uriSearchLines().firstOrNull { line ->
                !line.startsWith("#") && line.split('\t').firstOrNull()?.trim() == pkg
            }?.split('\t')?.getOrNull(1)?.trim()
        }.getOrNull()
        if (template.isNullOrEmpty()) return false
        val uri = template.replace("{q}", android.net.Uri.encode(query))
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setPackage(pkg)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun uriSearch(context: Context, service: AccessibilityService?, query: String): Boolean {
        if (query.isBlank()) return false
        val pkg = runCatching {
            service?.rootInActiveWindow?.packageName?.toString()
        }.getOrNull().orEmpty()
        if (pkg.isEmpty()) return false
        val template = runCatching {
            com.wae.core.config.BundledData.uriSearchLines().firstOrNull { line ->
                !line.startsWith("#") && line.split('\t').firstOrNull()?.trim() == pkg
            }?.split('\t')?.getOrNull(1)?.trim()
        }.getOrNull()
        if (template.isNullOrEmpty()) return false
        val uri = template.replace("{q}", android.net.Uri.encode(query))
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg.isNotEmpty()) intent.setPackage(pkg)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun launchApp(context: Context, appLabel: String): Boolean {
        if (appLabel.isBlank()) return false
        val pkg = resolvePkg(context, appLabel) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return com.wae.core.util.Degrade.orElse("opExecutor:startActivity1", false) { context.startActivity(intent); true }
    }

    private fun sendTo(context: Context, targetApp: String): Boolean {
        val pkg = resolvePkg(context, targetApp) ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return com.wae.core.util.Degrade.orElse("opExecutor:startActivity1", false) { context.startActivity(intent); true }
    }

    private fun pick(context: Context, mime: String): Boolean {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = mime.ifBlank { "image/*" }
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return com.wae.core.util.Degrade.orElse("opExecutor:startActivity1", false) { context.startActivity(intent); true }
    }

    private fun capture(context: Context): Boolean {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return com.wae.core.util.Degrade.orElse("opExecutor:startActivity1", false) { context.startActivity(intent); true }
    }

    private fun clickByText(context: Context, service: AccessibilityService?, target: String): Boolean {
        if (service == null || target.isBlank()) return false
        val snap = StepActor.snapshot(service, context) ?: return false
        // 目標可以是 `role:SUBMIT`（用詞庫角色找），比字面更穩
        if (target.startsWith("role:")) {
            val wanted = target.removePrefix("role:").trim()
            val el = snap.elements.firstOrNull { it.semanticRole == wanted && it.clickable } ?: return false
            val node = snap.nodes[el.id] ?: return false
            return com.wae.core.util.Degrade.orElse("opExecutor:click", false) { DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_CLICK) }
        }
        val resolved = com.wae.core.step.ClickResolver.resolve(
            com.wae.core.step.StepKind.LIST_ITEM, target, snap.elements
        )
        val node = when (resolved) {
            is com.wae.core.step.ClickResolver.Result.Hit -> snap.nodes[resolved.element.id]
            else -> null
        } ?: return false
        return runCatching {
            DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_CLICK) ||
                (node.parent?.let { DeviceIO.perform(it, AccessibilityNodeInfo.ACTION_CLICK) } ?: false)
        }.getOrDefault(false)
    }

    private fun typeIntoSearch(context: Context, service: AccessibilityService?, text: String): Boolean {
        if (service == null || text.isBlank()) return false
        val snap = StepActor.snapshot(service, context) ?: return false
        val edit = snap.elements.firstOrNull {
            it.semanticRole == "SEARCH" || it.role.contains("Edit", true) || it.role.contains("輸入", true)
        } ?: return false
        val node = snap.nodes[edit.id] ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args) &&
                (node.text?.toString()?.contains(text) == true)
        }.getOrDefault(false)
    }

    private fun setText(service: AccessibilityService?, text: String): Boolean {
        if (service == null || text.isBlank()) return false
        val snap = StepActor.snapshot(service) ?: return false
        val edit = snap.elements.firstOrNull { it.role.contains("Edit", true) } ?: return false
        val node = snap.nodes[edit.id] ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args) &&
                (node.text?.toString()?.contains(text) == true)
        }.getOrDefault(false)
    }

    private fun openPanel(service: AccessibilityService?): Boolean =
        runCatching {
            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) ?: false
        }.getOrDefault(false)

    private fun systemValue(context: Context, kind: String, dir: String): Boolean {
        val up = dir.contains("高") || dir.contains("亮") || dir.equals("up", true)
        return when (kind.lowercase()) {
            "volume" -> runCatching {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val stream = AudioManager.STREAM_MUSIC
                am.adjustStreamVolume(
                    stream,
                    if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            }.getOrDefault(false)
            "brightness" -> runCatching {
                val current = Settings.System.getInt(
                    context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
                )
                val next = (current + if (up) 30 else -30).coerceIn(10, 255)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
                true
            }.getOrDefault(false)
            else -> false
        }
    }

    private fun copyLastResult(context: Context): Boolean = com.wae.core.util.Degrade.orElse("opExecutor:copyLastResult", false) {
        val dir = context.getExternalFilesDir(null) ?: return@orElse false
        val file = java.io.File(dir, "gen5_result.txt")
        if (!file.exists()) return@orElse false
        val line = file.readLines(Charsets.UTF_8).lastOrNull { it.isNotBlank() } ?: return@orElse false
        val text = line.substringAfter('\t', "").trim()
        if (text.isEmpty()) return@orElse false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WAE", text))
        true
    }

    private fun waitFor(service: AccessibilityService?, target: String, timeoutMs: Int): Boolean {
        if (service == null) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snap = StepActor.snapshot(service)
            if (snap != null && (target.isBlank() || snap.elements.any { it.text.contains(target) })) return true
            SystemClock.sleep(200)
        }
        return false
    }

    /** 給「混合計畫」用：整串步驟跑完後，用目的的 post 驗一次。 */
    fun verifyPurpose(
        context: Context,
        service: AccessibilityService?,
        purpose: Purpose,
        slots: Map<String, String> = emptyMap()
    ): Boolean? = verify(context, service, purpose, slots)

    // ── 驗證 ────────────────────────────────────────────────────

    private fun verify(
        context: Context,
        service: AccessibilityService?,
        purpose: Purpose,
        slots: Map<String, String> = emptyMap()
    ): Boolean? {
        val specs = PostSpec.parse(purpose.post) ?: return null
        val facts = facts(context, service)
        return PostSpec.holdsAny(specs, facts, slots)
    }

    private fun facts(context: Context, service: AccessibilityService?): Map<String, String> {
        val out = HashMap<String, String>()
        val root = com.wae.core.util.Degrade.orNull("opExecutor:root") { service?.rootInActiveWindow }
        val pkg = root?.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty()) {
            out["pkg"] = pkg
            out["pkg_label"] = runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            }.getOrDefault(pkg)
        }
        if (root != null) {
            val texts = ArrayList<String>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var guard = 0
            while (queue.isNotEmpty() && guard < 200) {
                guard++
                val node = queue.removeFirst()
                (node.text ?: node.contentDescription)?.toString()?.trim()?.let {
                    if (it.isNotEmpty()) texts.add(it)
                }
                for (i in 0 until node.childCount) {
                    com.wae.core.util.Degrade.orNull("opExecutor:child") { node.getChild(i) }?.let { queue.add(it) }
                }
            }
            out["texts"] = texts.joinToString(" ")
        }
        // 系統面板／選圖器可以從 pkg 看出來
        if (pkg.contains("systemui", true)) out["panel_visible"] = "true"
        if (pkg.contains("photopicker", true) || pkg.contains("documentsui", true)) {
            out["picker_visible"] = "true"
        }
        out["api_ok"] = "true" // 有執行到這裡代表 API 沒丟例外
        return out
    }

    private fun resolvePkg(context: Context, appLabel: String): String? {
        val installed = runCatching {
            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)
        }.getOrDefault(emptyList())
        return installed.firstOrNull { it.label.equals(appLabel, true) }?.pkg
            ?: installed.firstOrNull { it.label.contains(appLabel, true) }?.pkg
    }
}
