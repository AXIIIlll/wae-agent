package com.wae.agent.core.match

import android.content.Context
import android.util.Log
import com.wae.core.match.ControlScorer
import com.wae.core.match.Definition
import com.wae.core.match.DefinitionTable
import com.wae.core.match.DefinitionMatcher

/**
 * 多定義比對器的**單一裝配點**（P1）。
 *
 * 為什麼要集中：這個專案有四套打分（`TargetResolver`、`DecisionRanker`、
 * `PredicateAgent.LabelSimilarity`、`TwinFastPath`），如果每套各自讀表，
 * 「改一份沒用」的歷史就會重演。這裡只建立一份 [DefinitionMatcher]，
 * 需要的人來拿同一個實例。
 *
 * 開關：`match.definitions_enabled`（預設 0）。預設關閉是刻意的——
 * 新的比對方式要先在真機 A/B 過，不能默默換掉現有行為。
 */
object MatchSource {

    private const val TAG = "WAE_MATCH"
    private const val ASSET_DEFS = "open_source_ai_databases/ui_entity_definitions.tsv"
    private const val ASSET_GROUPS = "open_source_ai_databases/ui_entity_groups.tsv"
    private const val ASSET_VIEWS = "open_source_ai_databases/ui_view_weights.tsv"

    const val KEY_ENABLED = "match.definitions_enabled"

    @Volatile private var table: DefinitionTable? = null
    @Volatile private var matcher: DefinitionMatcher? = null

    fun enabled(context: Context?): Boolean {
        if (context == null) return false
        return com.wae.agent.core.runtime.Tunables.getBool(context, KEY_ENABLED, false)
    }

    /** 沒有開關也能拿到比對器（診斷用）；對外決策請用 [scorer]。 */
    fun matcher(context: Context?): DefinitionMatcher? {
        matcher?.let { return it }
        if (context == null) return null
        synchronized(this) {
            matcher?.let { return it }
            val t = table ?: loadTable(context) ?: return null
            table = t
            val m = DefinitionMatcher(t, loadViewWeights(context))
            matcher = m
            Log.i(TAG, "definitions loaded: entities=" + t.entities.size + " defs=" + t.definitionCount())
            return m
        }
    }

    /** 決策用入口：開關沒開就回 null，呼叫端自然退回舊的相似度。 */
    fun scorer(context: Context?): ControlScorer? = if (!enabled(context)) null else matcher(context)

    fun reset() {
        synchronized(this) {
            table = null
            matcher = null
        }
    }

    private fun loadTable(context: Context): DefinitionTable? = runCatching {
        val defs = context.assets.open(ASSET_DEFS).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        val groups = runCatching {
            context.assets.open(ASSET_GROUPS).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        }.getOrDefault(emptyList())
        val parsed = DefinitionTable.parse(defs.asSequence(), groups.asSequence())
        if (parsed.entities.isEmpty()) {
            Log.w(TAG, "多定義表是空的：$ASSET_DEFS")
            null
        } else {
            parsed
        }
    }.getOrElse { t ->
        Log.w(TAG, "讀多定義表失敗：" + t.message)
        null
    }

    private fun loadViewWeights(context: Context): Map<Definition.View, Float> = runCatching {
        context.assets.open(ASSET_VIEWS).bufferedReader(Charsets.UTF_8).useLines { lines ->
            val acc = LinkedHashMap<Definition.View, Float>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cells = line.split('\t')
                if (cells.size < 2) continue
                val view = Definition.viewOf(cells[0]) ?: continue
                val w = cells[1].trim().toFloatOrNull() ?: continue
                acc[view] = w
            }
            acc
        }
    }.getOrDefault(emptyMap())
}
