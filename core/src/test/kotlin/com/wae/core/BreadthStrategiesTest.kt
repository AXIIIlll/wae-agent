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
import com.wae.core.means.MeansSolver
import com.wae.core.model.Frame
import com.wae.core.model.Means
import com.wae.core.model.Outcome
import com.wae.core.pipeline.Agent
import com.wae.core.pipeline.FrameSource
import com.wae.core.safety.Safety
import com.wae.core.time.FixedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 廣度策略測試：**不新增任何動詞**，只驗證「會找、會退」這兩個結構性程序。
 */
class BreadthStrategiesTest {

    @Test
    fun `seek scrolls until the target appears`() {
        var frame: Frame = TestFrames.scrollableFrame()
        val actuator = object : Actuator {
            override fun supports(means: Means): Boolean = true
            override fun run(means: Means): RawResult = when {
                means is Means.Gesture -> {
                    frame = TestFrames.toggleFrame(on = true)
                    RawResult.applied("scroll")
                }
                means is Means.A11y && means.action == "click" -> {
                    frame = TestFrames.toggleFrame(on = false)
                    RawResult.applied("click")
                }
                else -> RawResult.noEffect()
            }
        }

        val outcome = agent(FrameSource { frame }, actuator).run("關閉藍牙")

        assertTrue(outcome is Outcome.Success)
    }

    @Test
    fun `seek is bounded and then abstains`() {
        var frame: Frame = TestFrames.scrollableFrame()
        val actuator = object : Actuator {
            override fun supports(means: Means): Boolean = true
            override fun run(means: Means): RawResult = RawResult.applied("scroll")
        }
        val config = MapConfig(mapOf(Key.SEEK_MAX_SCROLLS to "2"))

        val outcome = agent(FrameSource { frame }, actuator, config).run("關閉藍牙")

        assertEquals(Reason.NO_CANDIDATE, (outcome as Outcome.Abstain).reason)
    }

    @Test
    fun `wrong effect triggers a retreat and a second attempt succeeds`() {
        var frame: Frame = TestFrames.toggleFrame(on = true)
        var clicks = 0
        var backs = 0
        val actuator = object : Actuator {
            override fun supports(means: Means): Boolean = true
            override fun run(means: Means): RawResult = when {
                means is Means.A11y && means.action == "click" -> {
                    clicks++
                    if (clicks == 1) {
                        // 第一次點錯：跳到別的畫面
                        frame = TestFrames.scrollableFrame()
                        RawResult.wrong("wrong_screen")
                    } else {
                        frame = TestFrames.toggleFrame(on = false)
                        RawResult.applied("click")
                    }
                }
                means is Means.SystemApi && means.api == "global_action:BACK" -> {
                    backs++
                    frame = TestFrames.toggleFrame(on = true)
                    RawResult.applied("back")
                }
                else -> RawResult.noEffect()
            }
        }

        val outcome = agent(FrameSource { frame }, actuator).run("關閉藍牙")

        assertEquals(1, backs)
        assertTrue(outcome is Outcome.Success)
    }

    private fun agent(
        frames: FrameSource,
        actuator: Actuator,
        config: MapConfig = MapConfig(emptyMap())
    ): Agent {
        val clock = FixedClock(0)
        val solver = MeansSolver(config)
        return Agent(
            config = config,
            clock = clock,
            memory = InMemoryMemory(config, clock),
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
