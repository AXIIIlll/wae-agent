package com.wae.core

import com.wae.core.config.MapConfig
import com.wae.core.exec.Actuator
import com.wae.core.exec.Executor
import com.wae.core.exec.RawResult
import com.wae.core.exec.Rollback
import com.wae.core.exec.TransactionLog
import com.wae.core.memory.ProgramCache
import com.wae.core.model.Effect
import com.wae.core.model.Means
import com.wae.core.model.Op
import com.wae.core.model.PostCondition
import com.wae.core.model.Step
import com.wae.core.time.FixedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** F6 回滾 ＆ C7 路徑快取。 */
class RollbackAndProgramTest {

    private val clock = FixedClock(0)
    private val config = MapConfig(emptyMap())

    @Test
    fun `rollback runs reverse actions in reverse order`() {
        val calls = ArrayList<String>()
        val actuator = object : Actuator {
            override fun supports(means: Means): Boolean = true
            override fun run(means: Means): RawResult {
                calls.add(means.describe())
                return RawResult.applied()
            }
        }
        val executor = Executor(actuator, clock, config)
        val log = TransactionLog()

        val click = Step(
            Effect("toggle:藍牙", Op.SET), Means.A11y("sw1", "click"),
            PostCondition { true }, idempotencyKey = "a"
        )
        val input = Step(
            Effect("query:x", Op.INPUT), Means.Input("hello"),
            PostCondition { true }, idempotencyKey = "b"
        )
        log.record(click, "fp1", "fp2", TransactionLog.reverseOf(click, "fp1"))
        log.record(input, "fp2", "fp3", TransactionLog.reverseOf(input, "fp2"))

        val report = Rollback(executor).run(log)

        assertEquals(2, report.attempted)
        assertEquals(2, report.rolledBack.size)
        // 後進先出：先清輸入，再點回來
        assertEquals(listOf("input:ime", "a11y:click"), calls)
    }

    @Test
    fun `app launch rolls back with BACK`() {
        val launch = Step(
            Effect("app:設定", Op.NAVIGATE),
            Means.Intent(action = "android.intent.action.MAIN", pkg = "com.android.settings", data = "設定"),
            PostCondition { true }, idempotencyKey = "c"
        )
        val reverse = TransactionLog.reverseOf(launch, "fp")
        assertTrue(reverse is Means.SystemApi)
        assertEquals("global_action:BACK", (reverse as Means.SystemApi).api)
    }

    @Test
    fun `program cache remembers and forgets hints`() {
        val dir = Files.createTempDirectory("wae-prog").toFile()
        try {
            val file = java.io.File(dir, "programs.tsv")
            val cache = ProgramCache(file)
            cache.record("cmd", "pkg", 6L, "SET:toggle:藍牙", "a11y:click")
            assertEquals(mapOf("SET:toggle:藍牙" to "a11y:click"), cache.hintsFor("cmd", "pkg", 6L))
            // 版本不同就失效（改版後不要沿用舊路徑）
            assertTrue(cache.hintsFor("cmd", "pkg", 7L).isEmpty())
            cache.save()

            val reloaded = ProgramCache(file)
            assertEquals(1, reloaded.size())
            assertEquals(mapOf("SET:toggle:藍牙" to "a11y:click"), reloaded.hintsFor("cmd", "pkg", 6L))

            reloaded.forget("cmd", "pkg", 6L, "SET:toggle:藍牙")
            assertTrue(reloaded.hintsFor("cmd", "pkg", 6L).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
