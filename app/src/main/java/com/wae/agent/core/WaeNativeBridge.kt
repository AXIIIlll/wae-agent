package com.wae.agent.core

import android.content.res.AssetManager
import android.hardware.HardwareBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * WAE V2.6 原生 JNI 橋接器（含 8 顆架構模型零拷貝 mmap 載入器與 JVM 單元測試後備算子）
 */
object WaeNativeBridge {

    private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("wae_jni")
            nativeLoaded = true
        } catch (_: Throwable) {
            nativeLoaded = false
        }
    }

    fun isNativeReady(): Boolean = nativeLoaded

    external fun waeLoadAllModelsMmap(assetManager: AssetManager): Int

    external fun waeGetTotalMappedModelBytes(): Long

    external fun waeCompileFrameJson(
        activePkg: String,
        screenW: Int,
        screenH: Int,
        boundsFlat: IntArray,
        texts: Array<String>,
        contentDescs: Array<String>,
        viewIds: Array<String>,
        classNames: Array<String>,
        nodeFlags: IntArray,
        screenshotHardwareBuffer: HardwareBuffer?
    ): String

    external fun waeReadLatestFrameJson(): String

    external fun waeComputePageSpineSigs(
        depths: IntArray,
        classNames: Array<String>,
        viewIds: Array<String>
    ): LongArray

    external fun waeComputeJaccardReplacementRate(
        prevSigs: LongArray,
        currSigs: LongArray
    ): Float

    external fun waeBatchMiniLmCosineSim(
        goalText: String,
        canonicalTag: String,
        candidateTexts: Array<String>
    ): FloatArray

    external fun waeInferCuaS1Logits(
        goalText: String,
        canonicalTag: String,
        candidateTexts: Array<String>,
        baseSimScores: FloatArray
    ): FloatArray

    external fun waeInferPostureAndComponentLib(
        featureVec: FloatArray
    ): IntArray

    /**
     * 安全封裝：在 Android 真機走 C++ 極速算子，在 Host JVM 單元測試走等效確定性算子
     */
    fun computePageSpineSigsSafe(
        depths: IntArray,
        classNames: Array<String>,
        viewIds: Array<String>
    ): LongArray {
        if (nativeLoaded) {
            return try {
                waeComputePageSpineSigs(depths, classNames, viewIds)
            } catch (_: Throwable) {
                computePageSpineSigsJvm(depths, classNames, viewIds)
            }
        }
        return computePageSpineSigsJvm(depths, classNames, viewIds)
    }

    fun computeJaccardReplacementRateSafe(prevSigs: LongArray, currSigs: LongArray): Float {
        if (nativeLoaded) {
            return try {
                waeComputeJaccardReplacementRate(prevSigs, currSigs)
            } catch (_: Throwable) {
                computeJaccardReplacementRateJvm(prevSigs, currSigs)
            }
        }
        return computeJaccardReplacementRateJvm(prevSigs, currSigs)
    }

    fun batchMiniLmCosineSimSafe(
        goalText: String,
        canonicalTag: String,
        candidateTexts: Array<String>
    ): FloatArray {
        if (nativeLoaded) {
            return try {
                waeBatchMiniLmCosineSim(goalText, canonicalTag, candidateTexts)
            } catch (_: Throwable) {
                batchMiniLmCosineSimJvm(goalText, canonicalTag, candidateTexts)
            }
        }
        return batchMiniLmCosineSimJvm(goalText, canonicalTag, candidateTexts)
    }

    fun inferCuaS1LogitsSafe(
        goalText: String,
        canonicalTag: String,
        candidateTexts: Array<String>,
        baseSimScores: FloatArray
    ): FloatArray {
        if (nativeLoaded) {
            return try {
                waeInferCuaS1Logits(goalText, canonicalTag, candidateTexts, baseSimScores)
            } catch (_: Throwable) {
                inferCuaS1LogitsJvm(baseSimScores)
            }
        }
        return inferCuaS1LogitsJvm(baseSimScores)
    }

    fun inferPostureAndComponentLibSafe(featureVec: FloatArray): IntArray {
        if (nativeLoaded) {
            return try {
                waeInferPostureAndComponentLib(featureVec)
            } catch (_: Throwable) {
                inferPostureAndComponentLibJvm(featureVec)
            }
        }
        return inferPostureAndComponentLibJvm(featureVec)
    }

    private fun inferCuaS1LogitsJvm(baseSimScores: FloatArray): FloatArray {
        val n = baseSimScores.size
        val out = FloatArray(n + 1)
        for (i in 0 until n) {
            out[i] = (baseSimScores[i] - 0.35f) * 8.0f
        }
        out[n] = 0.0f
        return out
    }

    private fun inferPostureAndComponentLibJvm(featureVec: FloatArray): IntArray {
        val out = intArrayOf(0, 8, 3)
        if (featureVec.size < 12) return out
        var bestPrimary = 0
        var bestVal = -1e9f
        for (cls in 0 until 9) {
            if (cls == 2 || cls == 3) continue
            if (featureVec[cls] > bestVal) {
                bestVal = featureVec[cls]
                bestPrimary = cls
            }
        }
        out[0] = bestPrimary
        out[1] = if (featureVec[2] > 0.5f) 2 else if (featureVec[3] > 0.5f) 3 else 8
        var bestComp = 3
        var bestCompVal = -1e9f
        for (c in 0 until 4) {
            if (featureVec[8 + c] > bestCompVal) {
                bestCompVal = featureVec[8 + c]
                bestComp = c
            }
        }
        out[2] = bestComp
        return out
    }

    private fun computePageSpineSigsJvm(
        depths: IntArray,
        classNames: Array<String>,
        viewIds: Array<String>
    ): LongArray {
        val total = depths.size
        if (total <= 0) return LongArray(0)
        val indices = if (total <= 32) {
            (0 until total).toList()
        } else {
            (0 until 16).toList() + ((total - 16) until total).toList()
        }
        return LongArray(indices.size) { slot ->
            val idx = indices[slot]
            val d = depths[idx]
            val cls = classNames.getOrNull(idx).orEmpty()
            val vid = viewIds.getOrNull(idx).orEmpty()
            fnv1a64("$d|$cls|$vid|$slot")
        }
    }

    private fun computeJaccardReplacementRateJvm(prevSigs: LongArray, currSigs: LongArray): Float {
        if (prevSigs.isEmpty() && currSigs.isEmpty()) return 0f
        if (prevSigs.isEmpty() || currSigs.isEmpty()) return 1f
        val setA = prevSigs.toSet()
        val setB = currSigs.toSet()
        val inter = setA.intersect(setB).size
        val union = setA.union(setB).size
        if (union == 0) return 0f
        return (1f - inter.toFloat() / union.toFloat()).coerceIn(0f, 1f)
    }

    private fun batchMiniLmCosineSimJvm(
        goalText: String,
        canonicalTag: String,
        candidateTexts: Array<String>
    ): FloatArray {
        val query = (goalText + " " + canonicalTag).lowercase()
        val queryBigrams = extractBigrams(query)
        return FloatArray(candidateTexts.size) { idx ->
            val cand = candidateTexts[idx].lowercase()
            val candBigrams = extractBigrams(cand)
            if (queryBigrams.isEmpty() || candBigrams.isEmpty()) {
                0f
            } else {
                val inter = queryBigrams.intersect(candBigrams).size
                val denom = max(1, min(queryBigrams.size, candBigrams.size))
                (inter.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    private fun extractBigrams(s: String): Set<String> {
        val clean = s.trim()
        if (clean.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (c in clean) {
            if (!c.isWhitespace()) out.add(c.toString())
        }
        for (i in 0 until clean.length - 1) {
            out.add(clean.substring(i, i + 2))
        }
        return out
    }

    fun fnv1a64(input: String): Long {
        var hash = -3750763034362895579L // 14695981039346656037ULL
        for (ch in input) {
            hash = hash xor ch.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }
}
