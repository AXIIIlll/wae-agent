package com.wae.agent.core.twin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Twin（被動軌跡）模組。
 *
 * ⚠️ **這個檔案是意外誤刪後重建的**：公開介面（類別、方法、參數名稱）
 * 依編譯產物與既有測試還原；行為以 `TwinStoreTest` 的規格為準：
 *  - 圖：以「前置 spine → 後置 spine」為邊，只有成功的步入圖
 *  - 重放：精確比對（同 App、同前後 spine）優先；找不到才用前置狀態 Jaccard 模糊匹配
 *  - 成本：重放每步算 1ms（查表級），用來對照現場感知的實際毫秒
 */
data class TwinStep(
    val pkg: String = "",
    val spineBefore: Long = 0L,
    val spineAfter: Long = 0L,
    val action: String = "",
    val target: String = "",
    val posture: String = "",
    val ok: Boolean = false,
    val ts: Long = 0L,
    val ms: Long = 0L,
    val sigsBefore: List<Long> = emptyList(),
    val sigsAfter: List<Long> = emptyList(),
    val primitive: String = "",
    val viewId: String = "",
    val className: String = "",
    val roleTag: String = "",
    val gridX: Int = 0,
    val gridY: Int = 0,
    val commandKey: String = ""
) {
    fun toJson(): JSONObject = stepToJson(this)
}

/** 任務鍵：把同一句話正規化成可比較的字串（去標點、去空白、小寫、長度上限 48）。 */
object TwinTaskKey {

    private val NOISE = Regex("[\\p{P}\\p{S}\\s\\u3000]+")

    fun of(command: String): String =
        command.trim().replace(NOISE, "").lowercase().take(MAX_LEN)

    /** 舊資料沒有任務簽名（空字串）→ 視為通用，否則升級後歷史軌跡全部失效。 */
    fun compatible(a: String, b: String): Boolean =
        a.isEmpty() || b.isEmpty() || a == b

    private const val MAX_LEN = 48
}

data class TwinGraphStats(
    val states: Int = 0,
    val edges: Int = 0,
    val steps: Int = 0,
    val successRate: Float = 0f,
    val avgStepMs: Long = 0L
) {
    fun summary(): String =
        "Twin: states=$states edges=$edges steps=$steps ok=${"%.0f".format(successRate * 100)}% avg=${avgStepMs}ms"
}

data class TwinReplayResult(
    val matchedSteps: Int = 0,
    val totalSteps: Int = 0,
    val simulatedMs: Long = 0L,
    val actualMs: Long = 0L,
    val success: Boolean = false
) {
    fun summary(): String {
        val saved = if (actualMs <= 0) 0 else ((actualMs - simulatedMs) * 100 / actualMs).coerceAtLeast(0)
        return "TwinReplay: matched=$matchedSteps/$totalSteps success=$success " +
            "sim=${simulatedMs}ms real=${actualMs}ms 省 ${saved}%"
    }
}

/** 被動軌跡記錄器（只觀察、不執行）。 */
object TwinRecorder {

    private const val DIR = "twin"
    private const val FILE = "steps.jsonl"
    private const val MAX_PENDING = 500

    private var pending: TwinStep? = null
    private val pendingList = ArrayList<TwinStep>()

    fun begin(
        pkg: String,
        spineBefore: Long,
        action: String,
        target: String,
        posture: String,
        sigsBefore: List<Long> = emptyList(),
        viewId: String = "",
        className: String = "",
        roleTag: String = "",
        gridX: Int = 0,
        gridY: Int = 0,
        commandKey: String = ""
    ) {
        pending = TwinStep(
            pkg = pkg,
            spineBefore = spineBefore,
            action = action,
            target = target,
            posture = posture,
            sigsBefore = sigsBefore,
            viewId = viewId,
            className = className,
            roleTag = roleTag,
            gridX = gridX,
            gridY = gridY,
            commandKey = commandKey,
            ok = true,
            ts = System.currentTimeMillis()
        )
    }

    fun complete(spineAfter: Long, sigsAfter: List<Long> = emptyList()) {
        val step = pending ?: return
        pending = null
        pendingList.add(
            step.copy(spineAfter = spineAfter, sigsAfter = sigsAfter, ms = System.currentTimeMillis() - step.ts)
        )
        if (pendingList.size > MAX_PENDING) pendingList.removeAt(0)
    }

    fun abort() {
        pending = null
    }

    fun clear() {
        pending = null
        pendingList.clear()
    }

    fun steps(): List<TwinStep> = pendingList.toList()

    fun pendingCount(): Int = pendingList.size

    fun flush(context: Context): TwinGraphStats {
        if (pendingList.isNotEmpty()) {
            runCatching {
                val dir = File(context.filesDir, DIR).apply { mkdirs() }
                val sb = StringBuilder()
                for (step in pendingList) sb.append(step.toJson()).append('\n')
                File(dir, FILE).appendText(sb.toString(), Charsets.UTF_8)
            }
            pendingList.clear()
        }
        return TwinStore.stats(loadPersisted(context))
    }

    fun loadPersisted(context: Context): List<TwinStep> {
        val file = File(File(context.filesDir, DIR), FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.mapNotNull { parseTwinStep(it) }.toList()
            }
        }.getOrDefault(emptyList())
    }
}

/** 軌跡圖的統計與重放。 */
object TwinStore {

    data class Edge(val from: Long, val to: Long, val count: Int)

    /** 狀態＝出現過的 spine（前置與後置都算）；失敗步也算，因為它證明「這裡走不通」。 */
    fun statesOf(steps: List<TwinStep>): Set<String> {
        val out = LinkedHashSet<String>()
        for (step in steps) {
            out.add(step.spineBefore.toString())
            out.add(step.spineAfter.toString())
        }
        return out
    }

    /** 邊只收成功步：失敗的轉移不能拿來重放。 */
    fun buildEdges(steps: List<TwinStep>): List<Edge> {
        val map = LinkedHashMap<String, Edge>()
        for (step in steps) {
            if (!step.ok) continue
            val key = step.spineBefore.toString() + "->" + step.spineAfter
            val prev = map[key]
            map[key] = if (prev == null) Edge(step.spineBefore, step.spineAfter, 1)
            else Edge(prev.from, prev.to, prev.count + 1)
        }
        return map.values.toList()
    }

    fun stats(steps: List<TwinStep>): TwinGraphStats {
        if (steps.isEmpty()) return TwinGraphStats()
        val ok = steps.count { it.ok }
        return TwinGraphStats(
            states = statesOf(steps).size,
            edges = buildEdges(steps).size,
            steps = steps.size,
            successRate = ok.toFloat() / steps.size,
            avgStepMs = steps.map { it.ms }.average().toLong()
        )
    }

    fun stateSimilarity(a: List<Long>, b: List<Long>): Float {
        if (a.isEmpty() && b.isEmpty()) return 0f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val sa = a.toSet()
        val sb = b.toSet()
        val inter = sa.intersect(sb).size.toFloat()
        val union = sa.union(sb).size.toFloat()
        return if (union == 0f) 0f else inter / union
    }

    /**
     * 重放：每一步先找精確邊（同 App、同前後 spine）；找不到才用前置狀態相似度。
     * 重放成本固定算 1ms/步——這是「查表 vs 現場感知」的對照基準。
     */
    fun replay(
        steps: List<TwinStep>,
        graphSource: List<TwinStep> = steps,
        fuzzyThreshold: Float = 0.4f,
        requireAfterSimilarity: Boolean = false
    ): TwinReplayResult {
        if (steps.isEmpty()) return TwinReplayResult(totalSteps = 0, success = true, simulatedMs = 0L)

        val graph = graphSource.filter { it.ok }
        var matched = 0
        var actual = 0L
        for (step in steps) {
            actual += step.ms
            val exact = graph.any {
                it.pkg == step.pkg &&
                    it.spineBefore == step.spineBefore &&
                    it.spineAfter == step.spineAfter
            }
            if (exact) {
                matched++
                continue
            }
            if (fuzzyThreshold <= MAX_THRESHOLD) {
                val fuzzy = graph.any { g ->
                    g.pkg == step.pkg &&
                        stateSimilarity(g.sigsBefore, step.sigsBefore) >= fuzzyThreshold &&
                        (!requireAfterSimilarity ||
                            stateSimilarity(g.sigsAfter, step.sigsAfter) >= fuzzyThreshold)
                }
                if (fuzzy) matched++
            }
        }
        return TwinReplayResult(
            matchedSteps = matched,
            totalSteps = steps.size,
            simulatedMs = matched.toLong() * MS_PER_REPLAYED_STEP,
            actualMs = actual,
            success = matched == steps.size
        )
    }

    /** 以時間切分 train/test；`excludeAfterTs` 之後的步都算 test。 */
    fun heldOut(steps: List<TwinStep>, excludeAfterTs: Long = 0L): Pair<List<TwinStep>, List<TwinStep>> {
        if (steps.isEmpty()) return emptyList<TwinStep>() to emptyList()
        if (excludeAfterTs > 0L) {
            val train = steps.filter { it.ts < excludeAfterTs }
            val test = steps.filter { it.ts >= excludeAfterTs }
            if (test.isNotEmpty()) return train to test
        }
        val cut = (steps.size * TRAIN_RATIO).toInt().coerceIn(1, (steps.size - 1).coerceAtLeast(1))
        return steps.take(cut) to steps.drop(cut)
    }

    /** 相鄰步真正接得上的比例（斷掉的鏈＝0）。 */
    fun chainCoverage(steps: List<TwinStep>): Float {
        if (steps.isEmpty()) return 0f
        if (steps.size == 1) return if (steps[0].ok) 1f else 0f
        var links = 0
        for (i in 1 until steps.size) {
            if (steps[i - 1].ok && steps[i].ok && steps[i - 1].spineAfter == steps[i].spineBefore) links++
        }
        return links.toFloat() / (steps.size - 1)
    }

    fun longestChain(steps: List<TwinStep>): Int {
        if (steps.isEmpty()) return 0
        var best = 1
        var run = 1
        for (i in 1 until steps.size) {
            val linked = steps[i - 1].ok && steps[i].ok && steps[i - 1].spineAfter == steps[i].spineBefore
            run = if (linked) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    fun coverage(replay: TwinReplayResult): Float =
        if (replay.totalSteps == 0) 0f else replay.matchedSteps.toFloat() / replay.totalSteps

    private const val MS_PER_REPLAYED_STEP = 1L
    private const val MAX_THRESHOLD = 1.0f
    private const val TRAIN_RATIO = 0.8
}

/** `"1,2,3"` → `[1,2,3]`（落盤格式用）。 */
fun toLongList(text: String): List<Long> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').mapNotNull { it.trim().toLongOrNull() }
}

/** 一條軌跡 → JSON（頂層函式版本，供舊呼叫點使用）。 */
fun toJson(step: TwinStep): JSONObject = stepToJson(step)

fun stepToJson(step: TwinStep): JSONObject = JSONObject().apply {
    put("pkg", step.pkg)
    put("spineBefore", step.spineBefore)
    put("spineAfter", step.spineAfter)
    put("action", step.action)
    put("target", step.target)
    put("posture", step.posture)
    put("ok", step.ok)
    put("ts", step.ts)
    put("ms", step.ms)
    put("sigsBefore", JSONArray(step.sigsBefore))
    put("sigsAfter", JSONArray(step.sigsAfter))
    put("primitive", step.primitive)
    put("viewId", step.viewId)
    put("className", step.className)
    put("roleTag", step.roleTag)
    put("gridX", step.gridX)
    put("gridY", step.gridY)
    put("commandKey", step.commandKey)
}

fun parseTwinStep(line: String): TwinStep? = runCatching {
    val o = JSONObject(line)
    TwinStep(
        pkg = o.optString("pkg"),
        spineBefore = o.optLong("spineBefore"),
        spineAfter = o.optLong("spineAfter"),
        action = o.optString("action"),
        target = o.optString("target"),
        posture = o.optString("posture"),
        ok = o.optBoolean("ok"),
        ts = o.optLong("ts"),
        ms = o.optLong("ms"),
        sigsBefore = longListOf(o.optJSONArray("sigsBefore")),
        sigsAfter = longListOf(o.optJSONArray("sigsAfter")),
        primitive = o.optString("primitive"),
        viewId = o.optString("viewId"),
        className = o.optString("className"),
        roleTag = o.optString("roleTag"),
        gridX = o.optInt("gridX"),
        gridY = o.optInt("gridY"),
        commandKey = o.optString("commandKey")
    )
}.getOrNull()

private fun longListOf(array: JSONArray?): List<Long> {
    if (array == null) return emptyList()
    val out = ArrayList<Long>(array.length())
    for (i in 0 until array.length()) out.add(array.optLong(i))
    return out
}
