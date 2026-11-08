package com.example.newandroidapp.chat

import android.graphics.Rect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ChatRole {
    USER,
    AI
}

data class ScreenCandidateItem(
    val index: Int,
    val label: String,
    val boundsInScreen: Rect? = null,
    val targetNodeId: String? = null
)

data class PendingSafetyAction(
    val label: String,
    val targetMarkId: Int? = null,
    val tapPixelX: Int = 0,
    val tapPixelY: Int = 0,
    val gridX: Int = 0,
    val gridY: Int = 0,
    val opcode: String = "",
    val packageName: String? = null
)

data class ConversationTurnContext(
    val lastTargetApp: String? = null,
    val lastSearchQuery: String? = null,
    val lastCandidates: List<ScreenCandidateItem> = emptyList(),
    val lastAiPrompt: String? = null,
    val pendingSafetyAction: PendingSafetyAction? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val pendingCoordinates: Pair<Int, Int>?
        get() = pendingSafetyAction?.let { Pair(it.tapPixelX, it.tapPixelY) }
}


data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadataHeader: String? = null,
    val isProactiveReport: Boolean = false,
    val candidates: List<ScreenCandidateItem> = emptyList(),
    val executableCommand: String? = null,
    /** 含畫面文字、通知、剪貼簿、聯絡人等私人內容：只在本次執行期間顯示，不寫入磁碟。 */
    val isSensitive: Boolean = false
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

