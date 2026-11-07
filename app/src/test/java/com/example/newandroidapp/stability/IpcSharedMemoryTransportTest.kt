package com.example.newandroidapp.stability

import com.example.newandroidapp.ai.NeedleNativeBridge
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IpcSharedMemoryTransportTest {

    @Before
    fun setUp() {
        IpcSharedMemoryTransport.resetStatsForTesting()
        NeedleNativeBridge.clearEmbedCacheForTest()
    }

    @After
    fun tearDown() {
        IpcSharedMemoryTransport.resetStatsForTesting()
        NeedleNativeBridge.clearEmbedCacheForTest()
    }

    @Test
    fun `test 1 - Large UI node tree and high-dim vectors are blocked from inline Bundle and routed to SharedMemory`() {
        // 構造超過 8KB (約 36KB) 的大型 UI 節點樹結構提示詞
        val largeNodeTreePrompt = buildString(36_000) {
            append("Goal: 找出最近提到聚餐地點的信件並打開地圖規劃路線\nNodes:\n")
            for (i in 1..600) {
                append("#$i [BTN] 郵件項目_$i 主旨:週五部門聚餐通知_捷運市政府站出口2集合 @(540,${(i * 12) % 2400})\n")
            }
        }
        assertTrue(
            "大型節點樹提示詞必須超過 8KB 內聯門檻",
            IpcSharedMemoryTransport.estimateUtf8Bytes(largeNodeTreePrompt) > IpcSharedMemoryTransport.INLINE_BUNDLE_MAX_BYTES
        )

        val reqBundle = IpcSharedMemoryTransport.packCompleteRequestToBundle(
            reqId = 101,
            roleId = "ROLE_COOP_STEP_DECIDER",
            sysPrompt = "Select the single next mobile UI action.",
            toolsJson = "[{\"name\":\"tap_element\"}]",
            userPrompt = largeNodeTreePrompt,
            maxTokens = 64
        )

        // 1. 驗證 Bundle 內僅存放輕量級共享記憶體描述符 (< 128 bytes)，絕未將 36KB 字串直接塞入一般 Bundle
        val desc = IpcSharedMemoryTransport.readDescriptorFromBundle(reqBundle)
        assertNotNull("超過 8KB 的大型節點樹請求必須建立 SharedMemory 描述符", desc)
        assertTrue(
            "Bundle 內聯負載必須小於 256 bytes，防止觸發 TransactionTooLargeException",
            IpcSharedMemoryTransport.estimateInlineBundlePayloadBytes(reqBundle) < 256
        )
        assertEquals(1, IpcSharedMemoryTransport.activeLeasedCount)

        // 2. 驗證 :ai 服務端零拷貝解包後 100% 完整還原原始大型節點樹，且自動釋放租約
        val unpacked = IpcSharedMemoryTransport.unpackCompleteRequestFromBundle(reqBundle, autoRelease = true)
        assertTrue(unpacked.usedSharedMemory)
        assertEquals("ROLE_COOP_STEP_DECIDER", unpacked.roleId)
        assertEquals(largeNodeTreePrompt, unpacked.userPrompt)
        assertEquals(64, unpacked.maxTokens)
        assertEquals("讀取完成後租約必須立即歸還重用池", 0, IpcSharedMemoryTransport.activeLeasedCount)
    }

    @Test
    fun `test 2 - Zero-Copy read and write with custom offsets for 1024D vectors batch matrices and ROI image patches`() {
        // A. 測試非零偏移量 (offset = 512) 之 1024 維語義嵌入向量零拷貝讀寫
        val expectedVec = FloatArray(1024) { idx -> ((idx % 37) - 18) * 0.025f }
        val vecDesc = IpcSharedMemoryTransport.packFloatVectorToSharedMemory(
            reqId = 201,
            vector = expectedVec,
            offset = 512
        )
        assertEquals(512, vecDesc.offset)
        assertEquals(1024 * 4, vecDesc.byteLength)

        val actualVec = IpcSharedMemoryTransport.readFloatVectorFromSharedMemory(vecDesc, autoRelease = true)
        assertArrayEquals(expectedVec, actualVec, 1e-6f)
        assertEquals(0, IpcSharedMemoryTransport.activeLeasedCount)

        // B. 測試批次嵌入向量矩陣 [8 x 1024] (32 KB) 零拷貝讀寫
        val batchVectors = List(8) { row ->
            FloatArray(1024) { col -> (row * 0.1f) + (col * 0.0005f) }
        }
        val batchBundle = IpcSharedMemoryTransport.packEmbedBatchReplyToBundle(
            reqId = 202,
            embeddings = batchVectors,
            dim = 1024
        )
        assertTrue(IpcSharedMemoryTransport.estimateInlineBundlePayloadBytes(batchBundle) < 256)

        val restoredBatch = IpcSharedMemoryTransport.unpackEmbedBatchReplyFromBundle(batchBundle, autoRelease = true)
        assertEquals(8, restoredBatch.size)
        for (r in 0 until 8) {
            assertArrayEquals(batchVectors[r], restoredBatch[r], 1e-6f)
        }
        assertEquals(0, IpcSharedMemoryTransport.activeLeasedCount)

        // C. 測試局部裁切影像特徵 (ROI Cropped Image Patch, 64KB, offset = 256) 零拷貝讀寫
        val roiBytes = ByteArray(64 * 1024) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val roiDesc = IpcSharedMemoryTransport.packImageRoiPatchToSharedMemory(
            reqId = 203,
            patchBytes = roiBytes,
            width = 160,
            height = 100,
            offset = 256
        )
        assertEquals(256, roiDesc.offset)
        assertEquals(64 * 1024, roiDesc.byteLength)

        val (restoredBytes, w, h) = IpcSharedMemoryTransport.readImageRoiPatchFromSharedMemory(roiDesc, autoRelease = true)
        assertEquals(160, w)
        assertEquals(100, h)
        assertArrayEquals(roiBytes, restoredBytes)
        assertEquals(0, IpcSharedMemoryTransport.activeLeasedCount)
    }

    @Test
    fun `test 3 - SharedMemory buffer pool reuses standard buffers and immediately closes oversize buffers`() {
        // 連續執行 20 次單步 1024D 向量跨進程傳輸，驗證始終重用同一個 128KB 緩衝區，不重複向系統配置新記憶體
        for (step in 1..20) {
            val vec = FloatArray(1024) { step.toFloat() }
            val replyBundle = IpcSharedMemoryTransport.packEmbedReplyToBundle(reqId = 300 + step, embedding = vec)
            val unpacked = IpcSharedMemoryTransport.unpackEmbedReplyFromBundle(replyBundle, autoRelease = true)
            assertEquals(1024, unpacked.size)
            assertEquals(step.toFloat(), unpacked[0], 1e-5f)
        }

        assertEquals("所有常規推論完成後不得殘留活躍租約", 0, IpcSharedMemoryTransport.activeLeasedCount)
        assertEquals("標準 128KB 緩衝區應只需配置 1 次", 1, IpcSharedMemoryTransport.totalAllocations)
        assertEquals("後續 19 次推論應 100% 命中重用池", 19, IpcSharedMemoryTransport.totalPoolReuseHits)
        assertEquals(1, IpcSharedMemoryTransport.pooledAvailableCount)

        // 測試超大臨時緩衝區 (512 KB > STANDARD_POOL_BUFFER_BYTES 128 KB)：使用完畢後應立即 close 銷毀，不佔用常駐池
        val hugeRoi = ByteArray(512 * 1024) { 0x5A.toByte() }
        val hugeDesc = IpcSharedMemoryTransport.packImageRoiPatchToSharedMemory(
            reqId = 399,
            patchBytes = hugeRoi,
            width = 512,
            height = 256
        )
        val (readHuge, _, _) = IpcSharedMemoryTransport.readImageRoiPatchFromSharedMemory(hugeDesc, autoRelease = true)
        assertEquals(512 * 1024, readHuge.size)
        assertEquals(0, IpcSharedMemoryTransport.activeLeasedCount)
        assertEquals("超大緩衝區釋放後必須立即關閉 Native 資源", 1, IpcSharedMemoryTransport.totalClosedBuffers)
        assertEquals("重用池內仍僅保留原本的 1 個標準緩衝區", 1, IpcSharedMemoryTransport.pooledAvailableCount)
    }

    @Test
    fun `test 4 - Buffer lifecycle safety on IPC timeout circuit breaker and isolated ai process crash`() {
        // 模擬 3 筆正在飛行中的跨進程共享記憶體請求 (reqId = 401, 402, 403)
        IpcSharedMemoryTransport.packEmbedBatchRequestToBundle(
            reqId = 401,
            texts = listOf("候選按鈕A", "候選按鈕B", "候選按鈕C")
        )
        IpcSharedMemoryTransport.packEmbedBatchRequestToBundle(
            reqId = 402,
            texts = listOf("搜尋框", "確認送出")
        )
        IpcSharedMemoryTransport.packImageRoiPatchToSharedMemory(
            reqId = 403,
            patchBytes = ByteArray(32 * 1024),
            width = 128,
            height = 64
        )
        assertEquals(3, IpcSharedMemoryTransport.activeLeasedCount)

        // 1. 模擬 reqId = 401 發生推論超時熔斷 -> 立即強制關閉並回收該請求的共享記憶體
        val evictedTimeout = IpcSharedMemoryTransport.abortAndCloseLeaseOnTimeoutOrBreaker(reqId = 401)
        assertEquals(1, evictedTimeout)
        assertEquals(2, IpcSharedMemoryTransport.activeLeasedCount)
        assertEquals("超時強制銷毀的緩衝區不得放回重用池", 0, IpcSharedMemoryTransport.pooledAvailableCount)

        // 2. 模擬 :ai 獨立推論進程意外崩潰斷線 (handleProcessDisconnected) -> 全量清理所有飛行中與池化緩衝區
        NeedleIsolatedClient.handleProcessDisconnected()
        assertEquals(":ai 進程斷線後活躍租約必須歸零", 0, IpcSharedMemoryTransport.activeLeasedCount)
        assertEquals(":ai 進程斷線後重用池必須清空釋放", 0, IpcSharedMemoryTransport.pooledAvailableCount)
        assertTrue(IpcSharedMemoryTransport.totalTimeoutOrCrashEvictions >= 3)
    }

    @Test
    fun `test 5 - Batch embedding coalescing with cache hit and SharedMemory matrix framing`() {
        val vecSearch = FloatArray(1024) { if (it == 0) 1.0f else 0.0f }
        val vecConfirm = FloatArray(1024) { if (it == 1) 1.0f else 0.0f }
        NeedleNativeBridge.putCachedEmbeddingForTest("搜尋郵件", vecSearch)
        NeedleNativeBridge.putCachedEmbeddingForTest("確認送出", vecConfirm)

        val batchResult = NeedleNativeBridge.embedTextBatch(listOf("搜尋郵件", "確認送出", "搜尋郵件"))
        assertEquals(3, batchResult.size)
        assertEquals(1.0f, NeedleNativeBridge.cosineSimilarity(batchResult[0], batchResult[2]), 1e-5f)
        assertEquals(0.0f, NeedleNativeBridge.cosineSimilarity(batchResult[0], batchResult[1]), 1e-5f)
    }
}
