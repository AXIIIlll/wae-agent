package com.example.newandroidapp.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * Empirical stress and concurrency tests for Milestone M1 (ConversationManager & persistence).
 * Challenges:
 * 1. 50 concurrent threads simultaneously appending, reading, updating context, and clearing.
 * 2. Race conditions in bounded queue (MAX_MESSAGES = 100).
 * 3. Observer registration/unregistration safety under concurrent message dispatch.
 * 4. Persistence resilience under corrupted JSON, truncated files, binary garbage, and null fields.
 */
class ConversationManagerConcurrencyStressTest {

    private lateinit var tempDir: File
    private lateinit var mockContext: Context
    private val emojiPattern = Pattern.compile("[\\u2600-\\u27BF]|[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]")

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("stress_test_files").toFile()
        mockContext = createMockContext(tempDir)
        ConversationManager.clearConversation(mockContext)
    }

    @After
    fun tearDown() {
        ConversationManager.clearConversation(mockContext)
        tempDir.deleteRecursively()
    }

    private fun createMockContext(dir: File): Context {
        val prefsMap = ConcurrentHashMap<String, Any>()
        val mockPrefs = object : SharedPreferences {
            override fun getAll(): Map<String, *> = prefsMap
            override fun getString(key: String?, defValue: String?): String? = prefsMap[key] as? String ?: defValue
            override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
                @Suppress("UNCHECKED_CAST") (prefsMap[key] as? Set<String>) ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (prefsMap[key] as? Number)?.toInt() ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (prefsMap[key] as? Number)?.toLong() ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (prefsMap[key] as? Number)?.toFloat() ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (prefsMap[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = prefsMap.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private val pending = HashMap<String, Any?>()
                private var clearPending = false
                override fun putString(key: String?, value: String?) = apply { pending[key ?: ""] = value }
                override fun putStringSet(key: String?, values: Set<String>?) = apply { pending[key ?: ""] = values }
                override fun putInt(key: String?, value: Int) = apply { pending[key ?: ""] = value }
                override fun putLong(key: String?, value: Long) = apply { pending[key ?: ""] = value }
                override fun putFloat(key: String?, value: Float) = apply { pending[key ?: ""] = value }
                override fun putBoolean(key: String?, value: Boolean) = apply { pending[key ?: ""] = value }
                override fun remove(key: String?) = apply { pending[key ?: ""] = null }
                override fun clear() = apply { clearPending = true }
                override fun commit(): Boolean { apply(); return true }
                override fun apply() {
                    if (clearPending) prefsMap.clear()
                    for ((k, v) in pending) {
                        if (v == null) prefsMap.remove(k) else prefsMap[k] = v
                    }
                }
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = dir
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = mockPrefs
            override fun getPackageName(): String = "com.example.newandroidapp"
        }
    }

    private fun resetIsLoadedFlag() {
        try {
            val field = ConversationManager::class.java.getDeclaredField("isLoaded")
            field.isAccessible = true
            field.set(ConversationManager, false)
        } catch (t: Throwable) {
            // If field not accessible, ignore
        }
    }

    // =========================================================================
    // Challenge 1: High Concurrency (50 Threads) Appending, Reading, Clearing
    // =========================================================================

    @Test
    fun testConcurrency_50ThreadsSimultaneousAppendsAndReads() {
        val threadCount = 50
        val operationsPerThread = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(threadCount)
        val caughtExceptions = Collections.synchronizedList(ArrayList<Throwable>())

        val successfulAppends = AtomicInteger(0)
        val successfulReads = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    for (i in 0 until operationsPerThread) {
                        if (t % 3 == 0) {
                            // User append
                            ConversationManager.appendUserMessage("Thread-$t message-$i")
                            successfulAppends.incrementAndGet()
                        } else if (t % 3 == 1) {
                            // AI append
                            ConversationManager.appendAiMessage(
                                text = "Thread-$t AI reply-$i",
                                metadataHeader = "步驟-$i",
                                isProactive = (i % 2 == 0)
                            )
                            successfulAppends.incrementAndGet()
                        } else {
                            // Read
                            val current = ConversationManager.getMessages()
                            assertTrue("Messages snapshot should never be null", current != null)
                            successfulReads.incrementAndGet()
                        }
                    }
                } catch (t: Throwable) {
                    caughtExceptions.add(t)
                } finally {
                    completionLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finishedInTime = completionLatch.await(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue("All 50 threads should finish within timeout", finishedInTime)
        if (caughtExceptions.isNotEmpty()) {
            val first = caughtExceptions.first()
            first.printStackTrace()
            fail("Encountered ${caughtExceptions.size} concurrency exceptions! First: ${first.javaClass.name}: ${first.message}")
        }

        val finalMessages = ConversationManager.getMessages()
        assertTrue("Final messages should not exceed MAX_MESSAGES (100)", finalMessages.size <= 100)
        assertTrue("Successful appends should be positive", successfulAppends.get() > 0)
    }

    @Test
    fun testConcurrency_50ThreadsWithConcurrentClear() {
        val threadCount = 50
        val operationsPerThread = 30
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(threadCount)
        val caughtExceptions = Collections.synchronizedList(ArrayList<Throwable>())

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    for (i in 0 until operationsPerThread) {
                        when {
                            t % 10 == 0 -> {
                                // Clear conversation concurrently while other threads append and read
                                ConversationManager.clearConversation(mockContext)
                            }
                            t % 2 == 0 -> {
                                ConversationManager.appendUserMessage("User msg from thread-$t #$i")
                            }
                            t % 3 == 0 -> {
                                ConversationManager.appendAiMessage("AI msg from thread-$t #$i")
                            }
                            t % 5 == 0 -> {
                                ConversationManager.updateTurnContext(
                                    ConversationTurnContext(
                                        lastTargetApp = "app.test.$t",
                                        lastSearchQuery = "query-$i"
                                    )
                                )
                            }
                            else -> {
                                val list = ConversationManager.getMessages()
                                val ctx = ConversationManager.getTurnContext()
                                assertNotNull(list)
                                assertNotNull(ctx)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    caughtExceptions.add(t)
                } finally {
                    completionLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finished = completionLatch.await(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue("Threads must complete within 10 seconds", finished)
        if (caughtExceptions.isNotEmpty()) {
            val ex = caughtExceptions.first()
            ex.printStackTrace()
            fail("High concurrency with clear threw exception: ${ex.javaClass.name}: ${ex.message}")
        }
    }

    @Test
    fun testConcurrency_ObserverSafetyUnderConcurrentMutations() {
        val threadCount = 20
        val iterations = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(threadCount)
        val caughtExceptions = Collections.synchronizedList(ArrayList<Throwable>())

        val notificationCount = AtomicInteger(0)
        val observer = object : ConversationObserver {
            override fun onMessagesUpdated(messages: List<ChatMessage>) {
                notificationCount.incrementAndGet()
            }
            override fun onTurnContextUpdated(context: ConversationTurnContext) {
                notificationCount.incrementAndGet()
            }
        }

        ConversationManager.addObserver(observer)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    for (i in 0 until iterations) {
                        if (t % 4 == 0) {
                            // Dynamic observer churn
                            val tempObserver = object : ConversationObserver {
                                override fun onMessagesUpdated(messages: List<ChatMessage>) {}
                                override fun onTurnContextUpdated(context: ConversationTurnContext) {}
                            }
                            ConversationManager.addObserver(tempObserver)
                            ConversationManager.removeObserver(tempObserver)
                        } else {
                            ConversationManager.appendUserMessage("Churn message $t-$i")
                        }
                    }
                } catch (t: Throwable) {
                    caughtExceptions.add(t)
                } finally {
                    completionLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finished = completionLatch.await(10, TimeUnit.SECONDS)
        executor.shutdownNow()
        ConversationManager.removeObserver(observer)

        assertTrue("Observer churn test finished in time", finished)
        if (caughtExceptions.isNotEmpty()) {
            val ex = caughtExceptions.first()
            fail("Observer concurrency failure: ${ex.javaClass.name}: ${ex.message}")
        }
        assertTrue("Notifications should have been received", notificationCount.get() > 0)
    }

    @Test
    fun testConcurrency_BoundedQueueIntegrityAtLimit() {
        // Test exactly at and above MAX_MESSAGES (100)
        val threadCount = 50
        val messagesPerThread = 5
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(threadCount)
        val caughtExceptions = Collections.synchronizedList(ArrayList<Throwable>())

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    for (i in 0 until messagesPerThread) {
                        ConversationManager.appendUserMessage("Boundary msg T$t-I$i")
                    }
                } catch (t: Throwable) {
                    caughtExceptions.add(t)
                } finally {
                    completionLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finished = completionLatch.await(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue("Must finish", finished)
        if (caughtExceptions.isNotEmpty()) {
            fail("Exception during queue boundary test: ${caughtExceptions.first().message}")
        }

        val messages = ConversationManager.getMessages()
        assertEquals("Queue must be strictly capped at 100 messages", 100, messages.size)
    }

    // =========================================================================
    // Challenge 2: JSON Corruption Resilience
    // =========================================================================

    @Test
    fun testJsonCorruption_TruncatedJson() {
        resetIsLoadedFlag()
        val historyFile = File(tempDir, "conversation_history.json")
        // Truncated JSON simulating sudden power loss or process kill mid-write
        historyFile.writeText("{\"version\": 1, \"messages\": [{\"id\": \"msg-1\", \"role\": \"US", Charsets.UTF_8)

        // Loading truncated JSON must not crash ConversationManager
        try {
            ConversationManager.initialize(mockContext)
            // If it handled gracefully, messages should either be empty or default
            val msgs = ConversationManager.getMessages()
            assertNotNull(msgs)
        } catch (t: Throwable) {
            fail("ConversationManager crashed on truncated JSON: ${t.javaClass.name}: ${t.message}")
        }
    }

    @Test
    fun testJsonCorruption_BinaryGarbage() {
        resetIsLoadedFlag()
        val historyFile = File(tempDir, "conversation_history.json")
        // Write raw binary garbage and non-JSON text
        historyFile.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte(), 0x12, 0x7F, 0x41, 0x42, 0x43))

        try {
            ConversationManager.initialize(mockContext)
            val msgs = ConversationManager.getMessages()
            assertNotNull(msgs)
        } catch (t: Throwable) {
            fail("ConversationManager crashed on binary garbage file: ${t.javaClass.name}: ${t.message}")
        }
    }

    @Test
    fun testJsonCorruption_BlankOrWhitespaceFile() {
        resetIsLoadedFlag()
        val historyFile = File(tempDir, "conversation_history.json")
        historyFile.writeText("   \n\t  \r\n   ", Charsets.UTF_8)

        try {
            ConversationManager.initialize(mockContext)
            val msgs = ConversationManager.getMessages()
            assertNotNull(msgs)
            assertEquals(0, msgs.size)
        } catch (t: Throwable) {
            fail("ConversationManager crashed on whitespace file: ${t.javaClass.name}: ${t.message}")
        }
    }

    @Test
    fun testJsonCorruption_SchemaMismatchRootType() {
        resetIsLoadedFlag()
        val historyFile = File(tempDir, "conversation_history.json")
        // Root is a JSON integer or string instead of object/array
        historyFile.writeText("123456789", Charsets.UTF_8)

        try {
            ConversationManager.initialize(mockContext)
            val msgs = ConversationManager.getMessages()
            assertNotNull(msgs)
        } catch (t: Throwable) {
            fail("ConversationManager crashed on primitive root JSON: ${t.javaClass.name}: ${t.message}")
        }
    }

    @Test
    fun testJsonCorruption_CorruptedMessageElements() {
        resetIsLoadedFlag()
        val historyFile = File(tempDir, "conversation_history.json")
        val malformedJson = """
            {
                "version": 1,
                "messages": [
                    { "id": 12345, "role": "INVALID_ROLE_UNKNOWN", "text": "正常文字", "timestamp": "not_a_long" },
                    { "invalid": true },
                    "not an object",
                    null,
                    { "id": "valid-1", "role": "AI", "text": "合法文字", "metadataHeader": null, "candidates": [123, "string", null] }
                ],
                "turnContext": {
                    "lastTargetApp": null,
                    "lastCandidates": "not a candidate list",
                    "timestamp": "invalid"
                }
            }
        """.trimIndent()
        historyFile.writeText(malformedJson, Charsets.UTF_8)

        try {
            ConversationManager.initialize(mockContext)
            val msgs = ConversationManager.getMessages()
            assertNotNull(msgs)
            val ctx = ConversationManager.getTurnContext()
            assertNotNull(ctx)
        } catch (t: Throwable) {
            fail("ConversationManager crashed on malformed elements in JSON: ${t.javaClass.name}: ${t.message}")
        }
    }

    @Test
    fun testJsonCorruption_StrandedTempFileDoesNotBlockNewWrites() {
        val tmpFile = File(tempDir, "conversation_history.json.tmp")
        tmpFile.writeText("stranded garbage from a crashed previous process", Charsets.UTF_8)

        ConversationManager.appendUserMessage("恢復寫入測試")
        Thread.sleep(150)

        // Even with prior stranded temp file, new message must be appended and saved
        val msgs = ConversationManager.getMessages()
        assertTrue(msgs.any { it.text == "恢復寫入測試" })
    }

    @Test
    fun testZeroEmojiIntegrityInStressStrings() {
        val testStrings = listOf(
            "Thread-1 message-1",
            "步驟-1",
            "恢復寫入測試",
            "Boundary msg T1-I1"
        )
        for (str in testStrings) {
            assertFalse("String '$str' must have zero emojis", emojiPattern.matcher(str).find())
        }
    }
}
