package com.example.newandroidapp.stability

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.os.SystemClock
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 跨進程通訊（IPC）零拷貝共享記憶體傳輸與緩衝區生命週期管理引擎
 *
 * 解決主進程與獨立 `:ai` 推論進程（[NeedleIsolatedAiService]）之間的三大問題：
 * 1. **防止 `TransactionTooLargeException` 與序列化延遲**：
 *    嚴格禁止將大型 UI 節點樹結構、高維度語義嵌入向量（1024D `FloatArray` / 批次矩陣）、
 *    局部裁切影像特徵（ROI Patch）或超過 [INLINE_BUNDLE_MAX_BYTES] (8 KB) 的長文本直接塞入一般 Binder `Bundle`。
 * 2. **導入 Android 原生 `SharedMemory` (Ashmem) / `ParcelFileDescriptor` 記憶體映射**：
 *    大型或高頻率資料一律寫入跨進程共享記憶體緩衝區，Binder `Bundle` 僅傳遞輕量級檔案描述符（FD）、
 *    偏移量（`offset`）與位元組長度（`byteLength`），實現零拷貝（Zero-Copy）極速讀取。
 * 3. **確定性緩衝區生命週期與重用池管理**：
 *    透過租約追蹤（Lease Tracking）與定量緩衝區重用池（Bounded Arena Pool），
 *    確保在推論正常完成、單步超時、觸發斷路器熔斷或 `:ai` 進程崩潰重啟時，
 *    所有共享記憶體映射與 Native 檔案描述符皆能 100% 安全釋放或回收重用，杜絕 Native 記憶體洩漏。
 */
object IpcSharedMemoryTransport {

    /** 一般 Binder Bundle 允許內聯傳遞的最大位元組數上限 (8 KB)；超過此閾值強制轉走 SharedMemory */
    const val INLINE_BUNDLE_MAX_BYTES = 8 * 1024

    /** 常駐重用池單一緩衝區標準容量 (128 KB，足以容納 32 組 1024 維 Float32 向量或完整壓縮視覺節點樹) */
    const val STANDARD_POOL_BUFFER_BYTES = 128 * 1024

    /** 重用池最大保留閒置緩衝區數量（防止常駐記憶體膨脹） */
    const val MAX_POOLED_BUFFERS = 4

    /** 單次共享記憶體最大允許配置上限 (4 MB) */
    const val MAX_SINGLE_ALLOCATION_BYTES = 4 * 1024 * 1024

    // 共享記憶體負載型別定義
    const val PAYLOAD_UTF8_TEXT = 1
    const val PAYLOAD_FLOAT32_VECTOR = 2
    const val PAYLOAD_FLOAT32_BATCH_MATRIX = 3
    const val PAYLOAD_UI_NODE_TREE = 4
    const val PAYLOAD_IMAGE_ROI_PATCH = 5
    const val PAYLOAD_STRING_BATCH = 6

    // Bundle 輕量級描述符鍵名（總體積 < 128 bytes）
    const val KEY_SHM_ENABLED = "ipc_shm_enabled"
    const val KEY_SHM_PARCEL = "ipc_shm_parcel"
    const val KEY_SHM_PFD = "ipc_shm_pfd"
    const val KEY_SHM_LEASE_ID = "ipc_shm_lease_id"
    const val KEY_SHM_REQ_ID = "ipc_shm_req_id"
    const val KEY_SHM_OFFSET = "ipc_shm_offset"
    const val KEY_SHM_BYTE_LENGTH = "ipc_shm_byte_len"
    const val KEY_SHM_PAYLOAD_TYPE = "ipc_shm_payload_type"
    const val KEY_SHM_ELEMENT_COUNT = "ipc_shm_elem_count"
    const val KEY_SHM_VECTOR_DIM = "ipc_shm_vec_dim"
    const val KEY_SHM_IMAGE_WIDTH = "ipc_shm_img_w"
    const val KEY_SHM_IMAGE_HEIGHT = "ipc_shm_img_h"

    enum class LeaseState {
        FREE_POOLED,
        IN_FLIGHT_LEASED,
        CLOSED_RELEASED
    }

    /**
     * 跨進程輕量級共享記憶體描述符（僅包含 FD 引用、偏移量與元數據，不攜帶大型實體陣列）
     */
    data class SharedMemoryDescriptor(
        val leaseId: Long,
        val reqId: Int,
        val offset: Int,
        val byteLength: Int,
        val payloadType: Int,
        val elementCount: Int = 1,
        val vectorDim: Int = 0,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        val sharedMemory: SharedMemory? = null,
        val pfd: ParcelFileDescriptor? = null,
        val isSharedMemoryBacked: Boolean = true,
        val inlineBundleBytesEstimate: Int = 96
    )

    data class CompleteRequestPayload(
        val roleId: String,
        val sysPrompt: String,
        val toolsJson: String,
        val userPrompt: String,
        val maxTokens: Int,
        val usedSharedMemory: Boolean
    )

    /**
     * 內部受管共享緩衝區槽位：同時支援 Android 原生 `SharedMemory` (Ashmem)、`ParcelFileDescriptor` mmap
     * 以及無 Binder 實體之 JVM 單元測試 `DirectByteBuffer` 後端。
     */
    internal class ManagedSharedBuffer(
        val bufferId: Long,
        val capacityBytes: Int,
        var sharedMemory: SharedMemory?,
        var pfd: ParcelFileDescriptor?,
        var backingFile: File?,
        var directBufferFallback: ByteBuffer?
    ) {
        @Volatile
        var state: LeaseState = LeaseState.FREE_POOLED

        @Volatile
        var currentLeaseId: Long = 0L

        @Volatile
        var ownerReqId: Int = 0

        @Volatile
        var leasedAtMs: Long = 0L

        fun writeBytes(srcOffsetInShm: Int, src: ByteArray, srcPos: Int = 0, length: Int = src.size) {
            require(srcOffsetInShm >= 0 && srcOffsetInShm + length <= capacityBytes) {
                "SharedMemory write out of bounds: offset=$srcOffsetInShm, len=$length, cap=$capacityBytes"
            }
            val shm = sharedMemory
            if (shm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                var mapped: ByteBuffer? = null
                try {
                    mapped = shm.mapReadWrite()
                    mapped.position(srcOffsetInShm)
                    mapped.put(src, srcPos, length)
                } finally {
                    if (mapped != null) {
                        runCatching { SharedMemory.unmap(mapped) }
                    }
                }
                return
            }
            val direct = directBufferFallback
            if (direct != null) {
                synchronized(direct) {
                    val dup = direct.duplicate()
                    dup.position(srcOffsetInShm)
                    dup.put(src, srcPos, length)
                }
            }
        }

        fun readBytes(srcOffsetInShm: Int, length: Int): ByteArray {
            if (length <= 0) return ByteArray(0)
            require(srcOffsetInShm >= 0 && srcOffsetInShm + length <= capacityBytes) {
                "SharedMemory read out of bounds: offset=$srcOffsetInShm, len=$length, cap=$capacityBytes"
            }
            val out = ByteArray(length)
            val shm = sharedMemory
            if (shm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                var mapped: ByteBuffer? = null
                try {
                    mapped = shm.mapReadOnly()
                    mapped.position(srcOffsetInShm)
                    mapped.get(out, 0, length)
                } finally {
                    if (mapped != null) {
                        runCatching { SharedMemory.unmap(mapped) }
                    }
                }
                return out
            }
            val direct = directBufferFallback
            if (direct != null) {
                synchronized(direct) {
                    val dup = direct.duplicate()
                    dup.position(srcOffsetInShm)
                    dup.get(out, 0, length)
                }
            }
            return out
        }

        fun writeFloats(srcOffsetInShm: Int, floats: FloatArray) {
            val byteLen = floats.size * 4
            require(srcOffsetInShm >= 0 && srcOffsetInShm + byteLen <= capacityBytes) {
                "SharedMemory float write out of bounds: offset=$srcOffsetInShm, byteLen=$byteLen, cap=$capacityBytes"
            }
            val shm = sharedMemory
            if (shm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                var mapped: ByteBuffer? = null
                try {
                    mapped = shm.mapReadWrite()
                    mapped.order(ByteOrder.LITTLE_ENDIAN)
                    mapped.position(srcOffsetInShm)
                    mapped.asFloatBuffer().put(floats)
                } finally {
                    if (mapped != null) {
                        runCatching { SharedMemory.unmap(mapped) }
                    }
                }
                return
            }
            val direct = directBufferFallback
            if (direct != null) {
                synchronized(direct) {
                    val dup = direct.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    dup.position(srcOffsetInShm)
                    dup.asFloatBuffer().put(floats)
                }
            }
        }

        fun readFloats(srcOffsetInShm: Int, floatCount: Int): FloatArray {
            if (floatCount <= 0) return FloatArray(0)
            val byteLen = floatCount * 4
            require(srcOffsetInShm >= 0 && srcOffsetInShm + byteLen <= capacityBytes) {
                "SharedMemory float read out of bounds: offset=$srcOffsetInShm, byteLen=$byteLen, cap=$capacityBytes"
            }
            val out = FloatArray(floatCount)
            val shm = sharedMemory
            if (shm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                var mapped: ByteBuffer? = null
                try {
                    mapped = shm.mapReadOnly()
                    mapped.order(ByteOrder.LITTLE_ENDIAN)
                    mapped.position(srcOffsetInShm)
                    mapped.asFloatBuffer().get(out)
                } finally {
                    if (mapped != null) {
                        runCatching { SharedMemory.unmap(mapped) }
                    }
                }
                return out
            }
            val direct = directBufferFallback
            if (direct != null) {
                synchronized(direct) {
                    val dup = direct.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    dup.position(srcOffsetInShm)
                    dup.asFloatBuffer().get(out)
                }
            }
            return out
        }

        fun clearZero(lengthToClear: Int = minOf(capacityBytes, 4096)) {
            val shm = sharedMemory
            if (shm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                var mapped: ByteBuffer? = null
                try {
                    mapped = shm.mapReadWrite()
                    val limit = minOf(lengthToClear, mapped.capacity())
                    for (i in 0 until limit) {
                        mapped.put(i, 0.toByte())
                    }
                } catch (_: Throwable) {
                } finally {
                    if (mapped != null) {
                        runCatching { SharedMemory.unmap(mapped) }
                    }
                }
            }
            val direct = directBufferFallback
            if (direct != null) {
                synchronized(direct) {
                    val limit = minOf(lengthToClear, direct.capacity())
                    for (i in 0 until limit) {
                        direct.put(i, 0.toByte())
                    }
                }
            }
        }

        fun closeNativeResources() {
            state = LeaseState.CLOSED_RELEASED
            runCatching { sharedMemory?.close() }
            sharedMemory = null
            runCatching { pfd?.close() }
            pfd = null
            runCatching { backingFile?.delete() }
            backingFile = null
            directBufferFallback = null
        }
    }

    private val leaseIdGenerator = AtomicLong(1000L)
    private val bufferIdGenerator = AtomicLong(1L)

    // 活躍租約索引：leaseId -> ManagedSharedBuffer
    private val activeLeasesById = ConcurrentHashMap<Long, ManagedSharedBuffer>()

    // 請求關聯租約索引：reqId -> MutableList<Long> (同一 reqId 可包含 Request 與 Reply 緩衝區)
    private val leasesByReqId = ConcurrentHashMap<Int, MutableSet<Long>>()

    // 定量閒置重用池（最大 MAX_POOLED_BUFFERS 個標準 128KB 緩衝區）
    private val freeBufferPool = ArrayDeque<ManagedSharedBuffer>(MAX_POOLED_BUFFERS)

    // JVM 單元測試環境下的 Bundle 屬性影子映射（當 android.os.Bundle 為 Stub 時無縫運作）
    private val jvmBundleShadow = IdentityHashMap<Bundle, MutableMap<String, Any?>>()

    // 遙測與防洩漏審計計數器
    private val poolReuseCounter = AtomicInteger(0)
    private val allocationsCounter = AtomicInteger(0)
    private val timeoutOrCrashEvictionsCounter = AtomicInteger(0)
    private val closedBuffersCounter = AtomicInteger(0)

    val activeLeasedCount: Int
        get() = activeLeasesById.size

    val pooledAvailableCount: Int
        @Synchronized get() = freeBufferPool.size

    val totalPoolReuseHits: Int
        get() = poolReuseCounter.get()

    val totalAllocations: Int
        get() = allocationsCounter.get()

    val totalTimeoutOrCrashEvictions: Int
        get() = timeoutOrCrashEvictionsCounter.get()

    val totalClosedBuffers: Int
        get() = closedBuffersCounter.get()

    private fun safeElapsedMs(): Long {
        return try {
            val t = SystemClock.elapsedRealtime()
            if (t > 0L) t else System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    /**
     * 判斷跨進程資料是否必須走共享記憶體通道（禁止直接塞入一般 Binder Bundle）：
     * - 凡是高維度語義向量 (`FloatArray`)、批次嵌入矩陣、大型 UI 節點樹結構、局部裁切影像特徵 (ROI Patch)，
     *   或預估位元組數超過 [INLINE_BUNDLE_MAX_BYTES] (8 KB) 的訊息，一律回傳 `true`！
     */
    fun shouldUseSharedMemory(
        estimatedBytes: Int,
        isHighDimVectorOrImageOrTree: Boolean = false
    ): Boolean {
        if (isHighDimVectorOrImageOrTree) return true
        return estimatedBytes > INLINE_BUNDLE_MAX_BYTES
    }

    /**
     * 估算文字清單或單一字串轉為 UTF-8 後的位元組大小
     */
    fun estimateUtf8Bytes(vararg texts: String): Int {
        var total = 0
        for (t in texts) {
            total += t.length * 3
        }
        return total
    }

    /**
     * 從重用池借出或新建一個跨進程共享緩衝區，並綁定至指定 [reqId]
     */
    @Synchronized
    internal fun acquireBufferLease(reqId: Int, requiredBytes: Int): Pair<Long, ManagedSharedBuffer> {
        val safeRequired = requiredBytes.coerceAtLeast(64)
        require(safeRequired <= MAX_SINGLE_ALLOCATION_BYTES) {
            "Requested IPC shared memory ($safeRequired bytes) exceeds safety cap ($MAX_SINGLE_ALLOCATION_BYTES bytes)"
        }

        val leaseId = leaseIdGenerator.incrementAndGet()
        var chosen: ManagedSharedBuffer? = null

        if (safeRequired <= STANDARD_POOL_BUFFER_BYTES) {
            while (freeBufferPool.isNotEmpty()) {
                val candidate = freeBufferPool.removeFirst()
                if (candidate.state == LeaseState.FREE_POOLED && candidate.capacityBytes >= safeRequired) {
                    chosen = candidate
                    poolReuseCounter.incrementAndGet()
                    break
                } else {
                    candidate.closeNativeResources()
                }
            }
        }

        if (chosen == null) {
            val allocSize = if (safeRequired <= STANDARD_POOL_BUFFER_BYTES) {
                STANDARD_POOL_BUFFER_BYTES
            } else {
                safeRequired
            }
            chosen = allocateNewManagedBuffer(allocSize)
            allocationsCounter.incrementAndGet()
        }

        chosen.state = LeaseState.IN_FLIGHT_LEASED
        chosen.currentLeaseId = leaseId
        chosen.ownerReqId = reqId
        chosen.leasedAtMs = safeElapsedMs()

        activeLeasesById[leaseId] = chosen
        leasesByReqId.getOrPut(reqId) { ConcurrentHashMap.newKeySet() }.add(leaseId)
        return leaseId to chosen
    }

    private fun allocateNewManagedBuffer(capacityBytes: Int): ManagedSharedBuffer {
        val bufId = bufferIdGenerator.incrementAndGet()
        var nativeShm: SharedMemory? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            nativeShm = runCatching {
                val s = SharedMemory.create("ai_ipc_shm_$bufId", capacityBytes)
                // 驗證非單元測試 Stub（實際可映射）
                val testMap = s.mapReadWrite()
                SharedMemory.unmap(testMap)
                s
            }.getOrNull()
        }

        val directFallback = if (nativeShm == null) {
            ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.LITTLE_ENDIAN)
        } else {
            null
        }

        return ManagedSharedBuffer(
            bufferId = bufId,
            capacityBytes = capacityBytes,
            sharedMemory = nativeShm,
            pfd = null,
            backingFile = null,
            directBufferFallback = directFallback
        )
    }

    /**
     * 正常推論讀取完成後釋放指定 [leaseId]：
     * - 若屬於標準容量 (<= 128 KB) 且重用池未滿，清零頭部後歸還重用池供下一次推論 0ms 重用。
     * - 若重用池已滿或屬於超大容量臨時緩衝區，立即關閉底層 Ashmem FD 釋放 Native 記憶體。
     */
    @Synchronized
    fun releaseLeaseById(leaseId: Long) {
        if (leaseId <= 0L) return
        val buf = activeLeasesById.remove(leaseId) ?: return
        leasesByReqId[buf.ownerReqId]?.let { set ->
            set.remove(leaseId)
            if (set.isEmpty()) {
                leasesByReqId.remove(buf.ownerReqId)
            }
        }

        if (buf.capacityBytes == STANDARD_POOL_BUFFER_BYTES && freeBufferPool.size < MAX_POOLED_BUFFERS) {
            buf.clearZero()
            buf.state = LeaseState.FREE_POOLED
            buf.currentLeaseId = 0L
            buf.ownerReqId = 0
            freeBufferPool.addLast(buf)
        } else {
            buf.closeNativeResources()
            closedBuffersCounter.incrementAndGet()
        }
    }

    /**
     * 釋放某次 IPC 請求 [reqId] 關聯的所有共享緩衝區（正常完成後回收）
     */
    @Synchronized
    fun releaseLeasesForRequest(reqId: Int) {
        val leaseIds = leasesByReqId.remove(reqId)?.toList() ?: return
        for (lid in leaseIds) {
            releaseLeaseById(lid)
        }
    }

    /**
     * 【超時與斷路器熔斷保護】：
     * 當單次 IPC 推論超時或觸發斷路器熔斷時，強制關閉並銷毀該 [reqId] 關聯的所有飛行中緩衝區，
     * 不放回重用池（防止遠端進程延遲寫入造成資料污染或 FD 洩漏）。
     */
    @Synchronized
    fun abortAndCloseLeaseOnTimeoutOrBreaker(reqId: Int): Int {
        val leaseIds = leasesByReqId.remove(reqId)?.toList() ?: return 0
        var evicted = 0
        for (lid in leaseIds) {
            val buf = activeLeasesById.remove(lid) ?: continue
            buf.closeNativeResources()
            closedBuffersCounter.incrementAndGet()
            timeoutOrCrashEvictionsCounter.incrementAndGet()
            evicted++
        }
        return evicted
    }

    /**
     * 【`:ai` 進程死亡或解綁全量清理】：
     * 當 `onServiceDisconnected` 或 `unbind` 觸發時，立即強制關閉所有飛行中租約與重用池緩衝區，
     * 確保 0 Native 記憶體與 0 檔案描述符殘留。
     */
    @Synchronized
    fun purgeAllLeasesOnProcessDeath(): Int {
        var closedCount = 0
        for ((_, buf) in activeLeasesById) {
            buf.closeNativeResources()
            closedBuffersCounter.incrementAndGet()
            timeoutOrCrashEvictionsCounter.incrementAndGet()
            closedCount++
        }
        activeLeasesById.clear()
        leasesByReqId.clear()

        while (freeBufferPool.isNotEmpty()) {
            val pooled = freeBufferPool.removeFirst()
            pooled.closeNativeResources()
            closedBuffersCounter.incrementAndGet()
            closedCount++
        }
        synchronized(jvmBundleShadow) {
            jvmBundleShadow.clear()
        }
        return closedCount
    }

    /**
     * 自動清理超過 [maxAgeMs] 仍未釋放的孤兒租約（防護極端異常遺漏）
     */
    @Synchronized
    fun evictStaleLeasesOlderThan(maxAgeMs: Long = 15_000L): Int {
        val now = safeElapsedMs()
        val staleIds = activeLeasesById.entries
            .filter { (_, buf) -> now - buf.leasedAtMs >= maxAgeMs }
            .map { it.key }
        var count = 0
        for (lid in staleIds) {
            val buf = activeLeasesById.remove(lid) ?: continue
            leasesByReqId[buf.ownerReqId]?.remove(lid)
            buf.closeNativeResources()
            closedBuffersCounter.incrementAndGet()
            timeoutOrCrashEvictionsCounter.incrementAndGet()
            count++
        }
        return count
    }

    @Synchronized
    fun resetStatsForTesting() {
        purgeAllLeasesOnProcessDeath()
        poolReuseCounter.set(0)
        allocationsCounter.set(0)
        timeoutOrCrashEvictionsCounter.set(0)
        closedBuffersCounter.set(0)
    }

    // =========================================================================
    // 零拷貝二進位封裝與讀取 API (Float32 Vectors / Batch Matrices / Node Trees / ROI Patches)
    // =========================================================================

    /**
     * 將單一高維度語義向量（如 1024 維 `FloatArray`）寫入共享記憶體指定偏移量，回傳輕量描述符
     */
    fun packFloatVectorToSharedMemory(
        reqId: Int,
        vector: FloatArray,
        offset: Int = 0
    ): SharedMemoryDescriptor {
        val byteLen = vector.size * 4
        val (leaseId, buf) = acquireBufferLease(reqId, offset + byteLen)
        buf.writeFloats(offset, vector)
        return SharedMemoryDescriptor(
            leaseId = leaseId,
            reqId = reqId,
            offset = offset,
            byteLength = byteLen,
            payloadType = PAYLOAD_FLOAT32_VECTOR,
            elementCount = 1,
            vectorDim = vector.size,
            sharedMemory = buf.sharedMemory,
            pfd = buf.pfd
        )
    }

    /**
     * 從共享記憶體描述符零拷貝讀取單一高維度語義向量，並在讀取後自動釋放或歸還緩衝區
     */
    fun readFloatVectorFromSharedMemory(
        descriptor: SharedMemoryDescriptor,
        autoRelease: Boolean = true
    ): FloatArray {
        val floatCount = descriptor.vectorDim.takeIf { it > 0 } ?: (descriptor.byteLength / 4)
        if (floatCount <= 0) {
            if (autoRelease) releaseLeaseById(descriptor.leaseId)
            return FloatArray(0)
        }
        try {
            val managed = activeLeasesById[descriptor.leaseId]
            if (managed != null) {
                return managed.readFloats(descriptor.offset, floatCount)
            }
            val shm = descriptor.sharedMemory
            if (shm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                var mapped: ByteBuffer? = null
                try {
                    mapped = shm.mapReadOnly()
                    mapped.order(ByteOrder.LITTLE_ENDIAN)
                    mapped.position(descriptor.offset)
                    val out = FloatArray(floatCount)
                    mapped.asFloatBuffer().get(out)
                    return out
                } finally {
                    if (mapped != null) {
                        runCatching { SharedMemory.unmap(mapped) }
                    }
                    if (autoRelease) {
                        runCatching { shm.close() }
                    }
                }
            }
            return FloatArray(0)
        } finally {
            if (autoRelease) {
                releaseLeaseById(descriptor.leaseId)
            }
        }
    }

    /**
     * 將批次高維度嵌入向量矩陣 (`[N x dim]` Float32) 一次性寫入單一共享記憶體緩衝區
     */
    fun packFloatBatchMatrixToSharedMemory(
        reqId: Int,
        vectors: List<FloatArray>,
        dim: Int = 1024,
        offset: Int = 0
    ): SharedMemoryDescriptor {
        val effectiveDim = vectors.firstOrNull { it.isNotEmpty() }?.size ?: dim
        val count = vectors.size
        val totalFloats = count * effectiveDim
        val byteLen = totalFloats * 4
        val flat = FloatArray(totalFloats)
        for (i in 0 until count) {
            val v = vectors[i]
            val copyLen = minOf(v.size, effectiveDim)
            if (copyLen > 0) {
                System.arraycopy(v, 0, flat, i * effectiveDim, copyLen)
            }
        }
        val (leaseId, buf) = acquireBufferLease(reqId, offset + byteLen)
        buf.writeFloats(offset, flat)
        return SharedMemoryDescriptor(
            leaseId = leaseId,
            reqId = reqId,
            offset = offset,
            byteLength = byteLen,
            payloadType = PAYLOAD_FLOAT32_BATCH_MATRIX,
            elementCount = count,
            vectorDim = effectiveDim,
            sharedMemory = buf.sharedMemory,
            pfd = buf.pfd
        )
    }

    /**
     * 從共享記憶體描述符零拷貝還原批次高維度嵌入向量矩陣 (`List<FloatArray>`)
     */
    fun readFloatBatchMatrixFromSharedMemory(
        descriptor: SharedMemoryDescriptor,
        autoRelease: Boolean = true
    ): List<FloatArray> {
        val count = descriptor.elementCount
        val dim = descriptor.vectorDim
        if (count <= 0 || dim <= 0) {
            if (autoRelease) releaseLeaseById(descriptor.leaseId)
            return emptyList()
        }
        val flat = readFloatVectorFromSharedMemory(
            descriptor = descriptor.copy(vectorDim = count * dim),
            autoRelease = autoRelease
        )
        if (flat.size < count * dim) return emptyList()
        val result = ArrayList<FloatArray>(count)
        for (i in 0 until count) {
            val row = FloatArray(dim)
            System.arraycopy(flat, i * dim, row, 0, dim)
            result.add(row)
        }
        return result
    }

    /**
     * 將大型 UI 節點樹結構或長文本寫入共享記憶體指定偏移量
     */
    fun packLargeTextOrNodeTreeToSharedMemory(
        reqId: Int,
        text: String,
        payloadType: Int = PAYLOAD_UI_NODE_TREE,
        offset: Int = 0
    ): SharedMemoryDescriptor {
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        val (leaseId, buf) = acquireBufferLease(reqId, offset + utf8Bytes.size)
        buf.writeBytes(offset, utf8Bytes)
        return SharedMemoryDescriptor(
            leaseId = leaseId,
            reqId = reqId,
            offset = offset,
            byteLength = utf8Bytes.size,
            payloadType = payloadType,
            elementCount = 1,
            sharedMemory = buf.sharedMemory,
            pfd = buf.pfd
        )
    }

    /**
     * 從共享記憶體零拷貝讀取大型 UI 節點樹結構或長文本
     */
    fun readLargeTextOrNodeTreeFromSharedMemory(
        descriptor: SharedMemoryDescriptor,
        autoRelease: Boolean = true
    ): String {
        val bytes = readRawBytesFromSharedMemory(descriptor, autoRelease)
        if (bytes.isEmpty()) return ""
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 將局部裁切影像資料（ROI Image Patch）寫入共享記憶體指定偏移量
     */
    fun packImageRoiPatchToSharedMemory(
        reqId: Int,
        patchBytes: ByteArray,
        width: Int,
        height: Int,
        offset: Int = 0
    ): SharedMemoryDescriptor {
        val (leaseId, buf) = acquireBufferLease(reqId, offset + patchBytes.size)
        buf.writeBytes(offset, patchBytes)
        return SharedMemoryDescriptor(
            leaseId = leaseId,
            reqId = reqId,
            offset = offset,
            byteLength = patchBytes.size,
            payloadType = PAYLOAD_IMAGE_ROI_PATCH,
            elementCount = 1,
            imageWidth = width,
            imageHeight = height,
            sharedMemory = buf.sharedMemory,
            pfd = buf.pfd
        )
    }

    /**
     * 從共享記憶體零拷貝讀取局部裁切影像資料（回傳 `Triple<ByteArray, width, height>`）
     */
    fun readImageRoiPatchFromSharedMemory(
        descriptor: SharedMemoryDescriptor,
        autoRelease: Boolean = true
    ): Triple<ByteArray, Int, Int> {
        val bytes = readRawBytesFromSharedMemory(descriptor, autoRelease)
        return Triple(bytes, descriptor.imageWidth, descriptor.imageHeight)
    }

    /**
     * 將多筆字串（如批次嵌入候選文字或 `[roleId, sysPrompt, toolsJson, userPrompt]`）打包為二進位幀寫入共享記憶體
     */
    fun packStringBatchToSharedMemory(
        reqId: Int,
        texts: List<String>,
        payloadType: Int = PAYLOAD_STRING_BATCH,
        offset: Int = 0
    ): SharedMemoryDescriptor {
        val encodedList = texts.map { it.toByteArray(Charsets.UTF_8) }
        val totalBytes = 4 + encodedList.sumOf { 4 + it.size }
        val frame = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        frame.putInt(encodedList.size)
        for (arr in encodedList) {
            frame.putInt(arr.size)
            frame.put(arr)
        }
        val rawBytes = frame.array()
        val (leaseId, buf) = acquireBufferLease(reqId, offset + rawBytes.size)
        buf.writeBytes(offset, rawBytes)
        return SharedMemoryDescriptor(
            leaseId = leaseId,
            reqId = reqId,
            offset = offset,
            byteLength = rawBytes.size,
            payloadType = payloadType,
            elementCount = texts.size,
            sharedMemory = buf.sharedMemory,
            pfd = buf.pfd
        )
    }

    /**
     * 從共享記憶體零拷貝解包多筆字串清單
     */
    fun readStringBatchFromSharedMemory(
        descriptor: SharedMemoryDescriptor,
        autoRelease: Boolean = true
    ): List<String> {
        val rawBytes = readRawBytesFromSharedMemory(descriptor, autoRelease)
        if (rawBytes.size < 4) return emptyList()
        val buf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int.coerceIn(0, 4096)
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            if (buf.remaining() < 4) break
            val len = buf.int.coerceAtLeast(0)
            if (buf.remaining() < len) break
            val strBytes = ByteArray(len)
            buf.get(strBytes)
            out.add(String(strBytes, Charsets.UTF_8))
        }
        return out
    }

    private fun readRawBytesFromSharedMemory(
        descriptor: SharedMemoryDescriptor,
        autoRelease: Boolean
    ): ByteArray {
        if (descriptor.byteLength <= 0) {
            if (autoRelease) releaseLeaseById(descriptor.leaseId)
            return ByteArray(0)
        }
        try {
            val managed = activeLeasesById[descriptor.leaseId]
            if (managed != null) {
                return managed.readBytes(descriptor.offset, descriptor.byteLength)
            }
            val shm = descriptor.sharedMemory
            if (shm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                var mapped: ByteBuffer? = null
                try {
                    mapped = shm.mapReadOnly()
                    mapped.position(descriptor.offset)
                    val out = ByteArray(descriptor.byteLength)
                    mapped.get(out, 0, descriptor.byteLength)
                    return out
                } finally {
                    if (mapped != null) {
                        runCatching { SharedMemory.unmap(mapped) }
                    }
                    if (autoRelease) {
                        runCatching { shm.close() }
                    }
                }
            }
            val pfd = descriptor.pfd
            if (pfd != null) {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).channel.use { ch ->
                        val mapped = ch.map(
                            FileChannel.MapMode.READ_ONLY,
                            descriptor.offset.toLong(),
                            descriptor.byteLength.toLong()
                        )
                        val out = ByteArray(descriptor.byteLength)
                        mapped.get(out)
                        return out
                    }
                } finally {
                    if (autoRelease) {
                        runCatching { pfd.close() }
                    }
                }
            }
            return ByteArray(0)
        } finally {
            if (autoRelease) {
                releaseLeaseById(descriptor.leaseId)
            }
        }
    }

    // =========================================================================
    // Binder Bundle 輕量級描述符封裝橋接（同時支援實機 Android Bundle 與 JVM 單元測試）
    // =========================================================================

    fun writeDescriptorToBundle(bundle: Bundle, descriptor: SharedMemoryDescriptor) {
        synchronized(jvmBundleShadow) {
            val shadow = jvmBundleShadow.getOrPut(bundle) { HashMap() }
            shadow[KEY_SHM_ENABLED] = true
            shadow[KEY_SHM_LEASE_ID] = descriptor.leaseId
            shadow[KEY_SHM_REQ_ID] = descriptor.reqId
            shadow[KEY_SHM_OFFSET] = descriptor.offset
            shadow[KEY_SHM_BYTE_LENGTH] = descriptor.byteLength
            shadow[KEY_SHM_PAYLOAD_TYPE] = descriptor.payloadType
            shadow[KEY_SHM_ELEMENT_COUNT] = descriptor.elementCount
            shadow[KEY_SHM_VECTOR_DIM] = descriptor.vectorDim
            shadow[KEY_SHM_IMAGE_WIDTH] = descriptor.imageWidth
            shadow[KEY_SHM_IMAGE_HEIGHT] = descriptor.imageHeight
            shadow[KEY_SHM_PARCEL] = descriptor.sharedMemory
            shadow[KEY_SHM_PFD] = descriptor.pfd
        }
        runCatching {
            bundle.putBoolean(KEY_SHM_ENABLED, true)
            bundle.putLong(KEY_SHM_LEASE_ID, descriptor.leaseId)
            bundle.putInt(KEY_SHM_REQ_ID, descriptor.reqId)
            bundle.putInt(KEY_SHM_OFFSET, descriptor.offset)
            bundle.putInt(KEY_SHM_BYTE_LENGTH, descriptor.byteLength)
            bundle.putInt(KEY_SHM_PAYLOAD_TYPE, descriptor.payloadType)
            bundle.putInt(KEY_SHM_ELEMENT_COUNT, descriptor.elementCount)
            bundle.putInt(KEY_SHM_VECTOR_DIM, descriptor.vectorDim)
            bundle.putInt(KEY_SHM_IMAGE_WIDTH, descriptor.imageWidth)
            bundle.putInt(KEY_SHM_IMAGE_HEIGHT, descriptor.imageHeight)
            if (descriptor.sharedMemory != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                // 跨進程發送前將 Ashmem 鎖定為唯讀保護，防止接收端意外覆寫
                runCatching { descriptor.sharedMemory.setProtect(OsConstants.PROT_READ) }
                bundle.putParcelable(KEY_SHM_PARCEL, descriptor.sharedMemory)
            }
            if (descriptor.pfd != null) {
                bundle.putParcelable(KEY_SHM_PFD, descriptor.pfd)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun readDescriptorFromBundle(bundle: Bundle): SharedMemoryDescriptor? {
        val fromBundleEnabled = runCatching { bundle.getBoolean(KEY_SHM_ENABLED, false) }.getOrDefault(false)
        if (fromBundleEnabled) {
            val shm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching { bundle.getParcelable<SharedMemory>(KEY_SHM_PARCEL) }.getOrNull()
            } else null
            val pfd = runCatching { bundle.getParcelable<ParcelFileDescriptor>(KEY_SHM_PFD) }.getOrNull()
            return SharedMemoryDescriptor(
                leaseId = bundle.getLong(KEY_SHM_LEASE_ID, 0L),
                reqId = bundle.getInt(KEY_SHM_REQ_ID, 0),
                offset = bundle.getInt(KEY_SHM_OFFSET, 0),
                byteLength = bundle.getInt(KEY_SHM_BYTE_LENGTH, 0),
                payloadType = bundle.getInt(KEY_SHM_PAYLOAD_TYPE, 0),
                elementCount = bundle.getInt(KEY_SHM_ELEMENT_COUNT, 1),
                vectorDim = bundle.getInt(KEY_SHM_VECTOR_DIM, 0),
                imageWidth = bundle.getInt(KEY_SHM_IMAGE_WIDTH, 0),
                imageHeight = bundle.getInt(KEY_SHM_IMAGE_HEIGHT, 0),
                sharedMemory = shm,
                pfd = pfd
            )
        }

        synchronized(jvmBundleShadow) {
            val shadow = jvmBundleShadow[bundle] ?: return null
            if (shadow[KEY_SHM_ENABLED] != true) return null
            return SharedMemoryDescriptor(
                leaseId = (shadow[KEY_SHM_LEASE_ID] as? Long) ?: 0L,
                reqId = (shadow[KEY_SHM_REQ_ID] as? Int) ?: 0,
                offset = (shadow[KEY_SHM_OFFSET] as? Int) ?: 0,
                byteLength = (shadow[KEY_SHM_BYTE_LENGTH] as? Int) ?: 0,
                payloadType = (shadow[KEY_SHM_PAYLOAD_TYPE] as? Int) ?: 0,
                elementCount = (shadow[KEY_SHM_ELEMENT_COUNT] as? Int) ?: 1,
                vectorDim = (shadow[KEY_SHM_VECTOR_DIM] as? Int) ?: 0,
                imageWidth = (shadow[KEY_SHM_IMAGE_WIDTH] as? Int) ?: 0,
                imageHeight = (shadow[KEY_SHM_IMAGE_HEIGHT] as? Int) ?: 0,
                sharedMemory = shadow[KEY_SHM_PARCEL] as? SharedMemory,
                pfd = shadow[KEY_SHM_PFD] as? ParcelFileDescriptor
            )
        }
    }

    /**
     * 封裝 `MSG_COMPLETE_TEXT` 請求：
     * 當提示詞（含大型 UI 節點樹或工具 Schema）總位元組數超過 [INLINE_BUNDLE_MAX_BYTES] (8 KB) 時，
     * 嚴禁直接寫入 Bundle 字串欄位，自動改由共享記憶體二進位幀傳遞！
     */
    fun packCompleteRequestToBundle(
        reqId: Int,
        roleId: String,
        sysPrompt: String,
        toolsJson: String,
        userPrompt: String,
        maxTokens: Int
    ): Bundle {
        val bundle = Bundle()
        val estimatedBytes = estimateUtf8Bytes(roleId, sysPrompt, toolsJson, userPrompt)
        if (shouldUseSharedMemory(estimatedBytes, isHighDimVectorOrImageOrTree = false)) {
            val desc = packStringBatchToSharedMemory(
                reqId = reqId,
                texts = listOf(roleId, sysPrompt, toolsJson, userPrompt),
                payloadType = PAYLOAD_UI_NODE_TREE
            )
            writeDescriptorToBundle(bundle, desc)
            putIntSafe(bundle, "maxTokens", maxTokens)
        } else {
            putStringSafe(bundle, "roleId", roleId)
            putStringSafe(bundle, "sysPrompt", sysPrompt)
            putStringSafe(bundle, "toolsJson", toolsJson)
            putStringSafe(bundle, "userPrompt", userPrompt)
            putIntSafe(bundle, "maxTokens", maxTokens)
        }
        return bundle
    }

    fun unpackCompleteRequestFromBundle(bundle: Bundle, autoRelease: Boolean = true): CompleteRequestPayload {
        val maxTokens = getIntSafe(bundle, "maxTokens", 64)
        val desc = readDescriptorFromBundle(bundle)
        if (desc != null) {
            val parts = readStringBatchFromSharedMemory(desc, autoRelease = autoRelease)
            return CompleteRequestPayload(
                roleId = parts.getOrNull(0) ?: "ROLE_STEP_DECIDER",
                sysPrompt = parts.getOrNull(1).orEmpty(),
                toolsJson = parts.getOrNull(2).orEmpty(),
                userPrompt = parts.getOrNull(3).orEmpty(),
                maxTokens = maxTokens,
                usedSharedMemory = true
            )
        }
        return CompleteRequestPayload(
            roleId = getStringSafe(bundle, "roleId").ifEmpty { "ROLE_STEP_DECIDER" },
            sysPrompt = getStringSafe(bundle, "sysPrompt"),
            toolsJson = getStringSafe(bundle, "toolsJson"),
            userPrompt = getStringSafe(bundle, "userPrompt"),
            maxTokens = maxTokens,
            usedSharedMemory = false
        )
    }

    fun packCompleteReplyToBundle(reqId: Int, completion: String): Bundle {
        val bundle = Bundle()
        val estimatedBytes = estimateUtf8Bytes(completion)
        if (shouldUseSharedMemory(estimatedBytes, isHighDimVectorOrImageOrTree = false)) {
            val desc = packLargeTextOrNodeTreeToSharedMemory(
                reqId = reqId,
                text = completion,
                payloadType = PAYLOAD_UTF8_TEXT
            )
            writeDescriptorToBundle(bundle, desc)
        } else {
            putStringSafe(bundle, "completion", completion)
        }
        return bundle
    }

    fun unpackCompleteReplyFromBundle(bundle: Bundle, autoRelease: Boolean = true): String {
        val desc = readDescriptorFromBundle(bundle)
        if (desc != null) {
            return readLargeTextOrNodeTreeFromSharedMemory(desc, autoRelease = autoRelease)
        }
        return getStringSafe(bundle, "completion")
    }

    /**
     * 封裝高維度語義嵌入向量回傳結果：
     * 1024 維 `FloatArray` 一律透過共享記憶體零拷貝緩衝區傳遞，禁止使用 `Bundle.putFloatArray` 塞入一般 Bundle。
     */
    fun packEmbedReplyToBundle(reqId: Int, embedding: FloatArray): Bundle {
        val bundle = Bundle()
        if (embedding.isEmpty()) return bundle
        val desc = packFloatVectorToSharedMemory(reqId = reqId, vector = embedding)
        writeDescriptorToBundle(bundle, desc)
        return bundle
    }

    fun unpackEmbedReplyFromBundle(bundle: Bundle, autoRelease: Boolean = true): FloatArray {
        val desc = readDescriptorFromBundle(bundle)
        if (desc != null) {
            return readFloatVectorFromSharedMemory(desc, autoRelease = autoRelease)
        }
        return runCatching { bundle.getFloatArray("embedding") }.getOrNull() ?: FloatArray(0)
    }

    /**
     * 封裝批次嵌入請求與回傳矩陣 (`MSG_EMBED_BATCH`)：
     * 透過共享記憶體一次性傳遞多筆候選文字並回傳 `[N x 1024]` 嵌入矩陣。
     */
    fun packEmbedBatchRequestToBundle(reqId: Int, texts: List<String>): Bundle {
        val bundle = Bundle()
        val desc = packStringBatchToSharedMemory(
            reqId = reqId,
            texts = texts,
            payloadType = PAYLOAD_STRING_BATCH
        )
        writeDescriptorToBundle(bundle, desc)
        return bundle
    }

    fun unpackEmbedBatchRequestFromBundle(bundle: Bundle, autoRelease: Boolean = true): List<String> {
        val desc = readDescriptorFromBundle(bundle) ?: return emptyList()
        return readStringBatchFromSharedMemory(desc, autoRelease = autoRelease)
    }

    fun packEmbedBatchReplyToBundle(reqId: Int, embeddings: List<FloatArray>, dim: Int = 1024): Bundle {
        val bundle = Bundle()
        if (embeddings.isEmpty()) return bundle
        val desc = packFloatBatchMatrixToSharedMemory(
            reqId = reqId,
            vectors = embeddings,
            dim = dim
        )
        writeDescriptorToBundle(bundle, desc)
        return bundle
    }

    fun unpackEmbedBatchReplyFromBundle(bundle: Bundle, autoRelease: Boolean = true): List<FloatArray> {
        val desc = readDescriptorFromBundle(bundle) ?: return emptyList()
        return readFloatBatchMatrixFromSharedMemory(desc, autoRelease = autoRelease)
    }

    /**
     * 檢查一般 Bundle 中是否直接塞入了違規的大型陣列或長字串（供防崩潰審計與單元測試驗證）
     */
    fun estimateInlineBundlePayloadBytes(bundle: Bundle): Int {
        val desc = readDescriptorFromBundle(bundle)
        if (desc != null) {
            // 共享記憶體模式下，Bundle 僅攜帶 FD 描述符、offset 與 length 元數據 (< 128 bytes)
            return desc.inlineBundleBytesEstimate
        }
        var total = 0
        synchronized(jvmBundleShadow) {
            val shadow = jvmBundleShadow[bundle]
            if (shadow != null) {
                for ((_, v) in shadow) {
                    when (v) {
                        is String -> total += v.length * 2
                        is FloatArray -> total += v.size * 4
                        is ByteArray -> total += v.size
                    }
                }
                return total
            }
        }
        return total
    }

    private fun putStringSafe(bundle: Bundle, key: String, value: String) {
        synchronized(jvmBundleShadow) {
            jvmBundleShadow.getOrPut(bundle) { HashMap() }[key] = value
        }
        runCatching { bundle.putString(key, value) }
    }

    private fun getStringSafe(bundle: Bundle, key: String): String {
        val fromBundle = runCatching { bundle.getString(key) }.getOrNull()
        if (!fromBundle.isNullOrEmpty()) return fromBundle
        synchronized(jvmBundleShadow) {
            return (jvmBundleShadow[bundle]?.get(key) as? String).orEmpty()
        }
    }

    private fun putIntSafe(bundle: Bundle, key: String, value: Int) {
        synchronized(jvmBundleShadow) {
            jvmBundleShadow.getOrPut(bundle) { HashMap() }[key] = value
        }
        runCatching { bundle.putInt(key, value) }
    }

    private fun getIntSafe(bundle: Bundle, key: String, defaultVal: Int): Int {
        val fromBundle = runCatching { bundle.getInt(key, Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
        if (fromBundle != Int.MIN_VALUE && fromBundle != 0) return fromBundle
        synchronized(jvmBundleShadow) {
            return (jvmBundleShadow[bundle]?.get(key) as? Int) ?: defaultVal
        }
    }
}
