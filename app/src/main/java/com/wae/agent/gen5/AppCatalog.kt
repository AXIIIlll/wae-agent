package com.wae.agent.gen5

import android.content.Context
import android.content.Intent

/**
 * App 目錄：名稱 → 套件名的唯一解析點。
 *
 * 之前同樣的比對邏輯寫在 Actuator 裡，Planner 要驗證「開對 App」時只能用
 * 「畫面變了」猜（實測會把「本來就在前景」誤判成失敗）。現在兩邊共用同一份。
 */
object AppCatalog {

    fun resolveLabel(context: Context, label: String): String? {
        if (label.isBlank()) return null
        val pm = context.packageManager
        // 使用者可能直接說套件名
        runCatching { if (pm.getLaunchIntentForPackage(label) != null) return label }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)

        fun findBy(candidate: String): String? {
            apps.firstOrNull { it.loadLabel(pm).toString().equals(candidate, ignoreCase = true) }
                ?.let { return it.activityInfo.packageName }
            apps.firstOrNull { it.loadLabel(pm).toString().contains(candidate, ignoreCase = true) }
                ?.let { return it.activityInfo.packageName }
            return null
        }

        findBy(label)?.let { return it }
        // 別名（叫法 → 系統標籤）：使用者說「電話」，系統標籤可能是 Phone
        AppAliases.canonical(context, label)?.let { canonical -> findBy(canonical)?.let { return it } }
        return null
    }

    fun launchIntent(context: Context, pkg: String): Intent? {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /**
     * 前景的 `pkg` 算不算是使用者說的 App。
     *
     * 除了精確比對，還接受「標籤包含」——同一支 App 可能有多個 package
     * （實測：開啟「設定」後前景變成 `com.google.android.settings.intelligence`）。
     */
    fun matches(context: Context, pkg: String, label: String): Boolean {
        if (pkg.isEmpty() || label.isBlank()) return false
        val expected = resolveLabel(context, label) ?: return false
        if (pkg == expected) return true
        val pm = context.packageManager
        val wanted = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(expected, 0)).toString() }
            .getOrDefault(label)
        val actual = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault("")
        if (actual.isNotEmpty() && wanted.isNotEmpty() &&
            (actual.contains(wanted, ignoreCase = true) || wanted.contains(actual, ignoreCase = true))
        ) {
            return true
        }
        // 同一個 App 的多個 package：共用足以識別的 token（例：settings）
        return shareDistinctiveToken(pkg, expected)
    }

    private val IGNORED_TOKENS = setOf(
        "com", "android", "google", "apps", "app", "mobile", "intelligence", "settingsintelligence"
    )

    private fun shareDistinctiveToken(a: String, b: String): Boolean {
        val ta = a.lowercase().split('.').filter { it.length >= 5 && it !in IGNORED_TOKENS }.toSet()
        val tb = b.lowercase().split('.').filter { it.length >= 5 && it !in IGNORED_TOKENS }.toSet()
        return ta.intersect(tb).isNotEmpty()
    }
}
