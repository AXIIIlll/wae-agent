package com.wae.agent.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「打開 X」的前置檢查（第 1 個修法）。
 *
 * 實測第 20 題：`打開 Wi-Fi` → `ABSTAIN reason=no_candidate app_not_installed=Wi-Fi`。
 * 系統目標被當成 App 名稱，整條指令在這一關就被判死，連系統面板都不會開。
 */
class MissingAppGuardTest {

    private val systemTargets = setOf("Wi-Fi", "wifi", "藍牙", "飛航模式", "亮度")

    private fun guard(
        firstStepText: String,
        installed: Set<String> = setOf("設定", "Chrome", "地圖")
    ): String? = MissingAppGuard.missingLabel(
        firstStepText = firstStepText,
        isSystemTarget = { it in systemTargets },
        isInstalled = { it in installed }
    )

    @Test
    fun `system targets are never reported as missing apps`() {
        // 這是踩過的坑：Wi-Fi 不是 App
        assertNull(guard("打開 Wi-Fi"))
        assertNull(guard("開啟藍牙"))
        assertNull(guard("打開飛航模式"))
        assertNull(guard("設定亮度"))
    }

    @Test
    fun `installed app passes`() {
        assertNull(guard("打開設定"))
        assertNull(guard("開啟 Chrome"))
    }

    @Test
    fun `really missing app is reported by name`() {
        assertEquals("不存在App", guard("打開不存在App"))
    }

    @Test
    fun `non launch sentences are not judged here`() {
        // 「在設定裡搜尋藍牙」不是開 App，這關不該插手
        assertNull(guard("在設定裡搜尋藍牙"))
        assertNull(guard("把亮度調高"))
    }

    @Test
    fun `prefixed forms all strip to the same label`() {
        assertEquals("不存在App", guard("開啟不存在App"))
        assertEquals("不存在App", guard("用不存在App"))
    }
}
