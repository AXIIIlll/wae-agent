package com.wae.agent.legal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 綠區合規守門測試（CI gate）。
 *
 * 對照 docs/GREEN_ZONE_POLICY.md 第 3 節「明確禁止」清單，
 * 掃描主原始碼樹，出現任何紅區符號即視為缺陷。
 *
 * 這個測試的意義：把「法律邊界」變成一個**會讓 build 失敗的客觀事實**，
 * 而不是靠 review 時的記憶。
 */
class GreenZoneGuardTest {

    private val bannedSymbols = linkedMapOf(
        "DexFile" to "反編譯 DEX",
        "dalvik.system" to "動態載入/反編譯",
        "dexlib" to "DEX 解析庫",
        "baksmali" to "DEX 反組譯",
        "jadx" to "反編譯器",
        "getResourcesForApplication" to "讀取他人資源表",
        "publicSourceDir" to "開啟他人 APK 路徑",
        "splitSourceDirs" to "開啟他人 APK 分割路徑",
        "DexClassLoader" to "載入他人程式碼",
        "Axmleditor" to "AXML 編輯/解析",
        "BinaryXml" to "二進位 manifest 解析",
        "frida" to "Hook 框架",
        "xposed" to "Hook 框架"
    )

    @Test
    fun `no red-zone API appears in production sources`() {
        val root = File("src/main/java")
        assertTrue("找不到 src/main/java，測試工作目錄可能不對", root.exists())

        val violations = ArrayList<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = try {
                    file.readText()
                } catch (_: Throwable) {
                    return@forEach
                }
                // 允許在註解中討論這些詞（例如本政策文件引用）
                val codeOnly = text.lines()
                    .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                    .joinToString("\n")
                for ((symbol, reason) in bannedSymbols) {
                    if (codeOnly.contains(symbol, ignoreCase = true)) {
                        violations.add("${file.path} 含 '$symbol'（$reason）")
                    }
                }
            }

        assertTrue(
            "綠區合規違規，請見 docs/GREEN_ZONE_POLICY.md：\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `capability module only uses package manager apis`() {
        val file = File("src/main/java/com/wae/agent/core/capability/AppCapabilityIndex.kt")
        assertTrue("能力圖譜模組必須存在", file.exists())
        val text = file.readText()
        // 該模組只允許 packageManager / Intent / 系統設定，不得出現檔案解析他人的痕跡
        assertTrue("能力圖譜必須透過 PackageManager 取得資訊", text.contains("packageManager"))
        assertTrue(
            "能力圖譜不得讀取他人 APK zip",
            !text.contains("ZipFile") && !text.contains("ZipInputStream")
        )
    }
}
