package com.wae.core

import com.wae.core.worldmodel.DeltaKind
import com.wae.core.worldmodel.Observation
import com.wae.core.worldmodel.RuleBaselinePredictor
import com.wae.core.worldmodel.Transition
import com.wae.core.worldmodel.WorldModelEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界模型的**評測地基**：先有 baseline 與指標，再談訓練。
 *
 * 這裡刻意不訓練任何東西。它回答的是：「一個不學的規則能拿幾分？」
 * 未來任何模型都必須打敗這個數字，否則世界模型只是昂貴的錯覺。
 */
class WorldModelBaselineTest {

    private val pkg = "com.example.demo"

    private fun obs(
        fp: String,
        pkg: String = this.pkg,
        structure: String = "struct-1",
        controls: Int = 10
    ) = Observation(fp = fp, pkg = pkg, controlCount = controls, structureHash = structure)

    private fun transition(
        action: String,
        before: Observation,
        after: Observation,
        reportedSuccess: Boolean = true
    ) = Transition(
        pkg = before.pkg,
        appVersion = 1L,
        actionKey = action,
        before = before,
        after = after,
        reportedSuccess = reportedSuccess
    )

    @Test
    fun `no change is detected when fingerprints match`() {
        val t = transition("a11y:click", obs("fp:a"), obs("fp:a"))
        assertEquals(DeltaKind.NO_CHANGE, t.actualDelta())
        assertTrue(t.isSilentFailure())
    }

    @Test
    fun `content change on the same layout is not a screen change`() {
        val t = transition("input:ime", obs("fp:a"), obs("fp:b", structure = "struct-1"))
        assertEquals(DeltaKind.SAME_SCREEN_CONTENT, t.actualDelta())
        assertTrue(!t.isSilentFailure())
    }

    @Test
    fun `different layout is a screen change`() {
        val t = transition("a11y:click", obs("fp:a"), obs("fp:b", structure = "struct-2"))
        assertEquals(DeltaKind.SCREEN_CHANGED, t.actualDelta())
    }

    @Test
    fun `package change is an app change`() {
        val t = transition("intent:launch", obs("fp:a"), obs("fp:b", pkg = "com.other", structure = "s"))
        assertEquals(DeltaKind.APP_CHANGED, t.actualDelta())
    }

    @Test
    fun `baseline predicts a screen change for a tap`() {
        val baseline = RuleBaselinePredictor()
        assertEquals(
            DeltaKind.SCREEN_CHANGED,
            baseline.predict(obs("fp:a", controls = 12), "a11y:click")
        )
        assertEquals(
            DeltaKind.NO_CHANGE,
            baseline.predict(obs("fp:a", controls = 0), "a11y:click")
        )
    }

    @Test
    fun `evaluator measures accuracy and silent failure recall`() {
        val transitions = listOf(
            // 預期換頁，實際換頁 → 猜對
            transition("a11y:click", obs("fp:1", structure = "s1"), obs("fp:2", structure = "s2")),
            // 預期換頁，實際沒變 → 猜錯，但抓到一個靜默失敗
            transition("a11y:click", obs("fp:3", structure = "s3"), obs("fp:3", structure = "s3")),
            // 輸入 → 內容變 → 猜對
            transition("input:ime", obs("fp:4", structure = "s4"), obs("fp:5", structure = "s4"))
        )

        val metrics = WorldModelEvaluator(RuleBaselinePredictor()).evaluate(transitions)

        assertEquals(3, metrics.samples)
        assertTrue(metrics.deltaAccuracy > 0.6f)
        assertEquals(1, metrics.silentFailureTotal)
        assertEquals(1, metrics.silentFailuresCaught)
        assertEquals(1f, metrics.silentFailureRecall)
    }

    @Test
    fun `empty evaluation does not claim success`() {
        val metrics = WorldModelEvaluator(RuleBaselinePredictor()).evaluate(emptyList())
        assertEquals(0, metrics.samples)
        assertEquals(0f, metrics.deltaAccuracy)
        assertEquals(0f, metrics.silentFailureRecall)
    }
}
