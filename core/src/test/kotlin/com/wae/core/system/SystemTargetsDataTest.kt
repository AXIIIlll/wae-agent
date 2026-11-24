package com.wae.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系統目標別名表的載入契約。
 *
 * 為什麼要測：這張表搬進 TSV 之後，程式就沒有內建別名了。
 * 如果載入失敗或不忠實，「打開 Wi-Fi」「調高音量」會突然不被認得——
 * 這種退步在真機上很難一眼看出來。
 */
class SystemTargetsDataTest {

    private val shipped = listOf(
        "# 註解要被忽略",
        "WIFI\twifi|wi-fi|無線網路|無線",
        "BLUETOOTH\t藍牙|藍芽|bluetooth",
        "AIRPLANE\t飛航模式|飛航|airplane",
        "BRIGHTNESS\t亮度|螢幕亮度|brightness",
        "VOLUME\t音量|音量大小|媒體音量|聲音|鈴聲|volume"
    )

    @Test
    fun `aliases come from the data not from code`() {
        SystemTargets.installFrom(shipped)
        assertEquals(SystemTargets.Target.WIFI, SystemTargets.normalize("Wi-Fi"))
        assertEquals(SystemTargets.Target.WIFI, SystemTargets.normalize("無線網路"))
        assertEquals(SystemTargets.Target.BLUETOOTH, SystemTargets.normalize("藍芽"))
        assertEquals(SystemTargets.Target.AIRPLANE, SystemTargets.normalize("飛航"))
        assertEquals(SystemTargets.Target.BRIGHTNESS, SystemTargets.normalize("螢幕亮度"))
        assertEquals(SystemTargets.Target.VOLUME, SystemTargets.normalize("volume"))
    }

    @Test
    fun `suffix wording still resolves`() {
        SystemTargets.installFrom(shipped)
        // 「Wi-Fi 開關」這種帶尾綴的說法
        assertEquals(SystemTargets.Target.WIFI, SystemTargets.normalize("Wi-Fi 開關"))
    }

    @Test
    fun `without data nothing resolves which is the honest fallback`() {
        SystemTargets.installFrom(emptyList())
        assertNull(SystemTargets.normalize("Wi-Fi"))
        assertNull(SystemTargets.normalize("音量"))
    }

    @Test
    fun `only panel targets need a panel`() {
        assertTrue(SystemTargets.needsPanel(SystemTargets.Target.WIFI))
        assertTrue(SystemTargets.needsPanel(SystemTargets.Target.BLUETOOTH))
        assertTrue(SystemTargets.needsPanel(SystemTargets.Target.AIRPLANE))
        // 亮度與音量有可讀回的系統 API，不該被當成「只能開面板」
        assertFalse(SystemTargets.needsPanel(SystemTargets.Target.BRIGHTNESS))
        assertFalse(SystemTargets.needsPanel(SystemTargets.Target.VOLUME))
    }

    @Test
    fun `unknown target names in data are ignored`() {
        SystemTargets.installFrom(listOf("NOPE\txxx", "WIFI\twifi"))
        assertNull(SystemTargets.normalize("xxx"))
        assertEquals(SystemTargets.Target.WIFI, SystemTargets.normalize("wifi"))
    }
}
