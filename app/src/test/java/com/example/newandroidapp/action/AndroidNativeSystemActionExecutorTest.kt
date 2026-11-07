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

        // Ensure multi-step UI instructions are not hijacked
        assertNull(AndroidNativeSystemActionExecutor.parseCommand("打開 YouTube 搜尋 周杰倫"))
        assertNull(AndroidNativeSystemActionExecutor.parseCommand("打開時鐘 點擊 鬧鐘 新增"))
    }
}
