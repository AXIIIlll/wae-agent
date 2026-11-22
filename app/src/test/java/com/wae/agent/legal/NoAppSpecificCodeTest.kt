package com.wae.agent.legal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「不得針對單一 App 寫死資料」守門測試（CI gate）。
 *
 * 產品要求：所有邏輯必須對**任意 App 通用**。
 * 因此生產程式碼中不得出現：
 *  - 特定第三方 App 的套件名稱（com.<vendor>.<app>）
 *  - 特定 App 的資源 id / view id（例如 xxx:id/et_search_kw）
 *  - 品牌名稱的分支判斷
 *
 * 允許：
 *  - 系統元件（permission controller 等 OS 級套件），但必須集中且有說明
 *  - 我們自己的套件名
 *  - 註解中的举例（註解會被剝除後再檢查）
 */
class NoAppSpecificCodeTest {

    /** 允許出現的套件名稱（系統元件 / 自身 / 第三方程式庫命名空間），集中管理 */
    private val allowedPackages = setOf(
        "com.wae.agent",
        // Gen-5 純 JVM 核心（自己的模組，不是被自動化的第三方 App）
        "com.wae.core",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "android",
        "com.android.settings",
        // 離線語音引擎（Apache-2.0 程式庫，非被自動化的第三方 App）
        "com.k2fsa.sherpa.onnx"
    )

    private val packageRegex = Regex("""\bcom\.[a-z0-9_]+\.[a-z0-9_.]+""")
    private val brandWords = listOf(
        "youtube", "gmail", "instagram", "facebook", "whatsapp", "spotify",
        "tiktok", "douyin", "wechat", "twitter", "telegram", "netflix",
        "amazon", "shopee"
        // 注意：不列 "line"，它會與英文單字 line（readLine/lineSequence/變數 line）衝突，
        // 該品牌的辨識交由上面的套件字面量檢查（com.linecorp.*）處理。
    )

    private fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var inBlock = false
        for (line in source.lineSequence()) {
            var l = line
            if (inBlock) {
                val end = l.indexOf("*/")
                if (end >= 0) {
                    l = l.substring(end + 2)
                    inBlock = false
                } else {
                    continue
                }
            }
            val blockStart = l.indexOf("/*")
            if (blockStart >= 0) {
                val blockEnd = l.indexOf("*/", blockStart)
                if (blockEnd >= 0) {
                    l = l.substring(0, blockStart) + l.substring(blockEnd + 2)
                } else {
                    l = l.substring(0, blockStart)
                    inBlock = true
                }
            }
            val lineComment = l.indexOf("//")
            if (lineComment >= 0) l = l.substring(0, lineComment)
            sb.append(l).append('\n')
        }
        return sb.toString()
    }

    @Test
    fun `production code contains no third-party app package literals`() {
        val root = File("src/main/java")
        assertTrue("找不到 src/main/java", root.exists())
        val violations = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val code = stripComments(file.readText())
            for (m in packageRegex.findAll(code)) {
                val pkg = m.value
                if (pkg in allowedPackages) continue
                if (allowedPackages.any { pkg == it || pkg.startsWith("$it.") }) continue
                violations.add("${file.name}: 出現套件字面量 '$pkg'")
            }
        }
        assertTrue(
            "生產程式碼不得寫死第三方 App 套件名（需對任意 App 通用）：\n" +
                violations.distinct().joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `production code contains no brand-specific branching`() {
        val root = File("src/main/java")
        val violations = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val code = stripComments(file.readText()).lowercase()
            for (brand in brandWords) {
                // 用詞邊界比對，避免 readLine / lineSequence / deadline 之類誤命中
                val re = Regex("""(?<![a-z0-9_])${Regex.escape(brand)}(?![a-z0-9_])""")
                if (re.containsMatchIn(code)) {
                    violations.add("${file.name}: 出現品牌字樣 '$brand'")
                }
            }
        }
        assertTrue(
            "生產程式碼不得以品牌名稱做分支（應改用角色/結構/語意）：\n" +
                violations.distinct().joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `production code contains no app-specific view ids`() {
        val root = File("src/main/java")
        val violations = ArrayList<String>()
        // 只禁「特定 App 專屬」的資源 id；AOSP/Material 通用命名（tab_strip_button、
        // navigation_bar_item 等）屬於跨 App 的結構特徵，允許使用。
        val idRegex = Regex("""\b(et_search_kw|search_omnibox_text_box|explore_tab_strip_button)\b""")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val code = stripComments(file.readText())
            for (m in idRegex.findAll(code)) {
                violations.add("${file.name}: 出現 App 專屬 view id '${m.value}'")
            }
        }
        assertTrue(
            "生產程式碼不得以特定 App 的 view id 做判斷：\n" +
                violations.distinct().joinToString("\n"),
            violations.isEmpty()
        )
    }
}
