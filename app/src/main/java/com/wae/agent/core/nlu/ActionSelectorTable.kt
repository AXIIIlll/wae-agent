package com.wae.agent.core.nlu

import android.content.Context
import com.wae.agent.core.ActionKind

/**
 * 動作判定表：`actionKind = table[verbClass][objectType]`（**資料驅動**）。
 *
 * 為什麼需要它（B2 的根因修法）：
 * 舊版只用「單一動作詞」決定動作類型（`'o' -> LAUNCH_APP`），
 * 於是「把深色模式**打開**」被當成「啟動 App」→ 只開了設定就回報成功（假成功）。
 *
 * 真正需要的是 `f(動詞, 受詞型別)`：
 *  - 「打開設定」      → 受詞是 App  → 啟動
 *  - 「把深色模式打開」→ 受詞是功能項 → 點擊（能力層可再升級為切換）
 *
 * 資料檔：`assets/open_source_ai_databases/ui_action_selector_map.tsv`
 */
object ActionSelectorTable {

    private const val DB_PATH = "open_source_ai_databases/ui_action_selector_map.tsv"

    data class Rule(val verbClass: String, val objectType: String, val kind: ActionKind)

    @Volatile
    private var rules: Map<String, ActionKind> = emptyMap()

    @Volatile
    private var loadAttempted = false

    /** 受詞型別 */
    object ObjectType {
        const val APP = "APP"
        const val EMPTY = "EMPTY"
        const val NON_APP = "NON_APP"
        const val UNKNOWN = "UNKNOWN"
        const val ANY = "ANY"
    }

    /** 動詞類別 */
    object VerbClass {
        const val LAUNCH = "LAUNCH"
        const val TAP = "TAP"
        const val TYPE = "TYPE"
        const val BACK = "BACK"
        const val SCROLL = "SCROLL"
        const val WAIT = "WAIT"
    }

    fun ensureLoaded(context: Context?) {
        if (loadAttempted || context == null) return
        loadAttempted = true
        try {
            val lines = context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            loadFromLines(lines)
        } catch (_: Throwable) {
        }
    }

    fun loadFromLines(lines: List<String>) {
        val map = HashMap<String, ActionKind>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 3) continue
            val verb = p[0].trim().uppercase()
            val obj = p[1].trim().uppercase()
            val kind = runCatching { ActionKind.valueOf(p[2].trim().uppercase()) }.getOrNull() ?: continue
            map["$verb\u0001$obj"] = kind
        }
        rules = map
    }

    fun resetForTest() {
        rules = emptyMap()
        loadAttempted = false
    }

    fun ruleCount(): Int = rules.size

    /** 動作詞的 primaryActionCode → 動詞類別 */
    fun verbClassOf(primaryActionCode: Char?): String = when (primaryActionCode) {
        'o' -> VerbClass.LAUNCH
        'c' -> VerbClass.TAP
        't', 's' -> VerbClass.TYPE
        'b' -> VerbClass.BACK
        'r' -> VerbClass.SCROLL
        else -> VerbClass.TAP
    }

    /**
     * 由「受詞文字」與「是否含 App 別名」決定受詞型別。
     *
     * @param residue 去掉動詞與 App 片語後剩下的受詞文字
     * @param appAlias 本句解析到的 App 別名（可空）
     */
    fun objectTypeOf(residue: String, appAlias: String): String {
        val r = residue.trim()
        if (r.isEmpty()) return ObjectType.EMPTY
        if (appAlias.isNotEmpty() && r.lowercase().contains(appAlias.lowercase())) return ObjectType.APP
        return ObjectType.NON_APP
    }

    /** 查表；找不到就回傳 default（預設 TAP，保守） */
    fun resolve(verbClass: String, objectType: String, default: ActionKind = ActionKind.TAP): ActionKind {
        val v = verbClass.uppercase()
        val o = objectType.uppercase()
        rules["$v\u0001$o"]?.let { return it }
        rules["$v\u0001${ObjectType.ANY}"]?.let { return it }
        return default
    }
}
