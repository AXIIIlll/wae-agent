package com.example.newandroidapp.stability

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ImeDismissGuard cooldown mechanism and FOCUS_INPUT state protection.
 */
class ImeDismissGuardTest {

    @Before
    fun setUp() {
        ImeDismissGuard.reset()
    }

    @Test
    fun testDefaultCooldownConstant() {
        assertEquals(800L, ImeDismissGuard.DEFAULT_COOLDOWN_MS)
    }

    @Test
    fun testDismissBlockedWhenNoTextTypedOrSubmitted() {
        val allowed = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = false,
            hasImeOcclusion = true,
            hasFocusedInput = true,
            currentElapsedMs = 1000L
        )
        assertFalse("Must not dismiss IME if user/AI never typed or submitted query", allowed)
    }

    @Test
    fun testDismissBlockedWhenNoImeWindowOcclusion() {
        val allowed = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = false,
            hasFocusedInput = true,
            currentElapsedMs = 1000L
        )
        assertFalse("Must not dismiss IME if no keyboard is occluding screen", allowed)
    }

    @Test
    fun testDismissBlockedWhenNoFocusedInput_CrucialPageRetreatProtection() {
        // Crucial defense: If no input field holds FOCUS_INPUT, pressing BACK would exit the page
        val allowed = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = false,
            currentElapsedMs = 1000L
        )
        assertFalse("Must block dismiss when no input holds FOCUS_INPUT to prevent exiting page", allowed)
    }

    @Test
    fun testDismissAllowedWhenAllConditionsMet() {
        val allowed = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = true,
            currentElapsedMs = 1000L
        )
        assertTrue("Should allow IME dismissal when typed, occluded, and input is focused", allowed)
    }

    @Test
    fun testCooldownBlocksRapidConsecutiveDismissals() {
        val t0 = 10000L
        // 1. Initial dismissal permitted
        val firstAllowed = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = true,
            currentElapsedMs = t0
        )
        assertTrue("First dismiss should be allowed", firstAllowed)
        ImeDismissGuard.recordDismiss(t0)

        // 2. Immediate second call at t0 + 100ms should be blocked by cooldown (800ms)
        val rapidCall1 = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = true,
            currentElapsedMs = t0 + 100L
        )
        assertFalse("Call within 100ms must be blocked by cooldown", rapidCall1)

        // 3. Call at t0 + 500ms should still be blocked
        val rapidCall2 = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = true,
            currentElapsedMs = t0 + 500L
        )
        assertFalse("Call within 500ms must be blocked by cooldown", rapidCall2)

        // 4. Call after cooldown expires at t0 + 801ms should succeed
        val expiredCall = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = true,
            currentElapsedMs = t0 + 801L
        )
        assertTrue("Call after 800ms cooldown must be allowed", expiredCall)
    }

    @Test
    fun testCustomCooldownPeriod() {
        val t0 = 5000L
        ImeDismissGuard.recordDismiss(t0)

        // Custom cooldown of 1200ms
        val blockedAt1000 = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = true,
            currentElapsedMs = t0 + 1000L,
            cooldownMs = 1200L
        )
        assertFalse("Call at 1000ms should be blocked by 1200ms cooldown", blockedAt1000)

        val allowedAt1300 = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = true,
            currentElapsedMs = t0 + 1300L,
            cooldownMs = 1200L
        )
        assertTrue("Call at 1300ms should be allowed by 1200ms cooldown", allowedAt1300)
    }

    @Test
    fun testResetClearsCooldown() {
        val t0 = 20000L
        ImeDismissGuard.recordDismiss(t0)

        val beforeReset = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = true,
            currentElapsedMs = t0 + 200L
        )
        assertFalse(beforeReset)

        ImeDismissGuard.reset()

        val afterReset = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = true,
            currentElapsedMs = t0 + 200L
        )
        assertTrue("After reset, dismiss should be allowed immediately", afterReset)
    }

    @Test
    fun testDismissAllowedWhenTargetInLowerEvenIfNoFocusedInput() {
        val allowed = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = false,
            isTargetInLowerOrOccluded = true,
            currentElapsedMs = 1000L
        )
        assertTrue("Must allow dismiss when target elements are occluded in lower screen", allowed)
    }

    @Test
    fun testDismissBlockedWhenTargetAlreadyInUpperAndNoFocusedInput() {
        val allowed = ImeDismissGuard.shouldDismissIme(
            hasTypedOrSubmittedText = true,
            hasImeOcclusion = true,
            hasFocusedInput = false,
            isTargetInLowerOrOccluded = false,
            currentElapsedMs = 1000L
        )
        assertFalse("Must block dismiss when target is in visible upper zone and no input holds focus", allowed)
    }

    private fun testRect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    @Test
    fun testShouldDismissOccludingIme_occludedBottomElements() {
        val screenHeight = 2400
        val imeHeight = 850

        // One button in visible zone (100, 200), and one button occluded below imeTopY (1600..1800)
        val actionableNodes = listOf(
            testRect(50, 200, 400, 300),
            testRect(100, 1600, 600, 1750)
        )

        val shouldDismiss = ImeDismissGuard.shouldDismissOccludingIme(
            hasImeOcclusion = true,
            imeHeightPx = imeHeight,
            screenHeightPx = screenHeight,
            actionableNodes = actionableNodes,
            currentElapsedMs = 1000L
        )
        assertTrue("Must detect occlusion when bottom button extends below imeTopY", shouldDismiss)
    }

    @Test
    fun testShouldDismissOccludingIme_sparseUpperElements() {
        val screenHeight = 2400
        val imeHeight = 850

        // Only 1 actionable node in upper area, none in lower
        val actionableNodes = listOf(
            testRect(50, 200, 400, 300)
        )

        val shouldDismiss = ImeDismissGuard.shouldDismissOccludingIme(
            hasImeOcclusion = true,
            imeHeightPx = imeHeight,
            screenHeightPx = screenHeight,
            actionableNodes = actionableNodes,
            currentElapsedMs = 1000L
        )
        assertTrue("Should dismiss when upper actionable nodes are sparse (< 2)", shouldDismiss)
    }

    @Test
    fun testShouldDismissOccludingIme_unoccludedAndSufficientUpper() {
        val screenHeight = 2400
        val imeHeight = 800 // imeTopY = 1600

        // 3 actionable buttons completely in upper area
        val actionableNodes = listOf(
            testRect(50, 100, 400, 200),
            testRect(50, 250, 400, 350),
            testRect(50, 400, 400, 500)
        )

        val shouldDismiss = ImeDismissGuard.shouldDismissOccludingIme(
            hasImeOcclusion = true,
            imeHeightPx = imeHeight,
            screenHeightPx = screenHeight,
            actionableNodes = actionableNodes,
            currentElapsedMs = 1000L
        )
        assertFalse("Should not dismiss when all actionable nodes are visible in upper area", shouldDismiss)
    }

    @Test
    fun testShouldDismissOccludingIme_noImeOcclusion() {
        val actionableNodes = listOf(testRect(100, 1800, 500, 1950))
        val shouldDismiss = ImeDismissGuard.shouldDismissOccludingIme(
            hasImeOcclusion = false,
            imeHeightPx = 800,
            screenHeightPx = 2400,
            actionableNodes = actionableNodes,
            currentElapsedMs = 1000L
        )
        assertFalse("Must not dismiss when hasImeOcclusion is false", shouldDismiss)
    }

    @Test
    fun testShouldDismissOccludingIme_cooldownBlocks() {
        val t0 = 3000L
        ImeDismissGuard.recordDismiss(t0)

        val blocked = ImeDismissGuard.shouldDismissOccludingIme(
            hasImeOcclusion = true,
            imeHeightPx = 800,
            screenHeightPx = 2400,
            actionableNodes = listOf(testRect(100, 1800, 500, 1950)),
            currentElapsedMs = t0 + 200L
        )
        assertFalse("Must be blocked within cooldown window", blocked)
    }
}
