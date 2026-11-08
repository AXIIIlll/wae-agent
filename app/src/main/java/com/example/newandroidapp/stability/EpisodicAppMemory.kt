package com.example.newandroidapp.stability

import android.content.Context
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.dataset.Phase2ButtonFunctionalityClassifier
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 【升級第 2 項 ＋ 動態抗改版與向量自癒記憶 (Self-Healing Memory)】
 * 跨任務持久化之 App 經驗記憶庫 (EpisodicAppMemory)
 *
 * 1. 儲存多維度語義特徵錨點：除了保存網格座標與結構路徑外，一併保存 32 維本地語義特徵向量（Embedding / 語義標籤 / 功能角色）與視覺幾何屬性。
 * 2. 改版偵測與自動降級重搜：當點擊前發現目標位置元件消失，或點擊後經閉環驗證發現畫面結構指紋未如預期改變時，
 *    自動將該筆記憶信心值歸零 (confidenceWeight = 0.0f)，不報錯中斷，無縫切換回全畫面掃描與語義精排。
 * 3. 餘弦相似度匹配「新替身」與靜默覆寫：重新掃描改版畫面時，計算候選新元件與舊記憶錨點語義特徵的餘弦相似度找出對應新按鈕；
 *    當點擊新元件並通過畫面跳轉驗證後，在背景自動將新座標與新結構靜默覆寫回本地記憶庫並動態覆蓋靜態先驗權重。
 */
object EpisodicAppMemory {

    private const val MEMORY_FILE_NAME = "learned_app_episodic_memory.json"
    private const val MAX_PROFILES_GLOBAL = 64
    private const val MAX_ANCHORS_PER_APP = 32
    private const val MAX_TRAPS_PER_APP = 64
    private const val MAX_PATHS_PER_APP = 24
    const val SEMANTIC_VECTOR_DIM = 32
    const val SEMANTIC_EMBEDDING_1024_DIM = 1024
    const val AVATAR_SIMILARITY_THRESHOLD = 0.68f

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "episodic-app-memory-io").apply { isDaemon = true }
    }
    private val fileLock = Any()
    private val savePending = AtomicBoolean(false)
    private val isSaving = AtomicBoolean(false)

    data class RedesignDetectionResult(
        val redesignDetected: Boolean,
        val invalidatedCount: Int,
        val diagnosticNote: String = ""
    )

    data class AvatarMatchResult(
        val matchedAnchor: LearnedNodeAnchor,
        val cosineSimilarity: Float,
        val combinedSimilarity: Float,
        val scoreBoost: Float,
        val diagnosticBadge: String
    )

    data class LearnedNodeAnchor(
        var label: String,
        var shortTypeTag: String,
        var gridX: Int,
        var gridY: Int,
        val intentKey: String,
        var successCount: Int = 1,
        var structuralPathSig: String = "",
        var functionRoleCode: String = "",
        var semanticTags: List<String> = emptyList(),
        var semanticEmbedding: FloatArray = FloatArray(SEMANTIC_VECTOR_DIM),
        var semanticEmbedding1024: FloatArray = FloatArray(SEMANTIC_EMBEDDING_1024_DIM),
        var geometricIconTag: String = "",
        var widthRatio: Float = 0.12f,
        var heightRatio: Float = 0.05f,
        var aspectRatio: Float = 2.0f,
        var nanoDetCategory: String = "",
        var confidenceWeight: Float = 1.0f,
        var isExpired: Boolean = false,
        var selfHealCount: Int = 0
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LearnedNodeAnchor) return false
            return label == other.label &&
                shortTypeTag == other.shortTypeTag &&
                gridX == other.gridX &&
                gridY == other.gridY &&
                intentKey == other.intentKey &&
                structuralPathSig == other.structuralPathSig &&
                functionRoleCode == other.functionRoleCode
        }

        override fun hashCode(): Int {
            var result = label.hashCode()
            result = 31 * result + shortTypeTag.hashCode()
            result = 31 * result + gridX
            result = 31 * result + gridY
            result = 31 * result + intentKey.hashCode()
            return result
        }
    }

    data class GraphTransitionStep(
        val fromScreenFp: String,
        val targetAnchor: LearnedNodeAnchor,
        val toScreenFp: String
    )

    data class GraphTransitionPath(
        val pathId: String,
        val goalIntent: String,
        val startScreenFp: String,
        val targetScreenFp: String,
        val steps: List<GraphTransitionStep>,
        var successCount: Int = 1,
        var lastUpdatedMs: Long = System.currentTimeMillis()
    )

    data class AppEpisodicProfile(
        val packageName: String,
        var appLabel: String,
        val verifiedSearchEntries: MutableList<LearnedNodeAnchor> = ArrayList(),
        val verifiedTargetClicks: MutableList<LearnedNodeAnchor> = ArrayList(),
        val deadEndTrapKeys: MutableSet<String> = LinkedHashSet(),
        var totalSuccessCount: Int = 0,
        var lastUpdatedMs: Long = System.currentTimeMillis(),
        val cachedTransitionPaths: MutableList<GraphTransitionPath> = ArrayList()
    )

    @Volatile
    private var isLoaded = false

    private val profiles = ConcurrentHashMap<String, AppEpisodicProfile>()

    fun ensureLoaded(context: Context?) {
        if (isLoaded || context == null) return
        val appContext = context.applicationContext ?: context
        synchronized(fileLock) {
            if (isLoaded) return
            isLoaded = true
            runCatching {
                val file = File(appContext.filesDir, MEMORY_FILE_NAME)
                if (!file.exists()) return
                val rawText = file.readText(Charsets.UTF_8)
                if (rawText.isBlank()) return
                val root = MiniJson.parse(rawText) ?: return
                @Suppress("UNCHECKED_CAST")
                val appsArr = root["apps"] as? List<Map<String, Any?>> ?: return
                for (obj in appsArr) {
                    val pkg = (obj["packageName"] as? String)?.trim() ?: continue
                    if (pkg.isEmpty()) continue
                    val profile = AppEpisodicProfile(
                        packageName = pkg,
                        appLabel = (obj["appLabel"] as? String) ?: pkg,
                        totalSuccessCount = (obj["totalSuccessCount"] as? Number)?.toInt() ?: 0,
                        lastUpdatedMs = (obj["lastUpdatedMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
                    )
                    @Suppress("UNCHECKED_CAST")
                    val searchArr = obj["verifiedSearchEntries"] as? List<Map<String, Any?>>
                    if (searchArr != null) {
                        for (s in searchArr) {
                            parseAnchorFromMap(s)?.let { profile.verifiedSearchEntries.add(it) }
                        }
                    }
                    @Suppress("UNCHECKED_CAST")
                    val targetArr = obj["verifiedTargetClicks"] as? List<Map<String, Any?>>
                    if (targetArr != null) {
                        for (t in targetArr) {
                            parseAnchorFromMap(t)?.let { profile.verifiedTargetClicks.add(it) }
                        }
                    }
                    @Suppress("UNCHECKED_CAST")
                    val trapsArr = obj["deadEndTrapKeys"] as? List<String>
                    if (trapsArr != null) {
                        for (trap in trapsArr) {
                            val clean = trap.trim()
                            if (clean.isNotEmpty()) profile.deadEndTrapKeys.add(clean)
                        }
                    }
                    @Suppress("UNCHECKED_CAST")
                    val pathsArr = obj["cachedTransitionPaths"] as? List<Map<String, Any?>>
                    if (pathsArr != null) {
                        for (p in pathsArr) {
                            val pathId = (p["pathId"] as? String)?.trim() ?: ""
                            val goalIntent = (p["goalIntent"] as? String)?.trim() ?: ""
                            val startScreenFp = (p["startScreenFp"] as? String)?.trim() ?: ""
                            val targetScreenFp = (p["targetScreenFp"] as? String)?.trim() ?: ""
                            val successCount = ((p["successCount"] as? Number)?.toInt() ?: 1).coerceAtLeast(1)
                            val lastUpdatedMs = (p["lastUpdatedMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
                            @Suppress("UNCHECKED_CAST")
                            val stepsArr = p["steps"] as? List<Map<String, Any?>>
                            val steps = ArrayList<GraphTransitionStep>()
                            if (stepsArr != null) {
                                for (st in stepsArr) {
                                    val fromFp = (st["fromScreenFp"] as? String) ?: ""
                                    val toFp = (st["toScreenFp"] as? String) ?: ""
                                    @Suppress("UNCHECKED_CAST")
                                    val anchorMap = st["targetAnchor"] as? Map<String, Any?>
                                    val anchor = parseAnchorFromMap(anchorMap)
                                    if (anchor != null) {
                                        steps.add(GraphTransitionStep(fromFp, anchor, toFp))
                                    }
                                }
                            }
                            if (pathId.isNotEmpty() && steps.isNotEmpty()) {
                                profile.cachedTransitionPaths.add(
                                    GraphTransitionPath(
                                        pathId = pathId,
                                        goalIntent = goalIntent,
                                        startScreenFp = startScreenFp,
                                        targetScreenFp = targetScreenFp,
                                        steps = steps,
                                        successCount = successCount,
                                        lastUpdatedMs = lastUpdatedMs
                                    )
                                )
                            }
                        }
                    }
                    profiles[pkg] = profile
                }
            }
        }
    }

    private fun parseAnchorFromMap(obj: Map<String, Any?>?): LearnedNodeAnchor? {
        if (obj == null) return null
        val label = (obj["label"] as? String)?.trim() ?: ""
        val tag = (obj["shortTypeTag"] as? String)?.trim() ?: ""
        if (tag.isEmpty()) return null
        val gx = (obj["gridX"] as? Number)?.toInt() ?: 500
        val gy = (obj["gridY"] as? Number)?.toInt() ?: 500
        val intentKey = (obj["intentKey"] as? String) ?: ""
        val successCount = ((obj["successCount"] as? Number)?.toInt() ?: 1).coerceAtLeast(1)
        val structuralPathSig = (obj["structuralPathSig"] as? String)?.trim() ?: "${tag}|${computeSpatialZoneTag(gx, gy)}"
        val functionRoleCode = (obj["functionRoleCode"] as? String)?.trim() ?: inferCanonicalRoleFromText(label, intentKey, tag)
        val widthRatio = ((obj["widthRatio"] as? Number)?.toFloat() ?: 0.12f).coerceIn(0.005f, 1f)
        val heightRatio = ((obj["heightRatio"] as? Number)?.toFloat() ?: 0.05f).coerceIn(0.005f, 1f)
        val aspectRatio = ((obj["aspectRatio"] as? Number)?.toFloat() ?: (widthRatio / heightRatio.coerceAtLeast(0.005f))).coerceIn(0.1f, 15f)
        val nanoDetCategory = (obj["nanoDetCategory"] as? String)?.trim() ?: ""
        val geometricIconTag = (obj["geometricIconTag"] as? String)?.trim()?.ifEmpty { null }
            ?: inferGeometricIconTag(label, functionRoleCode, nanoDetCategory, tag, aspectRatio)
        val confidenceWeight = ((obj["confidenceWeight"] as? Number)?.toFloat() ?: 1.0f).coerceIn(0f, 1.5f)
        val isExpired = (obj["isExpired"] as? Boolean) ?: (confidenceWeight <= 0f)
        val selfHealCount = ((obj["selfHealCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)

        @Suppress("UNCHECKED_CAST")
        val rawTags = (obj["semanticTags"] as? List<Any?>)?.mapNotNull { (it as? String)?.trim()?.lowercase() }?.filter { it.isNotEmpty() }
        val semanticTags = if (!rawTags.isNullOrEmpty()) {
            rawTags
        } else {
            extractSemanticTagsAndSynonyms(label, intentKey, functionRoleCode, nanoDetCategory)
        }

        @Suppress("UNCHECKED_CAST")
        val rawVec = (obj["semanticEmbedding"] as? List<Any?>)?.mapNotNull { (it as? Number)?.toFloat() }
        val semanticEmbedding = if (rawVec != null && rawVec.size == SEMANTIC_VECTOR_DIM) {
            rawVec.toFloatArray()
        } else {
            buildSemanticFeatureVector(
                label = label,
                intentKey = intentKey,
                shortTypeTag = tag,
                functionRoleCode = functionRoleCode,
                semanticTags = semanticTags,
                widthRatio = widthRatio,
                heightRatio = heightRatio,
                aspectRatio = aspectRatio,
                nanoDetCategory = nanoDetCategory
            )
        }

        val semanticEmbedding1024 = com.example.newandroidapp.ai.NeedleNativeBridge.embedText1024D(
            "$label $intentKey $functionRoleCode $geometricIconTag ${semanticTags.joinToString(" ")}"
        )

        return LearnedNodeAnchor(
            label = label,
            shortTypeTag = tag,
            gridX = gx,
            gridY = gy,
            intentKey = intentKey,
            successCount = successCount,
            structuralPathSig = structuralPathSig,
            functionRoleCode = functionRoleCode,
            semanticTags = semanticTags,
            semanticEmbedding = semanticEmbedding,
            semanticEmbedding1024 = semanticEmbedding1024,
            geometricIconTag = geometricIconTag,
            widthRatio = widthRatio,
            heightRatio = heightRatio,
            aspectRatio = aspectRatio,
            nanoDetCategory = nanoDetCategory,
            confidenceWeight = confidenceWeight,
            isExpired = isExpired,
            selfHealCount = selfHealCount
        )
    }

    /**
     * 背景單一執行緒非同步寫入經驗記憶庫，具備防抖合併 (Write Coalescing) 與暫存檔原子替換 (Atomic Move)
     */
    fun saveToDisk(context: Context?) {
        saveToDiskAsync(context)
    }

    fun saveToDiskAsync(context: Context?) {
        if (context == null) return
        val appContext = context.applicationContext ?: context
        savePending.set(true)
        if (isSaving.compareAndSet(false, true)) {
            ioExecutor.execute {
                try {
                    while (savePending.getAndSet(false)) {
                        performAtomicDiskWrite(appContext)
                    }
                } finally {
                    isSaving.set(false)
                    if (savePending.get()) {
                        saveToDiskAsync(appContext)
                    }
                }
            }
        }
    }

    /**
     * 同步執行磁碟寫入（供單元測試或強制刷入時使用）
     */
    fun saveToDiskSync(context: Context?): Boolean {
        if (context == null) return false
        val appContext = context.applicationContext ?: context
        return performAtomicDiskWrite(appContext)
    }

    /**
     * 等待所有非同步寫入佇列清空完成
     */
    fun flushPendingSaves(timeoutMs: Long = 5000L): Boolean {
        val latch = CountDownLatch(1)
        ioExecutor.execute {
            latch.countDown()
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun performAtomicDiskWrite(context: Context): Boolean {
        return synchronized(fileLock) {
            runCatching {
                val jsonString = serializeToJsonString()
                val dir = context.filesDir
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, MEMORY_FILE_NAME)
                val tempFile = File(dir, "$MEMORY_FILE_NAME.tmp")
                tempFile.writeText(jsonString, Charsets.UTF_8)

                try {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Throwable) {
                    try {
                        Files.move(
                            tempFile.toPath(),
                            targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: Throwable) {
                        if (!tempFile.renameTo(targetFile)) {
                            targetFile.delete()
                            tempFile.renameTo(targetFile)
                        }
                    }
                }
                true
            }.getOrDefault(false)
        }
    }

    private fun serializeToJsonString(): String {
        val sb = StringBuilder(1024)
        sb.append("{\"apps\":[")
        var firstApp = true
        for ((_, prof) in profiles) {
            if (!firstApp) sb.append(",")
            firstApp = false
            sb.append("{")
            sb.append("\"packageName\":").append(quote(prof.packageName)).append(",")
            sb.append("\"appLabel\":").append(quote(prof.appLabel)).append(",")
            sb.append("\"totalSuccessCount\":").append(prof.totalSuccessCount).append(",")
            sb.append("\"lastUpdatedMs\":").append(prof.lastUpdatedMs).append(",")

            sb.append("\"verifiedSearchEntries\":[")
            var firstSearch = true
            val searchSnapshot = synchronized(prof.verifiedSearchEntries) { prof.verifiedSearchEntries.toList() }
            for (a in searchSnapshot) {
                if (!firstSearch) sb.append(",")
                firstSearch = false
                sb.append(anchorToJsonString(a))
            }
            sb.append("],")

            sb.append("\"verifiedTargetClicks\":[")
            var firstTarget = true
            val targetSnapshot = synchronized(prof.verifiedTargetClicks) { prof.verifiedTargetClicks.toList() }
            for (a in targetSnapshot) {
                if (!firstTarget) sb.append(",")
                firstTarget = false
                sb.append(anchorToJsonString(a))
            }
            sb.append("],")

            sb.append("\"deadEndTrapKeys\":[")
            var firstTrap = true
            val trapsSnapshot = synchronized(prof.deadEndTrapKeys) { prof.deadEndTrapKeys.toList() }
            for (trap in trapsSnapshot) {
                if (!firstTrap) sb.append(",")
                firstTrap = false
                sb.append(quote(trap))
            }
            sb.append("],")

            sb.append("\"cachedTransitionPaths\":[")
            var firstPath = true
            val pathsSnapshot = synchronized(prof.cachedTransitionPaths) { prof.cachedTransitionPaths.toList() }
            for (path in pathsSnapshot) {
                if (!firstPath) sb.append(",")
                firstPath = false
                sb.append(pathToJsonString(path))
            }
            sb.append("]")
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun anchorToJsonString(a: LearnedNodeAnchor): String {
        fun fmt(v: Float): String = ((v * 10000f).toInt() / 10000f).toString()
        val tagsJson = a.semanticTags.joinToString(",") { quote(it) }
        val vecJson = a.semanticEmbedding.joinToString(",") { fmt(it) }
        return buildString(256) {
            append("{")
            append("\"label\":").append(quote(a.label)).append(",")
            append("\"shortTypeTag\":").append(quote(a.shortTypeTag)).append(",")
            append("\"gridX\":").append(a.gridX).append(",")
            append("\"gridY\":").append(a.gridY).append(",")
            append("\"intentKey\":").append(quote(a.intentKey)).append(",")
            append("\"successCount\":").append(a.successCount).append(",")
            append("\"structuralPathSig\":").append(quote(a.structuralPathSig)).append(",")
            append("\"functionRoleCode\":").append(quote(a.functionRoleCode)).append(",")
            append("\"semanticTags\":[").append(tagsJson).append("],")
            append("\"semanticEmbedding\":[").append(vecJson).append("],")
            append("\"widthRatio\":").append(fmt(a.widthRatio)).append(",")
            append("\"heightRatio\":").append(fmt(a.heightRatio)).append(",")
            append("\"aspectRatio\":").append(fmt(a.aspectRatio)).append(",")
            append("\"nanoDetCategory\":").append(quote(a.nanoDetCategory)).append(",")
            append("\"geometricIconTag\":").append(quote(a.geometricIconTag)).append(",")
            append("\"confidenceWeight\":").append(fmt(a.confidenceWeight)).append(",")
            append("\"isExpired\":").append(a.isExpired).append(",")
            append("\"selfHealCount\":").append(a.selfHealCount)
            append("}")
        }
    }

    private fun stepToJsonString(s: GraphTransitionStep): String {
        return "{\"fromScreenFp\":${quote(s.fromScreenFp)},\"toScreenFp\":${quote(s.toScreenFp)},\"targetAnchor\":${anchorToJsonString(s.targetAnchor)}}"
    }

    private fun pathToJsonString(p: GraphTransitionPath): String {
        val sb = StringBuilder(256)
        sb.append("{\"pathId\":").append(quote(p.pathId)).append(",")
        sb.append("\"goalIntent\":").append(quote(p.goalIntent)).append(",")
        sb.append("\"startScreenFp\":").append(quote(p.startScreenFp)).append(",")
        sb.append("\"targetScreenFp\":").append(quote(p.targetScreenFp)).append(",")
        sb.append("\"successCount\":").append(p.successCount).append(",")
        sb.append("\"lastUpdatedMs\":").append(p.lastUpdatedMs).append(",")
        sb.append("\"steps\":[")
        var firstStep = true
        for (st in p.steps) {
            if (!firstStep) sb.append(",")
            firstStep = false
            sb.append(stepToJsonString(st))
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 【1. 儲存多維度語義特徵錨點】從 `Stage2ConvergedNode` 建構包含 32D 語義特徵向量與視覺幾何屬性之錨點
     */
    fun buildAnchorFromNode(
        node: Stage2ConvergedNode,
        intentKey: String,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): LearnedNodeAnchor {
        val sw = screenWidth.toFloat().coerceAtLeast(320f)
        val sh = screenHeight.toFloat().coerceAtLeast(480f)
        val b = node.convergedBounds.takeIf { (it.right - it.left) > 0 && (it.bottom - it.top) > 0 } ?: node.rawOriginalBounds
        val bw = (b.right - b.left).coerceAtLeast(1).toFloat()
        val bh = (b.bottom - b.top).coerceAtLeast(1).toFloat()
        val widthRatio = (bw / sw).coerceIn(0.005f, 1f)
        val heightRatio = (bh / sh).coerceIn(0.005f, 1f)
        val aspectRatio = (bw / bh).coerceIn(0.1f, 15f)
        val normLabel = normalizeLabel(node.label)
        val normIntent = normalizeLabel(intentKey).ifEmpty { normLabel }
        val roleCode = node.predictedFunctionRole.trim().ifEmpty {
            inferCanonicalRoleFromText(normLabel, normIntent, node.shortTypeTag)
        }
        val clsShort = node.className.substringAfterLast('.').ifEmpty { node.shortTypeTag }
        val zoneTag = computeSpatialZoneTag(node.gridTapPoint.x, node.gridTapPoint.y)
        val structuralPathSig = "$clsShort|${node.shortTypeTag}|${node.sourceType.name}|$zoneTag"
        val semanticTags = extractSemanticTagsAndSynonyms(
            label = "${node.inlineStatePrefix} ${node.label}".trim(),
            intentKey = normIntent,
            functionRoleCode = roleCode,
            nanoDetCategory = node.nanoDetCategory
        )
        val semanticEmbedding = buildSemanticFeatureVector(
            label = normLabel,
            intentKey = normIntent,
            shortTypeTag = node.shortTypeTag,
            functionRoleCode = roleCode,
            semanticTags = semanticTags,
            widthRatio = widthRatio,
            heightRatio = heightRatio,
            aspectRatio = aspectRatio,
            nanoDetCategory = node.nanoDetCategory
        )
        val geometricIconTag = inferGeometricIconTag(
            label = "${node.inlineStatePrefix} ${node.label}".trim(),
            functionRoleCode = roleCode,
            nanoDetCategory = node.nanoDetCategory,
            shortTypeTag = node.shortTypeTag,
            aspectRatio = aspectRatio
        )
        val semanticEmbedding1024 = com.example.newandroidapp.ai.NeedleNativeBridge.embedText1024D(
            "$normLabel $normIntent $roleCode $geometricIconTag ${semanticTags.joinToString(" ")}"
        )
        return LearnedNodeAnchor(
            label = normLabel,
            shortTypeTag = node.shortTypeTag,
            gridX = node.gridTapPoint.x,
            gridY = node.gridTapPoint.y,
            intentKey = normIntent,
            successCount = 1,
            structuralPathSig = structuralPathSig,
            functionRoleCode = roleCode,
            semanticTags = semanticTags,
            semanticEmbedding = semanticEmbedding,
            semanticEmbedding1024 = semanticEmbedding1024,
            geometricIconTag = geometricIconTag,
            widthRatio = widthRatio,
            heightRatio = heightRatio,
            aspectRatio = aspectRatio,
            nanoDetCategory = node.nanoDetCategory,
            confidenceWeight = 1.0f,
            isExpired = false,
            selfHealCount = 0
        )
    }

    /**
     * 當點擊非 INP 的搜尋入口後，下一幀成功展開 INP 輸入框時呼叫，將該搜尋入口記入經驗庫；
     * 若存在因改版而信心歸零的舊錨點，自動執行背景靜默覆寫 (Self-Healing Overwrite) 並動態覆蓋先驗權重。
     */
    @Synchronized
    fun recordVerifiedSearchEntry(
        context: Context?,
        packageName: String,
        appLabel: String = "",
        node: Stage2ConvergedNode,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ) {
        ensureLoaded(context)
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        val prof = getOrCreateProfile(pkg, appLabel)
        val freshAnchor = buildAnchorFromNode(node, "SEARCH_ENTRY", screenWidth, screenHeight)

        // 優先檢查是否有因改版而待自癒的舊搜尋入口錨點 (confidenceWeight <= 0f)
        val pendingHeal = prof.verifiedSearchEntries.firstOrNull { it.confidenceWeight <= 0f }
        if (pendingHeal != null) {
            val healedCount = pendingHeal.selfHealCount + 1
            overwriteAnchorInPlace(pendingHeal, freshAnchor, healedCount)
            healTransitionPathAnchors(prof, "SEARCH_ENTRY", freshAnchor)
        } else {
            val existing = prof.verifiedSearchEntries.firstOrNull { a ->
                a.shortTypeTag == node.shortTypeTag &&
                    ((a.label == freshAnchor.label && freshAnchor.label.isNotEmpty()) ||
                        abs(a.gridX - node.gridTapPoint.x) + abs(a.gridY - node.gridTapPoint.y) < 90 ||
                        computeAvatarSimilarity(a, node, screenWidth, screenHeight) >= 0.78f)
            }
            if (existing != null) {
                val moved = abs(existing.gridX - node.gridTapPoint.x) + abs(existing.gridY - node.gridTapPoint.y) >= 90
                overwriteAnchorInPlace(
                    target = existing,
                    fresh = freshAnchor,
                    newHealCount = if (moved) existing.selfHealCount + 1 else existing.selfHealCount
                )
            } else {
                evictLowestValueAnchorIfNeeded(prof.verifiedSearchEntries, MAX_ANCHORS_PER_APP)
                prof.verifiedSearchEntries.add(freshAnchor)
            }
        }

        // 動態覆蓋靜態空間與詞彙先驗權重
        Phase2ButtonFunctionalityClassifier.recordAppSpecificDynamicPriorOverride(
            packageName = pkg,
            roleCode = freshAnchor.functionRoleCode.ifBlank { "SEARCH_ENTRY" },
            normCx = (freshAnchor.gridX / 1000f).coerceIn(0f, 1f),
            normCy = (freshAnchor.gridY / 1000f).coerceIn(0f, 1f),
            widthRatio = freshAnchor.widthRatio,
            heightRatio = freshAnchor.heightRatio,
            aspectRatio = freshAnchor.aspectRatio,
            labelTokens = freshAnchor.semanticTags
        )

        prof.totalSuccessCount++
        prof.lastUpdatedMs = System.currentTimeMillis()
        saveToDisk(context)
    }

    /**
     * 當點擊某元件成功推進子目標或完成任務時呼叫，記住「意圖關鍵字 -> 成功元件錨點」；
     * 若存在因改版而信心歸零的舊錨點，自動執行背景靜默覆寫 (Self-Healing Overwrite) 並動態覆蓋先驗權重。
     */
    @Synchronized
    fun recordVerifiedTargetClick(
        context: Context?,
        packageName: String,
        appLabel: String = "",
        intentKeyword: String,
        node: Stage2ConvergedNode,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ) {
        ensureLoaded(context)
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        val normIntent = normalizeLabel(intentKeyword)
        val normLabel = normalizeLabel(node.label)
        if (normIntent.isEmpty() && normLabel.isEmpty()) return

        val prof = getOrCreateProfile(pkg, appLabel)
        val effectiveIntent = normIntent.ifEmpty { normLabel }
        val freshAnchor = buildAnchorFromNode(node, effectiveIntent, screenWidth, screenHeight)

        // 優先檢查是否有因介面改版而信心歸零 (confidenceWeight <= 0f) 的待自癒舊錨點
        val pendingHeal = prof.verifiedTargetClicks.firstOrNull { a ->
            a.confidenceWeight <= 0f &&
                (intentKeysMatch(a.intentKey, effectiveIntent) ||
                    computeAvatarSimilarity(a, node, screenWidth, screenHeight) >= AVATAR_SIMILARITY_THRESHOLD)
        }
        if (pendingHeal != null) {
            val healedCount = pendingHeal.selfHealCount + 1
            overwriteAnchorInPlace(pendingHeal, freshAnchor, healedCount)
            healTransitionPathAnchors(prof, effectiveIntent, freshAnchor)
        } else {
            val existing = prof.verifiedTargetClicks.firstOrNull { a ->
                intentKeysMatch(a.intentKey, effectiveIntent) &&
                    a.shortTypeTag == node.shortTypeTag &&
                    (a.label == normLabel ||
                        abs(a.gridX - node.gridTapPoint.x) + abs(a.gridY - node.gridTapPoint.y) < 100 ||
                        computeAvatarSimilarity(a, node, screenWidth, screenHeight) >= 0.72f)
            }
            if (existing != null) {
                val moved = abs(existing.gridX - node.gridTapPoint.x) + abs(existing.gridY - node.gridTapPoint.y) >= 100
                overwriteAnchorInPlace(
                    target = existing,
                    fresh = freshAnchor,
                    newHealCount = if (moved) existing.selfHealCount + 1 else existing.selfHealCount
                )
                if (moved) {
                    healTransitionPathAnchors(prof, effectiveIntent, freshAnchor)
                }
            } else {
                evictLowestValueAnchorIfNeeded(prof.verifiedTargetClicks, MAX_ANCHORS_PER_APP)
                prof.verifiedTargetClicks.add(freshAnchor)
            }
        }

        if (freshAnchor.functionRoleCode.isNotBlank()) {
            Phase2ButtonFunctionalityClassifier.recordAppSpecificDynamicPriorOverride(
                packageName = pkg,
                roleCode = freshAnchor.functionRoleCode,
                normCx = (freshAnchor.gridX / 1000f).coerceIn(0f, 1f),
                normCy = (freshAnchor.gridY / 1000f).coerceIn(0f, 1f),
                widthRatio = freshAnchor.widthRatio,
                heightRatio = freshAnchor.heightRatio,
                aspectRatio = freshAnchor.aspectRatio,
                labelTokens = freshAnchor.semanticTags
            )
        }

        prof.totalSuccessCount++
        prof.lastUpdatedMs = System.currentTimeMillis()
        saveToDisk(context)
    }

    /**
     * 【2. 改版偵測與自動降級重搜 — 點擊前原位檢查】
     * 當依據歷史記憶準備執行點擊前，檢查記憶錨點的目標位置 (gridX, gridY) 元件是否消失或已被替換為不相容元件。
     * 若發現消失或位移，自動將該筆記憶信心值歸零 (confidenceWeight = 0.0f)，絕不報錯中斷，並無縫切換至全畫面掃描與餘弦相似度精排。
     */
    @Synchronized
    fun detectPreClickRedesignAndInvalidate(
        context: Context?,
        packageName: String,
        visibleNodes: List<Stage2ConvergedNode>,
        stage: InAppOperationStage,
        query: String = "",
        subGoalKeyword: String = "",
        screenFp: String = "",
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): RedesignDetectionResult {
        ensureLoaded(context)
        val pkg = packageName.trim()
        if (pkg.isEmpty() || visibleNodes.isEmpty()) {
            return RedesignDetectionResult(false, 0)
        }
        val prof = profiles[pkg] ?: return RedesignDetectionResult(false, 0)
        var invalidatedCount = 0
        val notes = ArrayList<String>(2)

        fun isAnchorPresentAtRememberedPosition(anchor: LearnedNodeAnchor): Boolean {
            for (node in visibleNodes) {
                val dist = abs(anchor.gridX - node.gridTapPoint.x) + abs(anchor.gridY - node.gridTapPoint.y)
                if (dist < 120 && anchor.shortTypeTag == node.shortTypeTag) {
                    val normCand = normalizeLabel(node.label)
                    if (anchor.label.isEmpty() || normCand == anchor.label) return true
                    val sim = computeAvatarSimilarity(anchor, node, screenWidth, screenHeight)
                    if (sim >= 0.55f) return true
                }
            }
            return false
        }

        if (stage == InAppOperationStage.STAGE_A_EXPAND_SEARCH) {
            for (anchor in prof.verifiedSearchEntries) {
                if (anchor.confidenceWeight > 0f && !isAnchorPresentAtRememberedPosition(anchor)) {
                    anchor.confidenceWeight = 0.0f
                    anchor.isExpired = true
                    invalidatedCount++
                    notes.add("搜尋入口(${anchor.label}@${anchor.gridX},${anchor.gridY})原位消失->標記過期並啟動自癒")
                }
            }
        }

        val normQuery = normalizeLabel(query)
        val normSubGoal = normalizeLabel(subGoalKeyword)
        for (anchor in prof.verifiedTargetClicks) {
            val relevant = (normQuery.isNotEmpty() && intentKeysMatch(anchor.intentKey, normQuery)) ||
                (normSubGoal.isNotEmpty() && intentKeysMatch(anchor.intentKey, normSubGoal))
            if (relevant && anchor.confidenceWeight > 0f && !isAnchorPresentAtRememberedPosition(anchor)) {
                anchor.confidenceWeight = 0.0f
                anchor.isExpired = true
                invalidatedCount++
                notes.add("目標錨點(${anchor.label}@${anchor.gridX},${anchor.gridY})原位消失->標記過期並啟動自癒")
            }
        }

        if (screenFp.isNotEmpty()) {
            val goalForPath = if (subGoalKeyword.isNotEmpty()) subGoalKeyword else query
            val nextStep = findNextStepInPath(context, pkg, screenFp, goalForPath)
            if (nextStep != null && nextStep.targetAnchor.confidenceWeight > 0f) {
                if (!isAnchorPresentAtRememberedPosition(nextStep.targetAnchor)) {
                    nextStep.targetAnchor.confidenceWeight = 0.0f
                    nextStep.targetAnchor.isExpired = true
                    invalidatedCount++
                    notes.add("跨頁圖譜錨點(${nextStep.targetAnchor.label})原位消失->標記過期並啟動自癒")
                }
            }
        }

        if (invalidatedCount > 0) {
            prof.lastUpdatedMs = System.currentTimeMillis()
            saveToDisk(context)
        }
        return RedesignDetectionResult(
            redesignDetected = invalidatedCount > 0,
            invalidatedCount = invalidatedCount,
            diagnosticNote = notes.joinToString("; ")
        )
    }

    /**
     * 【2. 改版偵測與自動降級重搜 — 點擊後閉環指紋未改變偵測】
     * 當點擊後經 `ClosedLoopVerifier` 發現畫面結構指紋未如預期改變 (!changed) 時，
     * 判定為目標 App 介面改版或熱區位移，自動將該筆記憶信心值歸零 (confidenceWeight = 0.0f)，不報錯中斷。
     */
    @Synchronized
    fun onAnchorClickVerificationFailed(
        context: Context?,
        packageName: String,
        clickedNode: Stage2ConvergedNode,
        stage: InAppOperationStage? = null,
        intentKeyword: String = "",
        screenFp: String = ""
    ): Int {
        ensureLoaded(context)
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return 0
        val prof = profiles[pkg] ?: return 0
        val normLabel = normalizeLabel(clickedNode.label)
        val normIntent = normalizeLabel(intentKeyword)
        val gx = clickedNode.gridTapPoint.x
        val gy = clickedNode.gridTapPoint.y
        var zeroedCount = 0

        for (anchor in prof.verifiedSearchEntries) {
            val matches = anchor.confidenceWeight > 0f &&
                (stage == InAppOperationStage.STAGE_A_EXPAND_SEARCH ||
                    abs(anchor.gridX - gx) + abs(anchor.gridY - gy) < 120 ||
                    (normLabel.isNotEmpty() && anchor.label == normLabel))
            if (matches) {
                anchor.confidenceWeight = 0.0f
                anchor.isExpired = true
                zeroedCount++
            }
        }

        for (anchor in prof.verifiedTargetClicks) {
            val matches = anchor.confidenceWeight > 0f &&
                (abs(anchor.gridX - gx) + abs(anchor.gridY - gy) < 120 ||
                    (normLabel.isNotEmpty() && anchor.label == normLabel) ||
                    (normIntent.isNotEmpty() && intentKeysMatch(anchor.intentKey, normIntent)))
            if (matches) {
                anchor.confidenceWeight = 0.0f
                anchor.isExpired = true
                zeroedCount++
            }
        }

        for (path in prof.cachedTransitionPaths) {
            for (st in path.steps) {
                val a = st.targetAnchor
                val fpMatches = screenFp.isEmpty() || st.fromScreenFp == screenFp || st.fromScreenFp.contains(screenFp)
                val nodeMatches = abs(a.gridX - gx) + abs(a.gridY - gy) < 120 ||
                    (normLabel.isNotEmpty() && a.label == normLabel)
                if (a.confidenceWeight > 0f && fpMatches && nodeMatches) {
                    a.confidenceWeight = 0.0f
                    a.isExpired = true
                    zeroedCount++
                }
            }
        }

        if (zeroedCount > 0) {
            prof.lastUpdatedMs = System.currentTimeMillis()
            saveToDisk(context)
        }
        return zeroedCount
    }

    /**
     * 【3. 餘弦相似度匹配「新替身」】
     * 計算畫面上候選新元件與舊記憶錨點之多維度特徵餘弦相似度（完全不依賴舊網格座標，支援跨螢幕大位移匹配）。
     */
    fun computeAvatarSimilarity(
        oldAnchor: LearnedNodeAnchor,
        candidate: Stage2ConvergedNode,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): Float {
        if (oldAnchor.shortTypeTag != candidate.shortTypeTag) {
            // 僅允許 BTN 與 ICO 之間在同為搜尋/點擊動作時跨標籤比對，禁止 INP 與非 INP 混用
            val bothClickableIconOrBtn = (oldAnchor.shortTypeTag == "BTN" || oldAnchor.shortTypeTag == "ICO") &&
                (candidate.shortTypeTag == "BTN" || candidate.shortTypeTag == "ICO")
            if (!bothClickableIconOrBtn) return 0f
        }

        val candAnchor = buildAnchorFromNode(candidate, oldAnchor.intentKey, screenWidth, screenHeight)
        val cosineSim32 = cosineSimilarity(oldAnchor.semanticEmbedding, candAnchor.semanticEmbedding).coerceIn(0f, 1f)
        val cosineSim1024 = cosineSimilarity(oldAnchor.semanticEmbedding1024, candAnchor.semanticEmbedding1024).coerceIn(0f, 1f)
        val iconMatchBonus = if (oldAnchor.geometricIconTag.isNotBlank() &&
            oldAnchor.geometricIconTag == candAnchor.geometricIconTag
        ) 0.14f else 0f
        val cosineSim = (0.55f * cosineSim1024 + 0.45f * cosineSim32 + iconMatchBonus).coerceIn(0f, 1f)

        // 語義標籤與功能角色一致性
        val oldTags = oldAnchor.semanticTags.toSet()
        val candTags = extractSemanticTagsAndSynonyms(
            label = "${candidate.inlineStatePrefix} ${candidate.label}".trim(),
            intentKey = "",
            functionRoleCode = candidate.predictedFunctionRole.ifBlank {
                inferCanonicalRoleFromText(normalizeLabel(candidate.label), "", candidate.shortTypeTag)
            },
            nanoDetCategory = candidate.nanoDetCategory
        ).toSet()

        val overlapCount = oldTags.intersect(candTags).size
        val tagUnion = max(1, min(oldTags.size, candTags.size))
        val tagIntersectionRatio = (overlapCount.toFloat() / tagUnion.toFloat()).coerceIn(0f, 1f)

        val roleMatches = oldAnchor.functionRoleCode.isNotBlank() &&
            candAnchor.functionRoleCode.isNotBlank() &&
            (oldAnchor.functionRoleCode == candAnchor.functionRoleCode ||
                (oldAnchor.functionRoleCode == "SEARCH_ENTRY" && candAnchor.functionRoleCode == "NAV_EXPLORE_DISCOVER_TAB") ||
                (oldAnchor.functionRoleCode == "NAV_EXPLORE_DISCOVER_TAB" && candAnchor.functionRoleCode == "SEARCH_ENTRY"))
        val roleAndTagScore = (if (roleMatches) 0.55f else 0f) + tagIntersectionRatio * 0.65f

        // 視覺幾何相似度（長寬比與面積比例，與畫面位置無關）
        val aspectDiff = abs(ln(1f + oldAnchor.aspectRatio) - ln(1f + candAnchor.aspectRatio))
        val widthDiff = abs(oldAnchor.widthRatio - candAnchor.widthRatio)
        val heightDiff = abs(oldAnchor.heightRatio - candAnchor.heightRatio)
        val geomSim = (1.0f - (aspectDiff * 0.45f + widthDiff * 0.8f + heightDiff * 1.2f)).coerceIn(0f, 1f)

        // 若候選元件本身毫無關聯標籤且角色也不相符，壓低純幾何分數避免誤配無關按鈕
        if (overlapCount == 0 && !roleMatches) {
            return (cosineSim * 0.45f + geomSim * 0.15f).coerceIn(0f, 0.58f)
        }

        return (0.48f * cosineSim + 0.34f * roleAndTagScore.coerceIn(0f, 1f) + 0.18f * geomSim).coerceIn(0f, 1f)
    }

    /**
     * 在改版後的候選節點清單中，根據舊錨點的語義向量與幾何特徵找出最佳「新替身 (New Avatar)」
     */
    fun findBestSelfHealingAvatar(
        context: Context?,
        packageName: String,
        candidates: List<Stage2ConvergedNode>,
        stage: InAppOperationStage,
        query: String = "",
        subGoalKeyword: String = "",
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): Pair<Stage2ConvergedNode, AvatarMatchResult>? {
        ensureLoaded(context)
        val pkg = packageName.trim()
        if (pkg.isEmpty() || candidates.isEmpty()) return null
        val prof = profiles[pkg] ?: return null

        val normQuery = normalizeLabel(query)
        val normSubGoal = normalizeLabel(subGoalKeyword)
        val targetAnchors = ArrayList<LearnedNodeAnchor>()
        if (stage == InAppOperationStage.STAGE_A_EXPAND_SEARCH) {
            targetAnchors.addAll(prof.verifiedSearchEntries)
        }
        for (a in prof.verifiedTargetClicks) {
            if ((normQuery.isNotEmpty() && intentKeysMatch(a.intentKey, normQuery)) ||
                (normSubGoal.isNotEmpty() && intentKeysMatch(a.intentKey, normSubGoal)) ||
                (normQuery.isEmpty() && normSubGoal.isEmpty())
            ) {
                targetAnchors.add(a)
            }
        }
        if (targetAnchors.isEmpty()) return null

        var bestNode: Stage2ConvergedNode? = null
        var bestMatch: AvatarMatchResult? = null

        for (cand in candidates) {
            for (anchor in targetAnchors) {
                val combinedSim = computeAvatarSimilarity(anchor, cand, screenWidth, screenHeight)
                if (combinedSim >= AVATAR_SIMILARITY_THRESHOLD) {
                    val candVec = buildAnchorFromNode(cand, anchor.intentKey, screenWidth, screenHeight).semanticEmbedding
                    val cosSim = cosineSimilarity(anchor.semanticEmbedding, candVec)
                    val boost = 28f * combinedSim
                    if (bestMatch == null || combinedSim > bestMatch.combinedSimilarity) {
                        bestNode = cand
                        bestMatch = AvatarMatchResult(
                            matchedAnchor = anchor,
                            cosineSimilarity = cosSim,
                            combinedSimilarity = combinedSim,
                            scoreBoost = boost,
                            diagnosticBadge = "向量自癒匹配新替身(sim=${(combinedSim * 100).toInt()}%)"
                        )
                    }
                }
            }
        }
        return if (bestNode != null && bestMatch != null) bestNode to bestMatch else null
    }

    /**
     * 當點擊某元件導致「走進無目標死胡同需自動返回」或「誤跳出外部 App / 廣告劫持」時，記錄為死胡同陷阱
     */
    @Synchronized
    fun recordDeadEndTrap(
        context: Context?,
        packageName: String,
        screenFp: String,
        node: Stage2ConvergedNode
    ) {
        ensureLoaded(context)
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        val prof = getOrCreateProfile(pkg, "")
        val scopedKey = makeTrapKey(screenFp, node)
        val globalKey = makeTrapKey("*", node)

        while (prof.deadEndTrapKeys.size >= MAX_TRAPS_PER_APP) {
            val first = prof.deadEndTrapKeys.firstOrNull() ?: break
            prof.deadEndTrapKeys.remove(first)
        }
        prof.deadEndTrapKeys.add(scopedKey)
        prof.deadEndTrapKeys.add(globalKey)
        prof.lastUpdatedMs = System.currentTimeMillis()
        saveToDisk(context)
    }

    /**
     * 【R3 跨頁面經驗記憶圖譜】記錄跨畫面跳轉路徑
     */
    @Synchronized
    fun recordTransitionPath(
        context: Context?,
        packageName: String,
        goalIntent: String,
        steps: List<GraphTransitionStep>
    ) {
        ensureLoaded(context)
        val pkg = packageName.trim()
        if (pkg.isEmpty() || steps.isEmpty()) return
        val normGoal = normalizeLabel(goalIntent)
        val startFp = steps.first().fromScreenFp
        val targetFp = steps.last().toScreenFp

        val prof = getOrCreateProfile(pkg, "")
        val existing = prof.cachedTransitionPaths.firstOrNull { p ->
            p.startScreenFp == startFp && p.targetScreenFp == targetFp &&
                (normGoal.isEmpty() || p.goalIntent.isEmpty() || p.goalIntent == normGoal ||
                    p.goalIntent.contains(normGoal) || normGoal.contains(p.goalIntent))
        }

        if (existing != null) {
            existing.successCount++
            existing.lastUpdatedMs = System.currentTimeMillis()
        } else {
            evictLowestValuePathIfNeeded(prof.cachedTransitionPaths, MAX_PATHS_PER_APP)
            val pathId = "path_${pkg.substringAfterLast('.')}_${startFp.take(8)}_to_${targetFp.take(8)}_${System.currentTimeMillis() % 10000}"
            prof.cachedTransitionPaths.add(
                GraphTransitionPath(
                    pathId = pathId,
                    goalIntent = normGoal,
                    startScreenFp = startFp,
                    targetScreenFp = targetFp,
                    steps = steps,
                    successCount = 1,
                    lastUpdatedMs = System.currentTimeMillis()
                )
            )
        }
        prof.totalSuccessCount++
        prof.lastUpdatedMs = System.currentTimeMillis()
        saveToDisk(context)
    }

    /**
     * 【R3 跨頁面經驗記憶圖譜】根據當前畫面指紋與使用者意圖尋找快顯路徑中的下一步進
     */
    fun findNextStepInPath(
        packageName: String,
        currentScreenFp: String,
        userGoal: String
    ): GraphTransitionStep? {
        return findNextStepInPath(null, packageName, currentScreenFp, userGoal)
    }

    fun findNextStepInPath(
        context: Context?,
        packageName: String,
        currentScreenFp: String,
        userGoal: String
    ): GraphTransitionStep? {
        ensureLoaded(context)
        val pkg = packageName.trim()
        if (pkg.isEmpty() || currentScreenFp.isBlank()) return null
        val prof = profiles[pkg] ?: return null
        val cleanUser = cleanIntent(userGoal)
        val cleanFp = currentScreenFp.trim()

        val matchingPaths = prof.cachedTransitionPaths
            .filter { p ->
                if (cleanUser.isEmpty()) true
                else {
                    val cleanPath = cleanIntent(p.goalIntent)
                    cleanPath.isEmpty() || cleanPath.contains(cleanUser) || cleanUser.contains(cleanPath)
                }
            }
            .sortedByDescending { it.successCount }

        for (path in matchingPaths) {
            val step = path.steps.firstOrNull { st ->
                st.fromScreenFp == cleanFp ||
                    (cleanFp.isNotEmpty() && st.fromScreenFp.contains(cleanFp)) ||
                    (st.fromScreenFp.isNotEmpty() && cleanFp.contains(st.fromScreenFp))
            }
            if (step != null) {
                return step
            }
        }

        if (cleanUser.isNotEmpty()) {
            for (path in prof.cachedTransitionPaths.sortedByDescending { it.successCount }) {
                val step = path.steps.firstOrNull { st ->
                    st.fromScreenFp == cleanFp ||
                        (cleanFp.isNotEmpty() && st.fromScreenFp.contains(cleanFp)) ||
                        (st.fromScreenFp.isNotEmpty() && cleanFp.contains(st.fromScreenFp))
                }
                if (step != null) {
                    return step
                }
            }
        }

        return null
    }

    /**
     * 根據該 App 的歷史經驗記憶，對當前候選元件進行加分、死胡同扣分、或改版後「新替身」餘弦相似度自癒加分
     */
    fun computeEpisodicScoreAdjustment(
        context: Context?,
        packageName: String,
        candidate: Stage2ConvergedNode,
        stage: InAppOperationStage,
        query: String = "",
        subGoalKeyword: String = "",
        screenFp: String = "",
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): Pair<Float, String?> {
        ensureLoaded(context)
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return 0f to null
        val prof = profiles[pkg] ?: return 0f to null

        val scopedTrapKey = makeTrapKey(screenFp, candidate)
        val globalTrapKey = makeTrapKey("*", candidate)
        if (prof.deadEndTrapKeys.contains(scopedTrapKey) || prof.deadEndTrapKeys.contains(globalTrapKey)) {
            return -55f to "經驗記憶避開死胡同/陷阱"
        }

        val candLabel = normalizeLabel(candidate.label)
        val gx = candidate.gridTapPoint.x
        val gy = candidate.gridTapPoint.y

        // 1. 跨頁面路徑圖譜比對 (R3: 跨頁面路徑步進 +35f，支援改版自癒替身匹配)
        if (screenFp.isNotEmpty()) {
            val goalForPath = if (subGoalKeyword.isNotEmpty()) subGoalKeyword else query
            val nextStep = findNextStepInPath(context, pkg, screenFp, goalForPath)
            if (nextStep != null) {
                val a = nextStep.targetAnchor
                if (a.confidenceWeight > 0f) {
                    val dist = abs(a.gridX - gx) + abs(a.gridY - gy)
                    val labelMatch = a.label.isNotEmpty() && a.label == candLabel
                    val coordAndCompatMatch = dist < 100 &&
                        (a.label.isEmpty() || labelMatch || computeAvatarSimilarity(a, candidate, screenWidth, screenHeight) >= 0.55f)
                    if (a.shortTypeTag == candidate.shortTypeTag && (labelMatch || coordAndCompatMatch)) {
                        return (35f * a.confidenceWeight) to "經驗記憶圖譜命中跨頁面路徑步進: ${nextStep.fromScreenFp} -> ${nextStep.toScreenFp}"
                    }
                } else {
                    val avatarSim = computeAvatarSimilarity(a, candidate, screenWidth, screenHeight)
                    if (avatarSim >= AVATAR_SIMILARITY_THRESHOLD) {
                        return (30f * avatarSim) to "經驗圖譜向量自癒匹配新替身(sim=${(avatarSim * 100).toInt()}%)"
                    }
                }
            }
        }

        // 2. Stage A：比對已驗證成功的搜尋入口錨點（若原位錨點已因改版歸零或位移，自動以餘弦相似度匹配新替身）
        if (stage == InAppOperationStage.STAGE_A_EXPAND_SEARCH) {
            val matchedSearch = prof.verifiedSearchEntries.firstOrNull { a ->
                a.confidenceWeight > 0f &&
                    a.shortTypeTag == candidate.shortTypeTag &&
                    ((a.label.isNotEmpty() && a.label == candLabel && abs(a.gridX - gx) + abs(a.gridY - gy) < 160) ||
                        (abs(a.gridX - gx) + abs(a.gridY - gy) < 120 &&
                            (a.label.isEmpty() || a.label == candLabel || computeAvatarSimilarity(a, candidate, screenWidth, screenHeight) >= 0.55f)))
            }
            if (matchedSearch != null) {
                return (24f * matchedSearch.confidenceWeight) to "經驗記憶命中搜尋入口"
            }

            // 檢查是否存在待自癒或改版位移之搜尋入口錨點，進行跨座標餘弦相似度匹配
            var bestSearchAvatarSim = 0f
            for (a in prof.verifiedSearchEntries) {
                val sim = computeAvatarSimilarity(a, candidate, screenWidth, screenHeight)
                if (sim > bestSearchAvatarSim) bestSearchAvatarSim = sim
            }
            if (bestSearchAvatarSim >= AVATAR_SIMILARITY_THRESHOLD) {
                return (26f * bestSearchAvatarSim) to "向量自癒匹配搜尋入口新替身(sim=${(bestSearchAvatarSim * 100).toInt()}%)"
            }
        }

        // 3. 比對已驗證成功的目標點擊錨點（含改版信心歸零後的餘弦相似度新替身匹配）
        val normQuery = normalizeLabel(query)
        val normSubGoal = normalizeLabel(subGoalKeyword)
        val relevantTargetAnchors = prof.verifiedTargetClicks.filter { a ->
            (normQuery.isNotEmpty() && intentKeysMatch(a.intentKey, normQuery)) ||
                (normSubGoal.isNotEmpty() && intentKeysMatch(a.intentKey, normSubGoal))
        }

        val matchedTarget = relevantTargetAnchors.firstOrNull { a ->
            a.confidenceWeight > 0f &&
                a.shortTypeTag == candidate.shortTypeTag &&
                ((a.label.isNotEmpty() && a.label == candLabel && abs(a.gridX - gx) + abs(a.gridY - gy) < 160) ||
                    (abs(a.gridX - gx) + abs(a.gridY - gy) < 100 &&
                        (a.label.isEmpty() || a.label == candLabel || computeAvatarSimilarity(a, candidate, screenWidth, screenHeight) >= 0.55f)))
        }
        if (matchedTarget != null) {
            return (20f * matchedTarget.confidenceWeight) to "經驗記憶命中成功路徑"
        }

        // 若目標錨點已因改版歸零 (confidenceWeight == 0f) 或座標大位移，計算餘弦相似度尋找新替身
        var bestTargetAvatarSim = 0f
        for (a in relevantTargetAnchors) {
            val sim = computeAvatarSimilarity(a, candidate, screenWidth, screenHeight)
            if (sim > bestTargetAvatarSim) bestTargetAvatarSim = sim
        }
        if (bestTargetAvatarSim >= AVATAR_SIMILARITY_THRESHOLD) {
            return (28f * bestTargetAvatarSim) to "向量自癒匹配目標新替身(sim=${(bestTargetAvatarSim * 100).toInt()}%)"
        }

        return 0f to null
    }

    /**
     * 產生繁體中文經驗記憶統計徽章，供主畫面或懸浮 HUD 顯示
     */
    fun getMemoryStatsBadge(context: Context?, currentPackage: String = ""): String {
        ensureLoaded(context)
        val appCount = profiles.size
        var totalPaths = 0
        var totalTraps = 0
        var totalHeals = 0
        for ((_, p) in profiles) {
            totalPaths += p.verifiedSearchEntries.size + p.verifiedTargetClicks.size + p.cachedTransitionPaths.size
            totalTraps += p.deadEndTrapKeys.size / 2
            totalHeals += p.verifiedSearchEntries.sumOf { it.selfHealCount } + p.verifiedTargetClicks.sumOf { it.selfHealCount }
        }
        val curPkg = currentPackage.trim()
        val curProf = if (curPkg.isNotEmpty()) profiles[curPkg] else null
        val healSuffix = if (totalHeals > 0) " / 自癒 $totalHeals 次" else ""
        val curSuffix = if (curProf != null) {
            " | 當前App(${curProf.appLabel}): ${curProf.verifiedSearchEntries.size + curProf.verifiedTargetClicks.size}路徑/${curProf.cachedTransitionPaths.size}跨頁/${(curProf.deadEndTrapKeys.size / 2)}陷阱"
        } else ""
        return "經驗記憶庫：已學習 $appCount 款 App / $totalPaths 條成功路徑$healSuffix / 避開 $totalTraps 個陷阱$curSuffix"
    }

    fun getProfile(context: Context?, packageName: String): AppEpisodicProfile? {
        ensureLoaded(context)
        return profiles[packageName.trim()]
    }

    @Synchronized
    fun clearMemory(context: Context?) {
        profiles.clear()
        Phase2ButtonFunctionalityClassifier.clearDynamicPriorOverrides()
        isLoaded = true
        if (context != null) {
            val appContext = context.applicationContext ?: context
            synchronized(fileLock) {
                runCatching {
                    val file = File(appContext.filesDir, MEMORY_FILE_NAME)
                    if (file.exists()) file.delete()
                    val temp = File(appContext.filesDir, "$MEMORY_FILE_NAME.tmp")
                    if (temp.exists()) temp.delete()
                }
            }
        }
    }

    fun resetForTesting() {
        profiles.clear()
        Phase2ButtonFunctionalityClassifier.clearDynamicPriorOverrides()
        isLoaded = false
        savePending.set(false)
        isSaving.set(false)
    }

    private fun overwriteAnchorInPlace(
        target: LearnedNodeAnchor,
        fresh: LearnedNodeAnchor,
        newHealCount: Int
    ) {
        target.label = fresh.label
        target.shortTypeTag = fresh.shortTypeTag
        target.gridX = fresh.gridX
        target.gridY = fresh.gridY
        target.structuralPathSig = fresh.structuralPathSig
        target.functionRoleCode = fresh.functionRoleCode
        target.semanticTags = fresh.semanticTags
        target.semanticEmbedding = fresh.semanticEmbedding
        target.semanticEmbedding1024 = fresh.semanticEmbedding1024
        target.geometricIconTag = fresh.geometricIconTag
        target.widthRatio = fresh.widthRatio
        target.heightRatio = fresh.heightRatio
        target.aspectRatio = fresh.aspectRatio
        target.nanoDetCategory = fresh.nanoDetCategory
        target.confidenceWeight = 1.0f
        target.isExpired = false
        target.selfHealCount = newHealCount
        target.successCount++
    }

    fun inferGeometricIconTag(
        label: String,
        functionRoleCode: String,
        nanoDetCategory: String,
        shortTypeTag: String,
        aspectRatio: Float
    ): String {
        val combined = "$label $functionRoleCode $nanoDetCategory".lowercase()
        return when {
            combined.contains("search") || combined.contains("搜尋") || combined.contains("搜索") || combined.contains("放大鏡") ->
                "ICON_SEARCH_MAGNIFIER"
            combined.contains("compose") || combined.contains("add") || combined.contains("plus") || combined.contains("新增") || combined.contains("建立") ->
                "ICON_ADD_PLUS"
            combined.contains("send") || combined.contains("submit") || combined.contains("傳送") || combined.contains("發送") ->
                "ICON_SEND_ARROW"
            combined.contains("setting") || combined.contains("gear") || combined.contains("設定") || combined.contains("選單") ->
                "ICON_SETTINGS_GEAR"
            combined.contains("close") || combined.contains("dismiss") || combined.contains("關閉") ->
                "ICON_CLOSE_CROSS"
            combined.contains("back") || combined.contains("返回") || combined.contains("上一頁") ->
                "ICON_BACK_CHEVRON"
            combined.contains("share") || combined.contains("分享") ->
                "ICON_SHARE_NODE"
            combined.contains("play") || combined.contains("播放") ->
                "ICON_PLAY_TRIANGLE"
            shortTypeTag == "INP" || aspectRatio >= 3.0f ->
                "GEOM_WIDE_INPUT_BAR"
            shortTypeTag == "ICO" || (aspectRatio in 0.75f..1.35f) ->
                "GEOM_SQUARE_ICON"
            else ->
                "GEOM_PILL_BUTTON"
        }
    }

    private fun healTransitionPathAnchors(
        prof: AppEpisodicProfile,
        intentKey: String,
        fresh: LearnedNodeAnchor
    ) {
        for (path in prof.cachedTransitionPaths) {
            for (st in path.steps) {
                val a = st.targetAnchor
                if (a.confidenceWeight <= 0f || intentKeysMatch(a.intentKey, intentKey)) {
                    overwriteAnchorInPlace(a, fresh, a.selfHealCount + 1)
                }
            }
        }
    }

    private fun intentKeysMatch(storedIntent: String, queryIntent: String): Boolean {
        if (storedIntent.isEmpty() || queryIntent.isEmpty()) return false
        if (storedIntent == queryIntent || storedIntent.contains(queryIntent) || queryIntent.contains(storedIntent)) {
            return true
        }
        val sTags = extractSemanticTagsAndSynonyms(storedIntent, storedIntent, "", "")
        val qTags = extractSemanticTagsAndSynonyms(queryIntent, queryIntent, "", "")
        return sTags.intersect(qTags.toSet()).isNotEmpty()
    }

    private fun computeSpatialZoneTag(gridX: Int, gridY: Int): String {
        val vZone = when {
            gridY < 180 -> "TOP_BAR"
            gridY > 820 -> "BOTTOM_NAV"
            gridY > 680 && gridX > 720 -> "FAB_CORNER"
            else -> "CONTENT_BODY"
        }
        val hZone = when {
            gridX < 280 -> "LEFT"
            gridX > 720 -> "RIGHT"
            else -> "CENTER"
        }
        return "${vZone}_$hZone"
    }

    private val CONCEPT_CLUSTERS: List<Set<String>> = listOf(
        // 0: SEARCH_EXPLORE
        setOf("搜尋", "搜索", "查找", "查詢", "探索", "發現", "放大鏡", "找", "查", "search", "explore", "find", "discover", "magnifying", "lookup", "search_entry", "search_input_box"),
        // 1: SEND_SUBMIT_CONFIRM
        setOf("傳送", "發送", "送出", "提交", "確認", "確定", "完成", "下一步", "繼續", "套用", "儲存", "send", "submit", "confirm", "done", "next", "continue", "apply", "save", "ok"),
        // 2: BACK_RETURN
        setOf("返回", "上一頁", "回上頁", "後退", "back", "navigate up", "return", "nav_back"),
        // 3: CLOSE_DISMISS
        setOf("關閉", "略過", "跳過", "取消", "不要", "稍後", "close", "dismiss", "skip", "cancel", "not now"),
        // 4: COMPOSE_CREATE_ADD
        setOf("撰寫", "新增", "建立", "發起", "寫信", "張貼", "發佈", "加入", "加號", "compose", "create", "add", "new", "post", "plus", "fab"),
        // 5: SETTINGS_MENU_MORE
        setOf("設定", "設置", "選單", "更多", "更多選項", "偏好設定", "管理", "settings", "menu", "more", "options", "preferences", "drawer"),
        // 6: PLAY_MEDIA
        setOf("播放", "暫停", "收聽", "開始播放", "隨機播放", "音樂", "影片", "play", "pause", "listen", "resume", "shuffle"),
        // 7: CART_BUY_CHECKOUT
        setOf("購物車", "購物袋", "購買", "立即購買", "結帳", "買單", "下單", "訂單", "cart", "bag", "buy", "checkout", "order", "purchase"),
        // 8: SHARE_EXPORT
        setOf("分享", "轉發", "匯出", "複製連結", "share", "repost", "forward", "export", "copy link"),
        // 9: LIKE_FAVORITE_BOOKMARK
        setOf("按讚", "喜歡", "收藏", "書籤", "訂閱", "追蹤", "願望清單", "like", "favorite", "bookmark", "subscribe", "follow", "wishlist"),
        // 10: FILTER_SORT
        setOf("篩選", "排序", "分類", "全部", "最新", "熱門", "filter", "sort", "category", "all", "latest"),
        // 11: HOME_MAIN_TAB
        setOf("首頁", "主頁", "主畫面", "動態", "home", "main", "feed"),
        // 12: PROFILE_ACCOUNT
        setOf("個人檔案", "我的", "帳戶", "帳號", "會員", "個人中心", "profile", "account", "me", "user", "member"),
        // 13: DELETE_REMOVE_TRASH
        setOf("刪除", "移除", "垃圾桶", "清除", "封存", "delete", "remove", "trash", "clear", "archive")
    )

    private fun inferCanonicalRoleFromText(label: String, intentKey: String, shortTypeTag: String): String {
        val combined = "$label $intentKey".lowercase()
        return when {
            shortTypeTag == "INP" -> "SEARCH_INPUT_BOX"
            CONCEPT_CLUSTERS[0].any { combined.contains(it) } -> "SEARCH_ENTRY"
            CONCEPT_CLUSTERS[1].any { combined.contains(it) } -> "SEND_SUBMIT_CONFIRM"
            CONCEPT_CLUSTERS[2].any { combined.contains(it) } -> "NAV_BACK"
            CONCEPT_CLUSTERS[3].any { combined.contains(it) } -> "CLOSE_DISMISS"
            CONCEPT_CLUSTERS[4].any { combined.contains(it) } -> "COMPOSE_CREATE_ADD"
            CONCEPT_CLUSTERS[5].any { combined.contains(it) } -> "SETTINGS_MENU_MORE"
            CONCEPT_CLUSTERS[6].any { combined.contains(it) } -> "MEDIA_PLAY_PAUSE"
            CONCEPT_CLUSTERS[7].any { combined.contains(it) } -> "ADD_TO_CART_BUY"
            CONCEPT_CLUSTERS[8].any { combined.contains(it) } -> "SHARE_EXPORT"
            CONCEPT_CLUSTERS[9].any { combined.contains(it) } -> "LIKE_FAVORITE_BOOKMARK"
            CONCEPT_CLUSTERS[10].any { combined.contains(it) } -> "FILTER_SORT_CHIP"
            CONCEPT_CLUSTERS[11].any { combined.contains(it) } -> "NAV_HOME_TAB"
            CONCEPT_CLUSTERS[12].any { combined.contains(it) } -> "NAV_PROFILE_ACCOUNT_TAB"
            CONCEPT_CLUSTERS[13].any { combined.contains(it) } -> "DELETE_REMOVE_TRASH"
            else -> "GENERIC_ACTION_BUTTON"
        }
    }

    fun extractSemanticTagsAndSynonyms(
        label: String,
        intentKey: String,
        functionRoleCode: String,
        nanoDetCategory: String
    ): List<String> {
        val out = LinkedHashSet<String>()
        val rawCombined = "$label $intentKey ${functionRoleCode.lowercase()} ${nanoDetCategory.lowercase()}".lowercase()
        val tokenRegex = Regex("[a-z0-9_]{2,20}|[\u4e00-\u9fa5]{1,12}")
        for (m in tokenRegex.findAll(rawCombined)) {
            val tok = m.value.trim()
            if (tok.isNotEmpty()) out.add(tok)
        }
        // 加入同義詞概念簇展開標籤，使「搜尋」與「查找內容/探索」具備交集
        for ((clusterIdx, cluster) in CONCEPT_CLUSTERS.withIndex()) {
            if (cluster.any { kw -> kw.length >= 2 && rawCombined.contains(kw) } ||
                (clusterIdx == 0 && (functionRoleCode == "SEARCH_ENTRY" || functionRoleCode == "NAV_EXPLORE_DISCOVER_TAB")) ||
                (clusterIdx == 1 && functionRoleCode == "SEND_SUBMIT_CONFIRM") ||
                (clusterIdx == 4 && functionRoleCode == "COMPOSE_CREATE_ADD") ||
                (clusterIdx == 5 && functionRoleCode == "SETTINGS_MENU_MORE")
            ) {
                out.add("concept_cluster_$clusterIdx")
                cluster.take(4).forEach { out.add(it) }
            }
        }
        return out.take(24)
    }

    /**
     * 建構 32 維 L2 正規化本地語義與視覺幾何特徵向量 (32D L2-Normalized Semantic Embedding)
     */
    fun buildSemanticFeatureVector(
        label: String,
        intentKey: String,
        shortTypeTag: String,
        functionRoleCode: String,
        semanticTags: List<String>,
        widthRatio: Float,
        heightRatio: Float,
        aspectRatio: Float,
        nanoDetCategory: String
    ): FloatArray {
        val vec = FloatArray(SEMANTIC_VECTOR_DIM)
        val combinedText = "$label $intentKey ${semanticTags.joinToString(" ")}".lowercase()

        // Dims 0..13: 14 大核心語義概念簇激活度
        for (i in 0 until 14) {
            val cluster = CONCEPT_CLUSTERS[i]
            val tagMarker = "concept_cluster_$i"
            if (semanticTags.contains(tagMarker) || cluster.any { kw -> kw.length >= 2 && combinedText.contains(kw) }) {
                vec[i] = 1.0f
            }
        }

        // Dims 14..19: 針對任意長尾自訂文字（如「藍牙」、「深色模式」、「帳號安全」）之確定性字元 N-Gram 雜湊投影
        val cleanCustom = cleanIntent("$label $intentKey")
        if (cleanCustom.isNotEmpty()) {
            for (i in 0 until cleanCustom.length) {
                val uniHash = (cleanCustom[i].code * 31 + 17).ushr(0)
                val slot1 = 14 + (uniHash % 6)
                vec[slot1] += 0.45f
                if (i + 1 < cleanCustom.length) {
                    val biHash = (cleanCustom[i].code * 131 + cleanCustom[i + 1].code * 37).ushr(0)
                    val slot2 = 14 + (biHash % 6)
                    vec[slot2] += 0.65f
                }
            }
        }

        // Dims 20..25: 功能角色子空間 (Function Role Subspace)
        when (functionRoleCode) {
            "SEARCH_ENTRY" -> {
                vec[20] = 1.0f
                vec[21] = 0.45f
            }
            "NAV_EXPLORE_DISCOVER_TAB" -> {
                vec[20] = 0.75f
                vec[21] = 0.85f
            }
            "SEARCH_INPUT_BOX", "SEARCH_SUBMIT" -> {
                vec[20] = 0.80f
                vec[22] = 0.70f
            }
            "SEND_SUBMIT_CONFIRM" -> vec[22] = 1.0f
            "COMPOSE_CREATE_ADD" -> vec[23] = 1.0f
            "SETTINGS_MENU_MORE", "FILTER_SORT_CHIP" -> vec[24] = 1.0f
            "NAV_BACK", "CLOSE_DISMISS" -> vec[25] = 1.0f
            else -> {
                val rHash = functionRoleCode.hashCode().ushr(1) % 6
                vec[20 + rHash] += 0.45f
            }
        }

        // Dims 26..31: 視覺幾何與元件互動屬性子空間 (Visual Geometric & Type Subspace)
        vec[26] = when (shortTypeTag) {
            "BTN" -> 0.90f
            "ICO" -> 0.82f
            "INP" -> -0.90f
            "CHK" -> 0.60f
            else -> 0.30f
        }
        vec[27] = widthRatio.coerceIn(0f, 1f) * 0.8f
        vec[28] = heightRatio.coerceIn(0f, 1f) * 1.2f
        vec[29] = (ln(1f + aspectRatio.coerceIn(0.1f, 15f)) / 2.8f).coerceIn(0f, 1f)
        vec[30] = if (nanoDetCategory.isNotBlank()) 0.75f else 0.15f
        vec[31] = 0.25f

        // L2 正規化 (Unit Norm)
        var normSq = 0f
        for (v in vec) normSq += v * v
        val norm = sqrt(normSq)
        if (norm > 1e-6f) {
            for (i in vec.indices) {
                vec[i] /= norm
            }
        }
        return vec
    }

    /**
     * 計算兩個特徵向量的餘弦相似度 (Cosine Similarity in [-1.0, 1.0])
     */
    fun cosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.isEmpty() || vecB.isEmpty()) return 0f
        val len = min(vecA.size, vecB.size)
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in 0 until len) {
            val a = vecA[i]
            val b = vecB[i]
            dot += a * b
            normA += a * a
            normB += b * b
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-6f) (dot / denom).coerceIn(-1f, 1f) else 0f
    }

    /**
     * 【中層記憶價值感知淘汰 (Value-Aware Eviction)】
     * 當單一 App 錨點容量達到上限時：
     * 1. 優先清除所有已標記過期 (`isExpired == true` 或 `confidenceWeight <= 0f`) 的無效殘留錨點；
     * 2. 若清除過期項目後仍滿載，則根據綜合價值分數 (`confidenceWeight * 100 + successCount * 10 + selfHealCount * 2`)
     *    淘汰價值最低的錨點，徹底避免傳統 FIFO 誤刪高頻成功的核心按鈕記憶。
     */
    internal fun evictLowestValueAnchorIfNeeded(
        anchors: MutableList<LearnedNodeAnchor>,
        maxCapacity: Int = MAX_ANCHORS_PER_APP
    ) {
        if (anchors.size < maxCapacity) return
        anchors.removeAll { it.isExpired || it.confidenceWeight <= 0f }
        while (anchors.size >= maxCapacity && anchors.isNotEmpty()) {
            var lowestIdx = 0
            var lowestScore = computeAnchorValueScore(anchors[0])
            for (i in 1 until anchors.size) {
                val score = computeAnchorValueScore(anchors[i])
                if (score < lowestScore) {
                    lowestScore = score
                    lowestIdx = i
                }
            }
            anchors.removeAt(lowestIdx)
        }
    }

    internal fun computeAnchorValueScore(anchor: LearnedNodeAnchor): Float {
        if (anchor.isExpired || anchor.confidenceWeight <= 0f) return -1f
        return anchor.confidenceWeight * 100f +
            anchor.successCount.coerceAtMost(50) * 10f +
            anchor.selfHealCount.coerceAtMost(20) * 2f
    }

    internal fun evictLowestValuePathIfNeeded(
        paths: MutableList<GraphTransitionPath>,
        maxCapacity: Int = MAX_PATHS_PER_APP
    ) {
        if (paths.size < maxCapacity) return
        paths.removeAll { p ->
            p.steps.any { st -> st.targetAnchor.isExpired || st.targetAnchor.confidenceWeight <= 0f }
        }
        val pathComparator = compareBy<GraphTransitionPath> { it.successCount }
            .thenBy { it.lastUpdatedMs }
        while (paths.size >= maxCapacity && paths.isNotEmpty()) {
            var lowestIdx = 0
            for (i in 1 until paths.size) {
                if (pathComparator.compare(paths[i], paths[lowestIdx]) < 0) {
                    lowestIdx = i
                }
            }
            paths.removeAt(lowestIdx)
        }
    }

    private const val NEW_PROFILE_GRACE_WINDOW_MS = 10 * 60_000L

    private fun getOrCreateProfile(pkg: String, appLabel: String): AppEpisodicProfile {
        val existing = profiles[pkg]
        if (existing != null) {
            if (appLabel.isNotBlank() && existing.appLabel == pkg) {
                existing.appLabel = appLabel
            }
            return existing
        }
        if (profiles.size >= MAX_PROFILES_GLOBAL) {
            val nowMs = System.currentTimeMillis()
            val matureEntries = profiles.entries.filter { (nowMs - it.value.lastUpdatedMs) >= NEW_PROFILE_GRACE_WINDOW_MS }
            val candidatePool = if (matureEntries.isNotEmpty()) matureEntries else profiles.entries.toList()
            val victimKey = candidatePool.minWithOrNull(
                compareBy<Map.Entry<String, AppEpisodicProfile>> { it.value.totalSuccessCount }
                    .thenBy { it.value.lastUpdatedMs }
            )?.key
            if (victimKey != null) {
                profiles.remove(victimKey)
            }
        }
        val created = AppEpisodicProfile(
            packageName = pkg,
            appLabel = appLabel.ifBlank { pkg.substringAfterLast('.') }
        )
        profiles[pkg] = created
        return created
    }

    private fun normalizeLabel(raw: String): String {
        return raw.trim().lowercase().take(24)
    }

    private fun cleanIntent(s: String): String {
        return s.lowercase().filter { it.isLetterOrDigit() || it in '\u4e00'..'\u9fa5' }
    }

    private fun makeTrapKey(screenFp: String, node: Stage2ConvergedNode): String {
        val fp = screenFp.trim().ifEmpty { "*" }
        val normLabel = normalizeLabel(node.label)
        val qx = node.gridTapPoint.x / 60
        val qy = node.gridTapPoint.y / 60
        return "$fp|${node.shortTypeTag}|$normLabel|${qx}_${qy}"
    }
}

/**
 * 輕量級純 Kotlin JSON 結構解析器，消除對 host JVM 模擬環境中 unmocked org.json 的依賴
 */
internal object MiniJson {
    fun parse(json: String): Map<String, Any?>? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return null
        return Parser(trimmed).parseObjectRoot()
    }

    private class Parser(private val text: String) {
        private var idx = 0

        fun parseObjectRoot(): Map<String, Any?>? {
            return try {
                parseObject()
            } catch (_: Throwable) {
                null
            }
        }

        private fun skipWhitespace() {
            while (idx < text.length && text[idx].isWhitespace()) idx++
        }

        private fun parseString(): String {
            idx++ // skip opening quote
            val sb = StringBuilder()
            while (idx < text.length) {
                val c = text[idx++]
                if (c == '"') break
                if (c == '\\' && idx < text.length) {
                    val esc = text[idx++]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (idx + 4 <= text.length) {
                                val hex = text.substring(idx, idx + 4)
                                idx += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Number {
            val start = idx
            if (idx < text.length && (text[idx] == '-' || text[idx] == '+')) idx++
            while (idx < text.length && (text[idx].isDigit() || text[idx] == '.' || text[idx] == 'e' || text[idx] == 'E' || text[idx] == '-' || text[idx] == '+')) {
                idx++
            }
            val numStr = text.substring(start, idx)
            return if (numStr.contains('.') || numStr.contains('e', ignoreCase = true)) {
                numStr.toDoubleOrNull() ?: 0.0
            } else {
                numStr.toLongOrNull() ?: 0L
            }
        }

        private fun parseArray(): List<Any?> {
            idx++ // skip '['
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (idx < text.length && text[idx] == ']') {
                idx++
                return list
            }
            while (idx < text.length) {
                list.add(parseValue())
                skipWhitespace()
                if (idx < text.length && text[idx] == ',') {
                    idx++
                    skipWhitespace()
                } else if (idx < text.length && text[idx] == ']') {
                    idx++
                    break
                }
            }
            return list
        }

        private fun parseObject(): Map<String, Any?> {
            idx++ // skip '{'
            val map = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (idx < text.length && text[idx] == '}') {
                idx++
                return map
            }
            while (idx < text.length) {
                skipWhitespace()
                if (idx >= text.length || text[idx] != '"') break
                val key = parseString()
                skipWhitespace()
                if (idx < text.length && text[idx] == ':') idx++
                skipWhitespace()
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (idx < text.length && text[idx] == ',') {
                    idx++
                    skipWhitespace()
                } else if (idx < text.length && text[idx] == '}') {
                    idx++
                    break
                }
            }
            return map
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (idx >= text.length) return null
            return when (val c = text[idx]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> {
                    if (text.startsWith("true", idx)) { idx += 4; true }
                    else if (text.startsWith("false", idx)) { idx += 5; false }
                    else null
                }
                'n' -> {
                    if (text.startsWith("null", idx)) { idx += 4 }
                    null
                }
                else -> {
                    if (c.isDigit() || c == '-') parseNumber() else null
                }
            }
        }
    }
}
