package com.wae.agent.core.app

/**
 * 「打開 X」的 X 到底是不是一個**不存在**的 App？
 *
 * 為什麼要獨立成純函式：這個判斷曾經把「打開 Wi-Fi」判成
 * `app_not_installed=Wi-Fi`（實測，第 20 題），整條指令在這一關就被判死，
 * 連系統面板都不會去開。它需要被單元測試釘住，而不是埋在 900 行的服務裡。
 *
 * 規則：
 *  1. 不是「打開／開啟／用 X」句型 → 不管（回 null）。
 *  2. X 是系統目標（Wi-Fi／藍牙／飛航／亮度）→ 不管。**這些不是 App**。
 *  3. 其餘才去問「已安裝清單裡有沒有它」，沒有才回報缺少。
 */
object MissingAppGuard {

    private val LAUNCH_PREFIXES = listOf("打開", "開啟", "用")

    fun missingLabel(
        firstStepText: String,
        isSystemTarget: (String) -> Boolean,
        isInstalled: (String) -> Boolean
    ): String? {
        val raw = firstStepText.trim()
        val label = LAUNCH_PREFIXES.fold(raw) { acc, p -> acc.removePrefix(p) }.trim()
        if (label.isEmpty() || label == raw) return null
        if (isSystemTarget(label)) return null
        return if (isInstalled(label)) null else label
    }
}
