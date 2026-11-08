package com.example.newandroidapp.action

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.example.newandroidapp.vision.ScreenTextCapture
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * 【AI 系統能力統一中樞 (`SystemCapabilityHub`)】
 *
 * 將 10 項底層系統資訊查詢與控制能力收斂為單一統一介面 (`CapabilityRequest` -> `CapabilityResult`)，
 * 讓 AI 只需透過 `SystemCapabilityHub` 即可存取：
 * 1. **通知收件匣與睡覺/勿擾期間訊息彙整 (`NotificationQuery`)**：由無障礙事件流即時收集，自動遮罩驗證碼與敏感通知。
 * 2. **聯絡人查詢與姓名直撥 (`ContactLookup`)**：透過 `ContactsContract` 依姓名查電話或直接帶入撥號面板。
 * 3. **勿擾模式與原生網路快速面板 (`DndAndPanelControl`)**：透過 `NotificationManager` 切換勿擾，或喚起 `Settings.Panel` 網路浮窗。
 * 4. **行事曆行程查詢 (`CalendarQuery`)**：透過 `CalendarContract` 查詢今/明/後天行程，未授權時自動直達對應日期日曆畫面。
 * 5. **儲存空間與記憶體狀態查詢 (`StorageAndRamQuery`)**：透過 `StatFs` 與 `ActivityManager` 0ms 讀取剩餘容量與可用 RAM。
 * 6. **本機快速筆記存取 (`QuickNoteSave` / `QuickNoteQuery` / `QuickNoteClear`)**：零權限本機持久化筆記庫，支援新增、關鍵字查詢與清空。
 * 7. **目前位置與定位狀態 (`LocationQuery`)**：透過 `LocationManager` 查詢最後已知座標或一鍵開啟地圖目前位置。
 * 8. **剪貼簿讀取與寫入 (`ClipboardRead` / `ClipboardWrite`)**：支援讀取剪貼簿、寫入指定文字或將剛擷取的螢幕文字複製到剪貼簿。
 * 9. **本機最新照片與下載檔案檢視 (`MediaAndDownloadsQuery`)**：透過 `MediaStore` 查詢最新照片或直達系統下載資料夾。
 */
object SystemCapabilityHub {

    private const val PREFS_NOTES = "ai_system_capability_notes"
    private const val KEY_NOTES_LIST = "notes_entries_v1"
    private const val MAX_NOTIFICATIONS = 30
    private const val MAX_NOTES = 50

    data class NotificationRecord(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val content: String,
        val timestampMs: Long,
        val isRedactedSensitive: Boolean = false
    ) {
        val formattedTime: String
            get() = SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(timestampMs))
    }

    data class QuickNoteEntry(
        val content: String,
        val timestampMs: Long
    ) {
        val formattedTime: String
            get() = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN).format(Date(timestampMs))
    }

    sealed class CapabilityRequest {
        data class QuickNoteSave(val content: String) : CapabilityRequest()
        data class QuickNoteQuery(val keyword: String = "") : CapabilityRequest()
        object QuickNoteClear : CapabilityRequest()

        data class NotificationQuery(
            val filterAppKeyword: String = "",
            val onlyMissedOrRecent: Boolean = false
        ) : CapabilityRequest()

        object ClipboardRead : CapabilityRequest()
        data class ClipboardWrite(val textToCopy: String, val fromScreenCapture: Boolean = false) : CapabilityRequest()

        object StorageAndRamQuery : CapabilityRequest()

        data class DndControl(val enableDnd: Boolean) : CapabilityRequest()
        data class ConnectivityPanel(val panelType: String) : CapabilityRequest()

        data class ContactLookup(val personName: String, val dialImmediately: Boolean) : CapabilityRequest()

        data class CalendarQuery(val dayOffset: Int, val dayLabel: String) : CapabilityRequest()

        object LocationQuery : CapabilityRequest()

        data class MediaAndDownloadsQuery(val targetType: String) : CapabilityRequest()
    }

    data class CapabilityResult(
        val succeeded: Boolean,
        val summaryText: String,
        val request: CapabilityRequest
    )

    private val recentNotifications = CopyOnWriteArrayList<NotificationRecord>()
    private val inMemoryNotes = CopyOnWriteArrayList<QuickNoteEntry>()
    private var prefsLoaded = false

    private const val NOTIFICATION_TTL_MS = 30 * 60_000L
    private const val NOTE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    private const val KEYSTORE_ALIAS_NOTES = "ai_capability_notes_aes_key"

    private val SENSITIVE_CJK_KEYWORDS = listOf(
        "驗證碼", "認證碼", "動態密碼", "交易密碼", "安全碼", "一次性密碼", "刷卡", "轉帳", "入帳", "扣款", "帳戶餘額"
    )
    private val SENSITIVE_ASCII_WORD_REGEX = Regex(
        "\\b(?:otp|passcode|verification\\s+code|security\\s+code|auth\\s+code|2fa|mfa|pin\\s+code)\\b",
        RegexOption.IGNORE_CASE
    )
    private val NUMERIC_OTP_CODE_REGEX = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
    private val NUMERIC_CODE_CONTEXT_REGEX = Regex(
        "(?:碼|驗證|認證|登入|動態|交易|授權|安全|\\b(?:code|otp|pin|verify|verification|login|auth|passcode|token)\\b)",
        RegexOption.IGNORE_CASE
    )
    private val SENSITIVE_PACKAGE_REGEX = Regex(
        "(?:^|[._])(?:bank|mymobibank|cathay|cathaybk|ctbc|esun|taishin|fubon|megabank|sinopac|firstbank|hncb|landbank|jkos|ipass|icash|twpay|wallet|gpay|samsungpay|linepay|pxpay|authenticator|authy|1password|bitwarden|keepass|dashlane)(?:[._0-9]|$)",
        RegexOption.IGNORE_CASE
    )
    private val SENSITIVE_NOTE_BLOCK_REGEX = Regex(
        "(?:密碼|驗證碼|認證碼|安全碼|信用卡號|卡號|助記詞|私鑰|\\b(?:password|passcode|otp|cvv|seed\\s+phrase|private\\s+key)\\b)",
        RegexOption.IGNORE_CASE
    )

    private val jvmFallbackAesKey: javax.crypto.SecretKey by lazy {
        javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    // =========================================================================
    // 無障礙通知事件流收集（零額外權限，自動過濾敏感驗證碼）
    // =========================================================================

    fun onAccessibilityNotificationEvent(
        context: Context?,
        packageName: String?,
        event: AccessibilityEvent
    ) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() ||
            pkg == "com.localai.visionassistant" ||
            pkg == "com.example.newandroidapp" ||
            pkg == "com.android.systemui"
        ) {
            return
        }

        val parcelNotif = event.parcelableData as? Notification
        val title = parcelNotif?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bodyFromExtras = parcelNotif?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bodyFromEvent = event.text.joinToString(" ") { it.toString().trim() }.trim()
        val rawContent = if (bodyFromExtras.isNotEmpty()) bodyFromExtras else bodyFromEvent

        if (title.isEmpty() && rawContent.isEmpty()) return

        val appLabel = if (context != null) {
            runCatching {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString().ifBlank { pkg }
            }.getOrDefault(pkg)
        } else {
            pkg
        }

        recordNotificationInternal(
            packageName = pkg,
            appLabel = appLabel,
            title = title,
            rawContent = rawContent,
            timestampMs = System.currentTimeMillis()
        )
    }

    internal fun recordNotificationInternal(
        packageName: String,
        appLabel: String,
        title: String,
        rawContent: String,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        pruneExpiredNotifications(timestampMs)
        val pkgLower = packageName.lowercase()
        val combinedLower = "$title $rawContent".lowercase()
        val isSensitivePkg = SENSITIVE_PACKAGE_REGEX.containsMatchIn(pkgLower)
        val hasContextualNumericOtp = NUMERIC_OTP_CODE_REGEX.containsMatchIn(rawContent) &&
            NUMERIC_CODE_CONTEXT_REGEX.containsMatchIn(combinedLower)
        val isSensitive = isSensitivePkg ||
            SENSITIVE_CJK_KEYWORDS.any { combinedLower.contains(it) } ||
            SENSITIVE_ASCII_WORD_REGEX.containsMatchIn(combinedLower) ||
            hasContextualNumericOtp

        val safeTitle = if (isSensitive) "敏感通知" else title.ifEmpty { appLabel }
        val safeContent = if (isSensitive) "[包含驗證碼、金融或敏感資訊，已自動隱藏]" else rawContent

        // 避免短時間完全相同的重複通知洗版
        val duplicate = recentNotifications.firstOrNull()
        if (duplicate != null &&
            duplicate.packageName == packageName &&
            duplicate.title == safeTitle &&
            duplicate.content == safeContent &&
            (timestampMs - duplicate.timestampMs) < 3_000L
        ) {
            return
        }

        recentNotifications.add(
            0,
            NotificationRecord(
                packageName = packageName,
                appLabel = appLabel,
                title = safeTitle,
                content = safeContent,
                timestampMs = timestampMs,
                isRedactedSensitive = isSensitive
            )
        )
        while (recentNotifications.size > MAX_NOTIFICATIONS) {
            recentNotifications.removeAt(recentNotifications.size - 1)
        }
    }

    private fun pruneExpiredNotifications(nowMs: Long = System.currentTimeMillis()) {
        recentNotifications.removeAll { (nowMs - it.timestampMs) > NOTIFICATION_TTL_MS }
    }

    internal fun clearNotificationsForTest() {
        recentNotifications.clear()
    }

    internal fun clearNotesForTest() {
        inMemoryNotes.clear()
        prefsLoaded = false
    }

    // =========================================================================
    // 統一自然語言指令解析入口
    // =========================================================================

    fun parseRequest(rawGoal: String): CapabilityRequest? {
        val clean = rawGoal.trim()
        if (clean.isEmpty()) return null
        val lower = clean.lowercase()

        // 1. 快速筆記（新增 / 查詢 / 清空）
        parseQuickNoteRequest(clean, lower)?.let { return it }

        // 2. 通知收件匣與睡覺/勿擾期間漏接訊息查詢
        parseNotificationQueryRequest(clean, lower)?.let { return it }

        // 3. 剪貼簿讀取與寫入（含複製螢幕擷取文字）
        parseClipboardRequest(clean, lower)?.let { return it }

        // 4. 儲存空間與記憶體 (RAM) 狀態查詢
        parseStorageAndRamRequest(lower)?.let { return it }

        // 5. 勿擾模式與原生網路快速面板
        parseDndAndPanelRequest(lower)?.let { return it }

        // 6. 聯絡人電話查詢與姓名直撥
        parseContactLookupRequest(clean, lower)?.let { return it }

        // 7. 行事曆行程查詢（今/明/後天）
        parseCalendarQueryRequest(clean, lower)?.let { return it }

        // 8. 目前位置與定位查詢
        parseLocationQueryRequest(lower)?.let { return it }

        // 9. 本機最新照片與下載檔案查詢
        parseMediaAndDownloadsRequest(lower)?.let { return it }

        return null
    }

    /** 這個請求的回覆是否會回顯私人內容（通知、剪貼簿、筆記、聯絡人、行程、位置）。 */
    fun isPrivateContentRequest(request: CapabilityRequest): Boolean = when (request) {
        is CapabilityRequest.NotificationQuery,
        CapabilityRequest.ClipboardRead,
        is CapabilityRequest.ClipboardWrite,
        is CapabilityRequest.QuickNoteSave,
        is CapabilityRequest.QuickNoteQuery,
        is CapabilityRequest.ContactLookup,
        is CapabilityRequest.CalendarQuery,
        CapabilityRequest.LocationQuery -> true
        else -> false
    }

    /** 使用者這句話的回覆是否會包含私人內容（含螢幕文字擷取），這類回覆不可寫入磁碟。 */
    fun isPrivateContentQuery(goal: String): Boolean {
        if (ScreenTextCapture.isCaptureRequest(goal)) return true
        val req = parseRequest(goal) ?: return false
        return isPrivateContentRequest(req)
    }

    /**
     * 檢查該語句對應之本地能力是否需要先向使用者請求 Android 執行階段權限
     * （`READ_CONTACTS`、`READ_CALENDAR`、`ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`）。
     */
    fun getMissingRuntimePermissions(goal: String, context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val req = parseRequest(goal) ?: return emptyList()
        return when (req) {
            is CapabilityRequest.ContactLookup -> {
                if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    listOf(Manifest.permission.READ_CONTACTS)
                } else emptyList()
            }
            is CapabilityRequest.CalendarQuery -> {
                if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    listOf(Manifest.permission.READ_CALENDAR)
                } else emptyList()
            }
            CapabilityRequest.LocationQuery -> {
                val hasCoarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasFine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasCoarse && !hasFine) {
                    listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                } else emptyList()
            }
            else -> emptyList()
        }
    }

    fun execute(
        request: CapabilityRequest,
        context: Context,
        accessibilityService: AccessibilityService? = null
    ): CapabilityResult {
        return when (request) {
            is CapabilityRequest.QuickNoteSave -> executeQuickNoteSave(request, context)
            is CapabilityRequest.QuickNoteQuery -> executeQuickNoteQuery(request, context)
            CapabilityRequest.QuickNoteClear -> executeQuickNoteClear(context)
            is CapabilityRequest.NotificationQuery -> executeNotificationQuery(request)
            CapabilityRequest.ClipboardRead -> executeClipboardRead(context)
            is CapabilityRequest.ClipboardWrite -> executeClipboardWrite(request, context)
            CapabilityRequest.StorageAndRamQuery -> executeStorageAndRamQuery(context)
            is CapabilityRequest.DndControl -> executeDndControl(request, context)
            is CapabilityRequest.ConnectivityPanel -> executeConnectivityPanel(request, context)
            is CapabilityRequest.ContactLookup -> executeContactLookup(request, context)
            is CapabilityRequest.CalendarQuery -> executeCalendarQuery(request, context)
            CapabilityRequest.LocationQuery -> executeLocationQuery(context)
            is CapabilityRequest.MediaAndDownloadsQuery -> executeMediaAndDownloadsQuery(request, context, accessibilityService)
        }
    }

    // =========================================================================
    // 1. 本機快速筆記 (Quick Notes)
    // =========================================================================

    private fun parseQuickNoteRequest(clean: String, lower: String): CapabilityRequest? {
        if (lower == "清空筆記" || lower == "刪除所有筆記" || lower == "清空備忘錄" || lower == "清除筆記") {
            return CapabilityRequest.QuickNoteClear
        }

        // 查詢筆記：「我剛記了什麼」、「查詢筆記」、「我的筆記有什麼」、「查看備忘錄」
        if (lower.contains("我剛記了什麼") || lower.contains("剛剛記了什麼") ||
            lower.contains("剛記了什麼") || lower.contains("記了什麼") ||
            lower == "查詢筆記" || lower == "查看筆記" || lower == "查看備忘錄" ||
            lower == "我的筆記" || lower == "我的備忘錄" || lower.contains("筆記有什麼") ||
            lower.contains("備忘錄有什麼") || lower.contains("看筆記") || lower.contains("查筆記") && clean.length <= 5
        ) {
            return CapabilityRequest.QuickNoteQuery()
        }
        val searchNoteMatch = Regex("^(?:搜尋筆記|找筆記|查筆記)\\s*[:：]?\\s*(.+)$").find(clean)
        if (searchNoteMatch != null) {
            return CapabilityRequest.QuickNoteQuery(keyword = searchNoteMatch.groupValues[1].trim())
        }

        // 新增筆記：「記下買牛奶」、「記下：明天交報告」、「筆記：買牛奶」、「備忘下午三點開會」、「幫我記下明天要買牛奶」
        val explicitSaveMatch = Regex("^(?:幫我|請)?(?:記下來|記下|記一下|記錄一下|筆記|備忘|快速筆記)\\s*[:：]?\\s*(.+)$").find(clean)
        if (explicitSaveMatch != null) {
            val noteBody = explicitSaveMatch.groupValues[1].trim()
            if (noteBody.isNotEmpty() && !noteBody.startsWith("有什麼") && !noteBody.startsWith("裡有什麼")) {
                return CapabilityRequest.QuickNoteSave(content = noteBody)
            }
        }
        return null
    }

    private fun pruneExpiredNotes(nowMs: Long = System.currentTimeMillis()) {
        inMemoryNotes.removeAll { (nowMs - it.timestampMs) > NOTE_TTL_MS }
    }

    internal fun encryptNotesPayload(plainText: String): String? {
        if (plainText.isEmpty()) return ""
        // 優先使用硬體級 AndroidKeyStore AES-GCM
        val hardwareEncrypted = runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = if (ks.containsAlias(KEYSTORE_ALIAS_NOTES)) {
                (ks.getEntry(KEYSTORE_ALIAS_NOTES, null) as java.security.KeyStore.SecretKeyEntry).secretKey
            } else {
                val keyGen = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
                )
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS_NOTES,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
            val ivB64 = java.util.Base64.getEncoder().encodeToString(cipher.iv)
            val ctB64 = java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)))
            "ENC1:$ivB64:$ctB64"
        }.getOrNull()
        if (hardwareEncrypted != null) return hardwareEncrypted

        // Host JVM 測試環境無 AndroidKeyStore 時，仍強制使用標準 JCA 256-bit AES/GCM/NoPadding，絕不退化為明文或 Base64
        return runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, jvmFallbackAesKey)
            val ivB64 = java.util.Base64.getEncoder().encodeToString(cipher.iv)
            val ctB64 = java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)))
            "ENC2:$ivB64:$ctB64"
        }.getOrNull()
    }

    internal fun decryptNotesPayload(stored: String): String {
        if (stored.isBlank()) return ""
        return runCatching {
            when {
                stored.startsWith("ENC1:") -> {
                    val parts = stored.split(":", limit = 3)
                    if (parts.size < 3) return ""
                    val iv = java.util.Base64.getDecoder().decode(parts[1])
                    val ct = java.util.Base64.getDecoder().decode(parts[2])
                    val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    val entry = ks.getEntry(KEYSTORE_ALIAS_NOTES, null) as? java.security.KeyStore.SecretKeyEntry
                        ?: return ""
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, entry.secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
                    String(cipher.doFinal(ct), Charsets.UTF_8)
                }
                stored.startsWith("ENC2:") -> {
                    val parts = stored.split(":", limit = 3)
                    if (parts.size < 3) return ""
                    val iv = java.util.Base64.getDecoder().decode(parts[1])
                    val ct = java.util.Base64.getDecoder().decode(parts[2])
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, jvmFallbackAesKey, javax.crypto.spec.GCMParameterSpec(128, iv))
                    String(cipher.doFinal(ct), Charsets.UTF_8)
                }
                else -> ""
            }
        }.getOrDefault("")
    }

    private fun ensureNotesLoaded(context: Context?) {
        if (prefsLoaded || context == null) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NOTES, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_NOTES_LIST, null).orEmpty()
            val raw = decryptNotesPayload(stored)
            if (raw.isNotBlank()) {
                val nowMs = System.currentTimeMillis()
                val parsed = raw.split("\n---\n").mapNotNull { block ->
                    val sep = block.indexOf('|')
                    if (sep <= 0) null
                    else {
                        val ts = block.substring(0, sep).toLongOrNull() ?: nowMs
                        val text = block.substring(sep + 1).trim()
                        if (text.isNotEmpty() && (nowMs - ts) <= NOTE_TTL_MS) QuickNoteEntry(text, ts) else null
                    }
                }
                if (inMemoryNotes.isEmpty()) {
                    inMemoryNotes.addAll(parsed)
                }
            }
            prefsLoaded = true
        }
    }

    private fun persistNotes(context: Context?): Boolean {
        if (context == null) return true
        return runCatching {
            pruneExpiredNotes()
            val prefs = context.getSharedPreferences(PREFS_NOTES, Context.MODE_PRIVATE)
            if (inMemoryNotes.isEmpty()) {
                prefs.edit().remove(KEY_NOTES_LIST).apply()
                return true
            }
            val serialized = inMemoryNotes.take(MAX_NOTES).joinToString("\n---\n") { "${it.timestampMs}|${it.content}" }
            val encrypted = encryptNotesPayload(serialized) ?: return false
            runCatching {
                android.util.Log.i("AiFlight", "NotesEncrypted scheme=${encrypted.substringBefore(':')} count=${inMemoryNotes.size}")
            }
            prefs.edit().putString(KEY_NOTES_LIST, encrypted).apply()
            true
        }.getOrDefault(false)
    }

    internal fun saveNoteDirect(content: String, context: Context? = null): CapabilityResult {
        ensureNotesLoaded(context)
        pruneExpiredNotes()
        val trimmed = content.trim()
        val req = CapabilityRequest.QuickNoteSave(trimmed)
        if (SENSITIVE_NOTE_BLOCK_REGEX.containsMatchIn(trimmed)) {
            return CapabilityResult(
                succeeded = false,
                summaryText = "為保護您的隱私與資安，快速筆記禁止儲存密碼、驗證碼、金融卡號或私鑰。",
                request = req
            )
        }
        val entry = QuickNoteEntry(content = trimmed, timestampMs = System.currentTimeMillis())
        inMemoryNotes.add(0, entry)
        while (inMemoryNotes.size > MAX_NOTES) {
            inMemoryNotes.removeAt(inMemoryNotes.size - 1)
        }
        if (!persistNotes(context)) {
            inMemoryNotes.remove(entry)
            return CapabilityResult(
                succeeded = false,
                summaryText = "筆記加密儲存失敗，為確保資安已取消寫入。",
                request = req
            )
        }
        return CapabilityResult(
            succeeded = true,
            summaryText = "已記下筆記：「$trimmed」（目前共 ${inMemoryNotes.size} 則筆記）",
            request = req
        )
    }

    private fun executeQuickNoteSave(req: CapabilityRequest.QuickNoteSave, context: Context): CapabilityResult {
        return saveNoteDirect(req.content, context)
    }

    internal fun queryNotesDirect(keyword: String = "", context: Context? = null): CapabilityResult {
        ensureNotesLoaded(context)
        pruneExpiredNotes()
        val req = CapabilityRequest.QuickNoteQuery(keyword)
        if (inMemoryNotes.isEmpty()) {
            return CapabilityResult(
                succeeded = true,
                summaryText = "目前還沒有任何已記錄的筆記。",
                request = req
            )
        }
        val filtered = if (keyword.isBlank()) {
            inMemoryNotes.take(5)
        } else {
            inMemoryNotes.filter { it.content.contains(keyword, ignoreCase = true) }.take(5)
        }
        if (filtered.isEmpty()) {
            return CapabilityResult(
                succeeded = true,
                summaryText = "找不到包含「$keyword」的筆記。",
                request = req
            )
        }
        val listStr = filtered.mapIndexed { idx, item ->
            "${idx + 1}. ${item.content}（${item.formattedTime}）"
        }.joinToString("\n")
        return CapabilityResult(
            succeeded = true,
            summaryText = "最近的筆記：\n$listStr",
            request = req
        )
    }

    private fun executeQuickNoteQuery(req: CapabilityRequest.QuickNoteQuery, context: Context): CapabilityResult {
        return queryNotesDirect(req.keyword, context)
    }

    private fun executeQuickNoteClear(context: Context): CapabilityResult {
        inMemoryNotes.clear()
        persistNotes(context)
        return CapabilityResult(
            succeeded = true,
            summaryText = "已清空所有本機快速筆記。",
            request = CapabilityRequest.QuickNoteClear
        )
    }

    // =========================================================================
    // 2. 通知收件匣與漏接訊息查詢 (Notification Inbox)
    // =========================================================================

    private fun parseNotificationQueryRequest(clean: String, lower: String): CapabilityRequest.NotificationQuery? {
        if (lower.contains("拉下通知欄") || lower.contains("打開通知欄") || lower.contains("開啟通知欄") || lower.contains("通知音量") || lower.contains("通知設定")) {
            return null
        }

        val isMissedQuery = lower.contains("誰傳訊息給我") || lower.contains("有誰找我") ||
            lower.contains("睡覺時有誰找我") || lower.contains("剛剛誰找我") ||
            lower.contains("有沒有人找我") || lower.contains("未讀訊息")
        if (isMissedQuery) {
            return CapabilityRequest.NotificationQuery(onlyMissedOrRecent = true)
        }

        val isGeneralNotifQuery = lower.contains("未讀通知") || lower.contains("有什麼通知") ||
            lower.contains("最近的通知") || lower.contains("剛剛的通知") ||
            lower == "查看通知" || lower == "查詢通知" || lower == "念出通知" || lower == "朗讀通知"
        if (isGeneralNotifQuery) {
            return CapabilityRequest.NotificationQuery(onlyMissedOrRecent = false)
        }

        return null
    }

    internal fun queryNotificationsDirect(onlyMissedOrRecent: Boolean = false): CapabilityResult {
        return executeNotificationQuery(CapabilityRequest.NotificationQuery(onlyMissedOrRecent = onlyMissedOrRecent))
    }

    private fun executeNotificationQuery(req: CapabilityRequest.NotificationQuery): CapabilityResult {
        pruneExpiredNotifications()
        if (recentNotifications.isEmpty()) {
            return CapabilityResult(
                succeeded = true,
                summaryText = "目前沒有收到新的系統通知紀錄。",
                request = req
            )
        }

        val topItems = recentNotifications.take(5)
        val formatted = topItems.mapIndexed { idx, item ->
            "${idx + 1}. [${item.appLabel} ${item.formattedTime}] ${item.title}：${item.content}"
        }.joinToString("\n")

        return CapabilityResult(
            succeeded = true,
            summaryText = "最近收到的 ${topItems.size} 則通知：\n$formatted",
            request = req
        )
    }

    // =========================================================================
    // 3. 剪貼簿讀取與寫入 (Clipboard Access)
    // =========================================================================

    private fun parseClipboardRequest(clean: String, lower: String): CapabilityRequest? {
        if (lower.contains("我剛複製了什麼") || lower.contains("剛剛複製了什麼") ||
            lower.contains("剪貼簿裡有什麼") || lower.contains("剪貼簿有什麼") ||
            lower.contains("剪貼簿內容") || lower.contains("讀取剪貼簿") ||
            lower.contains("查看剪貼簿") || lower.contains("查詢剪貼簿")
        ) {
            return CapabilityRequest.ClipboardRead
        }

        if (lower.contains("把剛剛擷取的文字複製") || lower == "複製螢幕上的文字" ||
            lower == "複製畫面上的文字" || lower == "複製螢幕文字"
        ) {
            val capturedText = ScreenTextCapture.lastCapture?.text.orEmpty()
            return CapabilityRequest.ClipboardWrite(textToCopy = capturedText, fromScreenCapture = true)
        }

        val copyExplicit = Regex("^(?:幫我)?複製\\s*[:：]\\s*(.+)$").find(clean)
            ?: Regex("^(?:幫我)?把「(.+)」複製起來$").find(clean)
        if (copyExplicit != null) {
            val text = copyExplicit.groupValues[1].trim()
            if (text.isNotEmpty()) {
                return CapabilityRequest.ClipboardWrite(textToCopy = text, fromScreenCapture = false)
            }
        }

        return null
    }

    private fun executeClipboardRead(context: Context): CapabilityResult {
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return CapabilityResult(false, "無法取得系統剪貼簿服務", CapabilityRequest.ClipboardRead)

            val clip = cm.primaryClip
            val itemText = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
            } else ""

            if (itemText.isEmpty()) {
                CapabilityResult(
                    succeeded = true,
                    summaryText = "目前剪貼簿是空的（或系統限制背景讀取）。",
                    request = CapabilityRequest.ClipboardRead
                )
            } else {
                val preview = if (itemText.length <= 200) itemText else itemText.take(200) + "…"
                CapabilityResult(
                    succeeded = true,
                    summaryText = "目前剪貼簿內容：「$preview」",
                    request = CapabilityRequest.ClipboardRead
                )
            }
        }.getOrElse {
            CapabilityResult(false, "讀取剪貼簿受系統限制", CapabilityRequest.ClipboardRead)
        }
    }

    private fun executeClipboardWrite(req: CapabilityRequest.ClipboardWrite, context: Context): CapabilityResult {
        if (req.textToCopy.isBlank()) {
            val msg = if (req.fromScreenCapture) "目前沒有已擷取的螢幕文字可供複製，請先說「擷取螢幕文字」。" else "沒有可複製的文字內容。"
            return CapabilityResult(false, msg, req)
        }
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return CapabilityResult(false, "無法取得系統剪貼簿服務", req)
            cm.setPrimaryClip(ClipData.newPlainText("AI_Copy", req.textToCopy))
            val preview = if (req.textToCopy.length <= 36) req.textToCopy else req.textToCopy.take(36) + "…"
            CapabilityResult(
                succeeded = true,
                summaryText = "已複製到剪貼簿：「$preview」",
                request = req
            )
        }.getOrElse {
            CapabilityResult(false, "寫入剪貼簿失敗", req)
        }
    }

    // =========================================================================
    // 4. 儲存空間與記憶體狀態查詢 (StatFs + ActivityManager)
    // =========================================================================

    private fun parseStorageAndRamRequest(lower: String): CapabilityRequest.StorageAndRamQuery? {
        val isStorageOrRam = lower.contains("剩多少空間") || lower.contains("還有多少空間") ||
            lower.contains("儲存空間") || lower.contains("剩餘空間") ||
            lower.contains("容量還剩多少") || lower.contains("手機容量") ||
            lower.contains("記憶體還剩多少") || lower.contains("可用記憶體") ||
            lower.contains("ram還剩多少") || lower.contains("ram 還剩多少")
        return if (isStorageOrRam) CapabilityRequest.StorageAndRamQuery else null
    }

    private fun executeStorageAndRamQuery(context: Context): CapabilityResult {
        return runCatching {
            val dataDir = Environment.getDataDirectory()
            val stat = StatFs(dataDir.path)
            val totalBytes = stat.blockSizeLong * stat.blockCountLong
            val availBytes = stat.blockSizeLong * stat.availableBlocksLong
            val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
            val availGb = availBytes / (1024.0 * 1024.0 * 1024.0)
            val usedPct = if (totalBytes > 0) (((totalBytes - availBytes) * 100.0) / totalBytes).roundToInt() else 0

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memStr = if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val availRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
                val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
                "，可用記憶體 ${"%.1f".format(availRamGb)} / ${"%.1f".format(totalRamGb)} GB"
            } else ""

            CapabilityResult(
                succeeded = true,
                summaryText = "手機剩餘儲存空間 ${"%.1f".format(availGb)} / ${"%.1f".format(totalGb)} GB（已用 $usedPct%$memStr）",
                request = CapabilityRequest.StorageAndRamQuery
            )
        }.getOrElse {
            CapabilityResult(
                succeeded = false,
                summaryText = "無法讀取儲存空間狀態",
                request = CapabilityRequest.StorageAndRamQuery
            )
        }
    }

    // =========================================================================
    // 5. 勿擾模式與原生網路快速面板 (NotificationManager / Settings.Panel)
    // =========================================================================

    private fun parseDndAndPanelRequest(lower: String): CapabilityRequest? {
        if (lower.contains("勿擾模式") || lower.contains("請勿打擾")) {
            val turnOff = lower.contains("關") || lower.contains("解除") || lower.contains("取消") || lower.contains("off")
            return CapabilityRequest.DndControl(enableDnd = !turnOff)
        }

        if (lower == "打開網路面板" || lower == "開啟網路面板" || lower == "切換wifi開關" || lower == "切換 wi-fi 開關") {
            return CapabilityRequest.ConnectivityPanel("INTERNET")
        }
        return null
    }

    private fun executeDndControl(req: CapabilityRequest.DndControl, context: Context): CapabilityResult {
        return runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm.isNotificationPolicyAccessGranted) {
                val filter = if (req.enableDnd) {
                    NotificationManager.INTERRUPTION_FILTER_NONE
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                }
                nm.setInterruptionFilter(filter)
                val stateLabel = if (req.enableDnd) "已開啟勿擾模式" else "已關閉勿擾模式"
                return CapabilityResult(true, stateLabel, req)
            }

            // 未授予勿擾存取權限時，直接開啟系統勿擾設定頁
            val intent = Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val opened = runCatching {
                context.startActivity(intent)
                true
            }.getOrElse {
                runCatching {
                    val soundIntent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(soundIntent)
                    true
                }.getOrDefault(false)
            }
            CapabilityResult(
                succeeded = opened,
                summaryText = if (opened) "已開啟系統勿擾模式設定頁" else "無法開啟勿擾設定頁",
                request = req
            )
        }.getOrElse {
            CapabilityResult(false, "切換勿擾模式失敗", req)
        }
    }

    private fun executeConnectivityPanel(req: CapabilityRequest.ConnectivityPanel, context: Context): CapabilityResult {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        val launched = runCatching {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        return CapabilityResult(
            succeeded = launched,
            summaryText = if (launched) "已喚起系統原生網路與 Wi-Fi 快速切換面板" else "無法喚起網路面板",
            request = req
        )
    }

    // =========================================================================
    // 6. 聯絡人電話查詢與姓名直撥 (ContactsContract)
    // =========================================================================

    private fun parseContactLookupRequest(clean: String, lower: String): CapabilityRequest.ContactLookup? {
        // 若包含純電話數字，交由既有 DialPhoneCommand 處理
        if (Regex("\\d{3,}").containsMatchIn(clean)) return null

        // 1. 「王小明的電話幾號」、「查媽媽的電話號碼」
        val queryPhoneMatch = Regex("^(?:幫我)?(?:查詢|查一下|查)?\\s*(.+?)\\s*的(?:電話號碼|電話|手機號碼|號碼)(?:是幾號|幾號|是多少|多少)?[？?]?$").find(clean)
        if (queryPhoneMatch != null) {
            val name = queryPhoneMatch.groupValues[1].trim()
            if (name.isNotEmpty() && name.length <= 12) {
                return CapabilityRequest.ContactLookup(personName = name, dialImmediately = false)
            }
        }

        // 2. 「打電話給媽媽」、「打給王小明」
        val dialNameMatch = Regex("^(?:幫我)?(?:打電話給|撥電話給|打給)\\s*(.+)$").find(clean)
        if (dialNameMatch != null) {
            val name = dialNameMatch.groupValues[1].trim()
            if (name.isNotEmpty() && name.length <= 12 && !name.contains("設定")) {
                return CapabilityRequest.ContactLookup(personName = name, dialImmediately = true)
            }
        }

        return null
    }

    private fun executeContactLookup(req: CapabilityRequest.ContactLookup, context: Context): CapabilityResult {
        val hasContactPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasContactPerm) {
            val foundNumber = queryPhoneByContactName(context, req.personName)
            if (!foundNumber.isNullOrBlank()) {
                if (req.dialImmediately) {
                    val launched = runCatching {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(foundNumber)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(dialIntent)
                        true
                    }.getOrDefault(false)
                    return CapabilityResult(
                        succeeded = launched,
                        summaryText = "已查到「${req.personName}」的電話（$foundNumber）並帶入撥號面板",
                        request = req
                    )
                } else {
                    return CapabilityResult(
                        succeeded = true,
                        summaryText = "聯絡人「${req.personName}」的電話是：$foundNumber",
                        request = req
                    )
                }
            }
        }

        // 若未授予聯絡人權限或找不到該筆姓名，開啟系統聯絡人頁面讓使用者查看
        val openedContacts = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        val reason = if (!hasContactPerm) {
            "尚未授予讀取聯絡人權限，已為您開啟通訊錄（授權後即可直接依姓名查號撥打）"
        } else {
            "通訊錄中找不到「${req.personName}」，已為您開啟通訊錄"
        }
        return CapabilityResult(
            succeeded = openedContacts,
            summaryText = reason,
            request = req
        )
    }

    private fun queryPhoneByContactName(context: Context, nameQuery: String): String? {
        return runCatching {
            val resolver = context.contentResolver
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$nameQuery%")
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIdx >= 0) cursor.getString(numberIdx)?.trim() else null
                } else null
            }
        }.getOrNull()
    }

    // =========================================================================
    // 7. 行事曆行程查詢 (CalendarContract)
    // =========================================================================

    private fun parseCalendarQueryRequest(clean: String, lower: String): CapabilityRequest.CalendarQuery? {
        // 排除新增行程
        if (lower.contains("新增") || lower.contains("建立") || lower.contains("加") || lower.contains("排")) {
            return null
        }

        val isCalendarQuery = lower.contains("有什麼行程") || lower.contains("有哪些行程") ||
            lower.contains("查詢行事曆") || lower.contains("查看行事曆") || lower.contains("查看日曆") ||
            lower.contains("查詢日曆") || lower.contains("今天的行程") || lower.contains("明天的行程") ||
            lower.contains("後天的行程") || lower.contains("今天行程") || lower.contains("明天行程") ||
            lower.contains("後天行程") || lower.contains("查詢行程") || lower.contains("查行程")
        if (!isCalendarQuery) return null

        val (dayOffset, dayLabel) = when {
            clean.contains("後天") -> 2 to "後天"
            clean.contains("明天") || clean.contains("明日") -> 1 to "明天"
            else -> 0 to "今天"
        }
        return CapabilityRequest.CalendarQuery(dayOffset = dayOffset, dayLabel = dayLabel)
    }

    private fun executeCalendarQuery(req: CapabilityRequest.CalendarQuery, context: Context): CapabilityResult {
        val calStart = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, req.dayOffset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMillis = calStart.timeInMillis
        val endMillis = startMillis + 24 * 60 * 60 * 1000L - 1L

        val hasCalendarPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasCalendarPerm) {
            val events = queryCalendarEventsForRange(context, startMillis, endMillis)
            if (events != null) {
                return if (events.isEmpty()) {
                    CapabilityResult(
                        succeeded = true,
                        summaryText = "${req.dayLabel}行事曆上沒有安排任何行程。",
                        request = req
                    )
                } else {
                    CapabilityResult(
                        succeeded = true,
                        summaryText = "${req.dayLabel}共有 ${events.size} 個行程：\n${events.joinToString("\n")}",
                        request = req
                    )
                }
            }
        }

        // 未授予讀取日曆權限時，直接開啟系統日曆並定位至該日期
        val opened = runCatching {
            val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            ContentUris.appendId(builder, startMillis)
            val intent = Intent(Intent.ACTION_VIEW).setData(builder.build()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        return CapabilityResult(
            succeeded = opened,
            summaryText = if (!hasCalendarPerm) {
                if (opened) "尚未授予讀取行事曆權限，已先為您開啟系統日曆查看「${req.dayLabel}」的行程" else "尚未授予讀取行事曆權限，請於系統設定允許日曆權限以直接查詢行程"
            } else {
                if (opened) "已直接開啟系統日曆查看「${req.dayLabel}」的行程" else "無法開啟系統日曆"
            },
            request = req
        )
    }

    private fun queryCalendarEventsForRange(context: Context, startMillis: Long, endMillis: Long): List<String>? {
        return runCatching {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMillis)
            ContentUris.appendId(builder, endMillis)
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN
            )
            val list = ArrayList<String>()
            val fmt = SimpleDateFormat("HH:mm", Locale.TAIWAN)
            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                while (cursor.moveToNext() && list.size < 8) {
                    val title = if (titleIdx >= 0) cursor.getString(titleIdx)?.trim().orEmpty() else ""
                    val begin = if (beginIdx >= 0) cursor.getLong(beginIdx) else startMillis
                    val timeStr = fmt.format(Date(begin))
                    list.add("- $timeStr ${title.ifEmpty { "（未命名行程）" }}")
                }
            }
            list
        }.getOrNull()
    }

    // =========================================================================
    // 8. 目前位置與定位查詢 (LocationManager + geo:)
    // =========================================================================

    private fun parseLocationQueryRequest(lower: String): CapabilityRequest.LocationQuery? {
        if (lower.contains("我現在在哪") || lower.contains("目前位置") ||
            lower.contains("我的位置在哪") || lower.contains("現在位置在哪")
        ) {
            return CapabilityRequest.LocationQuery
        }
        return null
    }

    private fun executeLocationQuery(context: Context): CapabilityResult {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGpsOn = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm?.isLocationEnabled == true
            } else {
                lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            }
        }.getOrDefault(false)

        val hasLocPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasLocPerm && lm != null) {
            val lastLoc = runCatching {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }.getOrNull()

            if (lastLoc != null) {
                val lat = "%.5f".format(lastLoc.latitude)
                val lng = "%.5f".format(lastLoc.longitude)
                return CapabilityResult(
                    succeeded = true,
                    summaryText = "目前定位座標：緯度 $lat、經度 $lng（GPS 狀態：${if (isGpsOn) "已開啟" else "未開啟"}）",
                    request = CapabilityRequest.LocationQuery
                )
            }
        }

        val mapOpened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        val statusDesc = if (isGpsOn) "系統定位已開啟" else "系統定位目前關閉"
        val permHint = if (!hasLocPerm) "（尚未授予應用程式定位權限）" else ""
        return CapabilityResult(
            succeeded = mapOpened || isGpsOn,
            summaryText = "$statusDesc$permHint，已為您打開地圖定位目前位置",
            request = CapabilityRequest.LocationQuery
        )
    }

    // =========================================================================
    // 9. 本機最新照片與下載檔案檢視 (MediaStore / DownloadManager)
    // =========================================================================

    private fun parseMediaAndDownloadsRequest(lower: String): CapabilityRequest.MediaAndDownloadsQuery? {
        if (lower.contains("打開下載資料夾") || lower.contains("查看下載檔案") ||
            lower.contains("最近下載的檔案") || lower == "查看下載"
        ) {
            return CapabilityRequest.MediaAndDownloadsQuery("DOWNLOADS")
        }
        if (lower.contains("找最近拍的照片") || lower.contains("最近拍的照片") ||
            lower.contains("查看最近的照片")
        ) {
            return CapabilityRequest.MediaAndDownloadsQuery("PHOTOS")
        }
        return null
    }

    private fun executeMediaAndDownloadsQuery(
        req: CapabilityRequest.MediaAndDownloadsQuery,
        context: Context,
        accessibilityService: AccessibilityService?
    ): CapabilityResult {
        if (req.targetType == "DOWNLOADS") {
            val opened = runCatching {
                val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            return CapabilityResult(
                succeeded = opened,
                summaryText = if (opened) "已直接開啟系統「下載」檔案列表" else "無法開啟下載資料夾",
                request = req
            )
        }

        val openedPhotos = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        return CapabilityResult(
            succeeded = openedPhotos,
            summaryText = if (openedPhotos) "已直接開啟系統相簿檢視最新照片" else "無法開啟系統相簿",
            request = req
        )
    }
}

