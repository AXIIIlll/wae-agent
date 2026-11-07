package com.example.newandroidapp.chat

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.regex.Pattern

class ConversationManagerTest {

    private val emojiPattern = Pattern.compile("[\\u2600-\\u27BF]|[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]")

    @Before
    fun setUp() {
        ConversationManager.clearConversation()
    }

    @Test
    fun testChatMessageCreationAndDefaults() {
        val msg = ChatMessage(
            role = ChatRole.USER,
            text = "打開 YouTube 搜尋周杰倫"
        )
        assertNotNull(msg.id)
        assertTrue(msg.id.isNotEmpty())
        assertEquals(ChatRole.USER, msg.role)
        assertEquals("打開 YouTube 搜尋周杰倫", msg.text)
        assertTrue(msg.timestamp > 0L)
        assertNull(msg.metadataHeader)
        assertFalse(msg.isProactiveReport)
        assertTrue(msg.candidates.isEmpty())
        assertNotNull(msg.formattedTime)
        assertFalse("Message must contain zero emojis", emojiPattern.matcher(msg.text).find())
    }

    @Test
    fun testConversationTurnContext() {
        val candidate = ScreenCandidateItem(
            index = 1,
            label = "周杰倫 - 最偉大的作品",
            boundsInScreen = Rect(0, 200, 1080, 400),
            targetNodeId = "node_video_1"
        )
        val context = ConversationTurnContext(
            lastTargetApp = "com.google.android.youtube",
            lastSearchQuery = "周杰倫",
            lastCandidates = listOf(candidate),
            lastAiPrompt = "請問要點擊第一個播放嗎？"
        )

        assertEquals("com.google.android.youtube", context.lastTargetApp)
        assertEquals("周杰倫", context.lastSearchQuery)
        assertEquals(1, context.lastCandidates.size)
        assertEquals("周杰倫 - 最偉大的作品", context.lastCandidates[0].label)
        assertEquals("node_video_1", context.lastCandidates[0].targetNodeId)
        assertEquals("請問要點擊第一個播放嗎？", context.lastAiPrompt)
    }

    @Test
    fun testAppendUserAndAiMessages() {
        val userMsg = ConversationManager.appendUserMessage("你好，請幫我看畫面")
        assertEquals(ChatRole.USER, userMsg.role)
        assertEquals("你好，請幫我看畫面", userMsg.text)

        val candidate = ScreenCandidateItem(index = 1, label = "選項一")
        val aiMsg = ConversationManager.appendAiMessage(
            text = "畫面上共有 3 個重點項目",
            metadataHeader = "剛剛 · 已完成",
            isProactive = true,
            candidates = listOf(candidate)
        )
        assertEquals(ChatRole.AI, aiMsg.role)
        assertEquals("畫面上共有 3 個重點項目", aiMsg.text)
        assertEquals("剛剛 · 已完成", aiMsg.metadataHeader)
        assertTrue(aiMsg.isProactiveReport)
        assertEquals(1, aiMsg.candidates.size)

        val messages = ConversationManager.getMessages()
        assertEquals(2, messages.size)
        assertEquals(userMsg.id, messages[0].id)
        assertEquals(aiMsg.id, messages[1].id)
    }

    @Test
    fun testObserverNotification() {
        var observedMessagesCount = -1
        var observedLastQuery: String? = null

        val observer = object : ConversationObserver {
            override fun onMessagesUpdated(messages: List<ChatMessage>) {
                observedMessagesCount = messages.size
            }

            override fun onTurnContextUpdated(context: ConversationTurnContext) {
                observedLastQuery = context.lastSearchQuery
            }
        }

        ConversationManager.addObserver(observer)

        ConversationManager.appendUserMessage("測試訊息一")
        assertEquals(1, observedMessagesCount)

        ConversationManager.appendAiMessage("測試回覆一")
        assertEquals(2, observedMessagesCount)

        val newContext = ConversationTurnContext(
            lastSearchQuery = "新搜尋詞"
        )
        ConversationManager.updateTurnContext(newContext)
        assertEquals("新搜尋詞", observedLastQuery)

        ConversationManager.removeObserver(observer)
        ConversationManager.appendUserMessage("移除監聽後的訊息")
        // Observer should not receive further updates
        assertEquals(2, observedMessagesCount)
    }

    @Test
    fun testClearConversation() {
        ConversationManager.appendUserMessage("待清除訊息")
        ConversationManager.updateTurnContext(ConversationTurnContext(lastTargetApp = "test.app"))
        assertEquals(1, ConversationManager.getMessages().size)
        assertEquals("test.app", ConversationManager.getTurnContext().lastTargetApp)

        ConversationManager.clearConversation()

        assertTrue(ConversationManager.getMessages().isEmpty())
        assertNull(ConversationManager.getTurnContext().lastTargetApp)
    }

    @Test
    fun testMaxMessagesBound() {
        for (i in 1..120) {
            ConversationManager.appendUserMessage("訊息 #$i")
        }
        val messages = ConversationManager.getMessages()
        assertEquals(100, messages.size)
        assertEquals("訊息 #21", messages.first().text)
        assertEquals("訊息 #120", messages.last().text)
    }

    @Test
    fun testZeroEmojiIntegrity() {
        val sampleStrings = listOf(
            "AI 助手",
            "已撤銷同意並清除所有對話紀錄、本機記憶庫與日誌",
            "剛剛 · 已完成",
            "已為您在 YouTube 搜尋「周杰倫」，畫面上找到以下結果："
        )
        for (str in sampleStrings) {
            assertFalse("String '$str' must not contain emojis", emojiPattern.matcher(str).find())
        }
    }
}
