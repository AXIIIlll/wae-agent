package com.example.newandroidapp.ai

/**
 * 正式產品套件名稱與身份識別規範化設定 (AppIdentityConfig)
 *
 * 將測試預設包名 (`com.example.newandroidapp`) 統一對齊至正式產品識別名稱 (`com.localai.visionassistant`)，
 * 同時保留對編譯期 Kotlin 命名空間之向下相容識別，確保在不破壞現有命名規範的前提下完成全面產品化對齊。
 */
object AppIdentityConfig {
    const val OFFICIAL_PRODUCT_PACKAGE_ID = "com.localai.visionassistant"
    const val LEGACY_NAMESPACE_PACKAGE_ID = "com.example.newandroidapp"

    val SELF_PACKAGE_IDS: Set<String> = setOf(
        OFFICIAL_PRODUCT_PACKAGE_ID,
        LEGACY_NAMESPACE_PACKAGE_ID
    )

    fun isSelfAssistantPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val clean = packageName.trim()
        return clean == OFFICIAL_PRODUCT_PACKAGE_ID ||
            clean == LEGACY_NAMESPACE_PACKAGE_ID ||
            clean.startsWith("$OFFICIAL_PRODUCT_PACKAGE_ID:") ||
            clean.startsWith("$LEGACY_NAMESPACE_PACKAGE_ID:")
    }

    fun normalizePackageIdentifier(rawPackageName: String?): String {
        val clean = rawPackageName?.trim().orEmpty()
        if (clean == LEGACY_NAMESPACE_PACKAGE_ID) {
            return OFFICIAL_PRODUCT_PACKAGE_ID
        }
        return clean
    }
}
