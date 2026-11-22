package com.wae.agent.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 評測集守門測試：確保 100 任務、難度分層齊全、EXTREME 真的 ≥30 步。
 *
 * 這個測試存在的意義：評測集是「等級宣稱」的唯一依據，
 * 不能因為隨手改動而悄悄退化（例如把 EXTREME 改短、或刪掉某一層）。
 */
class BenchmarkSuiteTest {

    private fun tasksFile(): File {
        val candidates = listOf(
            File("../benchmark/tasks.tsv"),
            File("benchmark/tasks.tsv"),
            File("E:/NewAndroidApp/benchmark/tasks.tsv")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到 benchmark/tasks.tsv")
    }

    private fun load(): List<Map<String, String>> {
        val rows = ArrayList<Map<String, String>>()
        tasksFile().readLines(Charsets.UTF_8).forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val p = line.split("\t")
            if (p.size < 7 || p[0] == "id") return@forEach
            rows.add(
                mapOf(
                    "id" to p[0], "tier" to p[1], "cmd" to p[3],
                    "min_steps" to p[4], "timeout" to p[5]
                )
            )
        }
        return rows
    }

    @Test
    fun `suite has exactly one hundred tasks`() {
        assertEquals(100, load().size)
    }

    @Test
    fun `every difficulty tier is present`() {
        val byTier = load().groupingBy { it["tier"] }.eachCount()
        listOf("EASY", "MEDIUM", "HARD", "EXTREME").forEach {
            assertTrue("難度層 $it 不得為空", (byTier[it] ?: 0) > 0)
        }
        assertEquals(30, byTier["EASY"])
        assertEquals(30, byTier["MEDIUM"])
        assertEquals(25, byTier["HARD"])
        assertEquals(15, byTier["EXTREME"])
    }

    @Test
    fun `extreme tasks are at least thirty steps`() {
        val extreme = load().filter { it["tier"] == "EXTREME" }
        extreme.forEach {
            assertTrue(
                "${it["id"]} 的 min_steps 必須 >= 30，實際=${it["min_steps"]}",
                (it["min_steps"]?.toIntOrNull() ?: 0) >= 30
            )
        }
    }

    @Test
    fun `task ids are unique and timeouts are sane`() {
        val rows = load()
        assertEquals("任務 id 必須唯一", rows.size, rows.map { it["id"] }.toSet().size)
        rows.forEach {
            val t = it["timeout"]?.toIntOrNull() ?: 0
            assertTrue("${it["id"]} 逾時設定不合理：$t", t in 20..900)
        }
    }
}