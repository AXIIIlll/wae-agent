package com.example.newandroidapp.e2e

import android.content.Context
import android.graphics.Rect
import com.example.newandroidapp.chat.ChatMessage
import com.example.newandroidapp.chat.ChatRole
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.chat.ConversationObserver
import com.example.newandroidapp.chat.ConversationTurnContext
import com.example.newandroidapp.chat.ScreenCandidateItem
import com.example.newandroidapp.e2e.TestHelpers.assertZeroEmojis
import com.example.newandroidapp.stability.PlayStoreComplianceHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tier 3: Pairwise Combinatorial Test Suite.
 * Exercises cross-feature combinations across key dimensions:
 * - Roles: [USER, AI]
 * - Context States: [EMPTY_CONTEXT, ACTIVE_WITH_CANDIDATES, STALE_CONTEXT]
 * - Screen States: [ZERO_NODES, FEW_CANDIDATES, HIGH_DENSITY, SAFETY_GATE]
 * - Intent Categories: [SCREEN_QA, TASK_STATUS, FOLLOW_UP, CALCULATION, PHYSICAL_AUTOMATION, GENERAL_CHAT]
 * - Surface Destinations: [MAIN_ACTIVITY, FLOATING_CAPSULE, DUAL_SYNC]
 * - Consent Status: [GRANTED, REVOKED]
 */
class Tier3PairwiseCombinatorialTest {

    private lateinit var tempDir: File
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("tier3_pairwise_files").toFile()
        mockContext = TestHelpers.createTestContext(tempDir)
        ConversationManager.initialize(mockContext)
        ConversationManager.clearConversation(mockContext)
    }

    @After
    fun tearDown() {
        ConversationManager.clearConversation(mockContext)
        tempDir.deleteRecursively()
    }

    enum class ContextState { EMPTY_CONTEXT, ACTIVE_WITH_CANDIDATES, STALE_CONTEXT }
    enum class ScreenState { ZERO_NODES, FEW_CANDIDATES, HIGH_DENSITY, SAFETY_GATE }
    enum class SurfaceDestination { MAIN_ACTIVITY, FLOATING_CAPSULE, DUAL_SYNC }
    enum class ConsentState { GRANTED, REVOKED }

    data class PairwiseTestCase(
        val id: String,
        val role: ChatRole,
        val contextState: ContextState,
        val screenState: ScreenState,
        val intentCategory: TestHelpers.IntentCategory,
        val surface: SurfaceDestination,
        val consent: ConsentState
    )

    @Test
    fun testP01_user_emptyContext_zeroNodes_screenQA_mainActivity_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P01",
                role = ChatRole.USER,
                contextState = ContextState.EMPTY_CONTEXT,
                screenState = ScreenState.ZERO_NODES,
                intentCategory = TestHelpers.IntentCategory.SCREEN_QA,
                surface = SurfaceDestination.MAIN_ACTIVITY,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP02_ai_activeCandidates_fewCandidates_followUp_dualSync_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P02",
                role = ChatRole.AI,
                contextState = ContextState.ACTIVE_WITH_CANDIDATES,
                screenState = ScreenState.FEW_CANDIDATES,
                intentCategory = TestHelpers.IntentCategory.FOLLOW_UP,
                surface = SurfaceDestination.DUAL_SYNC,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP03_user_activeCandidates_safetyGate_followUp_floatingCapsule_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P03",
                role = ChatRole.USER,
                contextState = ContextState.ACTIVE_WITH_CANDIDATES,
                screenState = ScreenState.SAFETY_GATE,
                intentCategory = TestHelpers.IntentCategory.FOLLOW_UP,
                surface = SurfaceDestination.FLOATING_CAPSULE,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP04_ai_staleContext_highDensity_taskStatus_mainActivity_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P04",
                role = ChatRole.AI,
                contextState = ContextState.STALE_CONTEXT,
                screenState = ScreenState.HIGH_DENSITY,
                intentCategory = TestHelpers.IntentCategory.TASK_STATUS,
                surface = SurfaceDestination.MAIN_ACTIVITY,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP05_user_staleContext_zeroNodes_calculation_dualSync_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P05",
                role = ChatRole.USER,
                contextState = ContextState.STALE_CONTEXT,
                screenState = ScreenState.ZERO_NODES,
                intentCategory = TestHelpers.IntentCategory.MATH_CALCULATION,
                surface = SurfaceDestination.DUAL_SYNC,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP06_user_emptyContext_fewCandidates_physicalAutomation_mainActivity_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P06",
                role = ChatRole.USER,
                contextState = ContextState.EMPTY_CONTEXT,
                screenState = ScreenState.FEW_CANDIDATES,
                intentCategory = TestHelpers.IntentCategory.PHYSICAL_AUTOMATION,
                surface = SurfaceDestination.MAIN_ACTIVITY,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP07_ai_emptyContext_highDensity_generalChat_floatingCapsule_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P07",
                role = ChatRole.AI,
                contextState = ContextState.EMPTY_CONTEXT,
                screenState = ScreenState.HIGH_DENSITY,
                intentCategory = TestHelpers.IntentCategory.GENERAL_CHAT,
                surface = SurfaceDestination.FLOATING_CAPSULE,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP08_user_activeCandidates_zeroNodes_screenQA_dualSync_revoked() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P08",
                role = ChatRole.USER,
                contextState = ContextState.ACTIVE_WITH_CANDIDATES,
                screenState = ScreenState.ZERO_NODES,
                intentCategory = TestHelpers.IntentCategory.SCREEN_QA,
                surface = SurfaceDestination.DUAL_SYNC,
                consent = ConsentState.REVOKED
            )
        )
    }

    @Test
    fun testP09_ai_staleContext_safetyGate_followUp_mainActivity_revoked() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P09",
                role = ChatRole.AI,
                contextState = ContextState.STALE_CONTEXT,
                screenState = ScreenState.SAFETY_GATE,
                intentCategory = TestHelpers.IntentCategory.FOLLOW_UP,
                surface = SurfaceDestination.MAIN_ACTIVITY,
                consent = ConsentState.REVOKED
            )
        )
    }

    @Test
    fun testP10_user_staleContext_fewCandidates_taskStatus_floatingCapsule_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P10",
                role = ChatRole.USER,
                contextState = ContextState.STALE_CONTEXT,
                screenState = ScreenState.FEW_CANDIDATES,
                intentCategory = TestHelpers.IntentCategory.TASK_STATUS,
                surface = SurfaceDestination.FLOATING_CAPSULE,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP11_ai_activeCandidates_highDensity_calculation_dualSync_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P11",
                role = ChatRole.AI,
                contextState = ContextState.ACTIVE_WITH_CANDIDATES,
                screenState = ScreenState.HIGH_DENSITY,
                intentCategory = TestHelpers.IntentCategory.MATH_CALCULATION,
                surface = SurfaceDestination.DUAL_SYNC,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP12_user_emptyContext_safetyGate_physicalAutomation_mainActivity_revoked() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P12",
                role = ChatRole.USER,
                contextState = ContextState.EMPTY_CONTEXT,
                screenState = ScreenState.SAFETY_GATE,
                intentCategory = TestHelpers.IntentCategory.PHYSICAL_AUTOMATION,
                surface = SurfaceDestination.MAIN_ACTIVITY,
                consent = ConsentState.REVOKED
            )
        )
    }

    @Test
    fun testP13_ai_emptyContext_fewCandidates_screenQA_dualSync_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P13",
                role = ChatRole.AI,
                contextState = ContextState.EMPTY_CONTEXT,
                screenState = ScreenState.FEW_CANDIDATES,
                intentCategory = TestHelpers.IntentCategory.SCREEN_QA,
                surface = SurfaceDestination.DUAL_SYNC,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP14_user_activeCandidates_highDensity_followUp_mainActivity_granted() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P14",
                role = ChatRole.USER,
                contextState = ContextState.ACTIVE_WITH_CANDIDATES,
                screenState = ScreenState.HIGH_DENSITY,
                intentCategory = TestHelpers.IntentCategory.FOLLOW_UP,
                surface = SurfaceDestination.MAIN_ACTIVITY,
                consent = ConsentState.GRANTED
            )
        )
    }

    @Test
    fun testP15_ai_staleContext_zeroNodes_generalChat_floatingCapsule_revoked() {
        executePairwiseTest(
            PairwiseTestCase(
                id = "P15",
                role = ChatRole.AI,
                contextState = ContextState.STALE_CONTEXT,
                screenState = ScreenState.ZERO_NODES,
                intentCategory = TestHelpers.IntentCategory.GENERAL_CHAT,
                surface = SurfaceDestination.FLOATING_CAPSULE,
                consent = ConsentState.REVOKED
            )
        )
    }

    private fun executePairwiseTest(testCase: PairwiseTestCase) {
        // 1. Setup consent state
        val isConsented = testCase.consent == ConsentState.GRANTED
        PlayStoreComplianceHelper.setProminentDisclosureAccepted(mockContext, isConsented)

        // 2. Setup turn context state
        val candidates = when (testCase.contextState) {
            ContextState.EMPTY_CONTEXT -> emptyList()
            ContextState.ACTIVE_WITH_CANDIDATES -> listOf(
                ScreenCandidateItem(1, "測試候選 1", Rect(0, 100, 1080, 200)),
                ScreenCandidateItem(2, "測試候選 2", Rect(0, 210, 1080, 310))
            )
            ContextState.STALE_CONTEXT -> listOf(
                ScreenCandidateItem(1, "過期候選", Rect(0, 100, 1080, 200))
            )
        }
        val contextTimestamp = if (testCase.contextState == ContextState.STALE_CONTEXT) {
            System.currentTimeMillis() - 600000L // 10 minutes ago
        } else {
            System.currentTimeMillis()
        }
        val turnCtx = ConversationTurnContext(
            lastTargetApp = "com.test.app",
            lastSearchQuery = "測試查詢",
            lastCandidates = candidates,
            timestamp = contextTimestamp
        )
        ConversationManager.updateTurnContext(turnCtx)

        // 3. Setup Surface Observers
        var mainActivityCount = 0
        var floatingCapsuleCount = 0

        val mainObserver = object : ConversationObserver {
            override fun onMessagesUpdated(messages: List<ChatMessage>) {
                mainActivityCount = messages.size
            }
            override fun onTurnContextUpdated(context: ConversationTurnContext) {}
        }
        val capsuleObserver = object : ConversationObserver {
            override fun onMessagesUpdated(messages: List<ChatMessage>) {
                floatingCapsuleCount = messages.size
            }
            override fun onTurnContextUpdated(context: ConversationTurnContext) {}
        }

        when (testCase.surface) {
            SurfaceDestination.MAIN_ACTIVITY -> ConversationManager.addObserver(mainObserver)
            SurfaceDestination.FLOATING_CAPSULE -> ConversationManager.addObserver(capsuleObserver)
            SurfaceDestination.DUAL_SYNC -> {
                ConversationManager.addObserver(mainObserver)
                ConversationManager.addObserver(capsuleObserver)
            }
        }

        // 4. Dispatch query according to intent category
        val query = when (testCase.intentCategory) {
            TestHelpers.IntentCategory.SCREEN_QA -> "現在畫面上有什麼重點？"
            TestHelpers.IntentCategory.TASK_STATUS -> "剛剛做到了哪一步？"
            TestHelpers.IntentCategory.MATH_CALCULATION -> "512 加 256 是多少？"
            TestHelpers.IntentCategory.FOLLOW_UP -> "點第一個"
            TestHelpers.IntentCategory.PHYSICAL_AUTOMATION -> "打開 音樂 搜尋測試"
            TestHelpers.IntentCategory.GENERAL_CHAT -> "你好，今天天氣如何？"
        }

        val classification = TestHelpers.classifyIntent(query, turnCtx)
        assertEquals(testCase.intentCategory, classification.category)

        // 5. Append message
        val postedMsg = if (testCase.role == ChatRole.USER) {
            ConversationManager.appendUserMessage(query)
        } else {
            val report = TestHelpers.synthesizeProactiveReport(
                actionSummary = "執行 $query",
                visibleLabels = candidates.map { it.label },
                isSafetyBlocked = (testCase.screenState == ScreenState.SAFETY_GATE)
            )
            ConversationManager.appendAiMessage(
                text = report.conversationalText,
                metadataHeader = report.metadataHeader,
                isProactive = true,
                candidates = report.candidates
            )
        }

        // 6. Assert Zero Emojis on all generated text
        assertZeroEmojis(postedMsg.text, "${testCase.id} message text")
        if (postedMsg.metadataHeader != null) {
            assertZeroEmojis(postedMsg.metadataHeader!!, "${testCase.id} header")
        }

        // 7. Verify synchronization
        when (testCase.surface) {
            SurfaceDestination.MAIN_ACTIVITY -> assertTrue(mainActivityCount > 0)
            SurfaceDestination.FLOATING_CAPSULE -> assertTrue(floatingCapsuleCount > 0)
            SurfaceDestination.DUAL_SYNC -> {
                assertEquals(mainActivityCount, floatingCapsuleCount)
                assertTrue(mainActivityCount > 0)
            }
        }

        // 8. If Revoked, verify privacy wipe action
        if (testCase.consent == ConsentState.REVOKED) {
            ConversationManager.clearConversation(mockContext)
            assertEquals(0, ConversationManager.getMessages().size)
        }

        // Cleanup observers
        ConversationManager.removeObserver(mainObserver)
        ConversationManager.removeObserver(capsuleObserver)
    }
}
