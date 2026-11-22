package com.wae.agent.core.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 被動記錄策略測試：只觀察已自動化過的 App、任務中不記錄、可關閉。 */
class PassiveRecordPolicyTest {

    private fun step(pkg: String) = TwinStep(
        pkg = pkg, spineBefore = 1, spineAfter = 2, action = "TAP",
        target = "x", posture = "", ok = true, ms = 100, ts = 0
    )

    @Test
    fun `records clicks in known package`() {
        assertTrue(
            PassiveRecordPolicy.shouldRecord(
                passiveDisabled = false,
                isBusyWithTask = false,
                pkg = "com.demo.mail",
                knownPackages = setOf("com.demo.mail"),
                eventType = "CLICK"
            )
        )
    }

    @Test
    fun `does not record unknown package`() {
        assertFalse(
            PassiveRecordPolicy.shouldRecord(
                false, false, "com.facebook.katana", setOf("com.demo.mail"), "CLICK"
            )
        )
    }

    @Test
    fun `does not record while task is running`() {
        assertFalse(
            PassiveRecordPolicy.shouldRecord(
                false, true, "com.demo.mail", setOf("com.demo.mail"), "CLICK"
            )
        )
    }

    @Test
    fun `respects user opt out`() {
        assertFalse(
            PassiveRecordPolicy.shouldRecord(
                true, false, "com.demo.mail", setOf("com.demo.mail"), "CLICK"
            )
        )
    }

    @Test
    fun `ignores self and non click events`() {
        assertFalse(PassiveRecordPolicy.shouldRecord(false, false, "com.wae.agent", setOf("com.wae.agent"), "CLICK"))
        assertFalse(PassiveRecordPolicy.shouldRecord(false, false, "com.demo.mail", setOf("com.demo.mail"), "SCROLL"))
    }

    @Test
    fun `known packages come from history`() {
        val known = PassiveRecordPolicy.knownPackagesFrom(
            listOf(step("com.demo.mail"), step("com.demo.mail"), step("com.wae.agent"))
        )
        assertEquals(setOf("com.demo.mail"), known)
    }
}
