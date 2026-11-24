package com.wae.core.plan

/**
 * 計畫的「執行前後條件」判定。
 *
 * 條件刻意寫成小型、可讀的字串，而不是 DSL：
 *  - `pkg=com.android.chrome`        目前前景是這個 App
 *  - `label:歷史紀錄`                 畫面上有這個標籤（可選項）
 *  - `changed`                       畫面指紋跟動作前不同
 *  - `checked:Wi-Fi=true`            某個開關變成指定狀態
 *  - 空字串                          不設條件（永遠成立）
 *
 * 為什麼不直接用 lambda：條件要能寫進日誌／軌跡，也要能從資料產生，
 * 純字串在事後追查時看得懂。
 */
object PlanCondition {

    /** 條件評估需要的事實（由呼叫端從畫面收集）。 */
    data class Facts(
        val packageName: String = "",
        val fingerprint: String = "",
        val labels: List<String> = emptyList(),
        val checked: Map<String, Boolean> = emptyMap(),
        /** 動作前的指紋（評估 `changed` 用）。 */
        val previousFingerprint: String = ""
    )

    /** 條件是否成立；空條件視為成立。 */
    fun holds(expr: String, facts: Facts): Boolean {
        val e = expr.trim()
        if (e.isEmpty()) return true
        return when {
            e.equals("changed", ignoreCase = true) ->
                facts.previousFingerprint.isNotEmpty() && facts.fingerprint != facts.previousFingerprint

            e.startsWith("pkg=", ignoreCase = true) -> {
                val want = e.substringAfter('=').trim()
                want.isEmpty() || facts.packageName.equals(want, ignoreCase = true)
            }

            e.startsWith("label:", ignoreCase = true) -> {
                val want = e.substringAfter(':').trim()
                want.isNotEmpty() && facts.labels.any { fold(it).contains(fold(want)) }
            }

            e.startsWith("checked:", ignoreCase = true) -> {
                // checked:Wi-Fi=true
                val rest = e.substringAfter(':')
                val wantLabel = rest.substringBefore('=').trim()
                val wantValue = rest.substringAfter('=', "true").trim().equals("true", ignoreCase = true)
                if (wantLabel.isEmpty()) {
                    false
                } else {
                    val actual = facts.checked.entries.firstOrNull {
                        fold(it.key).contains(fold(wantLabel))
                    }?.value
                    actual == wantValue
                }
            }

            // 不認識的條件：不要假裝成立（寧可讓上層看到 false 並記錄）
            else -> false
        }
    }

    /** 把條件轉成人看得懂的說明（軌跡用）。 */
    fun describe(expr: String): String = if (expr.isBlank()) "（無條件）" else expr

    private fun fold(s: String): String = com.wae.core.predicate.TextFold.fold(s)
}
