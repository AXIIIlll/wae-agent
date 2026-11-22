package com.wae.core

import com.wae.core.config.MapConfig
import com.wae.core.contract.ContractDeriver
import com.wae.core.effect.IntentCompiler
import com.wae.core.effect.Planner
import com.wae.core.exec.Actuator
import com.wae.core.exec.Executor
import com.wae.core.exec.RawResult
import com.wae.core.learn.Attributor
import com.wae.core.memory.FileMemory
import com.wae.core.memory.InMemoryMemory
import com.wae.core.means.MeansSolver
import com.wae.core.model.Attribution
import com.wae.core.model.Frame
import com.wae.core.model.Means
import com.wae.core.model.Outcome
import com.wae.core.model.Refutation
import com.wae.core.model.Verdict
import com.wae.core.pipeline.Agent
import com.wae.core.pipeline.FrameSource
import com.wae.core.safety.Safety
import com.wae.core.time.FixedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * MWM 吸收清單 §3 的兩個「半天項目」：
 *  - 靜默失敗計數（回報成功但沒發生）
 *  - 版本戳（反例帶 app_version，改版可失效）
 */
class SilentFailureAndVersionTest {

    @Test
    fun `silent failure is counted when executor claims success but post fails`() {
        // 執行器永遠說成功，但畫面從不改變 → 後置條件永遠不成立
        var frame: Frame = TestFrames.toggleFrame(on = true)
        val actuator = object : Actuator {
            override fun supports(means: Means): Boolean = true
            override fun run(means: Means): RawResult = RawResult.applied("pretend_ok")
        }
        val config = MapConfig(emptyMap())
        val clock = FixedClock(0)
        val memory = InMemoryMemory(config, clock)

        val outcome = Agent(
            config = config,
            clock = clock,
            memory = memory,
            frameSource = FrameSource { frame },
            compiler = IntentCompiler(),
            deriver = ContractDeriver(),
            planner = Planner(MeansSolver(config)),
            executor = Executor(actuator, clock, config),
            safety = Safety(config),
            attributor = Attributor()
        ).run("關閉藍牙")

        assertTrue(outcome is Outcome.Abstain)
        assertTrue("應至少記到一次靜默失敗", memory.silentFailures() >= 1)
    }

    @Test
    fun `refutations carry the app version and survive a save-load round trip`() {
        val dir = Files.createTempDirectory("wae-mem-test").toFile()
        try {
            val config = MapConfig(emptyMap())
            val clock = FixedClock(1_000)
            val memory = FileMemory(File(dir, "mem"), config, clock)

            val frame = Frame(
                fp = "fp:v1",
                pkg = "com.example",
                appVersion = 42L,
                controls = listOf(TestFrames.toggleControl())
            )
            val step = com.wae.core.model.Step(
                effect = com.wae.core.model.Effect.of(com.wae.core.model.Op.TOGGLE, "toggle:藍牙"),
                means = Means.A11y("sw1", "click"),
                post = { true },
                idempotencyKey = "k"
            )
            memory.recordFailure(step, Outcome.NoEffect, Attribution.LOCATION, frame)
            memory.flush()

            val reloaded = FileMemory(File(dir, "mem"), config, clock)
            val row = reloaded.refutations().first()

            assertEquals(42L, row.appVersion)
            assertEquals(Verdict.LOWER, row.verdict)
            assertEquals("fp:v1", row.frameFp)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `refutation log records version from frame`() {
        val config = MapConfig(emptyMap())
        val clock = FixedClock(0)
        val memory = InMemoryMemory(config, clock)
        val frame = Frame(fp = "fp:a", pkg = "p", appVersion = 7L, controls = emptyList())
        val step = com.wae.core.model.Step(
            effect = com.wae.core.model.Effect.of(com.wae.core.model.Op.NAVIGATE, "app:x"),
            means = Means.SystemApi("global_action:HOME"),
            post = { true },
            idempotencyKey = "k2"
        )

        memory.recordFailure(step, Outcome.NoEffect, Attribution.TIMING, frame)

        val row: Refutation = memory.refutations().first()
        assertEquals(7L, row.appVersion)
    }
}
