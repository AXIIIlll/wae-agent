package com.example.newandroidapp.stability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 【第 5 項】主行程 -> 獨立 `:ai` 行程的 Needle 3 呼叫客戶端
 *
 * 主行程（含無障礙服務）完全不載入、不呼叫 Needle 3 原生 C++；
 * 所有 embed / complete 都以 Messenger 送到 `:ai` 行程執行。即使原生層 SIGSEGV，只會讓 `:ai` 行程死亡並自動重啟。
 *
 * 【IPC 共享記憶體零拷貝與防崩潰加固】：
 * - 禁止將高維度語義向量、批次嵌入矩陣、大型 UI 節點樹結構或局部裁切影像資料直接塞入一般 Binder `Bundle`。
 * - 透過 [IpcSharedMemoryTransport] 建立跨進程 `SharedMemory` (Ashmem) 緩衝區，僅透過 IPC 傳遞輕量檔案描述符與偏移量。
 * - 在推論完成、單步超時、觸發 5 秒斷路器熔斷或 `:ai` 行程崩潰斷線時，自動執行確定性的緩衝區回收與 FD 關閉，杜絕 Native 記憶體洩漏。
 */
object NeedleIsolatedClient {

    private const val EMBED_TIMEOUT_MS = 4000L
    private const val COMPLETE_TIMEOUT_MS = 9000L
    private const val LOAD_TIMEOUT_MS = 25000L
    private const val BREAKER_COOLDOWN_MS = 5000L

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var sender: Messenger? = null

    @Volatile
    private var bound = false

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isLoaded: Boolean = false
        private set

    @Volatile
    var statusSummary: String = "隔離 AI 行程尚未連線"
        private set

    val restartCount = AtomicInteger(0)
    val timeoutCount = AtomicInteger(0)

    private val reqSeq = AtomicInteger(1)
    private val consecutiveTimeouts = AtomicInteger(0)

    @Volatile
    private var breakerUntilMs = 0L

    private class PendingReq {
        val latch = CountDownLatch(1)

        @Volatile
        var data: Bundle? = null
    }

    private val pending = ConcurrentHashMap<Int, PendingReq>()

    private val replyThread: HandlerThread by lazy {
        HandlerThread("needle-ai-reply").also { it.start() }
    }

    private val replyMessenger: Messenger by lazy {
        Messenger(Handler(replyThread.looper) { msg ->
            if (msg.what == NeedleIsolatedAiService.MSG_REPLY) {
                val matched = pending.remove(msg.arg1)
                if (matched != null) {
                    matched.data = msg.data ?: Bundle()
                    matched.latch.countDown()
                } else {
                    // 若該請求已因超時被移除，收到遲來的回覆時立即釋放其共享記憶體描述符，防止孤兒 FD 洩漏
                    msg.data?.let { lateBundle ->
                        IpcSharedMemoryTransport.readDescriptorFromBundle(lateBundle)?.let { desc ->
                            IpcSharedMemoryTransport.releaseLeaseById(desc.leaseId)
                        }
                    }
                    IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(msg.arg1)
                }
                true
            } else {
                false
            }
        })
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sender = Messenger(service)
            isConnected = true
            // 在背景執行緒要求 :ai 行程載入 needle3.cact（不阻塞綁定回呼所在的主執行緒）
            Thread {
                val reqId = nextRequestId()
                val reply = callBlockingWithReqId(
                    reqId = reqId,
                    what = NeedleIsolatedAiService.MSG_PING_LOAD,
                    data = Bundle(),
                    timeoutMs = LOAD_TIMEOUT_MS,
                    countAsTimeout = false
                )
                if (reply != null) {
                    isLoaded = reply.getBoolean("loaded", false)
                    statusSummary = reply.getString("status").orEmpty().ifBlank { "隔離行程已連線" }
                } else {
                    isLoaded = false
                    statusSummary = "隔離行程載入逾時"
                }
            }.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleProcessDisconnected()
        }
    }

    /**
     * 當 `:ai` 行程死亡或斷線時，立即喚醒所有等待中閂鎖並強制清除所有飛行中與閒置的共享記憶體緩衝區
     */
    internal fun handleProcessDisconnected() {
        sender = null
        isConnected = false
        isLoaded = false
        statusSummary = "隔離 AI 行程已終止，等待自動重啟..."
        restartCount.incrementAndGet()
        for ((reqId, req) in pending) {
            IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
            req.latch.countDown()
        }
        pending.clear()
        IpcSharedMemoryTransport.purgeAllLeasesOnProcessDeath()
        CrashGuardManager.recordThrowable(
            appCtx,
            "NEEDLE_AI_PROCESS_DIED",
            RuntimeException("Needle 3 隔離行程 (:ai) 終止，主行程不受影響，已釋放共享記憶體並自動重啟")
        )
    }

    fun nextRequestId(): Int = reqSeq.getAndIncrement()

    fun init(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
    }

    fun bind() {
        val ctx = appCtx ?: return
        if (bound) return
        bound = runCatching {
            ctx.bindService(Intent(ctx, NeedleIsolatedAiService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    fun unbind() {
        val ctx = appCtx
        if (bound && ctx != null) {
            runCatching { ctx.unbindService(connection) }
        }
        bound = false
        isConnected = false
        isLoaded = false
        sender = null
        IpcSharedMemoryTransport.purgeAllLeasesOnProcessDeath()
    }

    /** 非阻塞檢查：已連線且模型載入完成才回 true；否則觸發綁定並回 false */
    fun ensureReady(context: Context?): Boolean {
        if (context != null) init(context)
        if (!bound || !isConnected) {
            bind()
            return false
        }
        return isLoaded
    }

    /** 背景執行緒用：等待 :ai 行程連線並載入完成（最多 timeoutMs） */
    fun awaitReady(context: Context?, timeoutMs: Long): Boolean {
        val deadline = safeElapsedRealtime() + timeoutMs
        while (safeElapsedRealtime() < deadline) {
            if (ensureReady(context)) return true
            try {
                Thread.sleep(150L)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return isLoaded
    }

    /**
     * 單句 1024 維嵌入向量計算（回傳向量透過 SharedMemory 零拷貝讀取，並在讀取後自動歸還緩衝區至重用池）
     */
    fun embed(text: String): FloatArray {
        if (!isLoaded || isBreakerOpen()) return FloatArray(0)
        val reqId = nextRequestId()
        try {
            val data = Bundle()
            val estBytes = IpcSharedMemoryTransport.estimateUtf8Bytes(text)
            if (IpcSharedMemoryTransport.shouldUseSharedMemory(estBytes, isHighDimVectorOrImageOrTree = false)) {
                val desc = IpcSharedMemoryTransport.packLargeTextOrNodeTreeToSharedMemory(
                    reqId = reqId,
                    text = text,
                    payloadType = IpcSharedMemoryTransport.PAYLOAD_UTF8_TEXT
                )
                IpcSharedMemoryTransport.writeDescriptorToBundle(data, desc)
            } else {
                runCatching { data.putString("text", text) }
            }
            val reply = callBlockingWithReqId(reqId, NeedleIsolatedAiService.MSG_EMBED_TEXT, data, EMBED_TIMEOUT_MS)
                ?: return FloatArray(0)
            return IpcSharedMemoryTransport.unpackEmbedReplyFromBundle(reply, autoRelease = true)
        } finally {
            IpcSharedMemoryTransport.releaseLeasesForRequest(reqId)
        }
    }

    /**
     * 批次 1024 維嵌入向量計算：
     * 將多筆候選節點文字一次性封裝進單一 SharedMemory 緩衝區送往 `:ai` 行程，
     * 並從回傳的 SharedMemory 零拷貝讀取完整 `[N x 1024]` 向量矩陣，消除逐筆跨進程序列化延遲。
     */
    fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty() || !isLoaded || isBreakerOpen()) return emptyList()
        val reqId = nextRequestId()
        try {
            val data = IpcSharedMemoryTransport.packEmbedBatchRequestToBundle(reqId, texts)
            val reply = callBlockingWithReqId(reqId, NeedleIsolatedAiService.MSG_EMBED_BATCH, data, EMBED_TIMEOUT_MS)
                ?: return emptyList()
            return IpcSharedMemoryTransport.unpackEmbedBatchReplyFromBundle(reply, autoRelease = true)
        } finally {
            IpcSharedMemoryTransport.releaseLeasesForRequest(reqId)
        }
    }

    /**
     * 跨進程語言生成與 Tool-Call 推理：
     * 當提示詞（含大型 UI 節點樹或工具定義）超過 8 KB 時，自動走 SharedMemory 零拷貝通道，杜絕 `TransactionTooLargeException`。
     */
    fun complete(roleId: String, systemPrompt: String, toolsJson: String, userPrompt: String, maxTokens: Int): String {
        if (!isLoaded || isBreakerOpen()) return ""
        val reqId = nextRequestId()
        try {
            val data = IpcSharedMemoryTransport.packCompleteRequestToBundle(
                reqId = reqId,
                roleId = roleId,
                sysPrompt = systemPrompt,
                toolsJson = toolsJson,
                userPrompt = userPrompt,
                maxTokens = maxTokens
            )
            val reply = callBlockingWithReqId(reqId, NeedleIsolatedAiService.MSG_COMPLETE_TEXT, data, COMPLETE_TIMEOUT_MS)
                ?: return ""
            return IpcSharedMemoryTransport.unpackCompleteReplyFromBundle(reply, autoRelease = true)
        } finally {
            IpcSharedMemoryTransport.releaseLeasesForRequest(reqId)
        }
    }

    /**
     * 跨進程傳遞大型 UI 節點樹結構或局部裁切影像特徵 (ROI Patch)：
     * 100% 強制透過 SharedMemory 傳遞輕量 FD 與偏移量，禁止塞入一般 Binder Bundle。
     */
    fun transferImageRoiPatch(patchBytes: ByteArray, width: Int, height: Int): Boolean {
        if (patchBytes.isEmpty() || !isLoaded || isBreakerOpen()) return false
        val reqId = nextRequestId()
        try {
            val desc = IpcSharedMemoryTransport.packImageRoiPatchToSharedMemory(
                reqId = reqId,
                patchBytes = patchBytes,
                width = width,
                height = height
            )
            val data = Bundle()
            IpcSharedMemoryTransport.writeDescriptorToBundle(data, desc)
            val reply = callBlockingWithReqId(
                reqId = reqId,
                what = NeedleIsolatedAiService.MSG_TRANSFER_VISION_PAYLOAD,
                data = data,
                timeoutMs = EMBED_TIMEOUT_MS
            ) ?: return false
            return runCatching { reply.getBoolean("ack", false) }.getOrDefault(false)
        } finally {
            IpcSharedMemoryTransport.releaseLeasesForRequest(reqId)
        }
    }

    fun isBreakerOpen(): Boolean = safeElapsedRealtime() < breakerUntilMs

    private fun safeElapsedRealtime(): Long {
        return try {
            val t = SystemClock.elapsedRealtime()
            if (t > 0L) t else System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    private fun callBlockingWithReqId(
        reqId: Int,
        what: Int,
        data: Bundle,
        timeoutMs: Long,
        countAsTimeout: Boolean = true
    ): Bundle? {
        val s = sender
        if (s == null) {
            IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
            return null
        }
        val isOnMainLooper = runCatching {
            val main = android.os.Looper.getMainLooper()
            main != null && android.os.Looper.myLooper() == main
        }.getOrDefault(false)
        // 防護主執行緒 ANR：若意外於 UI 主執行緒呼叫跨行程 AI，強制縮短等待上限至 180ms 並不計入斷路器超時
        val effectiveTimeoutMs = if (isOnMainLooper) minOf(timeoutMs, 180L) else timeoutMs
        val effectiveCountAsTimeout = if (isOnMainLooper) false else countAsTimeout
        val req = PendingReq()
        pending[reqId] = req
        val msg = runCatching {
            Message.obtain(null, what, reqId, 0).apply {
                this.data = data
                replyTo = replyMessenger
            }
        }.getOrNull()
        if (msg == null) {
            pending.remove(reqId)
            IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
            return null
        }
        try {
            s.send(msg)
        } catch (_: Throwable) {
            pending.remove(reqId)
            IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
            return null
        }
        val finished = try {
            req.latch.await(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!finished || req.data == null) {
            pending.remove(reqId)
            // 超時或熔斷時，立即強制關閉並銷毀此 reqId 關聯的所有共享記憶體緩衝區，防止 Native 記憶體洩漏
            IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
            if (effectiveCountAsTimeout && !finished) {
                timeoutCount.incrementAndGet()
                if (consecutiveTimeouts.incrementAndGet() >= 2) {
                    breakerUntilMs = safeElapsedRealtime() + BREAKER_COOLDOWN_MS
                    consecutiveTimeouts.set(0)
                }
            }
            return null
        }
        consecutiveTimeouts.set(0)
        return req.data
    }
}
