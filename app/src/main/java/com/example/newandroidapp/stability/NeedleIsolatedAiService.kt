package com.example.newandroidapp.stability

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.example.newandroidapp.ai.NeedleNativeBridge
import java.util.concurrent.Executors

/**
 * 【第 5 項】Needle 3 原生 C++ 獨立隔離行程服務 (`android:process=":ai"`)
 *
 * 只有本行程會真正載入 `libneedle_jni.so` 並執行 `needle_embed` / `needle_complete`。
 * 即使底層 C++ 發生 SIGSEGV，也只會讓 `:ai` 行程終止；主程式與無障礙服務由 NeedleIsolatedClient 偵測後等待自動重啟。
 *
 * 【IPC 零拷貝共享記憶體升級】：
 * - 所有高維度 1024D 語義嵌入向量、批次嵌入矩陣、大型 UI 節點樹提示詞與局部裁切影像特徵，
 *   均透過 [IpcSharedMemoryTransport] 以 `SharedMemory` (Ashmem) / `ParcelFileDescriptor` 傳遞，
 *   徹底根除 `TransactionTooLargeException` 與 Binder 雙向序列化拷貝延遲。
 */
class NeedleIsolatedAiService : Service() {

    companion object {
        const val MSG_PING_LOAD = 1
        const val MSG_EMBED_TEXT = 2
        const val MSG_COMPLETE_TEXT = 3
        const val MSG_EMBED_BATCH = 4
        const val MSG_TRANSFER_VISION_PAYLOAD = 5
        const val MSG_REPLY = 100
    }

    private val aiWorker = Executors.newSingleThreadExecutor()

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        val replyTo = msg.replyTo
        val reqId = msg.arg1
        val data = msg.data ?: Bundle()

        when (msg.what) {
            MSG_PING_LOAD -> {
                aiWorker.execute {
                    val ok = runCatching { NeedleNativeBridge.ensureLoaded(applicationContext) }.getOrDefault(false)
                    val out = Bundle().apply {
                        putBoolean("loaded", ok)
                        putString("status", NeedleNativeBridge.getStatusSummary())
                    }
                    sendSafeReply(replyTo, reqId, out)
                }
                true
            }
            MSG_EMBED_TEXT -> {
                aiWorker.execute {
                    var out = Bundle()
                    try {
                        val desc = IpcSharedMemoryTransport.readDescriptorFromBundle(data)
                        val text = if (desc != null) {
                            IpcSharedMemoryTransport.readLargeTextOrNodeTreeFromSharedMemory(desc, autoRelease = true)
                        } else {
                            data.getString("text").orEmpty()
                        }
                        NeedleNativeBridge.ensureLoaded(applicationContext)
                        val vec = runCatching { NeedleNativeBridge.embedText(text) }.getOrDefault(FloatArray(0))
                        out = IpcSharedMemoryTransport.packEmbedReplyToBundle(reqId, vec)
                    } catch (_: Throwable) {
                        IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
                    }
                    sendSafeReply(replyTo, reqId, out)
                }
                true
            }
            MSG_EMBED_BATCH -> {
                aiWorker.execute {
                    var out = Bundle()
                    try {
                        val texts = IpcSharedMemoryTransport.unpackEmbedBatchRequestFromBundle(data, autoRelease = true)
                        NeedleNativeBridge.ensureLoaded(applicationContext)
                        val vectors = texts.map { t ->
                            runCatching { NeedleNativeBridge.embedText(t) }.getOrDefault(FloatArray(0))
                        }
                        out = IpcSharedMemoryTransport.packEmbedBatchReplyToBundle(reqId, vectors)
                    } catch (_: Throwable) {
                        IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
                    }
                    sendSafeReply(replyTo, reqId, out)
                }
                true
            }
            MSG_COMPLETE_TEXT -> {
                aiWorker.execute {
                    var out = Bundle()
                    try {
                        val reqPayload = IpcSharedMemoryTransport.unpackCompleteRequestFromBundle(data, autoRelease = true)
                        NeedleNativeBridge.ensureLoaded(applicationContext)
                        val resp = runCatching {
                            NeedleNativeBridge.completeWithRole(
                                roleId = reqPayload.roleId,
                                systemPrompt = reqPayload.sysPrompt,
                                toolsJson = reqPayload.toolsJson,
                                inputText = reqPayload.userPrompt,
                                maxNewTokens = reqPayload.maxTokens
                            )
                        }.getOrDefault("")
                        out = IpcSharedMemoryTransport.packCompleteReplyToBundle(reqId, resp)
                    } catch (_: Throwable) {
                        IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
                    }
                    sendSafeReply(replyTo, reqId, out)
                }
                true
            }
            MSG_TRANSFER_VISION_PAYLOAD -> {
                aiWorker.execute {
                    val out = Bundle()
                    try {
                        val desc = IpcSharedMemoryTransport.readDescriptorFromBundle(data)
                        if (desc != null) {
                            when (desc.payloadType) {
                                IpcSharedMemoryTransport.PAYLOAD_IMAGE_ROI_PATCH -> {
                                    val (patchBytes, w, h) = IpcSharedMemoryTransport.readImageRoiPatchFromSharedMemory(
                                        descriptor = desc,
                                        autoRelease = true
                                    )
                                    out.putBoolean("ack", patchBytes.isNotEmpty())
                                    out.putInt("receivedBytes", patchBytes.size)
                                    out.putInt("width", w)
                                    out.putInt("height", h)
                                }
                                else -> {
                                    val treeText = IpcSharedMemoryTransport.readLargeTextOrNodeTreeFromSharedMemory(
                                        descriptor = desc,
                                        autoRelease = true
                                    )
                                    out.putBoolean("ack", treeText.isNotEmpty())
                                    out.putInt("receivedBytes", treeText.length)
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
                    }
                    sendSafeReply(replyTo, reqId, out)
                }
                true
            }
            else -> false
        }
    }

    private val messenger = Messenger(incomingHandler)

    override fun onCreate() {
        super.onCreate()
        // 必須最先標記：之後本行程才會真正載入 JNI 並直接執行原生推論
        NeedleNativeBridge.markAsIsolatedAiProcess()
        CrashGuardManager.installGlobalCrashGuard(applicationContext)
        aiWorker.execute {
            runCatching { NeedleNativeBridge.ensureLoaded(applicationContext) }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return messenger.binder
    }

    override fun onDestroy() {
        runCatching { IpcSharedMemoryTransport.purgeAllLeasesOnProcessDeath() }
        runCatching { aiWorker.shutdownNow() }
        super.onDestroy()
    }

    private fun sendSafeReply(replyTo: Messenger?, reqId: Int, bundle: Bundle) {
        if (replyTo == null) {
            IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
            return
        }
        try {
            val reply = Message.obtain(null, MSG_REPLY).apply {
                arg1 = reqId
                data = bundle
            }
            replyTo.send(reply)
        } catch (_: Throwable) {
            IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId)
        }
    }
}
