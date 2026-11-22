package com.wae.core.pipeline

/**
 * App 名稱 → 套件名（B20）。
 *
 * 為什麼要這一層：core 不能碰 `PackageManager`，但「開 App」如果要能被驗證，
 * 就必須知道**目標套件名**（`pkg == 目標` 才是誠實的後置條件，而不是「畫面變了」）。
 *
 * `matches` 是為了處理「同一支 App、多個 package」的情況：
 * 實測開啟「設定」後前景可能是 `com.google.android.settings.intelligence`，
 * 精確比對會把成功的啟動判成失敗。
 */
interface AppResolver {
    fun resolve(label: String): String?

    /** 前景的 `pkg` 算不算是使用者说的那個 App。預設只做精確比對。 */
    fun matches(pkg: String, label: String): Boolean {
        val expected = resolve(label) ?: return false
        return pkg == expected
    }
}
