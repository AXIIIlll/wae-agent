package com.wae.agent.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 資料來源守門測試（使用者要求）：
 * **所有需要資料的地方一律從資料庫（assets 內的 .tsv 檔）抓，程式碼裡不准自己列名單。**
 *
 * 判定方式是「用資料庫反過來檢查程式碼」：
 *   1. 從 App 元資料庫讀出所有 App 顯示名稱
 *   2. 掃描生產程式碼（去掉註解）
 *   3. 若程式碼出現資料庫裡的 App 名稱 → 視為違規（代表有人把資料寫進程式）
 *
 * 這樣檢查清單本身也是資料驅動的：資料庫新增 App，檢查自動涵蓋，不需要改測試。
 */
class DataSourcePolicyTest {

    private fun repoFile(rel: String): File? {
        val candidates = listOf(File("../$rel"), File(rel), File("E:/NewAndroidApp/$rel"))
        return candidates.firstOrNull { it.exists() }
    }

    private fun appDisplayNames(): List<String> {
        val f = repoFile("app/src/main/assets/open_source_ai_databases/global_tw_cn_app_metadata.tsv")
            ?: return emptyList()
        return f.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val p = line.split("\t")
                val name = if (p.size >= 2) p[1].trim() else ""
                // 只檢查含中文的名稱：英文品牌名（Edge/Threads）與程式識別字衝突
                // 只檢查 >=3 個中文字的名稱：2 字名稱（設定/地圖/相機）同時是通用 UI 詞，誤判率太高
                if (name.length >= 3 && name.all { it.code in 0x4E00..0x9FFF }) name else null
            }
            .distinct()
    }

    private fun productionSources(): List<File> {
        val root = repoFile("app/src/main/java") ?: return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun stripComments(text: String): String =
        text.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    @Test
    fun `app names come from database not code`() {
        val names = appDisplayNames()
        assertTrue("App 元資料庫應可讀取", names.isNotEmpty())
        val violations = ArrayList<String>()
        for (file in productionSources()) {
            val code = stripComments(file.readText())
            for (name in names) {
                if (code.contains(name)) {
                    violations.add("${file.name}: 出現 App 名稱 '$name'")
                }
            }
        }
        assertTrue(
            "程式碼中不得硬寫 App 名稱（請從 assets 資料庫讀取）：\n" +
                violations.distinct().joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `databases exist and are non trivial`() {
        val required = listOf(
            "app/src/main/assets/open_source_ai_databases/command_lexicon.tsv",
            "app/src/main/assets/open_source_ai_databases/global_tw_cn_app_metadata.tsv",
            "app/src/main/assets/open_source_ai_databases/zh_classifier_lexicon.tsv",
            "app/src/main/assets/open_source_ai_databases/ui_action_vocabulary.tsv",
            "app/src/main/assets/open_source_ai_databases/opencc_zh_tw_cn_lexicon.tsv"
        )
        for (rel in required) {
            val f = repoFile(rel)
            assertTrue("缺少資料庫：$rel", f != null && f.length() > 1000)
        }
        val metaFile = repoFile("app/src/main/assets/open_source_ai_databases/global_tw_cn_app_metadata.tsv")
        val rowCount = metaFile?.readLines(Charsets.UTF_8)
            ?.count { it.isNotBlank() && !it.startsWith("#") } ?: 0
        assertTrue("App 元資料庫應有數十個 App，實際=$rowCount", rowCount >= 20)
    }
}