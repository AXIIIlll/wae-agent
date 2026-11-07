package com.example.newandroidapp.chat

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class ConversationManagerAdversarialTest {

    private val emojiPattern = Pattern.compile("[\\u2600-\\u27BF]|[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]")

    @Before
    fun setUp() {
        ConversationManager.clearConversation()
    }

    @Test
    fun testExtremeMessageLength_50kChars() {
        // Construct 50,000 characters without emojis
        val chunk = "對話式AI系統繁體中文長文本壓力測試1234567890ABCDEF\n"
        val builder = java.lang.StringBuilder(50000)
        while (builder.length < 50000) {
            builder.append(chunk)
        }
        val text50k = builder.substring(0, 50000)
        assertEquals(50000, text50k.length)
        assertFalse("Payload must contain zero emojis", emojiPattern.matcher(text50k).find())

        val userMsg = ConversationManager.appendUserMessage(text50k)
        assertEquals(50000, userMsg.text.length)
        assertEquals(text50k, userMsg.text)

        val aiMsg = ConversationManager.appendAiMessage(text50k, metadataHeader = "長文本測試")
        assertEquals(50000, aiMsg.text.length)
        assertEquals(text50k, aiMsg.text)

        val messages = ConversationManager.getMessages()
        assertEquals(2, messages.size)
        assertEquals(50000, messages[0].text.length)
        assertEquals(50000, messages[1].text.length)
    }

    @Test
    fun testCandidateList_100PlusItems() {
        val candidateCount = 125
        val candidates = (1..candidateCount).map { index ->
            ScreenCandidateItem(
                index = index,
                label = "搜尋候選結果標題第 $index 項",
                boundsInScreen = if (index % 3 == 0) null else Rect(0, index * 50, 1080, (index + 1) * 50),
                targetNodeId = if (index % 2 == 0) null else "node_candidate_$index"
            )
        }
        assertEquals(candidateCount, candidates.size)

        val aiMsg = ConversationManager.appendAiMessage(
            text = "已提取多個搜尋候選項目",
            candidates = candidates
        )
        assertEquals(candidateCount, aiMsg.candidates.size)
        assertEquals(1, aiMsg.candidates.first().index)
        assertEquals(candidateCount, aiMsg.candidates.last().index)

        // Test context preservation with 100+ candidates
        val turnContext = ConversationTurnContext(
            lastTargetApp = "com.google.android.youtube",
            lastSearchQuery = "台灣經典電影",
            lastCandidates = candidates,
            lastAiPrompt = "找到 125 個候選項目，請問要點選哪一個？"
        )
        ConversationManager.updateTurnContext(turnContext)
        val fetchedContext = ConversationManager.getTurnContext()
        assertEquals(candidateCount, fetchedContext.lastCandidates.size)
        assertEquals("com.google.android.youtube", fetchedContext.lastTargetApp)
        assertEquals("台灣經典電影", fetchedContext.lastSearchQuery)
    }

    @Test
    fun testNullValuesHandling() {
        // AI message with null metadataHeader and empty candidates
        val aiMsg = ConversationManager.appendAiMessage(
            text = "無狀態標頭測試訊息",
            metadataHeader = null,
            candidates = emptyList()
        )
        assertNull(aiMsg.metadataHeader)
        assertTrue(aiMsg.candidates.isEmpty())

        // TurnContext with all null optional fields
        val emptyContext = ConversationTurnContext(
            lastTargetApp = null,
            lastSearchQuery = null,
            lastCandidates = emptyList(),
            lastAiPrompt = null
        )
        ConversationManager.updateTurnContext(emptyContext)
        val retrieved = ConversationManager.getTurnContext()
        assertNull(retrieved.lastTargetApp)
        assertNull(retrieved.lastSearchQuery)
        assertNull(retrieved.lastAiPrompt)
        assertTrue(retrieved.lastCandidates.isEmpty())
    }

    @Test
    fun testObserverExceptionIsolation() {
        // Register a buggy observer that always throws an unhandled RuntimeException
        val buggyObserver = object : ConversationObserver {
            override fun onMessagesUpdated(messages: List<ChatMessage>) {
                throw RuntimeException("Simulated observer crash during onMessagesUpdated")
            }

            override fun onTurnContextUpdated(context: ConversationTurnContext) {
                throw RuntimeException("Simulated observer crash during onTurnContextUpdated")
            }
        }

        val healthyObserverCalled = AtomicBoolean(false)
        val healthyObserverContextCalled = AtomicBoolean(false)
        val healthyObserver = object : ConversationObserver {
            override fun onMessagesUpdated(messages: List<ChatMessage>) {
                healthyObserverCalled.set(true)
            }

            override fun onTurnContextUpdated(context: ConversationTurnContext) {
                healthyObserverContextCalled.set(true)
            }
        }

        try {
            ConversationManager.addObserver(buggyObserver)
            ConversationManager.addObserver(healthyObserver)

            // Reset tracking flags
            healthyObserverCalled.set(false)
            healthyObserverContextCalled.set(false)

            try {
                // Append message should NOT crash the caller even if an observer throws
                ConversationManager.appendUserMessage("例外隔離測試訊息")
            } catch (t: Throwable) {
                fail("ConversationManager must isolate observer exceptions, but threw: ${t.javaClass.simpleName}: ${t.message}")
            }

            // The healthy observer MUST have received the notification
            assertTrue(
                "Healthy observer must still be notified when another observer throws",
                healthyObserverCalled.get()
            )

            try {
                ConversationManager.updateTurnContext(ConversationTurnContext(lastSearchQuery = "隔離測試"))
            } catch (t: Throwable) {
                fail("updateTurnContext must isolate observer exceptions, but threw: ${t.javaClass.simpleName}: ${t.message}")
            }

            assertTrue(
                "Healthy observer must still receive context update when another observer throws",
                healthyObserverContextCalled.get()
            )
        } finally {
            ConversationManager.removeObserver(buggyObserver)
            ConversationManager.removeObserver(healthyObserver)
        }
    }

    @Test
    fun testConcurrentWipeAndRead() {
        val executor = Executors.newFixedThreadPool(8)
        val running = AtomicBoolean(true)
        val readErrors = AtomicInteger(0)
        val latch = CountDownLatch(8)

        // 4 reader threads
        for (i in 0 until 4) {
            executor.execute {
                try {
                    while (running.get()) {
                        val msgs = ConversationManager.getMessages()
                        val ctx = ConversationManager.getTurnContext()
                        // Ensure state consistency
                        for (m in msgs) {
                            if (m.text.isEmpty() && m.role == null) {
                                readErrors.incrementAndGet()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    readErrors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // 2 writer threads
        for (i in 0 until 2) {
            executor.execute {
                try {
                    var counter = 0
                    while (running.get()) {
                        ConversationManager.appendUserMessage("並發寫入訊息 #$counter")
                        counter++
                        if (counter % 10 == 0) {
                            Thread.yield()
                        }
                    }
                } catch (t: Throwable) {
                    readErrors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // 2 wiper threads
        for (i in 0 until 2) {
            executor.execute {
                try {
                    while (running.get()) {
                        ConversationManager.clearConversation()
                        Thread.sleep(5)
                    }
                } catch (t: Throwable) {
                    readErrors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        Thread.sleep(500)
        running.set(false)
        assertTrue("All threads should terminate cleanly", latch.await(2, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Zero errors during concurrent read/write/wipe", 0, readErrors.get())
    }
}
