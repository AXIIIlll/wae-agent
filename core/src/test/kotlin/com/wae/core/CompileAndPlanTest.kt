package com.wae.core

import com.wae.core.config.MapConfig
import com.wae.core.contract.ContractDeriver
import com.wae.core.effect.IntentCompiler
import com.wae.core.effect.Planner
import com.wae.core.means.MeansSolver
import com.wae.core.model.Constraints
import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.Effect
import com.wae.core.model.Means
import com.wae.core.model.Op
import com.wae.core.model.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompileAndPlanTest {

    private val config = MapConfig(emptyMap())
    private val solver = MeansSolver(config)
    private val planner = Planner(solver)
    private val deriver = ContractDeriver()
    private val compiler = IntentCompiler()

    // ── 編譯 ─────────────────────────────────────────────────────────

    @Test
    fun `compiles app launch`() {
        val effects = compiler.compile("打開設定")
        assertEquals(1, effects.size)
        assertEquals("app:設定", effects[0].target)
        assertEquals(Op.NAVIGATE, effects[0].op)
    }

    @Test
    fun `compiles toggle`() {
        val effects = compiler.compile("關閉藍牙")
        assertEquals("toggle:藍牙", effects[0].target)
        assertEquals(Op.SET, effects[0].op)
    }

    @Test
    fun `compiles query`() {
        val effects = compiler.compile("搜尋台北101")
        assertEquals("query:台北101", effects[0].target)
        assertEquals(Op.SELECT, effects[0].op)
    }

    @Test
    fun `compiles home`() {
        val effects = compiler.compile("回到主頁")
        assertEquals("page:home", effects[0].target)
    }

    @Test
    fun `compiles volume with value`() {
        val effects = compiler.compile("調高音量")
        assertEquals("volume:master", effects[0].target)
        assertEquals(IntentCompiler.VALUE_UP, effects[0].value)
    }

    @Test
    fun `unknown verb yields empty list`() {
        assertTrue(compiler.compile("今天天氣如何").isEmpty())
        assertTrue(compiler.compile("").isEmpty())
    }

    // ── 契約 ─────────────────────────────────────────────────────────

    @Test
    fun `derives contracts for known kinds only`() {
        val frame = TestFrames.frame(
            listOf(
                TestFrames.toggleControl(),
                TestFrames.rowControl(),
                TestFrames.fieldControl(),
                TestFrames.toggleControl(key = "x", text = "未知").copy(kind = ControlKind.UNKNOWN)
            )
        )
        val contracts = deriver.derive(frame)
        assertEquals(3, contracts.size)
        assertTrue(contracts.none { it.kind == ControlKind.UNKNOWN })
    }

    @Test
    fun `disabled control gets lower confidence`() {
        val enabled = deriver.deriveOne(TestFrames.toggleControl(enabled = true))!!
        val disabled = deriver.deriveOne(TestFrames.toggleControl(enabled = false))!!
        assertTrue(disabled.confidence < enabled.confidence)
    }

    // ── 求解 ─────────────────────────────────────────────────────────

    @Test
    fun `solver prefers cheaper means`() {
        val frame = TestFrames.toggleFrame()
        val effect = Effect.of(Op.TOGGLE, "toggle:藍牙")
        val a11y = com.wae.core.model.Step(
            effect = effect,
            means = Means.A11y("sw1", "click"),
            post = { true },
            idempotencyKey = "k1"
        )
        val systemApi = com.wae.core.model.Step(
            effect = effect,
            means = Means.SystemApi("bluetooth:off"),
            post = { true },
            idempotencyKey = "k2"
        )
        val ranked = solver.rank(listOf(a11y, systemApi), Constraints.NONE)
        assertEquals(Means.Kind.SYSTEM_API, ranked.first().means.kind)
        assertEquals(Means.Kind.A11Y, ranked.last().means.kind)
    }

    @Test
    fun `solver drops banned means`() {
        val effect = Effect.of(Op.TOGGLE, "toggle:藍牙")
        val step = com.wae.core.model.Step(
            effect = effect,
            means = Means.A11y("sw1", "click"),
            post = { true },
            idempotencyKey = "k1"
        )
        val banned = Constraints(
            banned = setOf(Constraints.pair(effect.key(), step.means.describe()))
        )
        assertTrue(solver.rank(listOf(step), banned).isEmpty())
    }

    // ── 計畫 ─────────────────────────────────────────────────────────

    @Test
    fun `planner builds a11y chain when text matches`() {
        val frame = TestFrames.toggleFrame(on = true)
        val contracts = deriver.derive(frame)
        val plan = planner.plan(compiler.compile("關閉藍牙"), frame, contracts, Constraints.NONE)
        assertEquals(1, plan.steps.size)
        assertEquals(Means.Kind.A11Y, plan.steps[0].means.kind)
        assertTrue(plan.complete)
    }

    @Test
    fun `planner marks unsatisfied when nothing matches`() {
        val frame = TestFrames.toggleFrame(on = true)
        val contracts = deriver.derive(frame)
        val plan = planner.plan(compiler.compile("關閉Wi-Fi"), frame, contracts, Constraints.NONE)
        assertTrue(plan.steps.isEmpty())
        assertEquals(1, plan.unsatisfied.size)
        assertFalse(plan.complete)
    }

    @Test
    fun `planner builds input chain for query`() {
        val frame = TestFrames.frame(listOf(TestFrames.fieldControl()))
        val contracts = deriver.derive(frame)
        val plan: Plan = planner.plan(compiler.compile("搜尋台北101"), frame, contracts, Constraints.NONE)
        // 第一條路是「帶查詢字串的搜尋意圖」：最便宜、不必叫輸入法。
        // 不支援的 App 會在 Actuator 回 noEffect，那條路被反例擋掉後就換 UI 路徑。
        assertEquals(1, plan.steps.size)
        assertEquals(Means.Kind.INTENT, plan.steps[0].means.kind)
    }

    @Test
    fun `query falls back to focus plus input when search intent is banned`() {
        val frame = TestFrames.frame(listOf(TestFrames.fieldControl()))
        val contracts = deriver.derive(frame)
        val compiled = compiler.compile("搜尋台北101")
        val banned = Constraints(
            banned = setOf(
                com.wae.core.model.Constraints.pair(compiled.first().key(), "intent:wae.action.SEARCH")
            )
        )
        val plan = planner.plan(compiled, frame, contracts, banned)
        assertEquals(2, plan.steps.size)
        assertEquals(Means.Kind.A11Y, plan.steps[0].means.kind)
        assertEquals(Means.Kind.INPUT, plan.steps[1].means.kind)
    }

    @Test
    fun `planner uses system api for volume`() {
        val frame = TestFrames.toggleFrame()
        val plan = planner.plan(compiler.compile("調高音量"), frame, deriver.derive(frame), Constraints.NONE)
        assertEquals(1, plan.steps.size)
        assertEquals(Means.Kind.SYSTEM_API, plan.steps[0].means.kind)
    }
}
