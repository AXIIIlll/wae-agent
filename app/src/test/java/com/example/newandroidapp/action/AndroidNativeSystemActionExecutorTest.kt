package com.example.newandroidapp.action

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import com.example.newandroidapp.ai.AndroidNativeAiEngine
import com.example.newandroidapp.ai.LocalKnowledgeAndReasoningEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNativeSystemActionExecutorTest {

    @Test
    fun `test volume commands parsing for raise lower percent mute unmute and stream types`() {
        val cmd50 = AndroidNativeSystemActionExecutor.parseCommand("把音量調到 50%")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.VolumeCommand
        assertNotNull(cmd50)
        assertEquals(AndroidNativeSystemActionExecutor.AudioStreamTarget.MUSIC, cmd50!!.stream)
        assertEquals(AndroidNativeSystemActionExecutor.VolumeOperation.SET_PERCENT, cmd50.operation)
        assertEquals(50, cmd50.targetPercent)

        val cmdAlarmMax = AndroidNativeSystemActionExecutor.parseCommand("把鬧鐘音量調到最大")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.VolumeCommand
        assertNotNull(cmdAlarmMax)
        assertEquals(AndroidNativeSystemActionExecutor.AudioStreamTarget.ALARM, cmdAlarmMax!!.stream)
        assertEquals(AndroidNativeSystemActionExecutor.VolumeOperation.MAX, cmdAlarmMax.operation)

        val cmdRingLower = AndroidNativeSystemActionExecutor.parseCommand("鈴聲音量調小一點")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.VolumeCommand
        assertNotNull(cmdRingLower)
        assertEquals(AndroidNativeSystemActionExecutor.AudioStreamTarget.RING, cmdRingLower!!.stream)
        assertEquals(AndroidNativeSystemActionExecutor.VolumeOperation.LOWER, cmdRingLower.operation)

        val cmdMute = AndroidNativeSystemActionExecutor.parseCommand("靜音")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.VolumeCommand
        assertNotNull(cmdMute)
        assertEquals(AndroidNativeSystemActionExecutor.VolumeOperation.MUTE, cmdMute!!.operation)

        val cmdUnmute = AndroidNativeSystemActionExecutor.parseCommand("解除靜音")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.VolumeCommand
        assertNotNull(cmdUnmute)
        assertEquals(AndroidNativeSystemActionExecutor.VolumeOperation.UNMUTE, cmdUnmute!!.operation)
    }

    @Test
    fun `test brightness commands parsing for percent raise lower max min and auto`() {
        val b40 = AndroidNativeSystemActionExecutor.parseCommand("把亮度調到 40%")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.BrightnessCommand
        assertNotNull(b40)
        assertEquals(AndroidNativeSystemActionExecutor.BrightnessOperation.SET_PERCENT, b40!!.operation)
        assertEquals(40, b40.targetPercent)

        val bDim = AndroidNativeSystemActionExecutor.parseCommand("把螢幕調暗一點")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.BrightnessCommand
        assertNotNull(bDim)
        assertEquals(AndroidNativeSystemActionExecutor.BrightnessOperation.LOWER, bDim!!.operation)

        val bBright = AndroidNativeSystemActionExecutor.parseCommand("把亮度調亮一點")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.BrightnessCommand
        assertNotNull(bBright)
        assertEquals(AndroidNativeSystemActionExecutor.BrightnessOperation.RAISE, bBright!!.operation)

        val bAuto = AndroidNativeSystemActionExecutor.parseCommand("開啟自動亮度")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.BrightnessCommand
        assertNotNull(bAuto)
        assertEquals(AndroidNativeSystemActionExecutor.BrightnessOperation.AUTO, bAuto!!.operation)
    }

    @Test
    fun `test system status query commands for battery and volume`() {
        val batCmd = AndroidNativeSystemActionExecutor.parseCommand("現在剩多少電")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.SystemStatusQueryCommand
        assertNotNull(batCmd)
        assertEquals(AndroidNativeSystemActionExecutor.StatusQueryType.BATTERY, batCmd!!.queryType)

        val volCmd = AndroidNativeSystemActionExecutor.parseCommand("現在音量幾%")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.SystemStatusQueryCommand
        assertNotNull(volCmd)
        assertEquals(AndroidNativeSystemActionExecutor.StatusQueryType.VOLUME, volCmd!!.queryType)

        val allCmd = AndroidNativeSystemActionExecutor.parseCommand("查詢系統狀態")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.SystemStatusQueryCommand
        assertNotNull(allCmd)
        assertEquals(AndroidNativeSystemActionExecutor.StatusQueryType.ALL, allCmd!!.queryType)
    }

    @Test
    fun `test alarm commands parsing with 24h Arabic and Chinese time and skipUi true`() {
        val alarmMorning = AndroidNativeSystemActionExecutor.parseCommand("設定早上 7 點 30 分的鬧鐘")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.SetAlarmCommand
        assertNotNull(alarmMorning)
        assertEquals(7, alarmMorning!!.hour24)
        assertEquals(30, alarmMorning.minute)
        assertTrue(alarmMorning.skipUi)

        val alarmPmHalf = AndroidNativeSystemActionExecutor.parseCommand("幫我設下午兩點半開會的鬧鐘")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.SetAlarmCommand
        assertNotNull(alarmPmHalf)
        assertEquals(14, alarmPmHalf!!.hour24)
        assertEquals(30, alarmPmHalf.minute)
        assertEquals("開會", alarmPmHalf.label)
        assertTrue(alarmPmHalf.skipUi)

        val alarmColon = AndroidNativeSystemActionExecutor.parseCommand("設定 06:45 鬧鐘")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.SetAlarmCommand
        assertNotNull(alarmColon)
        assertEquals(6, alarmColon!!.hour24)
        assertEquals(45, alarmColon.minute)
    }

    @Test
    fun `test timer commands parsing for minutes and seconds`() {
        val timer10Min = AndroidNativeSystemActionExecutor.parseCommand("計時 10 分鐘")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.SetTimerCommand
        assertNotNull(timer10Min)
        assertEquals(600, timer10Min!!.totalSeconds)
        assertTrue(timer10Min.skipUi)

        val timerMixed = AndroidNativeSystemActionExecutor.parseCommand("幫我倒數三分鐘30秒")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.SetTimerCommand
        assertNotNull(timerMixed)
        assertEquals(210, timerMixed!!.totalSeconds)
    }

    @Test
    fun `test flashlight commands parsing for on and off`() {
        val flashOn = AndroidNativeSystemActionExecutor.parseCommand("打開手電筒")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.FlashlightCommand
        assertNotNull(flashOn)
        assertTrue(flashOn!!.enable)

        val flashOff = AndroidNativeSystemActionExecutor.parseCommand("關掉手電筒")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.FlashlightCommand
        assertNotNull(flashOff)
        assertFalse(flashOff!!.enable)
    }

    @Test
    fun `test google maps navigation and place search deep intents`() {
        val navDrive = AndroidNativeSystemActionExecutor.parseCommand("導航去台北車站")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.MapNavigationCommand
        assertNotNull(navDrive)
        assertEquals("台北車站", navDrive!!.destination)
        assertEquals("d", navDrive.travelMode)

        val navWalk = AndroidNativeSystemActionExecutor.parseCommand("步行導航到台北 101")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.MapNavigationCommand
        assertNotNull(navWalk)
        assertEquals("台北 101", navWalk!!.destination)
        assertEquals("w", navWalk.travelMode)

        val mapSearchNearby = AndroidNativeSystemActionExecutor.parseCommand("搜尋附近的加油站")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.MapSearchCommand
        assertNotNull(mapSearchNearby)
        assertEquals("附近的加油站", mapSearchNearby!!.query)

        val mapSearchExplicit = AndroidNativeSystemActionExecutor.parseCommand("在地圖搜尋桃園機場")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.MapSearchCommand
        assertNotNull(mapSearchExplicit)
        assertEquals("桃園機場", mapSearchExplicit!!.query)
    }

    @Test
    fun `test youtube and web direct search deep intents`() {
        val ytSearch = AndroidNativeSystemActionExecutor.parseCommand("在 YouTube 搜尋周杰倫")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.YouTubeSearchCommand
        assertNotNull(ytSearch)
        assertEquals("周杰倫", ytSearch!!.query)

        val webSearch = AndroidNativeSystemActionExecutor.parseCommand("上網搜尋今日天氣")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.WebSearchCommand
        assertNotNull(webSearch)
        assertEquals("今日天氣", webSearch!!.query)
    }

    @Test
    fun `test calendar event dial phone and compose sms deep intents`() {
        val calCmd = AndroidNativeSystemActionExecutor.parseCommand("新增明天下午 3 點開會的日曆行程")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.AddCalendarEventCommand
        assertNotNull(calCmd)
        assertEquals(1, calCmd!!.dayOffset)
        assertEquals(15, calCmd.hour24)
        assertEquals(0, calCmd.minute)
        assertEquals("開會", calCmd.title)

        val dialCmd = AndroidNativeSystemActionExecutor.parseCommand("打電話給 0912-345-678")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.DialPhoneCommand
        assertNotNull(dialCmd)
        assertEquals("0912345678", dialCmd!!.phoneNumber)

        val smsCmd = AndroidNativeSystemActionExecutor.parseCommand("傳簡訊給 0912345678 說我快到了")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.ComposeSmsCommand
        assertNotNull(smsCmd)
        assertEquals("0912345678", smsCmd!!.phoneNumber)
        assertEquals("我快到了", smsCmd.body)
    }

    @Test
    fun `test global actions and settings subpage deep intents`() {
        val homeCmd = AndroidNativeSystemActionExecutor.parseCommand("回到桌面")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.GlobalActionCommand
        assertNotNull(homeCmd)
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, homeCmd!!.action.globalActionId)

        val wifiCmd = AndroidNativeSystemActionExecutor.parseCommand("打開 Wi-Fi 設定")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.OpenSettingsSubpageCommand
        assertNotNull(wifiCmd)
        assertEquals(Settings.ACTION_WIFI_SETTINGS, wifiCmd!!.settingsAction)

        val btCmd = AndroidNativeSystemActionExecutor.parseCommand("打開藍牙設定")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.OpenSettingsSubpageCommand
        assertNotNull(btCmd)
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, btCmd!!.settingsAction)
    }

    @Test
    fun `test ensurePlannedExecutableGoal and conversational reply preserve native system commands without rewriting to Chrome`() {
        val rawAlarm = "設定早上 7 點 30 分的鬧鐘"
        assertEquals(rawAlarm, LocalKnowledgeAndReasoningEngine.ensurePlannedExecutableGoal(rawAlarm))

        val (reply, execCmd) = AndroidNativeAiEngine.generateConversationalReply(rawAlarm, null)
        assertTrue(reply.isNotBlank())
        assertEquals(rawAlarm, execCmd)

        val rawBattery = "現在剩多少電"
        val (batReply, batExecCmd) = AndroidNativeAiEngine.generateConversationalReply(rawBattery, null)
        assertTrue(batReply.isNotBlank())
        assertEquals(rawBattery, batExecCmd)

        // Ensure multi-step UI instructions are not hijacked
        assertNull(AndroidNativeSystemActionExecutor.parseCommand("打開 YouTube 搜尋 周杰倫"))
        assertNull(AndroidNativeSystemActionExecutor.parseCommand("打開時鐘 點擊 鬧鐘 新增"))
    }

    @Test
    fun `test SystemCapabilityHub unified 10 capabilities parsing and execution`() {
        SystemCapabilityHub.clearNotesForTest()
        SystemCapabilityHub.clearNotificationsForTest()

        // 1. Quick Notes save & query
        val noteSaveCmd = AndroidNativeSystemActionExecutor.parseCommand("記下：明天下午三點交報告")
            as? AndroidNativeSystemActionExecutor.NativeSystemCommand.UnifiedCapabilityCommand
        assertNotNull(noteSaveCmd)
        val saveReq = noteSaveCmd!!.request as? SystemCapabilityHub.CapabilityRequest.QuickNoteSave
        assertNotNull(saveReq)
        assertEquals("明天下午三點交報告", saveReq!!.content)

        val (saveReply, _) = AndroidNativeAiEngine.generateConversationalReply("記下：明天下午三點交報告", null)
        assertTrue(saveReply.contains("明天下午三點交報告"))

        val (queryReply, _) = AndroidNativeAiEngine.generateConversationalReply("我剛記了什麼", null)
        assertTrue(queryReply.contains("明天下午三點交報告"))

        // 2. Notification inbox & sensitive OTP redaction
        SystemCapabilityHub.recordNotificationInternal("jp.naver.line.android", "LINE", "媽媽", "今晚回家吃飯嗎")
        SystemCapabilityHub.recordNotificationInternal("com.google.android.apps.messaging", "訊息", "銀行", "您的動態驗證碼為 839201")
        val notifReq = SystemCapabilityHub.parseRequest("剛剛誰傳訊息給我")
            as? SystemCapabilityHub.CapabilityRequest.NotificationQuery
        assertNotNull(notifReq)
        assertTrue(notifReq!!.onlyMissedOrRecent)

        // 3. Clipboard read & write
        assertTrue(SystemCapabilityHub.parseRequest("我剛複製了什麼") is SystemCapabilityHub.CapabilityRequest.ClipboardRead)
        val clipWrite = SystemCapabilityHub.parseRequest("複製：台北市信義區信義路五段7號")
            as? SystemCapabilityHub.CapabilityRequest.ClipboardWrite
        assertNotNull(clipWrite)
        assertEquals("台北市信義區信義路五段7號", clipWrite!!.textToCopy)

        // 4. Storage & RAM query
        assertTrue(SystemCapabilityHub.parseRequest("手機還剩多少空間") is SystemCapabilityHub.CapabilityRequest.StorageAndRamQuery)

        // 5. DND & Connectivity panel
        val dndOn = SystemCapabilityHub.parseRequest("開啟勿擾模式") as? SystemCapabilityHub.CapabilityRequest.DndControl
        assertNotNull(dndOn)
        assertTrue(dndOn!!.enableDnd)

        // 6. Contact lookup & dial by name
        val dialMom = SystemCapabilityHub.parseRequest("打電話給媽媽") as? SystemCapabilityHub.CapabilityRequest.ContactLookup
        assertNotNull(dialMom)
        assertEquals("媽媽", dialMom!!.personName)
        assertTrue(dialMom.dialImmediately)

        val lookupPhone = SystemCapabilityHub.parseRequest("王小明的電話幾號") as? SystemCapabilityHub.CapabilityRequest.ContactLookup
        assertNotNull(lookupPhone)
        assertEquals("王小明", lookupPhone!!.personName)
        assertFalse(lookupPhone.dialImmediately)

        // 7. Calendar agenda query
        val calTomorrow = SystemCapabilityHub.parseRequest("明天有什麼行程") as? SystemCapabilityHub.CapabilityRequest.CalendarQuery
        assertNotNull(calTomorrow)
        assertEquals(1, calTomorrow!!.dayOffset)

        // 8. Location query
        assertTrue(SystemCapabilityHub.parseRequest("我現在在哪") is SystemCapabilityHub.CapabilityRequest.LocationQuery)

        // 9. Media & Downloads query
        val dlQuery = SystemCapabilityHub.parseRequest("打開下載資料夾") as? SystemCapabilityHub.CapabilityRequest.MediaAndDownloadsQuery
        assertNotNull(dlQuery)
        assertEquals("DOWNLOADS", dlQuery!!.targetType)
    }
}
