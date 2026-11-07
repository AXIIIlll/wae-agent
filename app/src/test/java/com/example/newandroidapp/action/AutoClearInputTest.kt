package com.example.newandroidapp.action

import com.example.newandroidapp.e2e.TestHelpers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoClearInputTest {

    private class FakeNodeTarget(
        var textValue: String?,
        var hintValue: String?,
        var setTextSuccess: Boolean = true,
        var pasteSuccess: Boolean = true
    ) : GestureExecutor.NodeTextActionTarget {

        val recordedCalls = ArrayList<String>()

        override fun getText(): CharSequence? = textValue
        override fun getHintText(): CharSequence? = hintValue

        override fun performSetText(text: String): Boolean {
            recordedCalls.add("SET_TEXT:$text")
            return if (setTextSuccess) {
                textValue = text
                true
            } else {
                false
            }
        }

        override fun performSetSelection(start: Int, end: Int): Boolean {
            recordedCalls.add("SET_SELECTION:$start..$end")
            return true
        }

        override fun performPaste(): Boolean {
            recordedCalls.add("PASTE")
            return pasteSuccess
        }
    }

    @Test
    fun testHasExistingText_detectsOldContent() {
        assertTrue(GestureExecutor.hasExistingText("台北 101", "搜尋地點"))
        assertTrue(GestureExecutor.hasExistingText("hello", null))
        assertTrue(GestureExecutor.hasExistingText("0912345678", "請輸入手機號碼"))
    }

    @Test
    fun testHasExistingText_ignoresNullAndBlank() {
        assertFalse(GestureExecutor.hasExistingText(null, "搜尋地點"))
        assertFalse(GestureExecutor.hasExistingText("", "搜尋地點"))
        assertFalse(GestureExecutor.hasExistingText("   ", "搜尋地點"))
    }

    @Test
    fun testHasExistingText_ignoresPlaceholderMatchingHint() {
        assertFalse(GestureExecutor.hasExistingText("請輸入關鍵字", "請輸入關鍵字"))
        assertFalse(GestureExecutor.hasExistingText("Search...", "Search..."))
    }

    @Test
    fun testClearExistingText_executesTier1AndTier2() {
        val oldString = "舊的查詢關鍵字"
        val fake = FakeNodeTarget(textValue = oldString, hintValue = "請輸入關鍵字")

        val cleared = GestureExecutor.clearExistingText(fake)
        assertTrue("clearExistingText should succeed", cleared)

        // Verify Tier 1: performSetText with empty string
        assertTrue(
            "Tier 1 SET_TEXT: must be called",
            fake.recordedCalls.contains("SET_TEXT:")
        )

        // Verify Tier 2: performSetSelection with 0..oldLength
        assertTrue(
            "Tier 2 SET_SELECTION:0..${oldString.length} must be called",
            fake.recordedCalls.contains("SET_SELECTION:0..${oldString.length}")
        )
    }

    @Test
    fun testClearExistingText_noOpWhenAlreadyEmpty() {
        val fake = FakeNodeTarget(textValue = "", hintValue = "搜尋")
        val cleared = GestureExecutor.clearExistingText(fake)
        assertTrue(cleared)
        assertEquals(0, fake.recordedCalls.size)
    }

    @Test
    fun testAutoClearAndSetText_fullSequence() {
        val fake = FakeNodeTarget(textValue = "舊文字", hintValue = "請輸入")
        val result = GestureExecutor.autoClearAndSetText(fake, "新文字")
        assertTrue(result)

        // Must have Tier 1 (clear), Tier 2 (select all), and subsequent injection of new text
        assertTrue(fake.recordedCalls.contains("SET_TEXT:"))
        assertTrue(fake.recordedCalls.contains("SET_SELECTION:0..3"))
        assertTrue(fake.recordedCalls.contains("SET_TEXT:新文字"))

        assertEquals("新文字", fake.textValue)
    }

    @Test
    fun testAutoClearAndSetText_pasteFallback() {
        val fake = FakeNodeTarget(textValue = "舊內容", hintValue = "提示", setTextSuccess = false, pasteSuccess = true)
        val result = GestureExecutor.autoClearAndSetText(
            target = fake,
            newText = "貼上文字",
            onPrepareTransientClipboardForPaste = { fake.recordedCalls.add("PREPARE_TRANSIENT_CLIPBOARD") },
            onRestoreClipboardAfterPaste = { fake.recordedCalls.add("RESTORE_USER_CLIPBOARD") }
        )
        assertTrue(result)

        // When performSetText fails, it must prepare transient clipboard, fallback to performPaste, and immediately restore
        val prepIdx = fake.recordedCalls.indexOf("PREPARE_TRANSIENT_CLIPBOARD")
        val pasteIdx = fake.recordedCalls.indexOf("PASTE")
        val restoreIdx = fake.recordedCalls.indexOf("RESTORE_USER_CLIPBOARD")
        assertTrue("Must prepare transient clipboard before PASTE", prepIdx >= 0)
        assertTrue("Must fallback to PASTE when SET_TEXT returns false", pasteIdx > prepIdx)
        assertTrue("Must restore user clipboard immediately after PASTE", restoreIdx > pasteIdx)
    }

    @Test
    fun testAutoClearAndSetText_neverTouchesClipboardWhenSetTextSucceeds() {
        val fake = FakeNodeTarget(textValue = "舊內容", hintValue = "提示", setTextSuccess = true, pasteSuccess = true)
        var clipboardPrepared = false
        var clipboardRestored = false
        val result = GestureExecutor.autoClearAndSetText(
            target = fake,
            newText = "台北101",
            onPrepareTransientClipboardForPaste = { clipboardPrepared = true },
            onRestoreClipboardAfterPaste = { clipboardRestored = true }
        )
        assertTrue(result)
        assertFalse("Must NEVER touch system clipboard when ACTION_SET_TEXT succeeds", clipboardPrepared)
        assertFalse("No clipboard restore needed when clipboard was untouched", clipboardRestored)
    }

    @Test
    fun testNeverClicksClearButton() {
        val fake = FakeNodeTarget(textValue = "既有文字", hintValue = "搜尋")
        GestureExecutor.autoClearAndSetText(fake, "新文字")

        // Assert no click actions occurred in recording
        val hasClick = fake.recordedCalls.any { it.contains("CLICK") }
        assertFalse("Must NEVER trigger click actions (clear buttons are forbidden)", hasClick)
    }

    @Test
    fun testZeroEmojiCompliance() {
        TestHelpers.assertZeroEmojis("AutoClearInputTest", "class name")
        assertFalse(TestHelpers.containsEmoji(AutoClearInputTest::class.java.simpleName))
    }
}
