package com.example.newandroidapp.chat

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

interface ConversationObserver {
    fun onMessagesUpdated(messages: List<ChatMessage>)
    fun onTurnContextUpdated(context: ConversationTurnContext)
}

object ConversationManager {
    private const val TAG = "ConversationManager"
    private const val FILE_NAME = "conversation_history.json"
    private const val MAX_MESSAGES = 100

    private val messages = CopyOnWriteArrayList<ChatMessage>()

    @Volatile
    private var turnContext = ConversationTurnContext()

    private val observers = CopyOnWriteArrayList<ConversationObserver>()
    private val lambdaListeners = CopyOnWriteArrayList<(List<ChatMessage>) -> Unit>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var isLoaded = false
    private val initLock = Any()
    private val fileLock = Any()
    private val messageLock = Any()
    private val isSaving = java.util.concurrent.atomic.AtomicBoolean(false)
    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun safeLogW(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: Throwable) {
            System.err.println("[$tag] $message")
        }
    }

    private val mainHandler: Handler? by lazy {
        try {
            val looper = Looper.getMainLooper()
            if (looper != null) Handler(looper) else null
        } catch (t: Throwable) {
            null
        }
    }

    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (isLoaded) return
        synchronized(initLock) {
            if (isLoaded) return
            isLoaded = true
            loadFromDisk(context.applicationContext)
        }
    }

    fun getMessages(): List<ChatMessage> {
        synchronized(messageLock) {
            return ArrayList(messages)
        }
    }

    /**
     * 取得允許持久化至本地磁碟的對話紀錄（嚴格排除畫面文字、通知、剪貼簿等 `isSensitive = true` 的隱私訊息）
     */
    internal fun getPersistableMessages(): List<ChatMessage> = getMessages().filter { !it.isSensitive }

    fun getTurnContext(): ConversationTurnContext {
        return turnContext
    }

    fun addObserver(observer: ConversationObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ConversationObserver) {
        observers.remove(observer)
    }

    fun addListener(listener: (List<ChatMessage>) -> Unit) {
        lambdaListeners.add(listener)
    }

    fun removeListener(listener: (List<ChatMessage>) -> Unit) {
        lambdaListeners.remove(listener)
    }

    fun appendUserMessage(text: String): ChatMessage {
        val cleanText = text.trim()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            text = cleanText,
            timestamp = System.currentTimeMillis()
        )
        addMessageInternal(message)
        return message
    }

    fun appendAiMessage(
        text: String,
        metadataHeader: String? = null,
        isProactive: Boolean = false,
        candidates: List<ScreenCandidateItem> = emptyList(),
        executableCommand: String? = null,
        isSensitive: Boolean = false
    ): ChatMessage {
        val cleanText = text.trim()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.AI,
            text = cleanText,
            timestamp = System.currentTimeMillis(),
            metadataHeader = metadataHeader,
            isProactiveReport = isProactive,
            candidates = candidates,
            executableCommand = executableCommand,
            isSensitive = isSensitive
        )
        addMessageInternal(message)
        return message
    }

    fun postUserMessage(context: Context, text: String): ChatMessage {
        appContext = context.applicationContext
        return appendUserMessage(text)
    }

    fun postAiMessage(
        context: Context,
        text: String,
        statusTag: String? = null,
        executableCommand: String? = null
    ): ChatMessage {
        appContext = context.applicationContext
        return appendAiMessage(text = text, metadataHeader = statusTag, executableCommand = executableCommand)
    }

    fun updateTurnContext(context: ConversationTurnContext) {
        turnContext = context
        notifyTurnContextUpdated()
        saveToDisk()
    }

    fun clearConversation(context: Context? = null) {
        val targetCtx = context?.applicationContext ?: appContext
        if (context != null) {
            appContext = targetCtx
        }
        savePending.set(false)
        synchronized(messageLock) {
            messages.clear()
            turnContext = ConversationTurnContext()
        }
        notifyMessagesUpdated()
        notifyTurnContextUpdated()

        val dir = targetCtx?.filesDir
        ioExecutor.execute {
            synchronized(fileLock) {
                runCatching {
                    if (dir != null) {
                        val file = File(dir, FILE_NAME)
                        if (file.exists()) file.delete()
                        val tmp = File(dir, "$FILE_NAME.tmp")
                        if (tmp.exists()) tmp.delete()
                    }
                }.onFailure { e ->
                    safeLogW(TAG, "Failed to clear conversation history file: ${e.message}")
                }
            }
        }
    }

    private fun addMessageInternal(message: ChatMessage) {
        synchronized(messageLock) {
            messages.add(message)
            while (messages.size > MAX_MESSAGES && messages.isNotEmpty()) {
                messages.removeAt(0)
            }
        }
        notifyMessagesUpdated()
        saveToDisk()
    }

    private fun dispatchCallback(action: () -> Unit) {
        try {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper != null && Looper.myLooper() == mainLooper) {
                action()
            } else if (mainLooper != null) {
                val handler = mainHandler
                if (handler != null) {
                    handler.post(action)
                } else {
                    action()
                }
            } else {
                action()
            }
        } catch (t: Throwable) {
            action()
        }
    }

    private fun notifyMessagesUpdated() {
        val msgs = getMessages()
        dispatchCallback {
            for (obs in observers) {
                runCatching {
                    obs.onMessagesUpdated(msgs)
                }
            }
            for (listener in lambdaListeners) {
                runCatching {
                    listener(msgs)
                }
            }
        }
    }

    private fun notifyTurnContextUpdated() {
        val ctx = getTurnContext()
        dispatchCallback {
            for (obs in observers) {
                runCatching {
                    obs.onTurnContextUpdated(ctx)
                }
            }
        }
    }

    private fun escapeJson(str: String): String {
        val sb = StringBuilder()
        for (c in str) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code in 0x00..0x1F) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun candidateToJsonString(c: ScreenCandidateItem): String {
        val sb = StringBuilder()
        sb.append("{\"index\":").append(c.index).append(",")
        sb.append("\"label\":\"").append(escapeJson(c.label)).append("\"")
        if (c.targetNodeId != null) {
            sb.append(",\"targetNodeId\":\"").append(escapeJson(c.targetNodeId)).append("\"")
        }
        if (c.boundsInScreen != null) {
            val r = c.boundsInScreen
            sb.append(",\"bounds\":{\"left\":").append(r.left)
                .append(",\"top\":").append(r.top)
                .append(",\"right\":").append(r.right)
                .append(",\"bottom\":").append(r.bottom).append("}")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun buildJsonString(msgs: List<ChatMessage>, ctx: ConversationTurnContext): String {
        val sb = StringBuilder()
        sb.append("{\"version\":1,\"messages\":[")
        msgs.forEachIndexed { i, m ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":\"").append(escapeJson(m.id)).append("\",")
            sb.append("\"role\":\"").append(m.role.name).append("\",")
            sb.append("\"text\":\"").append(escapeJson(m.text)).append("\",")
            sb.append("\"timestamp\":").append(m.timestamp)
            if (m.metadataHeader != null) {
                sb.append(",\"metadataHeader\":\"").append(escapeJson(m.metadataHeader)).append("\"")
            }
            if (m.executableCommand != null) {
                sb.append(",\"executableCommand\":\"").append(escapeJson(m.executableCommand)).append("\"")
            }
            sb.append(",\"isProactiveReport\":").append(m.isProactiveReport)
            if (m.candidates.isNotEmpty()) {
                sb.append(",\"candidates\":[")
                m.candidates.forEachIndexed { ci, c ->
                    if (ci > 0) sb.append(",")
                    sb.append(candidateToJsonString(c))
                }
                sb.append("]")
            }
            sb.append("}")
        }
        sb.append("],\"turnContext\":{")
        var firstCtx = true
        if (ctx.lastTargetApp != null) {
            sb.append("\"lastTargetApp\":\"").append(escapeJson(ctx.lastTargetApp)).append("\"")
            firstCtx = false
        }
        if (ctx.lastSearchQuery != null) {
            if (!firstCtx) sb.append(",")
            sb.append("\"lastSearchQuery\":\"").append(escapeJson(ctx.lastSearchQuery)).append("\"")
            firstCtx = false
        }
        if (ctx.lastAiPrompt != null) {
            if (!firstCtx) sb.append(",")
            sb.append("\"lastAiPrompt\":\"").append(escapeJson(ctx.lastAiPrompt)).append("\"")
            firstCtx = false
        }
        if (!firstCtx) sb.append(",")
        sb.append("\"timestamp\":").append(ctx.timestamp)
        if (ctx.lastCandidates.isNotEmpty()) {
            sb.append(",\"candidates\":[")
            ctx.lastCandidates.forEachIndexed { ci, c ->
                if (ci > 0) sb.append(",")
                sb.append(candidateToJsonString(c))
            }
            sb.append("]")
        }
        if (ctx.pendingSafetyAction != null) {
            val ps = ctx.pendingSafetyAction
            sb.append(",\"pendingSafetyAction\":{")
                .append("\"label\":\"").append(escapeJson(ps.label)).append("\",")
                .append("\"tapPixelX\":").append(ps.tapPixelX).append(",")
                .append("\"tapPixelY\":").append(ps.tapPixelY).append(",")
                .append("\"gridX\":").append(ps.gridX).append(",")
                .append("\"gridY\":").append(ps.gridY)
            if (ps.targetMarkId != null) {
                sb.append(",\"targetMarkId\":").append(ps.targetMarkId)
            }
            if (ps.opcode.isNotEmpty()) {
                sb.append(",\"opcode\":\"").append(escapeJson(ps.opcode)).append("\"")
            }
            if (ps.packageName != null) {
                sb.append(",\"packageName\":\"").append(escapeJson(ps.packageName)).append("\"")
            }
            sb.append("}")
        }
        sb.append("}}")
        return sb.toString()
    }

    private fun saveToDisk() {
        val ctx = appContext ?: return
        savePending.set(true)
        if (isSaving.compareAndSet(false, true)) {
            ioExecutor.execute {
                try {
                    while (savePending.getAndSet(false)) {
                        // 私人內容（畫面文字、通知、剪貼簿等）絕不寫入磁碟
                        val currentMsgs = getPersistableMessages()
                        val currentCtx = getTurnContext()
                        val dir = appContext?.filesDir ?: ctx.filesDir
                        synchronized(fileLock) {
                            runCatching {
                                val jsonStr = buildJsonString(currentMsgs, currentCtx)
                                if (!dir.exists()) dir.mkdirs()
                                val targetFile = File(dir, FILE_NAME)
                                val tempFile = File(dir, "$FILE_NAME.tmp")
                                tempFile.writeText(jsonStr, Charsets.UTF_8)
                                try {
                                    java.nio.file.Files.move(
                                        tempFile.toPath(),
                                        targetFile.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                                    )
                                } catch (_: Throwable) {
                                    try {
                                        java.nio.file.Files.move(
                                            tempFile.toPath(),
                                            targetFile.toPath(),
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                                        )
                                    } catch (_: Throwable) {
                                        if (!tempFile.renameTo(targetFile)) {
                                            targetFile.delete()
                                            tempFile.renameTo(targetFile)
                                        }
                                    }
                                }
                                Unit
                            }.onFailure { e ->
                                safeLogW(TAG, "Failed to save conversation history: ${e.message}")
                            }
                        }
                    }
                } finally {
                    isSaving.set(false)
                    if (savePending.get()) {
                        saveToDisk()
                    }
                }
            }
        }
    }

    private fun loadFromDisk(ctx: Context) {
        synchronized(fileLock) {
            runCatching {
                val file = File(ctx.filesDir, FILE_NAME)
                if (!file.exists()) return
                val rawText = file.readText(Charsets.UTF_8).trim()
                if (rawText.isBlank()) return

                val loadedMsgs = ArrayList<ChatMessage>()
                var loadedTurnCtx: ConversationTurnContext? = null

                if (rawText.startsWith("[")) {
                    val arr = JSONArray(rawText)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        parseChatMessage(obj)?.let { loadedMsgs.add(it) }
                    }
                } else {
                    val root = JSONObject(rawText)
                    val arr = root.optJSONArray("messages")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            parseChatMessage(obj)?.let { loadedMsgs.add(it) }
                        }
                    }
                    val ctxObj = root.optJSONObject("turnContext")
                    if (ctxObj != null) {
                        loadedTurnCtx = parseTurnContext(ctxObj)
                    }
                }

                if (loadedMsgs.isNotEmpty()) {
                    messages.clear()
                    messages.addAll(loadedMsgs)
                    notifyMessagesUpdated()
                }
                if (loadedTurnCtx != null) {
                    turnContext = loadedTurnCtx
                    notifyTurnContextUpdated()
                }
            }.onFailure { e ->
                safeLogW(TAG, "Failed to load conversation history: ${e.message}")
            }
        }
    }

    private fun parseChatMessage(obj: JSONObject): ChatMessage? {
        val id = obj.optString("id", "")
        val roleStr = obj.optString("role", "")
        val text = obj.optString("text", "")
        if (text.isEmpty() && roleStr.isEmpty()) return null

        val role = try {
            ChatRole.valueOf(roleStr)
        } catch (e: Exception) {
            ChatRole.USER
        }

        val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        val metadataHeader = if (obj.has("metadataHeader")) {
            obj.optString("metadataHeader")
        } else if (obj.has("statusTag")) {
            obj.optString("statusTag")
        } else {
            null
        }
        val isProactive = obj.optBoolean("isProactiveReport", false)
        val execCmd = if (obj.has("executableCommand")) obj.optString("executableCommand").takeIf { it.isNotEmpty() } else null

        val candidates = ArrayList<ScreenCandidateItem>()
        val candArr = obj.optJSONArray("candidates")
        if (candArr != null) {
            for (i in 0 until candArr.length()) {
                val cObj = candArr.optJSONObject(i) ?: continue
                parseCandidate(cObj)?.let { candidates.add(it) }
            }
        }

        return ChatMessage(
            id = if (id.isNotEmpty()) id else UUID.randomUUID().toString(),
            role = role,
            text = text,
            timestamp = timestamp,
            metadataHeader = metadataHeader,
            isProactiveReport = isProactive,
            candidates = candidates,
            executableCommand = execCmd
        )
    }

    private fun parseTurnContext(obj: JSONObject): ConversationTurnContext {
        val lastApp = if (obj.has("lastTargetApp")) obj.optString("lastTargetApp") else null
        val lastQuery = if (obj.has("lastSearchQuery")) obj.optString("lastSearchQuery") else null
        val lastPrompt = if (obj.has("lastAiPrompt")) obj.optString("lastAiPrompt") else null
        val timestamp = obj.optLong("timestamp", System.currentTimeMillis())

        val candidates = ArrayList<ScreenCandidateItem>()
        val candArr = obj.optJSONArray("candidates") ?: obj.optJSONArray("lastCandidates")
        if (candArr != null) {
            for (i in 0 until candArr.length()) {
                val cObj = candArr.optJSONObject(i) ?: continue
                parseCandidate(cObj)?.let { candidates.add(it) }
            }
        }

        val pendingAction = if (obj.has("pendingSafetyAction")) {
            val pObj = obj.optJSONObject("pendingSafetyAction")
            if (pObj != null) {
                PendingSafetyAction(
                    label = pObj.optString("label", ""),
                    targetMarkId = if (pObj.has("targetMarkId")) pObj.optInt("targetMarkId") else null,
                    tapPixelX = pObj.optInt("tapPixelX", 0),
                    tapPixelY = pObj.optInt("tapPixelY", 0),
                    gridX = pObj.optInt("gridX", 0),
                    gridY = pObj.optInt("gridY", 0),
                    opcode = pObj.optString("opcode", ""),
                    packageName = if (pObj.has("packageName")) pObj.optString("packageName") else null
                )
            } else null
        } else null

        return ConversationTurnContext(
            lastTargetApp = lastApp,
            lastSearchQuery = lastQuery,
            lastCandidates = candidates,
            lastAiPrompt = lastPrompt,
            pendingSafetyAction = pendingAction,
            timestamp = timestamp
        )
    }

    private fun candidateToJson(c: ScreenCandidateItem): JSONObject {
        return JSONObject().apply {
            put("index", c.index)
            put("label", c.label)
            c.targetNodeId?.let { put("targetNodeId", it) }
            c.boundsInScreen?.let { r ->
                val rObj = JSONObject().apply {
                    put("left", r.left)
                    put("top", r.top)
                    put("right", r.right)
                    put("bottom", r.bottom)
                }
                put("bounds", rObj)
            }
        }
    }

    private fun parseCandidate(cObj: JSONObject): ScreenCandidateItem? {
        val label = cObj.optString("label", "")
        val index = cObj.optInt("index", 0)
        val targetNodeId = if (cObj.has("targetNodeId")) cObj.optString("targetNodeId") else null
        var rect: Rect? = null
        val rObj = cObj.optJSONObject("bounds") ?: cObj.optJSONObject("boundsInScreen")
        if (rObj != null) {
            rect = Rect(
                rObj.optInt("left", 0),
                rObj.optInt("top", 0),
                rObj.optInt("right", 0),
                rObj.optInt("bottom", 0)
            )
        }
        return ScreenCandidateItem(
            index = index,
            label = label,
            boundsInScreen = rect,
            targetNodeId = targetNodeId
        )
    }
}

