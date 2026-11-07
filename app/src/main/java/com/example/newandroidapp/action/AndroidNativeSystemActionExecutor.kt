package com.example.newandroidapp.action

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * 【Android 原生系統指令直通執行器 (`AndroidNativeSystemActionExecutor`)】
 *
 * 100% 透過 Android OS 原生底層 API（`AudioManager`、`AlarmClock`、`CameraManager`、
 * `AccessibilityService.performGlobalAction`、`Settings` Deep Intent）瞬間完成系統控制與預設應用指令，
 * 完全免開啟 App 畫面慢慢點按鈕，零剪貼簿佔用、零網路依賴、零第三方套件。
 *
 * 支援五大原生能力：
 * 1. **音量控制 (`AudioManager`)**：調整媒體/鈴聲/鬧鐘/通知/通話音量（調大、調小、指定百分比、最大、最小、靜音、解除靜音）。
 * 2. **鬧鐘與計時器 (`AlarmClock`)**：直接以 `EXTRA_SKIP_UI = true` 在背景設定指定幾點幾分的鬧鐘，或設定幾分幾秒的倒數計時器。
 * 3. **硬體手電筒 (`CameraManager.setTorchMode`)**：瞬間開啟或關閉相機閃光燈手電筒。
 * 4. **全域系統動作 (`AccessibilityService.performGlobalAction`)**：回桌面、返回上一頁、打開通知欄、打開快速設定、最近使用的應用程式、系統截圖、鎖定螢幕。
 * 5. **系統設定子頁直達 (`Settings` Deep Intent)**：直接開啟 Wi-Fi、藍牙、飛航模式、聲音、顯示、電池、定位、NFC 等設定子頁面。
 */
object AndroidNativeSystemActionExecutor {

    enum class AudioStreamTarget(val streamType: Int, val displayLabel: String) {
        MUSIC(AudioManager.STREAM_MUSIC, "媒體音量"),
        RING(AudioManager.STREAM_RING, "鈴聲音量"),
        ALARM(AudioManager.STREAM_ALARM, "鬧鐘音量"),
        NOTIFICATION(AudioManager.STREAM_NOTIFICATION, "通知音量"),
        VOICE_CALL(AudioManager.STREAM_VOICE_CALL, "通話音量")
    }

    enum class VolumeOperation {
        RAISE,
        LOWER,
        SET_PERCENT,
        MUTE,
        UNMUTE,
        MAX,
        MIN
    }

    enum class GlobalSystemAction(val globalActionId: Int, val displayLabel: String) {
        HOME(AccessibilityService.GLOBAL_ACTION_HOME, "回到主畫面"),
        BACK(AccessibilityService.GLOBAL_ACTION_BACK, "返回上一頁"),
        RECENTS(AccessibilityService.GLOBAL_ACTION_RECENTS, "打開最近使用的應用程式"),
        NOTIFICATIONS(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "拉下通知欄"),
        QUICK_SETTINGS(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "拉下快速設定面板"),
        SCREENSHOT(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else 9,
            "執行系統截圖"
        ),
        LOCK_SCREEN(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else 8,
            "鎖定螢幕"
        )
    }

    sealed class NativeSystemCommand {
        data class VolumeCommand(
            val stream: AudioStreamTarget,
            val operation: VolumeOperation,
            val targetPercent: Int? = null
        ) : NativeSystemCommand()

        data class SetAlarmCommand(
            val hour24: Int,
            val minute: Int,
            val label: String = "",
            val skipUi: Boolean = true
        ) : NativeSystemCommand()

        data class SetTimerCommand(
            val totalSeconds: Int,
            val label: String = "",
            val skipUi: Boolean = true
        ) : NativeSystemCommand()

        data class FlashlightCommand(
            val enable: Boolean
        ) : NativeSystemCommand()

        data class GlobalActionCommand(
            val action: GlobalSystemAction
        ) : NativeSystemCommand()

        data class OpenSettingsSubpageCommand(
            val settingsAction: String,
            val pageTitle: String
        ) : NativeSystemCommand()
    }

    data class ExecutionResult(
        val handled: Boolean,
        val succeeded: Boolean,
        val summaryText: String,
        val command: NativeSystemCommand? = null
    )

    private val MULTI_STEP_CHAIN_MARKERS = listOf(
        "然後", "接著", "之後", "再去", "並且在", "並搜尋", "並點", "再輸入"
    )

    /**
     * 解析自然語言指令，判斷是否屬於可由 Android 原生系統 API 直接執行的單步系統指令。
     * 若指令包含跨 App 搜尋或多步操作（如「打開時鐘 點擊 鬧鐘 新增」或「去 YouTube 搜尋...」），回傳 `null` 交由視覺規劃器處理。
     */
    fun parseCommand(rawGoal: String): NativeSystemCommand? {
        val clean = rawGoal.trim()
        if (clean.isEmpty()) return null
        val lower = clean.lowercase()

        // 若是視覺規劃器展開的顯式 UI 步驟（如「打開設定 搜尋...」、「打開時鐘 點擊...」），不攔截，以維持既有規劃測試相容性
        if (clean.startsWith("打開設定 ") || clean.startsWith("打開時鐘 ") ||
            clean.startsWith("打開 ") || clean.startsWith("開啟 ")
        ) {
            val afterOpen = clean.replace(Regex("^(打開|開啟)\\s*"), "")
            if (afterOpen.contains("搜尋") || afterOpen.contains("點擊") || afterOpen.contains("輸入")) {
                return null
            }
        }
        if (MULTI_STEP_CHAIN_MARKERS.any { lower.contains(it) }) {
            return null
        }

        // 1. 解析硬體手電筒指令
        parseFlashlightCommand(lower)?.let { return it }

        // 2. 解析音量與靜音指令
        parseVolumeCommand(clean, lower)?.let { return it }

        // 3. 解析設定鬧鐘指令（含具體時間，如「設定早上 7 點 30 分的鬧鐘」）
        parseSetAlarmCommand(clean, lower)?.let { return it }

        // 4. 解析倒數計時器指令（如「計時 5 分鐘」、「倒數 30 秒」）
        parseSetTimerCommand(clean, lower)?.let { return it }

        // 5. 解析全域系統動作（回桌面、通知欄、快速設定、多工畫面、截圖、鎖屏）
        parseGlobalActionCommand(lower)?.let { return it }

        // 6. 解析系統設定子頁直達（如「打開 Wi-Fi 設定」、「打開藍牙設定」）
        parseSettingsSubpageCommand(lower)?.let { return it }

        return null
    }

    /**
     * 嘗試直接執行 Android 原生系統指令。
     * 若指令不屬於原生系統指令則回傳 `ExecutionResult(handled = false, ...)`。
     */
    fun tryExecuteIfMatched(
        rawGoal: String,
        context: Context,
        accessibilityService: AccessibilityService? = null
    ): ExecutionResult {
        val cmd = parseCommand(rawGoal)
            ?: return ExecutionResult(handled = false, succeeded = false, summaryText = "", command = null)
        return executeCommand(cmd, context, accessibilityService)
    }

    fun executeCommand(
        cmd: NativeSystemCommand,
        context: Context,
        accessibilityService: AccessibilityService? = null
    ): ExecutionResult {
        return when (cmd) {
            is NativeSystemCommand.VolumeCommand -> executeVolumeCommand(cmd, context)
            is NativeSystemCommand.SetAlarmCommand -> executeSetAlarmCommand(cmd, context)
            is NativeSystemCommand.SetTimerCommand -> executeSetTimerCommand(cmd, context)
            is NativeSystemCommand.FlashlightCommand -> executeFlashlightCommand(cmd, context)
            is NativeSystemCommand.GlobalActionCommand -> executeGlobalActionCommand(cmd, accessibilityService)
            is NativeSystemCommand.OpenSettingsSubpageCommand -> executeOpenSettingsSubpage(cmd, context)
        }
    }

    // =========================================================================
    // 1. 音量控制解析與執行 (AudioManager)
    // =========================================================================

    private fun parseVolumeCommand(clean: String, lower: String): NativeSystemCommand.VolumeCommand? {
        val hasVolumeWord = lower.contains("音量") || lower.contains("聲音") ||
            lower.contains("大聲") || lower.contains("小聲") ||
            lower.contains("靜音") || lower.contains("volume") || lower.contains("mute")
        if (!hasVolumeWord) return null

        // 排除開啟設定頁的句子（例如「打開設定 搜尋 聲音與震動」）
        if (lower.contains("打開設定") || lower.contains("開啟設定") || lower.contains("聲音設定")) {
            return null
        }

        val stream = when {
            lower.contains("鬧鐘") -> AudioStreamTarget.ALARM
            lower.contains("鈴聲") || lower.contains("來電") -> AudioStreamTarget.RING
            lower.contains("通知") || lower.contains("訊息音") -> AudioStreamTarget.NOTIFICATION
            lower.contains("通話") || lower.contains("聽筒") -> AudioStreamTarget.VOICE_CALL
            else -> AudioStreamTarget.MUSIC
        }

        // 解除靜音
        if (lower.contains("解除靜音") || lower.contains("取消靜音") || lower.contains("關閉靜音") ||
            lower.contains("打開聲音") || lower.contains("開啟聲音") || lower.contains("恢復聲音") ||
            lower.contains("unmute")
        ) {
            return NativeSystemCommand.VolumeCommand(stream = stream, operation = VolumeOperation.UNMUTE)
        }

        // 靜音
        if (lower.contains("靜音") || lower == "mute" || lower.contains("關掉聲音") || lower.contains("把聲音關掉")) {
            return NativeSystemCommand.VolumeCommand(stream = stream, operation = VolumeOperation.MUTE)
        }

        // 調到最大 / 最小
        if (lower.contains("最大") || lower.contains("開到最滿") || lower.contains("調到最滿") ||
            Regex("(?<!\\d)100\\s*[%％]").containsMatchIn(lower)
        ) {
            return NativeSystemCommand.VolumeCommand(stream = stream, operation = VolumeOperation.MAX, targetPercent = 100)
        }
        if (lower.contains("最小") || Regex("(?<!\\d)0\\s*[%％]").containsMatchIn(lower)) {
            return NativeSystemCommand.VolumeCommand(stream = stream, operation = VolumeOperation.MIN, targetPercent = 0)
        }

        // 指定百分比或數值（例如「把音量調到 50%」、「音量設為 60」、「音量調到一半」）
        if (lower.contains("一半")) {
            return NativeSystemCommand.VolumeCommand(stream = stream, operation = VolumeOperation.SET_PERCENT, targetPercent = 50)
        }
        val pctMatch = Regex("(?:調到|設為|設成|調成|設定為|改為|改成|至|到)?\\s*(\\d{1,3})\\s*(?:%|％|趴|格|成)?").find(clean)
        if (pctMatch != null && (lower.contains("調") || lower.contains("設") || lower.contains("改") || lower.contains("%") || lower.contains("％") || lower.contains("趴"))) {
            val rawVal = pctMatch.groupValues[1].toIntOrNull()
            if (rawVal != null) {
                val pct = if (clean.contains("成") && rawVal in 1..10 && !clean.contains("設成") && !clean.contains("調成") && !clean.contains("改成")) {
                    rawVal * 10
                } else {
                    rawVal.coerceIn(0, 100)
                }
                return NativeSystemCommand.VolumeCommand(
                    stream = stream,
                    operation = VolumeOperation.SET_PERCENT,
                    targetPercent = pct
                )
            }
        }

        // 調大 / 調高 / 大聲一點
        if (lower.contains("調大") || lower.contains("調高") || lower.contains("大聲") ||
            lower.contains("加大") || lower.contains("增加") || lower.contains("太小聲") ||
            lower.contains("聽不見") || lower.contains("volume up")
        ) {
            return NativeSystemCommand.VolumeCommand(stream = stream, operation = VolumeOperation.RAISE)
        }

        // 調小 / 調低 / 小聲一點
        if (lower.contains("調小") || lower.contains("調低") || lower.contains("小聲") ||
            lower.contains("減小") || lower.contains("降低") || lower.contains("太吵") ||
            lower.contains("太大聲") || lower.contains("關小") || lower.contains("volume down")
        ) {
            return NativeSystemCommand.VolumeCommand(stream = stream, operation = VolumeOperation.LOWER)
        }

        return null
    }

    private fun executeVolumeCommand(
        cmd: NativeSystemCommand.VolumeCommand,
        context: Context
    ): ExecutionResult {
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return ExecutionResult(
                    handled = true,
                    succeeded = false,
                    summaryText = "無法取得系統音訊管理服務",
                    command = cmd
                )

            val streamType = cmd.stream.streamType
            val maxVol = am.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { am.getStreamMinVolume(streamType) }.getOrDefault(0)
            } else 0
            val curVol = am.getStreamVolume(streamType)
            val flags = AudioManager.FLAG_SHOW_UI

            when (cmd.operation) {
                VolumeOperation.MUTE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, flags)
                    } else {
                        am.setStreamVolume(streamType, minVol, flags)
                    }
                    ExecutionResult(
                        handled = true,
                        succeeded = true,
                        summaryText = "已將${cmd.stream.displayLabel}設為靜音",
                        command = cmd
                    )
                }
                VolumeOperation.UNMUTE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, flags)
                    }
                    val afterVol = am.getStreamVolume(streamType)
                    if (afterVol <= minVol) {
                        val half = ((maxVol - minVol) * 0.5f + minVol).roundToInt().coerceIn(minVol, maxVol)
                        am.setStreamVolume(streamType, half, flags)
                    }
                    val finalVol = am.getStreamVolume(streamType)
                    val pct = ((finalVol.toFloat() / maxVol.toFloat()) * 100f).roundToInt()
                    ExecutionResult(
                        handled = true,
                        succeeded = true,
                        summaryText = "已解除${cmd.stream.displayLabel}靜音（目前 $pct%）",
                        command = cmd
                    )
                }
                VolumeOperation.MAX -> {
                    am.setStreamVolume(streamType, maxVol, flags)
                    ExecutionResult(
                        handled = true,
                        succeeded = true,
                        summaryText = "已將${cmd.stream.displayLabel}調至最大（100%）",
                        command = cmd
                    )
                }
                VolumeOperation.MIN -> {
                    am.setStreamVolume(streamType, minVol, flags)
                    ExecutionResult(
                        handled = true,
                        succeeded = true,
                        summaryText = "已將${cmd.stream.displayLabel}調至最小",
                        command = cmd
                    )
                }
                VolumeOperation.SET_PERCENT -> {
                    val pct = (cmd.targetPercent ?: 50).coerceIn(0, 100)
                    val targetIndex = if (pct == 0) {
                        minVol
                    } else {
                        ((maxVol - minVol) * (pct / 100f) + minVol).roundToInt().coerceIn(minVol, maxVol)
                    }
                    if (pct > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching { am.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0) }
                    }
                    am.setStreamVolume(streamType, targetIndex, flags)
                    ExecutionResult(
                        handled = true,
                        succeeded = true,
                        summaryText = "已將${cmd.stream.displayLabel}調整為 $pct%（$targetIndex/$maxVol）",
                        command = cmd
                    )
                }
                VolumeOperation.RAISE -> {
                    val step = (maxVol * 0.15f).roundToInt().coerceAtLeast(1)
                    val nextVol = (curVol + step).coerceIn(minVol, maxVol)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching { am.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0) }
                    }
                    am.setStreamVolume(streamType, nextVol, flags)
                    val pct = ((nextVol.toFloat() / maxVol.toFloat()) * 100f).roundToInt()
                    ExecutionResult(
                        handled = true,
                        succeeded = true,
                        summaryText = "已調高${cmd.stream.displayLabel}至 $pct%（$nextVol/$maxVol）",
                        command = cmd
                    )
                }
                VolumeOperation.LOWER -> {
                    val step = (maxVol * 0.15f).roundToInt().coerceAtLeast(1)
                    val nextVol = (curVol - step).coerceIn(minVol, maxVol)
                    am.setStreamVolume(streamType, nextVol, flags)
                    val pct = ((nextVol.toFloat() / maxVol.toFloat()) * 100f).roundToInt()
                    ExecutionResult(
                        handled = true,
                        succeeded = true,
                        summaryText = "已調低${cmd.stream.displayLabel}至 $pct%（$nextVol/$maxVol）",
                        command = cmd
                    )
                }
            }
        }.getOrElse { e ->
            ExecutionResult(
                handled = true,
                succeeded = false,
                summaryText = "調整${cmd.stream.displayLabel}受系統限制（${e.javaClass.simpleName}）",
                command = cmd
            )
        }
    }

    // =========================================================================
    // 2. 原生鬧鐘設定解析與執行 (AlarmClock.ACTION_SET_ALARM)
    // =========================================================================

    private fun parseSetAlarmCommand(clean: String, lower: String): NativeSystemCommand.SetAlarmCommand? {
        val hasAlarmIntent = lower.contains("鬧鐘") || lower.contains("叫我") || lower.contains("提醒我起床")
        if (!hasAlarmIntent) return null
        // 排除查鬧鐘或刪除/關閉鬧鐘
        if (lower.contains("刪除") || lower.contains("取消") || lower.contains("關掉鬧鐘") || lower.contains("關閉鬧鐘")) {
            return null
        }

        val timePair = extractHourAndMinute(clean) ?: return null
        val (hour24, minute) = timePair

        // 提取可選鬧鐘標籤（例如「設早上7點30分起床的鬧鐘」或「設8點開會鬧鐘」）
        var label = ""
        val afterTimeMatch = Regex("(?:\\d{1,2}\\s*[:：]\\s*\\d{1,2}|[零一二兩三四五六七八九十\\d]{1,3}\\s*(?:點|時)(?:\\s*半|\\s*[零一二三四五六七八九十\\d]{1,3}\\s*分?)?)\\s*(.*)$").find(clean)
        if (afterTimeMatch != null) {
            val candidate = afterTimeMatch.groupValues[1]
                .replace(Regex("(?:的)?鬧鐘.*$"), "")
                .replace(Regex("^(?:的|去|要)\\s*"), "")
                .trim()
            if (candidate.isNotBlank() && candidate.length <= 12) {
                label = candidate
            }
        }

        return NativeSystemCommand.SetAlarmCommand(
            hour24 = hour24,
            minute = minute,
            label = label,
            skipUi = true
        )
    }

    internal fun extractHourAndMinute(text: String): Pair<Int, Int>? {
        val isPm = text.contains("下午") || text.contains("晚上") || text.contains("傍晚") || text.contains("夜間") || text.lowercase().contains("pm")
        val isEarlyMorning = text.contains("凌晨") || text.contains("早上") || text.contains("上午") || text.contains("清晨") || text.lowercase().contains("am")

        // 格式 A：HH:MM（如 07:30、19:45）
        val colonMatch = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})").find(text)
        if (colonMatch != null) {
            val rawH = colonMatch.groupValues[1].toIntOrNull() ?: return null
            val rawM = colonMatch.groupValues[2].toIntOrNull() ?: return null
            if (rawH in 0..23 && rawM in 0..59) {
                val h24 = normalizeHour24(rawH, isPm, isEarlyMorning)
                return h24 to rawM
            }
        }

        // 格式 B：中文/阿拉伯數字「X點半」、「X點Y分」、「X點」
        val timeRegex = Regex("([零一二兩三四五六七八九十\\d]{1,3})\\s*(?:點|時)\\s*(半|([零一二三四五六七八九十\\d]{1,3})\\s*分?)?")
        val match = timeRegex.find(text) ?: return null
        val rawHourStr = match.groupValues[1]
        val halfOrMinFull = match.groupValues[2]
        val rawMinStr = match.groupValues[3]

        val rawHour = parseChineseOrArabicNumber(rawHourStr) ?: return null
        if (rawHour !in 0..24) return null

        val minute = when {
            halfOrMinFull == "半" -> 30
            rawMinStr.isNotBlank() -> (parseChineseOrArabicNumber(rawMinStr) ?: 0).coerceIn(0, 59)
            else -> 0
        }
        val hour24 = normalizeHour24(rawHour, isPm, isEarlyMorning)
        return hour24 to minute
    }

    private fun normalizeHour24(rawHour: Int, isPm: Boolean, isEarlyMorning: Boolean): Int {
        return when {
            isPm && rawHour in 1..11 -> rawHour + 12
            isEarlyMorning && rawHour == 12 -> 0
            rawHour == 24 -> 0
            else -> rawHour.coerceIn(0, 23)
        }
    }

    internal fun parseChineseOrArabicNumber(raw: String): Int? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { return it }

        val digitMap = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2,
            '三' to 3, '四' to 4, '五' to 5, '六' to 6,
            '七' to 7, '八' to 8, '九' to 9
        )
        if (trimmed == "十") return 10
        if (trimmed.contains('十')) {
            val parts = trimmed.split('十')
            val tens = if (parts[0].isEmpty()) 1 else (digitMap[parts[0].first()] ?: return null)
            val ones = if (parts.size < 2 || parts[1].isEmpty()) 0 else (digitMap[parts[1].first()] ?: return null)
            return tens * 10 + ones
        }
        var result = 0
        for (ch in trimmed) {
            val d = digitMap[ch] ?: return null
            result = result * 10 + d
        }
        return result
    }

    private fun executeSetAlarmCommand(
        cmd: NativeSystemCommand.SetAlarmCommand,
        context: Context
    ): ExecutionResult {
        val timeFormatted = "%02d:%02d".format(cmd.hour24, cmd.minute)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, cmd.hour24)
            putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, cmd.skipUi)
            if (cmd.label.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, cmd.label)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        val labelSuffix = if (cmd.label.isNotBlank()) "（${cmd.label}）" else ""
        return if (launched) {
            ExecutionResult(
                handled = true,
                succeeded = true,
                summaryText = "已透過系統原生指令設定 $timeFormatted 鬧鐘$labelSuffix",
                command = cmd
            )
        } else {
            ExecutionResult(
                handled = true,
                succeeded = false,
                summaryText = "系統未安裝支援原生鬧鐘指令的時鐘應用",
                command = cmd
            )
        }
    }

    // =========================================================================
    // 3. 原生倒數計時器解析與執行 (AlarmClock.ACTION_SET_TIMER)
    // =========================================================================

    private fun parseSetTimerCommand(clean: String, lower: String): NativeSystemCommand.SetTimerCommand? {
        val hasTimerKeyword = lower.contains("計時") || lower.contains("倒數") || lower.contains("timer")
        if (!hasTimerKeyword) return null

        var totalSeconds = 0
        val hourMatch = Regex("([零一二兩三四五六七八九十\\d]{1,3})\\s*(?:小時|鐘頭)").find(clean)
        val minMatch = Regex("([零一二兩三四五六七八九十\\d]{1,3})\\s*分(?:鐘)?").find(clean)
        val secMatch = Regex("([零一二兩三四五六七八九十\\d]{1,3})\\s*秒(?:鐘)?").find(clean)

        if (hourMatch != null) {
            totalSeconds += (parseChineseOrArabicNumber(hourMatch.groupValues[1]) ?: 0) * 3600
        }
        if (minMatch != null) {
            totalSeconds += (parseChineseOrArabicNumber(minMatch.groupValues[1]) ?: 0) * 60
        }
        if (secMatch != null) {
            totalSeconds += (parseChineseOrArabicNumber(secMatch.groupValues[1]) ?: 0)
        }

        if (totalSeconds <= 0) return null
        return NativeSystemCommand.SetTimerCommand(
            totalSeconds = totalSeconds,
            label = "AI 計時器",
            skipUi = true
        )
    }

    private fun executeSetTimerCommand(
        cmd: NativeSystemCommand.SetTimerCommand,
        context: Context
    ): ExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, cmd.totalSeconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, cmd.skipUi)
            if (cmd.label.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, cmd.label)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        val minPart = cmd.totalSeconds / 60
        val secPart = cmd.totalSeconds % 60
        val readableDuration = buildString {
            if (minPart > 0) append("${minPart}分")
            if (secPart > 0 || minPart == 0) append("${secPart}秒")
        }
        return if (launched) {
            ExecutionResult(
                handled = true,
                succeeded = true,
                summaryText = "已透過系統原生指令啟動 $readableDuration 倒數計時器",
                command = cmd
            )
        } else {
            ExecutionResult(
                handled = true,
                succeeded = false,
                summaryText = "系統未安裝支援原生計時器指令的時鐘應用",
                command = cmd
            )
        }
    }

    // =========================================================================
    // 4. 硬體手電筒開關 (CameraManager.setTorchMode)
    // =========================================================================

    private fun parseFlashlightCommand(lower: String): NativeSystemCommand.FlashlightCommand? {
        if (!lower.contains("手電筒") && !lower.contains("閃光燈") && !lower.contains("flashlight") && !lower.contains("torch")) {
            return null
        }
        val isTurnOff = lower.contains("關") || lower.contains("off") || lower.contains("熄")
        val isTurnOn = lower.contains("開") || lower.contains("on") || lower.contains("亮") || !isTurnOff
        return NativeSystemCommand.FlashlightCommand(enable = isTurnOn && !isTurnOff)
    }

    private fun executeFlashlightCommand(
        cmd: NativeSystemCommand.FlashlightCommand,
        context: Context
    ): ExecutionResult {
        return runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ExecutionResult(
                    handled = true,
                    succeeded = false,
                    summaryText = "無法存取系統相機閃光燈服務",
                    command = cmd
                )

            val torchCameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cm.cameraIdList.firstOrNull()

            if (torchCameraId == null) {
                return ExecutionResult(
                    handled = true,
                    succeeded = false,
                    summaryText = "此裝置未偵測到支援手電筒的閃光燈硬體",
                    command = cmd
                )
            }

            cm.setTorchMode(torchCameraId, cmd.enable)
            val actionWord = if (cmd.enable) "開啟" else "關閉"
            ExecutionResult(
                handled = true,
                succeeded = true,
                summaryText = "已直接為您${actionWord}手電筒",
                command = cmd
            )
        }.getOrElse { e ->
            ExecutionResult(
                handled = true,
                succeeded = false,
                summaryText = "手電筒切換失敗（${e.javaClass.simpleName}）",
                command = cmd
            )
        }
    }

    // =========================================================================
    // 5. 全域系統動作 (AccessibilityService.performGlobalAction)
    // =========================================================================

    private fun parseGlobalActionCommand(lower: String): NativeSystemCommand.GlobalActionCommand? {
        return when {
            lower == "回桌面" || lower == "回到桌面" || lower == "回首頁" || lower == "回到主畫面" || lower == "回主畫面" -> {
                NativeSystemCommand.GlobalActionCommand(GlobalSystemAction.HOME)
            }
            lower == "返回" || lower == "返回上一頁" || lower == "回上一頁" || lower == "上一頁" -> {
                NativeSystemCommand.GlobalActionCommand(GlobalSystemAction.BACK)
            }
            lower.contains("最近使用的應用") || lower.contains("多工畫面") || lower.contains("背景應用") -> {
                NativeSystemCommand.GlobalActionCommand(GlobalSystemAction.RECENTS)
            }
            lower == "打開通知欄" || lower == "拉下通知欄" || lower == "查看通知欄" || lower == "開啟通知欄" -> {
                NativeSystemCommand.GlobalActionCommand(GlobalSystemAction.NOTIFICATIONS)
            }
            lower == "打開快速設定" || lower == "拉下快速設定" || lower == "開啟快速設定" || lower == "打開控制中心" -> {
                NativeSystemCommand.GlobalActionCommand(GlobalSystemAction.QUICK_SETTINGS)
            }
            lower == "截圖" || lower == "幫我截圖" || lower == "螢幕截圖" || lower == "畫面截圖" -> {
                NativeSystemCommand.GlobalActionCommand(GlobalSystemAction.SCREENSHOT)
            }
            lower == "鎖定螢幕" || lower == "鎖屏" || lower == "把螢幕鎖起來" || lower == "關閉螢幕" -> {
                NativeSystemCommand.GlobalActionCommand(GlobalSystemAction.LOCK_SCREEN)
            }
            else -> null
        }
    }

    private fun executeGlobalActionCommand(
        cmd: NativeSystemCommand.GlobalActionCommand,
        accessibilityService: AccessibilityService?
    ): ExecutionResult {
        if (accessibilityService == null) {
            return ExecutionResult(
                handled = true,
                succeeded = false,
                summaryText = "執行「${cmd.action.displayLabel}」需先開啟無障礙服務（點右上角開啟懸浮球）",
                command = cmd
            )
        }
        val ok = runCatching {
            accessibilityService.performGlobalAction(cmd.action.globalActionId)
        }.getOrDefault(false)
        return ExecutionResult(
            handled = true,
            succeeded = ok,
            summaryText = if (ok) "已執行系統原生動作：${cmd.action.displayLabel}" else "系統拒絕執行：${cmd.action.displayLabel}",
            command = cmd
        )
    }

    // =========================================================================
    // 6. 系統設定子頁一鍵直達 (Settings Deep Intent)
    // =========================================================================

    private fun parseSettingsSubpageCommand(lower: String): NativeSystemCommand.OpenSettingsSubpageCommand? {
        val wantsOpenOrSet = lower.contains("打開") || lower.contains("開啟") || lower.contains("進入") ||
            lower.contains("前往") || lower.contains("去") || lower.endsWith("設定")
        if (!wantsOpenOrSet) return null

        return when {
            (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("無線網路")) && lower.contains("設定") -> {
                NativeSystemCommand.OpenSettingsSubpageCommand(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi 設定")
            }
            lower.contains("藍牙") && lower.contains("設定") -> {
                NativeSystemCommand.OpenSettingsSubpageCommand(Settings.ACTION_BLUETOOTH_SETTINGS, "藍牙設定")
            }
            (lower.contains("飛航模式") || lower.contains("飛行模式")) && (lower.contains("設定") || lower.contains("打開") || lower.contains("開啟") || lower.contains("關閉")) -> {
                NativeSystemCommand.OpenSettingsSubpageCommand(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "飛航模式設定")
            }
            (lower.contains("聲音設定") || lower.contains("音效設定") || lower.contains("震動設定")) -> {
                NativeSystemCommand.OpenSettingsSubpageCommand(Settings.ACTION_SOUND_SETTINGS, "聲音與震動設定")
            }
            (lower.contains("顯示設定") || lower.contains("螢幕亮度設定") || lower.contains("亮度設定")) -> {
                NativeSystemCommand.OpenSettingsSubpageCommand(Settings.ACTION_DISPLAY_SETTINGS, "顯示與亮度設定")
            }
            (lower.contains("電池設定") || lower.contains("省電設定")) -> {
                NativeSystemCommand.OpenSettingsSubpageCommand(Settings.ACTION_BATTERY_SAVER_SETTINGS, "電池與省電設定")
            }
            (lower.contains("定位設定") || lower.contains("位置設定") || lower.contains("gps設定")) -> {
                NativeSystemCommand.OpenSettingsSubpageCommand(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "定位服務設定")
            }
            lower.contains("nfc") && lower.contains("設定") -> {
                NativeSystemCommand.OpenSettingsSubpageCommand(Settings.ACTION_NFC_SETTINGS, "NFC 設定")
            }
            else -> null
        }
    }

    private fun executeOpenSettingsSubpage(
        cmd: NativeSystemCommand.OpenSettingsSubpageCommand,
        context: Context
    ): ExecutionResult {
        val launched = runCatching {
            val intent = Intent(cmd.settingsAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            runCatching {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(fallback)
                true
            }.getOrDefault(false)
        }
        return ExecutionResult(
            handled = true,
            succeeded = launched,
            summaryText = if (launched) "已直接開啟系統「${cmd.pageTitle}」頁面" else "無法開啟系統「${cmd.pageTitle}」頁面",
            command = cmd
        )
    }
}
