package com.wae.agent.core.runtime

import com.wae.core.config.ConfigParser
import com.wae.core.config.Key
import com.wae.core.config.MapConfig
import com.wae.core.safety.ContractBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 預算單一來源守門測試。
 *
 * 存在理由：這個專案踩過「一個概念多份實作」——同一個步數上限在舊路徑叫
 * `agent.max_steps`、在 Gen5 叫 `budget.max_steps`，兩邊值還不一樣（12 對 20），
 * 改一份等於沒改。這個測試把「只能有一份」變成可執行的規則。
 *
 * 判定方式刻意不用「列舉可疑鍵名」，而是用結構規則：
 *  1. 任何定義了預算鍵的參數表，彼此的**同名鍵必須同值**（資料庫自己對自己）。
 *  2. 預算鍵的**末段名稱**（`max_steps` 這種）在全專案 TSV 只能出現一次，
 *     不管前面掛哪個前綴——換前綴就是另立一份。
 *  3. 兩條程式路徑（舊迴圈 `AgentLimits`、Gen5 `ContractBudget`）解析出的
 *     數字必須相同，等於證明「改一份就是全部生效」。
 */
class BudgetSingleSourceTest {

    private fun repoFile(rel: String): File? =
        listOf(File("../$rel"), File(rel), File("E:/NewAndroidApp/$rel")).firstOrNull { it.exists() }

    private fun appParameterTable(): File? =
        repoFile("app/src/main/assets/open_source_ai_databases/ui_tunables.tsv")

    private fun coreParameterTable(): File? = repoFile("core/src/main/resources/ui/ui_tunables.tsv")

    private fun allTsvFiles(): List<File> {
        val roots = listOf(
            "app/src/main/assets/open_source_ai_databases",
            "core/src/main/resources/ui"
        ).mapNotNull { repoFile(it) }
        return roots.flatMap { root ->
            root.listFiles().orEmpty().filter { it.isFile && it.extension == "tsv" }.toList()
        }
    }

    private fun parse(file: File): Map<String, String> =
        ConfigParser.parse(file.readLines(Charsets.UTF_8).asSequence())

    /** 每個 TSV 的第一欄（鍵名）；沒有 TSV 讀不到時回空，讓測試自己判斷。 */
    private fun firstColumn(file: File): List<String> =
        file.readLines(Charsets.UTF_8)
            .map { it.trimStart('\uFEFF').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { it.split('\t').firstOrNull()?.trim() }
            .filter { it.isNotEmpty() }

    @Before
    fun setUp() {
        Tunables.resetForTest()
    }

    @Test
    fun `budget keys are defined in both parameter tables with identical values`() {
        val app = appParameterTable()
        val core = coreParameterTable()
        assertTrue("找不到 App 參數表", app != null)
        assertTrue("找不到 core 內建參數表", core != null)

        val appValues = parse(app!!)
        val coreValues = parse(core!!)

        for (key in BudgetSource.KEYS) {
            val a = appValues[key]
            val c = coreValues[key]
            assertTrue("App 參數表缺少預算鍵 $key（值必須放 ui_tunables.tsv）", a != null)
            assertTrue("core 內建參數表缺少預算鍵 $key", c != null)
            assertEquals("預算鍵 $key 在兩張表的值不一致：app=$a core=$c", c, a)
        }
    }

    @Test
    fun `no other tsv redefines a budget concept under another prefix`() {
        val files = allTsvFiles()
        assertTrue("找不到任何 TSV，無法檢查", files.isNotEmpty())

        val leaves = BudgetSource.KEYS.map { it.substringAfterLast('.') }.toSet()
        val violations = ArrayList<String>()
        for (file in files) {
            for (key in firstColumn(file)) {
                val leaf = key.substringAfterLast('.')
                if (leaf in leaves && key !in BudgetSource.KEYS) {
                    violations.add("${file.name}: $key 與預算鍵同義（末段 $leaf），請改用 budget.* 同一份")
                }
            }
        }
        assertTrue(
            "預算概念被另立一份（改它不會生效）：\n" + violations.distinct().joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `legacy loop and gen5 contract read the same numbers`() {
        val app = appParameterTable()
        assertTrue("找不到 App 參數表", app != null)
        Tunables.loadFromLines(app!!.readLines(Charsets.UTF_8).asSequence())

        // 舊路徑：Tunables → AgentLimits
        val limits = BudgetSource.limits(null)
        // Gen5 路徑：assets 合併內建後 → ContractBudget
        val merged = LinkedHashMap(parse(coreParameterTable()!!))
        merged.putAll(parse(app))
        val contract = ContractBudget.fromConfig(MapConfig(merged))

        assertEquals(contract.maxSteps, limits.maxSteps)
        assertEquals(contract.maxWallClockMs, limits.deadlineMs)
        assertEquals(contract.maxHighRiskOps, Tunables.getInt(null, Key.BUDGET_MAX_HIGH_RISK, -1))
        assertTrue("步數上限必須是正數", limits.maxSteps > 0)
        assertTrue("時限必須是正數", limits.deadlineMs > 0)
    }
}
