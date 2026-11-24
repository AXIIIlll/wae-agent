package com.wae.core.system

/**
 * 系統設定的「目標名稱」正規化（D10）。
 *
 * 為什麼要一層：使用者會說「Wi-Fi」「wifi」「無線網路」「藍牙」「藍芽」；
 * 這些不是 App，也不該被當成 App 去開。這裡只做名稱 → 類別的映射。
 *
 * 別名表在 `ui_system_targets.tsv`（資料驅動，程式不內建詞表）。
 * 沒有載入資料時 `normalize` 一律回 null——寧可走原本的流程，
 * 也不要用一份過期的內建清單假裝認得。
 *
 * 誠實邊界（Android 10+）：
 *  - Wi-Fi／藍牙／飛航：第三方**不能**直接開關，只能開面板讓使用者自己切。
 *  - 亮度：需要 WRITE_SETTINGS 才能改，改了可以讀回確認。
 *  - 音量：可以用 AudioManager 調整，也能讀回確認。
 */
object SystemTargets {

    enum class Target { WIFI, BLUETOOTH, AIRPLANE, BRIGHTNESS, VOLUME }

    /** 正規化後的鍵 → 目標。key 一律小寫、去空白。 */
    @Volatile
    private var aliases: Map<String, Target> = emptyMap()

    /** 由 App 啟動時載入（見 `SystemActionLoader`）。 */
    fun installFrom(lines: List<String>) {
        val out = HashMap<String, Target>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            if (p.size < 2) continue
            val target = com.wae.core.util.Degrade.orNull("systemTargets:target") {
                Target.valueOf(p[0].trim().uppercase())
            } ?: continue
            for (alias in p[1].split('|')) {
                val key = alias.trim().lowercase(java.util.Locale.ROOT).replace(" ", "")
                if (key.isNotEmpty()) out[key] = target
            }
        }
        aliases = out
    }

    fun normalize(text: String): Target? {
        if (aliases.isEmpty()) return null
        val key = text.trim().lowercase(java.util.Locale.ROOT).replace(" ", "")
        aliases[key]?.let { return it }
        // 允許「Wi-Fi 開關」這種帶尾綴的說法
        return aliases.entries.firstOrNull { key.contains(it.key) }?.value
    }

    fun keyOf(target: Target): String = when (target) {
        Target.WIFI -> "wifi"
        Target.BLUETOOTH -> "bluetooth"
        Target.AIRPLANE -> "airplane"
        Target.BRIGHTNESS -> "brightness"
        Target.VOLUME -> "volume"
    }

    /**
     * 需要「開面板讓使用者自己切」的目標。
     *
     * 音量與亮度不算：它們有可讀回的系統 API，不需要面板。
     */
    fun needsPanel(target: Target): Boolean =
        target == Target.WIFI || target == Target.BLUETOOTH || target == Target.AIRPLANE
}
