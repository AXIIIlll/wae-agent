package com.wae.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wae.agent.llm.LlmPlanner
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** M1：在裝置/模擬器上驗證 JNI 推論可用（3 句，不追求 50 句的速度）。 */
@RunWith(AndroidJUnit4::class)
class PlannerSmokeTest {

    @Test
    fun jniPlannerProducesPlans() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val planner = LlmPlanner(ctx, nThreads = 4)
        assertTrue("模型載入失敗", planner.ensureLoaded())
        val goals = listOf("我想聽音樂", "幫我查一下今天的天氣", "幫我找附近的餐廳")
        var ok = 0
        for (g in goals) {
            val plan = planner.plan(g)
            if (plan != null && plan.steps.isNotEmpty()) ok++
            android.util.Log.i("WAE_PLAN_TEST", "smoke goal=" + g + " steps=" + (plan?.steps?.size ?: 0))
        }
        planner.unload()
        assertTrue("3 句中至少 2 句要能產生計畫（實際 $ok）", ok >= 2)
    }
}
