package com.wae.core.system

/**
 * 系統設定的「目標名稱」正規化（D10）。
 *
 * 為什麼要一層：使用者會說「Wi-Fi」「wifi」「無線網路」「藍牙」「藍芽」；
 * 這些不是 App，也不該被當成 App 去開。這裡只做名稱 → 類別的映射。
 *
 * 誠實邊界：Android 10 以後，第三方 App **不能**直接開關 Wi-Fi／藍牙；
 * 只能開對應的設定面板讓使用者自己切。所以這些目標的結果是 `panel:*`，
 * 不是「我幫你關好了」。
 */
object SystemTargets {

    enum class Target { WIFI, BLUETOOTH, AIRPLANE, BRIGHTNESS }

    private val ALIASES: Map<String, Target> = mapOf(
        "wifi" to Target.WIFI,
        "wi-fi" to Target.WIFI,
        "無線網路" to Target.WIFI,
        "無線" to Target.WIFI,
        "藍牙" to Target.BLUETOOTH,
        "藍芽" to Target.BLUETOOTH,
        "bluetooth" to Target.BLUETOOTH,
        "飛航模式" to Target.AIRPLANE,
        "飛航" to Target.AIRPLANE,
        "airplane" to Target.AIRPLANE,
        "亮度" to Target.BRIGHTNESS,
        "螢幕亮度" to Target.BRIGHTNESS
    )

    fun normalize(text: String): Target? {
        val key = text.trim().lowercase(java.util.Locale.ROOT).replace(" ", "")
        ALIASES[key]?.let { return it }
        // 允許「Wi-Fi 開關」這種帶尾綴的說法
        return ALIASES.entries.firstOrNull { key.contains(it.key) }?.value
    }

    fun keyOf(target: Target): String = when (target) {
        Target.WIFI -> "wifi"
        Target.BLUETOOTH -> "bluetooth"
        Target.AIRPLANE -> "airplane"
        Target.BRIGHTNESS -> "brightness"
    }

    /** 需要「開面板讓使用者自己切」的目標。 */
    fun needsPanel(target: Target): Boolean = target != Target.BRIGHTNESS
}
