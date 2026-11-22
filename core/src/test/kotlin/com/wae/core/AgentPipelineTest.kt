package com.wae.core

import com.wae.core.config.Key
import com.wae.core.config.MapConfig
import com.wae.core.config.Reason
import com.wae.core.contract.ContractDeriver
import com.wae.core.effect.IntentCompiler
import com.wae.core.effect.Planner
import com.wae.core.exec.Actuator
import com.wae.core.exec.Executor
import com.wae.core.exec.RawResult
import com.wae.core.learn.Attributor
import com.wae.core.memory.InMemoryMemory
import com.wae.core.memory.Memory
import com.wae.core.means.MeansSolver
import com.wae.core.model.Frame
import com.wae.core.model.Means
import com.wae.core.model.Outcome
import com.wae.core.model.PostCondition
import com.wae.core.model.Step
import com.wae.core.pipeline.Agent
import com.wae.core.pipeline.FrameSource
import com.wae.core.safety.Safety
import com.wae.core.time.FixedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPipelineTest {

    @Test
    fun `toggle command flips state and succeeds`() {
        var frame: Frame = TestFrames.toggleFrame(on = true)
        val actuator = object : Actuator {
            override fun supports(means: Means): Boolean = true
            override fun run(means: Means): RawResult {
                frame = TestFrames.toggleFrame(on = false)
                return RawResult.applied()
            }
        }

        val outcome = agent(FrameSource { frame }, actuator).run("關閉藍牙")

        assertTrue(outcome is Outcome.Success)
        assertEquals(false, frame.controls.first().state.on)
    }

    @Test
    fun `unknown verb abstains instead of guessing`() {
        val outcome = agent(FrameSource { TestFrames.toggleFrame() }, noopActuator()).run("今天天氣如何")
        assertEquals(Reason.UNKNOWN_VERB, (outcome as Outcome.Abstain).reason)
    }

    @Test
    fun `no matching control abstains`() {
        val outcome = agent(FrameSource { TestFrames.toggleFrame() }, noopActuator()).run("關閉Wi-Fi")
        assertEquals(Reason.NO_CANDIDATE, (outcome as Outcome.Abstain).reason)
    }

    @Test
    fun `high risk effect requires confirmation`() {
        val outcome = agent(FrameSource { TestFrames.toggleFrame() }, noopActuator()).run("傳訊息給媽媽")
        assertEquals(Reason.SENSITIVE_OPERATION, (outcome as Outcome.Abstain).reason)
        assertTrue((outcome as Outcome.Abstain).options.isNotEmpty())
    }

    @Test
    fun `repeated failure records refutation and abstains when replans exhausted`() {
        val config = MapConfig(mapOf(Key.PLAN_MAX_REPLANS to "1"))
        val clock = FixedClock(0)
        val memory = InMemoryMemory(config, clock)
        val actuator = object : Actuator {
            override fun supports(means: Means): Boolean = true
            override fun run(means: Means): RawResult = RawResult.noEffect()
        }

        val outcome = agent(FrameSource { TestFrames.toggleFrame() }, actuator, config, clock, memory)
            .run("關閉藍牙")

        assertEquals(Reason.REPLANS_EXHAUSTED, (outcome as Outcome.Abstain).reason)
        assertTrue(memory.refutations().isNotEmpty())
    }

    @Test
    fun `executor does not resend the same intent inside the idempotency window`() {
        val clock = FixedClock(0)
        var calls = 0
        val actuator = object : Actuator {
            override fun supports(means: Means): Boolean = true
            override fun run(means: Means): RawResult {
                calls++
                return RawResult.applied()
            }
        }
        val executor = Executor(actuator, clock, MapConfig(emptyMap()))
        val step = Step(
            effect = com.wae.core.model.Effect.of(com.wae.core.model.Op.TOGGLE, "toggle:藍牙"),
            means = Means.A11y("sw1", "click"),
            post = PostCondition { true },
            idempotencyKey = "same-key"
        )

        executor.run(step)
        clock.advance(100)
        val second = executor.run(step)

        assertTrue(second is Outcome.Success)
        assertEquals(1, calls)
    }

    private fun noopActuator(): Actuator = object : Actuator {
        override fun supports(means: Means): Boolean = true
        override fun run(means: Means): RawResult = RawResult.noEffect()
    }

    private fun agent(
        frames: FrameSource,
        actuator: Actuator,
        config: MapConfig = MapConfig(emptyMap()),
        clock: FixedClock = FixedClock(0),
        memory: Memory = InMemoryMemory(config, clock)
    ): Agent {
        val solver = MeansSolver(config)
        return Agent(
            config = config,
            clock = clock,
            memory = memory,
            frameSource = frames,
            compiler = IntentCompiler(),
            deriver = ContractDeriver(),
            planner = Planner(solver),
            executor = Executor(actuator, clock, config),
            safety = Safety(config),
            attributor = Attributor()
        )
    }
}
