package com.example.newandroidapp.stability

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.e2e.TestHelpers
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for EpisodicAppMemory:
 * - Async thread-safe single-threaded writes
 * - Write coalescing
 * - Atomic temporary file replacement (no corruptions)
 * - Restoring memory via ensureLoaded
 * - Score adjustments and dead-end trap defenses
 * - High-concurrency safety
 */
class EpisodicAppMemoryTest {

    private lateinit var tempDir: File
    private lateinit var testContext: Context

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("episodic_mem_test").toFile()
        testContext = TestHelpers.createTestContext(tempDir)
        EpisodicAppMemory.resetForTesting()
        EpisodicAppMemory.clearMemory(testContext)
    }

    @After
    fun tearDown() {
        EpisodicAppMemory.clearMemory(testContext)
        tempDir.deleteRecursively()
    }

    private fun makeTestNode(
        id: String = "node_1",
        tag: String = "BTN",
        label: String = "搜尋按鈕",
        gx: Int = 500,
        gy: Int = 500
    ): Stage2ConvergedNode {
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = 1,
            className = "android.widget.Button",
            shortTypeTag = tag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = Rect(100, 100, 200, 200),
            convergedBounds = Rect(100, 100, 200, 200),
            finalTapPx = Point(150, 150),
            gridTapPoint = Point(gx, gy),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
    }

    @Test
    fun testRecordVerifiedSearchEntrySavesAsyncAtomically() {
        val node = makeTestNode(label = "搜尋框", tag = "INP", gx = 300, gy = 150)
        EpisodicAppMemory.recordVerifiedSearchEntry(
            context = testContext,
            packageName = "com.google.android.youtube",
            appLabel = "YouTube",
            node = node
        )

        // Flush async background worker
        assertTrue(EpisodicAppMemory.flushPendingSaves(3000L))

        val targetFile = File(testContext.filesDir, "learned_app_episodic_memory.json")
        val tempFile = File(testContext.filesDir, "learned_app_episodic_memory.json.tmp")

        assertTrue("Main memory file must exist on disk", targetFile.exists())
        assertFalse("Temp file must not linger after atomic rename", tempFile.exists())

        // Verify valid JSON content
        val json = MiniJson.parse(targetFile.readText(Charsets.UTF_8))
        assertNotNull("JSON root must not be null", json)
        @Suppress("UNCHECKED_CAST")
        val apps = json!!["apps"] as List<Map<String, Any?>>
        assertEquals(1, apps.size)
        val app = apps[0]
        assertEquals("com.google.android.youtube", app["packageName"])
        assertEquals("YouTube", app["appLabel"])
        @Suppress("UNCHECKED_CAST")
        val entries = app["verifiedSearchEntries"] as List<Map<String, Any?>>
        assertEquals(1, entries.size)
        assertEquals("搜尋框", entries[0]["label"])
    }

    @Test
    fun testRecordVerifiedTargetClickAndDeadEndTrap() {
        val clickNode = makeTestNode(label = "第一部影片", tag = "BTN", gx = 400, gy = 600)
        EpisodicAppMemory.recordVerifiedTargetClick(
            context = testContext,
            packageName = "com.example.shop",
            appLabel = "ShopApp",
            intentKeyword = "購買咖啡",
            node = clickNode
        )

        val trapNode = makeTestNode(label = "全螢幕廣告關閉", tag = "BTN", gx = 950, gy = 50)
        EpisodicAppMemory.recordDeadEndTrap(
            context = testContext,
            packageName = "com.example.shop",
            screenFp = "SCREEN_FP_123",
            node = trapNode
        )

        assertTrue(EpisodicAppMemory.flushPendingSaves(3000L))

        val targetFile = File(testContext.filesDir, "learned_app_episodic_memory.json")
        assertTrue(targetFile.exists())

        val json = MiniJson.parse(targetFile.readText(Charsets.UTF_8))
        assertNotNull(json)
        @Suppress("UNCHECKED_CAST")
        val apps = json!!["apps"] as List<Map<String, Any?>>
        val appObj = apps[0]
        assertEquals("com.example.shop", appObj["packageName"])
        @Suppress("UNCHECKED_CAST")
        val clicks = appObj["verifiedTargetClicks"] as List<Map<String, Any?>>
        assertEquals(1, clicks.size)
        @Suppress("UNCHECKED_CAST")
        val traps = appObj["deadEndTrapKeys"] as List<String>
        assertEquals(2, traps.size)
    }

    @Test
    fun testPersistenceAndRestoreViaEnsureLoaded() {
        val node = makeTestNode(label = "設定項目", tag = "TXT", gx = 200, gy = 300)
        EpisodicAppMemory.recordVerifiedTargetClick(
            context = testContext,
            packageName = "com.android.settings",
            appLabel = "設定",
            intentKeyword = "WIFI設定",
            node = node
        )
        assertTrue(EpisodicAppMemory.flushPendingSaves(3000L))

        // Simulate application restart by wiping in-memory state
        EpisodicAppMemory.resetForTesting()

        // Reload from disk
        EpisodicAppMemory.ensureLoaded(testContext)

        // Verify loaded state via score adjustment
        val candidate = makeTestNode(label = "設定項目", tag = "TXT", gx = 205, gy = 305)
        val (score, reason) = EpisodicAppMemory.computeEpisodicScoreAdjustment(
            context = testContext,
            packageName = "com.android.settings",
            candidate = candidate,
            stage = InAppOperationStage.STAGE_C_SELECT_RESULT,
            query = "WIFI設定"
        )

        assertEquals(20f, score, 0.001f)
        assertNotNull(reason)
        assertTrue(reason!!.contains("經驗記憶命中成功路徑"))
    }

    @Test
    fun testDeadEndTrapAvoidancePenalty() {
        val trap = makeTestNode(label = "廣告跳轉", tag = "BTN", gx = 500, gy = 500)
        EpisodicAppMemory.recordDeadEndTrap(
            context = testContext,
            packageName = "com.example.game",
            screenFp = "HOME_SCREEN",
            node = trap
        )
        assertTrue(EpisodicAppMemory.flushPendingSaves(3000L))

        val candidate = makeTestNode(label = "廣告跳轉", tag = "BTN", gx = 502, gy = 502)
        val (score, reason) = EpisodicAppMemory.computeEpisodicScoreAdjustment(
            context = testContext,
            packageName = "com.example.game",
            candidate = candidate,
            stage = InAppOperationStage.STAGE_C_SELECT_RESULT,
            screenFp = "HOME_SCREEN"
        )

        assertEquals(-55f, score, 0.001f)
        assertNotNull(reason)
        assertTrue(reason!!.contains("避開死胡同/陷阱"))
    }

    @Test
    fun testHighConcurrencyThreadSafety() {
        val threadCount = 20
        val operationsPerThread = 15
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        for (t in 1..threadCount) {
            Thread {
                try {
                    for (i in 1..operationsPerThread) {
                        val node = makeTestNode(
                            id = "t${t}_n$i",
                            label = "按鈕_${t}_$i",
                            gx = (t * 40) % 900,
                            gy = (i * 50) % 900
                        )
                        if (i % 3 == 0) {
                            EpisodicAppMemory.recordVerifiedSearchEntry(
                                testContext,
                                "com.app.pkg$t",
                                "App$t",
                                node
                            )
                        } else if (i % 3 == 1) {
                            EpisodicAppMemory.recordVerifiedTargetClick(
                                testContext,
                                "com.app.pkg$t",
                                "App$t",
                                "目標關鍵字_$i",
                                node
                            )
                        } else {
                            EpisodicAppMemory.recordDeadEndTrap(
                                testContext,
                                "com.app.pkg$t",
                                "SCREEN_FP_$i",
                                node
                            )
                        }
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue("All concurrency worker threads should finish within 10s", latch.await(10, TimeUnit.SECONDS))
        assertEquals("Zero concurrency exceptions expected", 0, errorCount.get())

        // Ensure all background disk writes flush completely
        assertTrue(EpisodicAppMemory.flushPendingSaves(5000L))

        val targetFile = File(testContext.filesDir, "learned_app_episodic_memory.json")
        assertTrue("Memory file must exist and be intact after concurrent writes", targetFile.exists())

        // File must parse as valid JSON
        val root = MiniJson.parse(targetFile.readText(Charsets.UTF_8))
        assertNotNull("Root must not be null", root)
        @Suppress("UNCHECKED_CAST")
        val apps = root!!["apps"] as List<Map<String, Any?>>
        assertTrue("Apps array should have multiple recorded profiles", apps.isNotEmpty())
    }

    @Test
    fun testClearMemoryRemovesDiskFile() {
        val node = makeTestNode()
        EpisodicAppMemory.recordVerifiedSearchEntry(testContext, "com.example.clear", "ClearTest", node)
        assertTrue(EpisodicAppMemory.flushPendingSaves(3000L))

        val targetFile = File(testContext.filesDir, "learned_app_episodic_memory.json")
        assertTrue(targetFile.exists())

        EpisodicAppMemory.clearMemory(testContext)
        assertFalse("Target memory file must be deleted after clearMemory()", targetFile.exists())
    }

    @Test
    fun testZeroEmojiComplianceInStatsBadge() {
        val node = makeTestNode(label = "首頁入口")
        EpisodicAppMemory.recordVerifiedSearchEntry(testContext, "com.example.badge", "BadgeApp", node)
        val badge = EpisodicAppMemory.getMemoryStatsBadge(testContext, "com.example.badge")
        TestHelpers.assertZeroEmojis(badge, "EpisodicAppMemory badge")
    }
}
