package com.wae.core.predicate

import com.wae.core.model.ControlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 巡覽狀態機的契約測試（P0）。
 *
 * 為什麼要測這個：實測「開熱點」時，AI 鑽進錯的分支（儲存空間）後**卡死在裡面**，
 * 因為 `PredicateAgent` 從來不產生 `Back`。這裡把「該退就要退」變成一條可驗證的行為。
 */
class PredicateAgentNavTest {

    private fun world(vararg labels: String) = FactsWorld(
        pkg = "com.android.settings",
        controls = labels.mapIndexed { i, l ->
            FactsControl(key = "k$i", label = l, kind = ControlKind.ROW)
        }
    )

    /** 目標：某個開關被打開；畫面上完全沒有它。 */
    private val goal: Phi = Phi.Exists(
        listOf(
            Atom.LabelContains("熱點"),
            Atom.KindIs(ControlKind.TOGGLE),
            Atom.CheckedIs(true)
        )
    )

    private fun ctx(
        depth: Int,
        canGoBack: Boolean,
        w: PredicateWorld = world("Wi-Fi", "藍牙", "顯示", "儲存空間")
    ) = PredicateAgent.Context(
        world = w,
        goal = goal,
        depth = depth,
        maxDepth = 3,
        canGoBack = canGoBack,
        // 下降門檻調高 → 這頁沒有夠相關的入口，模擬「這條路走不通」
        descentFloor = 0.99f,
        similarity = PredicateAgent.LabelSimilarity { _, _ -> 0.1f },
        maxScrolls = 0
    )

    @Test
    fun `dead end at depth produces Back instead of giving up`() {
        val action = PredicateAgent.next(ctx(depth = 1, canGoBack = true), PredicateAgent.SectionLookup { emptyList() })
        assertTrue("depth>0 且走不通 → 必須退回上一層，實際=$action", action is AgentAction.Back)
    }

    @Test
    fun `at root with no option it gives up honestly`() {
        val action = PredicateAgent.next(ctx(depth = 0, canGoBack = false), PredicateAgent.SectionLookup { emptyList() })
        assertTrue("在根層沒路可走 → GiveUp，實際=$action", action is AgentAction.GiveUp)
    }

    @Test
    fun `max depth blocks further descent`() {
        // 深度已達上限，即使有「看起來相關」的列也不能再鑽
        val w = world("儲存空間")
        val c = ctx(depth = 3, canGoBack = true, w = w).copy(
            descentFloor = 0.0f, // 任何分數都過門檻
            similarity = PredicateAgent.LabelSimilarity { _, _ -> 1.0f }
        )
        val action = PredicateAgent.next(c, PredicateAgent.SectionLookup { emptyList() })
        assertTrue("深度用完不可以繼續鑽，實際=$action", action !is AgentAction.OpenSection)
        assertTrue("應該退回上一層，實際=$action", action is AgentAction.Back)
    }

    @Test
    fun `depth zero and a reachable row descends`() {
        val c = ctx(depth = 0, canGoBack = false).copy(
            descentFloor = 0.0f,
            similarity = PredicateAgent.LabelSimilarity { _, _ -> 1.0f }
        )
        val action = PredicateAgent.next(c, PredicateAgent.SectionLookup { emptyList() })
        assertTrue("根層有相關入口 → OpenSection，實際=$action", action is AgentAction.OpenSection)
    }
}
