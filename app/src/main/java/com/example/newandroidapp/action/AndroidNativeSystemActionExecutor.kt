package com.example.newandroidapp.action

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 【Android 原生系統指令與 Deep Intent 直通執行器 (`AndroidNativeSystemActionExecutor`)】
 *
 * 100% 透過 Android OS 原生底層 API（`AudioManager`、`AlarmClock`、`CameraManager`、
 * `BatteryManager`、`CalendarContract`、`Settings.System`、`AccessibilityService.performGlobalAction`、
 * 以及系統級 Deep Intent 協議 `google.navigation:`、`geo:`、`tel:`、`smsto:`、`ACTION_SEARCH`）
 * 瞬間完成系統控制與一鍵直達應用功能，完全免開啟 App 畫面慢慢點按鈕，零剪貼簿佔用、零網路依賴、零第三方套件。
 *
 * 支援十二大原生能力：
 * 1. **音量控制 (`AudioManager`)**：調整媒體/鈴聲/鬧鐘/通知/通話音量（調大、調小、指定百分比、最大、最小、靜音、解除靜音）。
 * 2. **鬧鐘與計時器 (`AlarmClock`)**：直接以 `EXTRA_SKIP_UI = true` 在背景設定指定幾點幾分的鬧鐘，或設定幾分幾秒的倒數計時器。
 * 3. **硬體手電筒 (`CameraManager.setTorchMode`)**：瞬間開啟或關閉相機閃光燈手電筒。
 * 4. **螢幕亮度控制 (`Settings.System.SCREEN_BRIGHTNESS` + 原生亮度浮窗)**：直接調整螢幕亮度百分比、調亮、調暗、自動亮度，若未授予修改系統設定權限則自動喚起原生快速亮度調整列。
 * 5. **系統狀態即時回報 (`BatteryManager` + `AudioManager`)**：0ms 讀取目前電量百分比、充電狀態、電池溫度與各聲道音量。
 * 6. **Google Maps 導航一鍵直達 (`google.navigation:`)**：直接啟動開車、騎車、步行路線導航，免開圖點搜尋框。
 * 7. **地圖地點與周邊搜尋直達 (`geo:0,0?q=`)**：一鍵直達附近加油站、超商、停車場或指定地標。
 * 8. **YouTube 影音搜尋直達 (`ACTION_SEARCH`)**：直接帶入關鍵字跳轉 YouTube 搜尋結果頁。
 * 9. **瀏覽器 / Google 網頁搜尋直達 (`ACTION_WEB_SEARCH`)**：直接啟動系統搜尋結果頁。
 * 10. **日曆行程一鍵建立 (`CalendarContract.Events`)**：自動換算今/明/後天與幾點幾分，預填標題與時段。
 * 11. **電話撥號與簡訊預填直達 (`ACTION_DIAL` / `ACTION_SENDTO`)**：直接帶入電話號碼或簡訊內容。
 * 12. **全域系統動作與設定子頁直達 (`performGlobalAction` / `Settings.ACTION_*`)**：回桌面、返回、多工、通知欄、快速設定、截圖、鎖屏與 Wi-Fi/藍牙/定位等設定子頁。
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

    enum class BrightnessOperation {
        RAISE,
        LOWER,
        SET_PERCENT,
        MAX,
        MIN,
        AUTO
    }

    enum class StatusQueryType {
        BATTERY,
        VOLUME,
        ALL
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

        data class BrightnessCommand(
            val operation: BrightnessOperation,
            val targetPercent: Int? = null
        ) : NativeSystemCommand()

        data class SystemStatusQueryCommand(
            val queryType: StatusQueryType
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

        data class MapNavigationCommand(
            val destination: String,
            val travelMode: String = "d",
            val modeLabel: String = "開車"
        ) : NativeSystemCommand()

        data class MapSearchCommand(
            val query: String
        ) : NativeSystemCommand()

        data class YouTubeSearchCommand(
            val query: String
        ) : NativeSystemCommand()

        data class WebSearchCommand(
            val query: String
        ) : NativeSystemCommand()

        data class AddCalendarEventCommand(
            val title: String,
            val hour24: Int,
            val minute: Int,
            val dayOffset: Int = 0
        ) : NativeSystemCommand()

        data class DialPhoneCommand(
            val phoneNumber: String
        ) : NativeSystemCommand()

        data class ComposeSmsCommand(
            val phoneNumber: String,
            val body: String = ""
        ) : NativeSystemCommand()
    }

    data class ExecutionResult(
        val handled: Boolean,
        val succeeded: Boolean,
        val summaryText: String,
        val command: NativeSystemCommand? = null
    )

    private val MULTI_STEP_CHAIN_MARKERS = listOf(
        "然後", "接著", "之後", "再去", "並且在", "並搜尋", "並點", "再輸入", "並播放"
    )

    /**
     * 解析自然語言指令，判斷是否屬於可由 Android 原生系統 API 或 Deep Intent 直接執行的單步指令。
     * 若指令屬於視覺規劃器展開的顯式 UI 步驟（如「打開時鐘 點擊 鬧鐘 新增」或「打開 YouTube 搜尋...」），回傳 `null` 交由視覺規劃器處理。
     */
    fun parseCommand(rawGoal: String): NativeSystemCommand? {
        val clean = rawGoal.trim()
        if (clean.isEmpty()) return null
        val lower = clean.lowercase()

        // 若是視覺規劃器展開的顯式 UI 步驟（如「打開設定 搜尋...」、「打開時鐘 點擊...」、「打開 YouTube 搜尋...」），不攔截，以維持既有規劃測試相容性
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

        // 2. 解析電量與系統狀態即時查詢指令（如「現在剩多少電」、「目前音量幾%」）
        parseSystemStatusQueryCommand(lower)?.let { return it }

        // 3. 解析螢幕亮度控制指令（如「把亮度調到 40%」、「螢幕調暗一點」）
        parseBrightnessCommand(clean, lower)?.let { return it }

        // 4. 解析音量與靜音指令
        parseVolumeCommand(clean, lower)?.let { return it }

        // 5. 解析設定鬧鐘指令（含具體時間，如「設定早上 7 點 30 分的鬧鐘」）
        parseSetAlarmCommand(clean, lower)?.let { return it }

        // 6. 解析倒數計時器指令（如「計時 5 分鐘」、「倒數 30 秒」）
        parseSetTimerCommand(clean, lower)?.let { return it }

        // 7. 解析日曆行程新增指令（如「新增明天下午 3 點開會的日曆行程」）
        parseAddCalendarEventCommand(clean, lower)?.let { return it }

        // 8. 解析電話撥號與簡訊預填指令（如「打電話給 0912345678」、「傳簡訊給 0912345678 說我到了」）
        parseDialPhoneCommand(clean, lower)?.let { return it }
        parseComposeSmsCommand(clean, lower)?.let { return it }

        // 9. 解析 Google Maps 導航與地點搜尋 Deep Intent（如「導航去台北車站」、「搜尋附近的加油站」）
        parseMapNavigationCommand(clean, lower)?.let { return it }
        parseMapSearchCommand(clean, lower)?.let { return it }

        // 10. 解析 YouTube 與網頁直達搜尋 Deep Intent（如「在 YouTube 搜尋周杰倫」、「上網搜尋今日天氣」）
        parseYouTubeSearchCommand(clean, lower)?.let { return it }
        parseWebSearchCommand(clean, lower)?.let { return it }

        // 11. 解析全域系統動作（回桌面、通知欄、快速設定、多工畫面、截圖、鎖屏）
        parseGlobalActionCommand(lower)?.let { return it }

        // 12. 解析系統設定子頁直達（如「打開 Wi-Fi 設定」、「打開藍牙設定」）
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
            is NativeSystemCommand.BrightnessCommand -> executeBrightnessCommand(cmd, context, accessibilityService)
            is NativeSystemCommand.SystemStatusQueryCommand -> executeSystemStatusQueryCommand(cmd, context)
            is NativeSystemCommand.SetAlarmCommand -> executeSetAlarmCommand(cmd, context)
            is NativeSystemCommand.SetTimerCommand -> executeSetTimerCommand(cmd, context)
            is NativeSystemCommand.FlashlightCommand -> executeFlashlightCommand(cmd, context)
            is NativeSystemCommand.GlobalActionCommand -> executeGlobalActionCommand(cmd, accessibilityService)
            is NativeSystemCommand.OpenSettingsSubpageCommand -> executeOpenSettingsSubpage(cmd, context)
            is NativeSystemCommand.MapNavigationCommand -> executeMapNavigationCommand(cmd, context)
            is NativeSystemCommand.MapSearchCommand -> executeMapSearchCommand(cmd, context)
            is NativeSystemCommand.YouTubeSearchCommand -> executeYouTubeSearchCommand(cmd, context)
            is NativeSystemCommand.WebSearchCommand -> executeWebSearchCommand(cmd, context)
            is NativeSystemCommand.AddCalendarEventCommand -> executeAddCalendarEventCommand(cmd, context)
            is NativeSystemCommand.DialPhoneCommand -> executeDialPhoneCommand(cmd, context)
            is NativeSystemCommand.ComposeSmsCommand -> executeComposeSmsCommand(cmd, context)
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

        // 排除開啟設定頁或查詢音量的句子
        if (lower.contains("打開設定") || lower.contains("開啟設定") || lower.contains("聲音設定") ||
            lower.contains("多大") || lower.contains("多少") || lower.contains("幾%") || lower.contains("幾趴")
        ) {
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
    // 2. 螢幕亮度控制解析與執行 (Settings.System.SCREEN_BRIGHTNESS + 浮動亮度面板)
    // =========================================================================

    private fun parseBrightnessCommand(clean: String, lower: String): NativeSystemCommand.BrightnessCommand? {
        // 排除開啟設定頁（如「打開亮度設定」、「打開顯示設定」）
        if (lower.contains("設定頁") || (lower.contains("設定") && (lower.contains("打開") || lower.contains("開啟")))) {
            return null
        }

        val hasBrightnessWord = lower.contains("亮度") || lower.contains("brightness") ||
            lower.contains("螢幕調亮") || lower.contains("螢幕調暗") ||
            lower.contains("把螢幕調亮") || lower.contains("把螢幕調暗")
        if (!hasBrightnessWord) return null

        if (lower.contains("自動亮度") || lower.contains("自動調整亮度")) {
            return NativeSystemCommand.BrightnessCommand(operation = BrightnessOperation.AUTO)
        }

        if (lower.contains("最大") || lower.contains("最亮") || Regex("(?<!\\d)100\\s*[%％]").containsMatchIn(lower)) {
            return NativeSystemCommand.BrightnessCommand(operation = BrightnessOperation.MAX, targetPercent = 100)
        }
        if (lower.contains("最小") || lower.contains("最暗") || Regex("(?<!\\d)0\\s*[%％]").containsMatchIn(lower)) {
            return NativeSystemCommand.BrightnessCommand(operation = BrightnessOperation.MIN, targetPercent = 5)
        }
        if (lower.contains("一半")) {
            return NativeSystemCommand.BrightnessCommand(operation = BrightnessOperation.SET_PERCENT, targetPercent = 50)
        }

        val pctMatch = Regex("(?:調到|設為|設成|調成|設定為|改為|改成|至|到)?\\s*(\\d{1,3})\\s*(?:%|％|趴|成)?").find(clean)
        if (pctMatch != null && (lower.contains("調") || lower.contains("設") || lower.contains("改") || lower.contains("%") || lower.contains("％") || lower.contains("趴"))) {
            val rawVal = pctMatch.groupValues[1].toIntOrNull()
            if (rawVal != null) {
                val pct = rawVal.coerceIn(1, 100)
                return NativeSystemCommand.BrightnessCommand(
                    operation = BrightnessOperation.SET_PERCENT,
                    targetPercent = pct
                )
            }
        }

        if (lower.contains("調亮") || lower.contains("調高") || lower.contains("調大") ||
            lower.contains("亮一點") || lower.contains("增加亮度")
        ) {
            return NativeSystemCommand.BrightnessCommand(operation = BrightnessOperation.RAISE)
        }

        if (lower.contains("調暗") || lower.contains("調低") || lower.contains("調小") ||
            lower.contains("暗一點") || lower.contains("降低亮度")
        ) {
            return NativeSystemCommand.BrightnessCommand(operation = BrightnessOperation.LOWER)
        }

        return null
    }

    private fun executeBrightnessCommand(
        cmd: NativeSystemCommand.BrightnessCommand,
        context: Context,
        accessibilityService: AccessibilityService?
    ): ExecutionResult {
        return runCatching {
            val resolver = context.contentResolver
            val curRaw = runCatching {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrDefault(128).coerceIn(1, 255)
            val curPct = ((curRaw / 255f) * 100f).roundToInt().coerceIn(1, 100)

            val targetPct = when (cmd.operation) {
                BrightnessOperation.MAX -> 100
                BrightnessOperation.MIN -> 5
                BrightnessOperation.SET_PERCENT -> (cmd.targetPercent ?: 50).coerceIn(1, 100)
                BrightnessOperation.RAISE -> (curPct + 20).coerceIn(1, 100)
                BrightnessOperation.LOWER -> (curPct - 20).coerceIn(5, 100)
                BrightnessOperation.AUTO -> curPct
            }

            val canWriteSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.System.canWrite(context)
            } else {
                true
            }

            if (canWriteSettings) {
                if (cmd.operation == BrightnessOperation.AUTO) {
                    Settings.System.putInt(
                        resolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    )
                    return ExecutionResult(
                        handled = true,
                        succeeded = true,
                        summaryText = "已開啟系統自動調整螢幕亮度",
                        command = cmd
                    )
                }
                val targetRaw = ((targetPct / 100f) * 255f).roundToInt().coerceIn(1, 255)
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, targetRaw)
                return ExecutionResult(
                    handled = true,
                    succeeded = true,
                    summaryText = "已將螢幕亮度調整為 $targetPct%",
                    command = cmd
                )
            }

            // 未授予 WRITE_SETTINGS 時：優先喚起 Android 原生浮動亮度拉桿對話框或下拉快速設定面板
            val dialogLaunched = runCatching {
                val brightnessDialogIntent = Intent("com.android.intent.action.SHOW_BRIGHTNESS_DIALOG").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(brightnessDialogIntent)
                true
            }.getOrDefault(false)

            if (dialogLaunched) {
                return ExecutionResult(
                    handled = true,
                    succeeded = true,
                    summaryText = "已喚起系統原生亮度調整滑桿（目前亮度約 $curPct%，目標 $targetPct%）",
                    command = cmd
                )
            }

            val quickSettingsOpened = runCatching {
                accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) == true
            }.getOrDefault(false)

            if (quickSettingsOpened) {
                return ExecutionResult(
                    handled = true,
                    succeeded = true,
                    summaryText = "已拉下快速設定亮度滑桿面板（目前亮度約 $curPct%）",
                    command = cmd
                )
            }

            val displayLaunched = runCatching {
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)

            ExecutionResult(
                handled = true,
                succeeded = displayLaunched,
                summaryText = if (displayLaunched) {
                    "已開啟系統顯示與亮度設定（目前亮度約 $curPct%）"
                } else {
                    "無法開啟系統亮度調整介面"
                },
                command = cmd
            )
        }.getOrElse { e ->
            ExecutionResult(
                handled = true,
                succeeded = false,
                summaryText = "調整螢幕亮度失敗（${e.javaClass.simpleName}）",
                command = cmd
            )
        }
    }

    // =========================================================================
    // 3. 電量與系統狀態即時查詢 (BatteryManager + AudioManager)
    // =========================================================================

    private fun parseSystemStatusQueryCommand(lower: String): NativeSystemCommand.SystemStatusQueryCommand? {
        if (lower.contains("省電") || lower.contains("電池設定") || lower.contains("打開設定")) {
            return null
        }

        val isBatteryQuery = lower.contains("剩多少電") || lower.contains("還有多少電") ||
            lower.contains("目前電量") || lower.contains("現在電量") || lower.contains("電量多少") ||
            lower.contains("電池幾趴") || lower.contains("電量幾趴") || lower.contains("電量幾%") ||
            lower.contains("手機電量") || lower.contains("有沒有在充電") || lower.contains("是否在充電")
        if (isBatteryQuery) {
            return NativeSystemCommand.SystemStatusQueryCommand(StatusQueryType.BATTERY)
        }

        val isVolumeQuery = (lower.contains("音量") || lower.contains("聲音")) &&
            (lower.contains("多大") || lower.contains("多少") || lower.contains("幾%") || lower.contains("幾趴"))
        if (isVolumeQuery) {
            return NativeSystemCommand.SystemStatusQueryCommand(StatusQueryType.VOLUME)
        }

        if (lower == "系統狀態" || lower == "查詢系統狀態" || lower == "手機狀態" || lower == "目前手機狀態") {
            return NativeSystemCommand.SystemStatusQueryCommand(StatusQueryType.ALL)
        }

        return null
    }

    private fun executeSystemStatusQueryCommand(
        cmd: NativeSystemCommand.SystemStatusQueryCommand,
        context: Context
    ): ExecutionResult {
        return runCatching {
            val batteryInfo = readBatterySummary(context)
            val volumeInfo = readVolumeSummary(context)

            val summary = when (cmd.queryType) {
                StatusQueryType.BATTERY -> batteryInfo
                StatusQueryType.VOLUME -> volumeInfo
                StatusQueryType.ALL -> "$batteryInfo；$volumeInfo"
            }
            ExecutionResult(
                handled = true,
                succeeded = true,
                summaryText = summary,
                command = cmd
            )
        }.getOrElse {
            ExecutionResult(
                handled = true,
                succeeded = false,
                summaryText = "無法讀取目前系統狀態",
                command = cmd
            )
        }
    }

    internal fun readBatterySummary(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacityProp = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val stickyIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val level = if (capacityProp in 0..100) {
            capacityProp
        } else if (stickyIntent != null) {
            val l = stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = stickyIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (l >= 0) ((l * 100f) / s).roundToInt().coerceIn(0, 100) else -1
        } else {
            -1
        }

        val status = stickyIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = stickyIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

        val chargeState = when {
            status == BatteryManager.BATTERY_STATUS_FULL -> "已充飽"
            isCharging -> "充電中"
            else -> "未充電"
        }
        val tempSuffix = if (tempTenths in 100..650) {
            "，溫度 ${"%.1f".format(tempTenths / 10f)}°C"
        } else ""

        return if (level in 0..100) {
            "目前手機電量 $level%（$chargeState$tempSuffix）"
        } else {
            "目前充電狀態：$chargeState"
        }
    }

    internal fun readVolumeSummary(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "無法讀取音量狀態"

        fun streamPct(stream: Int): Int {
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            val cur = am.getStreamVolume(stream).coerceAtLeast(0)
            return ((cur.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        }

        val musicPct = streamPct(AudioManager.STREAM_MUSIC)
        val ringPct = streamPct(AudioManager.STREAM_RING)
        val alarmPct = streamPct(AudioManager.STREAM_ALARM)
        return "目前媒體音量 $musicPct%、鈴聲音量 $ringPct%、鬧鐘音量 $alarmPct%"
    }

    // =========================================================================
    // 4. 原生鬧鐘設定解析與執行 (AlarmClock.ACTION_SET_ALARM)
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
    // 5. 原生倒數計時器解析與執行 (AlarmClock.ACTION_SET_TIMER)
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
    // 6. 硬體手電筒開關 (CameraManager.setTorchMode)
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
    // 7. Google Maps 導航與地點搜尋一鍵直達 (google.navigation: / geo:)
    // =========================================================================

    private fun parseMapNavigationCommand(clean: String, lower: String): NativeSystemCommand.MapNavigationCommand? {
        if (!lower.contains("導航")) return null

        val (travelMode, modeLabel) = when {
            lower.contains("走路") || lower.contains("步行") -> "w" to "步行"
            lower.contains("騎車") || lower.contains("機車") || lower.contains("摩托車") -> "l" to "機車"
            lower.contains("單車") || lower.contains("腳踏車") || lower.contains("自行車") -> "b" to "單車"
            else -> "d" to "開車"
        }

        val match = Regex("導航\\s*(?:去|到|至|前往|往)?\\s*(.+)$").find(clean) ?: return null
        val dest = match.groupValues[1]
            .replace(Regex("^(?:去|到|至|前往)\\s*"), "")
            .trim()
        if (dest.isEmpty()) return null

        return NativeSystemCommand.MapNavigationCommand(
            destination = dest,
            travelMode = travelMode,
            modeLabel = modeLabel
        )
    }

    private fun executeMapNavigationCommand(
        cmd: NativeSystemCommand.MapNavigationCommand,
        context: Context
    ): ExecutionResult {
        val encodedDest = Uri.encode(cmd.destination)
        val navUri = Uri.parse("google.navigation:q=$encodedDest&mode=${cmd.travelMode}")
        val launchedMapsNav = runCatching {
            val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(mapIntent)
            true
        }.getOrElse {
            runCatching {
                val genericNavIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericNavIntent)
                true
            }.getOrElse {
                runCatching {
                    val geoFallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encodedDest")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(geoFallback)
                    true
                }.getOrDefault(false)
            }
        }

        return ExecutionResult(
            handled = true,
            succeeded = launchedMapsNav,
            summaryText = if (launchedMapsNav) {
                "已透過系統原生導航協議直接啟動前往「${cmd.destination}」（${cmd.modeLabel}模式）"
            } else {
                "系統未安裝支援導航協議的地圖應用"
            },
            command = cmd
        )
    }

    private fun parseMapSearchCommand(clean: String, lower: String): NativeSystemCommand.MapSearchCommand? {
        // 支援「搜尋附近的加油站」、「找附近的超商」、「在地圖搜尋台北 101」、「用地圖找附近的停車場」
        val nearbyMatch = Regex("^(?:幫我)?(?:在地圖|用地圖|去地圖)?\\s*(?:搜尋|搜索|找|查找|查一下|查詢)\\s*(附近(?:的)?.+)$").find(clean)
        if (nearbyMatch != null) {
            val query = nearbyMatch.groupValues[1].trim()
            if (query.isNotEmpty()) {
                return NativeSystemCommand.MapSearchCommand(query = query)
            }
        }

        val mapExplicitMatch = Regex("^(?:幫我)?(?:在|用)\\s*(?:google\\s*地圖|google\\s*maps|google\\s*map|地圖)\\s*(?:搜尋|搜索|找|查找|查詢)\\s*(.+)$", RegexOption.IGNORE_CASE).find(clean)
        if (mapExplicitMatch != null) {
            val query = mapExplicitMatch.groupValues[1].trim()
            if (query.isNotEmpty()) {
                return NativeSystemCommand.MapSearchCommand(query = query)
            }
        }

        return null
    }

    private fun executeMapSearchCommand(
        cmd: NativeSystemCommand.MapSearchCommand,
        context: Context
    ): ExecutionResult {
        val encodedQuery = Uri.encode(cmd.query)
        val geoUri = Uri.parse("geo:0,0?q=$encodedQuery")
        val launched = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            runCatching {
                val fallback = Intent(Intent.ACTION_VIEW, geoUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            }.getOrDefault(false)
        }

        return ExecutionResult(
            handled = true,
            succeeded = launched,
            summaryText = if (launched) {
                "已透過原生 geo 協議直接開啟地圖搜尋「${cmd.query}」"
            } else {
                "無法啟動地圖搜尋「${cmd.query}」"
            },
            command = cmd
        )
    }

    // =========================================================================
    // 8. YouTube 與網頁直達搜尋 Deep Intent (ACTION_SEARCH / ACTION_WEB_SEARCH)
    // =========================================================================

    private fun parseYouTubeSearchCommand(clean: String, lower: String): NativeSystemCommand.YouTubeSearchCommand? {
        // 僅匹配明確使用「在 YouTube 搜尋...」、「用 YouTube 搜尋...」、「YouTube 直達搜尋...」的直達句型
        // 保留「打開 YouTube 搜尋...」與「去youtube查...」給既有視覺管線與單元測試
        val match = Regex("^(?:幫我)?(?:直接在|在|用)\\s*(?:youtube|yt)\\s*(?:直達)?(?:搜尋|搜索|找|查找)\\s*(.+)$", RegexOption.IGNORE_CASE).find(clean)
            ?: Regex("^youtube\\s*直達搜尋\\s*(.+)$", RegexOption.IGNORE_CASE).find(clean)
            ?: return null

        val query = match.groupValues[1].trim()
        if (query.isEmpty()) return null
        return NativeSystemCommand.YouTubeSearchCommand(query = query)
    }

    private fun executeYouTubeSearchCommand(
        cmd: NativeSystemCommand.YouTubeSearchCommand,
        context: Context
    ): ExecutionResult {
        val launched = runCatching {
            val ytSearchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", cmd.query)
                putExtra(SearchManager.QUERY, cmd.query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(ytSearchIntent)
            true
        }.getOrElse {
            runCatching {
                val webYtUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cmd.query)}")
                val viewIntent = Intent(Intent.ACTION_VIEW, webYtUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(viewIntent)
                true
            }.getOrDefault(false)
        }

        return ExecutionResult(
            handled = true,
            succeeded = launched,
            summaryText = if (launched) {
                "已透過原生搜尋協議直達 YouTube「${cmd.query}」結果頁"
            } else {
                "無法直達 YouTube 搜尋「${cmd.query}」"
            },
            command = cmd
        )
    }

    private fun parseWebSearchCommand(clean: String, lower: String): NativeSystemCommand.WebSearchCommand? {
        // 匹配「上網搜尋...」、「用瀏覽器搜尋...」、「Google 搜尋...」、「直接搜尋...」
        // 注意：保留「幫我上網查...」給 CognitiveReasoningEngine 既有測試
        val match = Regex("^(?:幫我)?(?:上網搜尋|用瀏覽器搜尋|在瀏覽器搜尋|google\\s*搜尋|直接搜尋)\\s*(.+)$", RegexOption.IGNORE_CASE).find(clean)
            ?: return null
        val query = match.groupValues[1].trim()
        if (query.isEmpty()) return null
        return NativeSystemCommand.WebSearchCommand(query = query)
    }

    private fun executeWebSearchCommand(
        cmd: NativeSystemCommand.WebSearchCommand,
        context: Context
    ): ExecutionResult {
        val launched = runCatching {
            val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, cmd.query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webSearchIntent)
            true
        }.getOrElse {
            runCatching {
                val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(cmd.query)}")
                val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(viewIntent)
                true
            }.getOrDefault(false)
        }

        return ExecutionResult(
            handled = true,
            succeeded = launched,
            summaryText = if (launched) {
                "已透過系統原生搜尋直達「${cmd.query}」網頁結果"
            } else {
                "無法啟動網頁搜尋「${cmd.query}」"
            },
            command = cmd
        )
    }

    // =========================================================================
    // 9. 日曆行程一鍵建立 (CalendarContract.Events)
    // =========================================================================

    private fun parseAddCalendarEventCommand(clean: String, lower: String): NativeSystemCommand.AddCalendarEventCommand? {
        val hasCalendarKeyword = lower.contains("日曆") || lower.contains("行事曆") || lower.contains("行程")
        if (!hasCalendarKeyword) return null
        if (!lower.contains("新增") && !lower.contains("建立") && !lower.contains("加") && !lower.contains("排") && !lower.contains("設定")) {
            return null
        }

        val timePair = extractHourAndMinute(clean) ?: (9 to 0)
        val (hour24, minute) = timePair

        val dayOffset = when {
            clean.contains("大後天") -> 3
            clean.contains("後天") -> 2
            clean.contains("明天") || clean.contains("明日") -> 1
            else -> 0
        }

        var title = clean
            .replace(Regex("^(?:幫我|請)?\\s*(?:在)?(?:日曆|行事曆)?(?:新增|建立|加上|加個|加一個|排一個|設定)(?:一個|個)?"), "")
            .replace(Regex("(?:今天|明天|明日|後天|大後天)?\\s*(?:凌晨|早上|上午|中午|下午|傍晚|晚上|夜間)?\\s*(?:\\d{1,2}\\s*[:：]\\s*\\d{1,2}|[零一二兩三四五六七八九十\\d]{1,3}\\s*(?:點|時)(?:\\s*半|\\s*[零一二三四五六七八九十\\d]{1,3}\\s*分?)?)"), "")
            .replace(Regex("(?:的)?(?:日曆行程|行事曆行程|日曆|行事曆|行程)$"), "")
            .replace(Regex("^(?:的|去|要)\\s*"), "")
            .trim()

        if (title.isBlank()) {
            title = "AI 提醒事項"
        }

        return NativeSystemCommand.AddCalendarEventCommand(
            title = title,
            hour24 = hour24,
            minute = minute,
            dayOffset = dayOffset
        )
    }

    private fun executeAddCalendarEventCommand(
        cmd: NativeSystemCommand.AddCalendarEventCommand,
        context: Context
    ): ExecutionResult {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, cmd.dayOffset)
            set(Calendar.HOUR_OF_DAY, cmd.hour24)
            set(Calendar.MINUTE, cmd.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMillis = cal.timeInMillis
        val endMillis = startMillis + 60 * 60 * 1000L

        val launched = runCatching {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                putExtra(CalendarContract.Events.TITLE, cmd.title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        val dayLabel = when (cmd.dayOffset) {
            1 -> "明天"
            2 -> "後天"
            3 -> "大後天"
            else -> "今天"
        }
        val timeFormatted = "%02d:%02d".format(cmd.hour24, cmd.minute)

        return ExecutionResult(
            handled = true,
            succeeded = launched,
            summaryText = if (launched) {
                "已透過原生 CalendarContract 建立「$dayLabel $timeFormatted ${cmd.title}」日曆行程"
            } else {
                "系統未安裝支援原生日曆指令的行事曆應用"
            },
            command = cmd
        )
    }

    // =========================================================================
    // 10. 電話撥號與簡訊預填直達 (ACTION_DIAL / ACTION_SENDTO)
    // =========================================================================

    private fun parseDialPhoneCommand(clean: String, lower: String): NativeSystemCommand.DialPhoneCommand? {
        if (!lower.contains("打電話") && !lower.contains("撥打") && !lower.contains("撥號")) {
            return null
        }
        val phoneMatch = Regex("(\\+?\\d[\\d\\-\\s]{2,15}\\d)").find(clean) ?: return null
        val normalizedNumber = phoneMatch.groupValues[1].replace(Regex("[\\s\\-]"), "")
        if (normalizedNumber.length < 3) return null
        return NativeSystemCommand.DialPhoneCommand(phoneNumber = normalizedNumber)
    }

    private fun executeDialPhoneCommand(
        cmd: NativeSystemCommand.DialPhoneCommand,
        context: Context
    ): ExecutionResult {
        val launched = runCatching {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(cmd.phoneNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            true
        }.getOrDefault(false)

        return ExecutionResult(
            handled = true,
            succeeded = launched,
            summaryText = if (launched) {
                "已透過原生撥號面板帶入號碼：${cmd.phoneNumber}"
            } else {
                "無法啟動系統電話撥號面板"
            },
            command = cmd
        )
    }

    private fun parseComposeSmsCommand(clean: String, lower: String): NativeSystemCommand.ComposeSmsCommand? {
        if (!lower.contains("傳簡訊") && !lower.contains("發簡訊") && !lower.contains("寄簡訊")) {
            return null
        }
        val phoneMatch = Regex("(\\+?\\d[\\d\\-\\s]{2,15}\\d)").find(clean) ?: return null
        val rawPhone = phoneMatch.groupValues[1]
        val normalizedNumber = rawPhone.replace(Regex("[\\s\\-]"), "")
        if (normalizedNumber.length < 3) return null

        val afterPhone = clean.substringAfter(rawPhone).trim()
            .replace(Regex("^(?:說|內容是|內容為|寫|表示)\\s*"), "")
            .trim()

        return NativeSystemCommand.ComposeSmsCommand(
            phoneNumber = normalizedNumber,
            body = afterPhone
        )
    }

    private fun executeComposeSmsCommand(
        cmd: NativeSystemCommand.ComposeSmsCommand,
        context: Context
    ): ExecutionResult {
        val launched = runCatching {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(cmd.phoneNumber)}")).apply {
                if (cmd.body.isNotBlank()) {
                    putExtra("sms_body", cmd.body)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
            true
        }.getOrDefault(false)

        val bodyPreview = if (cmd.body.isNotBlank()) "（內容：${cmd.body}）" else ""
        return ExecutionResult(
            handled = true,
            succeeded = launched,
            summaryText = if (launched) {
                "已透過原生簡訊協議預填收件人 ${cmd.phoneNumber}$bodyPreview"
            } else {
                "無法啟動系統簡訊應用"
            },
            command = cmd
        )
    }

    // =========================================================================
    // 11. 全域系統動作 (AccessibilityService.performGlobalAction)
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
    // 12. 系統設定子頁一鍵直達 (Settings Deep Intent)
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
