package com.wae.agent.core.grounding

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G0：GroundingTrace 序列化與 recorder 緩衝/落盤測試（純 JVM）。
 *
 * 覆蓋：
 *  1. trace → JSON 的欄位順序、數字格式、字串跳脫（含中文原樣輸出）
 *  2. 非有限分數不產生非法 JSON
 *  3. record 緩衝、上限丟舊、開關關閉
 *  4. flushToDirectory 的 UTF-8 / 無 BOM / append 行為與失敗回滾
 *  5. validate() 對壞資料回報而不丟例外
 */
class GroundingTraceTest {

    @After
    fun tearDown() {
        GroundingTraceRecorder.resetForTest()
    }

    private fun candidate(
        slot: Int = 0,
        text: String = "T1",
        className: String = "android.widget.EditText",
        viewId: String = "V1",
        bounds: IntArray = intArrayOf(0, 0, 100, 50),
        clickable: Boolean = true,
        editable: Boolean = true,
        checkable: Boolean = false,
        checked: Boolean = false,
        enabled: Boolean = true,
        score: Float = 0.91f
    ) = TraceCandidate(
        slot = slot,
        text = text,
        className = className,
        viewId = viewId,
        bounds = bounds,
        clickable = clickable,
        editable = editable,
        checkable = checkable,
        checked = checked,
        enabled = enabled,
        score = score
    )

    private fun trace(
        pkg: String = "com.wae.agent",
        posture: String = "FORM",
        stateId: String = "S1",
        goal: String = "T1",
        candidates: List<TraceCandidate> = listOf(candidate()),
        chosenIndex: Int = 0,
        changed: Boolean = true,
        postconditionVerified: Boolean = false,
        elapsedMs: Long = 12L
    ) = GroundingTrace(
        pkg = pkg,
        posture = posture,
        stateId = stateId,
        goal = goal,
        candidates = candidates,
        chosenIndex = chosenIndex,
        changed = changed,
        postconditionVerified = postconditionVerified,
        elapsedMs = elapsedMs
    )

    private fun newTempDir(): File {
        return java.nio.file.Files.createTempDirectory("wae-grounding-trace").toFile()
    }

    private fun deleteTree(file: File) {
        file.listFiles()?.forEach { deleteTree(it) }
        file.delete()
    }

    @Test
    fun `toJson writes stable field order and formatting`() {
        val json = GroundingTraceRecorder.toJson(trace())
        assertEquals(
            "{\"pkg\":\"com.wae.agent\",\"posture\":\"FORM\",\"stateId\":\"S1\",\"goal\":\"T1\"," +
                "\"chosenIndex\":0,\"changed\":true,\"postconditionVerified\":false,\"elapsedMs\":12," +
                "\"candidates\":[{\"slot\":0,\"text\":\"T1\",\"className\":\"android.widget.EditText\"," +
                "\"viewId\":\"V1\",\"bounds\":[0,0,100,50],\"clickable\":true,\"editable\":true," +
                "\"checkable\":false,\"checked\":false,\"enabled\":true,\"score\":0.910}]}",
            json
        )
    }

    @Test
    fun `toJson escapes json special characters and keeps utf8 text`() {
        val json = GroundingTraceRecorder.toJson(trace(goal = "a\"b\\c\nT1"))
        assertTrue(json.contains("\"goal\":\"a\\\"b\\\\c\\nT1\""))

        val chinese = GroundingTraceRecorder.toJson(trace(goal = "開啟T1"))
        assertTrue("中文不得被轉義成 \\uXXXX，應原樣輸出 UTF-8", chinese.contains("\"goal\":\"開啟T1\""))
    }

    @Test
    fun `non finite score is normalized so json stays valid`() {
        val nan = GroundingTraceRecorder.toJson(trace(candidates = listOf(candidate(score = Float.NaN))))
        assertTrue(nan.contains("\"score\":0.000"))

        val inf = GroundingTraceRecorder.toJson(
            trace(candidates = listOf(candidate(score = Float.POSITIVE_INFINITY)))
        )
        assertTrue(inf.contains("\"score\":0.000"))
    }

    @Test
    fun `record buffers traces and toJson returns one line per trace`() {
        GroundingTraceRecorder.record(trace(goal = "T1"))
        GroundingTraceRecorder.record(trace(goal = "T2"))
        assertEquals(2, GroundingTraceRecorder.bufferedCount())
        val lines = GroundingTraceRecorder.toJson().split('\n')
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"goal\":\"T1\""))
        assertTrue(lines[1].contains("\"goal\":\"T2\""))
    }

    @Test
    fun `disabled recorder drops new traces`() {
        GroundingTraceRecorder.ENABLED = false
        GroundingTraceRecorder.record(trace())
        assertEquals(0, GroundingTraceRecorder.bufferedCount())
    }

    @Test
    fun `buffer cap drops oldest traces and counts drops`() {
        GroundingTraceRecorder.MAX_BUFFER = 2
        GroundingTraceRecorder.record(trace(goal = "T1"))
        GroundingTraceRecorder.record(trace(goal = "T2"))
        GroundingTraceRecorder.record(trace(goal = "T3"))
        val goals = GroundingTraceRecorder.snapshot().map { it.goal }
        assertEquals(listOf("T2", "T3"), goals)
        assertEquals(1L, GroundingTraceRecorder.droppedCount)
    }

    @Test
    fun `resetForTest restores defaults and clears buffer`() {
        GroundingTraceRecorder.record(trace())
        GroundingTraceRecorder.ENABLED = false
        GroundingTraceRecorder.MAX_BUFFER = 1
        GroundingTraceRecorder.TRACE_FILE = "other.jsonl"
        GroundingTraceRecorder.resetForTest()
        assertEquals(0, GroundingTraceRecorder.bufferedCount())
        assertTrue(GroundingTraceRecorder.ENABLED)
        assertEquals(GroundingTraceRecorder.DEFAULT_MAX_BUFFER, GroundingTraceRecorder.MAX_BUFFER)
        assertEquals(GroundingTraceRecorder.DEFAULT_TRACE_FILE, GroundingTraceRecorder.TRACE_FILE)
        assertEquals(0L, GroundingTraceRecorder.droppedCount)
    }

    @Test
    fun `flushToDirectory appends utf8 jsonl without bom`() {
        val dir = newTempDir()
        try {
            GroundingTraceRecorder.record(trace(goal = "T1"))
            GroundingTraceRecorder.record(trace(goal = "T2"))
            assertTrue(GroundingTraceRecorder.flushToDirectory(dir))

            val file = File(dir, GroundingTraceRecorder.DEFAULT_TRACE_FILE)
            val bytes = file.readBytes()
            assertFalse("JSONL 檔頭不得有 UTF-8 BOM", bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            val content = file.readText(Charsets.UTF_8)
            assertTrue(content.endsWith("\n"))
            assertEquals(2, content.count { it == '\n' })
            val lines = content.split('\n').filter { it.isNotEmpty() }
            assertEquals(GroundingTraceRecorder.toJson(trace(goal = "T1")), lines[0])
            assertEquals(GroundingTraceRecorder.toJson(trace(goal = "T2")), lines[1])
            assertEquals(0, GroundingTraceRecorder.bufferedCount())

            // 第二次 flush 必須 append，不是覆蓋
            GroundingTraceRecorder.record(trace(goal = "T3"))
            assertTrue(GroundingTraceRecorder.flushToDirectory(dir))
            val after = file.readText(Charsets.UTF_8)
            assertEquals(3, after.count { it == '\n' })
            val lastLine = after.split('\n').last { it.isNotEmpty() }
            assertTrue(lastLine.contains("\"goal\":\"T3\""))
        } finally {
            deleteTree(dir)
        }
    }

    @Test
    fun `flushToDirectory returns false for empty buffer or null dir`() {
        assertFalse(GroundingTraceRecorder.flushToDirectory(null))
        val dir = newTempDir()
        try {
            assertFalse(GroundingTraceRecorder.flushToDirectory(dir))
        } finally {
            deleteTree(dir)
        }
    }

    @Test
    fun `flush failure restores buffer so no trace is lost`() {
        // 傳入「一個檔案」而不是目錄 → mkdirs/append 必失敗
        val tempFile = java.nio.file.Files.createTempFile("wae-grounding-trace", ".tmp").toFile()
        try {
            GroundingTraceRecorder.record(trace(goal = "T1"))
            assertFalse(GroundingTraceRecorder.flushToDirectory(tempFile))
            assertEquals(1, GroundingTraceRecorder.bufferedCount())
            assertEquals("T1", GroundingTraceRecorder.snapshot()[0].goal)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `flushToFile with null context is a safe no-op`() {
        GroundingTraceRecorder.record(trace())
        assertFalse(GroundingTraceRecorder.flushToFile(null))
        assertEquals(1, GroundingTraceRecorder.bufferedCount())
    }

    @Test
    fun `validate reports bad payload without throwing`() {
        val bad = trace(
            candidates = listOf(candidate(bounds = intArrayOf(0, 0, 100), score = Float.NaN)),
            chosenIndex = 5
        )
        val errors = bad.validate()
        assertTrue(errors.any { it.contains("chosenIndex") })
        assertTrue(errors.any { it.contains("bounds.size") })
        assertTrue(errors.any { it.contains("不是有限值") })
        assertTrue(trace().validate().isEmpty())
    }

    @Test
    fun `trace candidate equality is structural for bounds`() {
        assertEquals(candidate(), candidate())
        assertEquals(candidate().hashCode(), candidate().hashCode())
        assertNotEquals(candidate(bounds = intArrayOf(0, 0, 100, 51)), candidate())
    }
}
