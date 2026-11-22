package com.wae.core.rule

/**
 * 操作層 DSL（`op:`）：方法的每一步只描述「用哪種操作、參數是什麼」。
 *
 * 為什麼要有：`cmd:`（一句人話）把「用哪種手段」交回舊管線，
 * 規則層就無法指定手段、比較手段、統計手段。`op:` 讓手段也變成資料。
 *
 * 刻意只做薄薄一層：`op` → 既有 `Means`（Intent／A11y／Input／SystemApi／Gesture），
 * 不新增第二條執行路徑（單一裝置入口 I1）。
 */
data class OpCall(
    val id: String,
    /** `$slot` 或字面值。 */
    val args: List<String>
) {
    val slotArgs: List<String> get() = args.filter { it.startsWith("$") }.map { it.removePrefix("$") }
}

/**
 * 操作目錄：DSL 的 `op` id → 底層操作（`ui/operations.tsv` 的 id）＋參數數量。
 *
 * 這裡是「封閉集合」：不在表上的 op 一律不合法（schema 測試會紅）。
 */
object OpCatalog {

    data class Spec(val operationId: String, val arity: Int, val note: String = "")

    private val specs = linkedMapOf(
        "INTENT_LAUNCH" to Spec("INTENT", 1, "action=MAIN, pkg=app"),
        "INTENT_SEARCH" to Spec("INTENT", 2, "action=SEARCH, pkg=app, data=query"),
        "INTENT_SEND" to Spec("INTENT", 1, "action=SEND, target=app"),
        "INTENT_PICK" to Spec("INTENT", 1, "action=GET_CONTENT, mime"),
        "INTENT_CAPTURE" to Spec("INTENT", 0, "action=IMAGE_CAPTURE"),
        "IN_APP_SEARCH" to Spec("A11Y_SET_TEXT", 1, "在目前 App 內搜尋（找框→寫字→送出）"),
        "URI_SEARCH" to Spec("INTENT", 1, "用前景 App 的 URI scheme 搜尋（表在 uri_search.tsv）"),
        "A11Y_CLICK" to Spec("A11Y_CLICK", 1, "target 名稱／角色"),
        "A11Y_SET_TEXT" to Spec("A11Y_SET_TEXT", 1, "要寫入的文字"),
        "A11Y_SUBMIT" to Spec("A11Y_CLICK", 1, "送出／搜尋按鈕"),
        "PANEL" to Spec("PANEL", 1, "wifi／bluetooth／airplane／hotspot"),
        "SYSTEM_VALUE" to Spec("SYSTEM_API", 2, "kind(brightness|volume), dir(up|down)"),
        "BACK" to Spec("SYSTEM_API", 0, "global back"),
        "HOME" to Spec("SYSTEM_API", 0, "global home"),
        "CLIPBOARD_COPY" to Spec("CLIPBOARD", 1, "來源：last_result"),
        "WAIT" to Spec("", 1, "毫秒"),
        "WAIT_FOR" to Spec("", 2, "target, 毫秒")
    )

    fun spec(id: String): Spec? = specs[id]

    fun ids(): Set<String> = specs.keys

    /** 驗證：id 存在、參數數量正確、槽位引用都看得到。 */
    fun validate(call: OpCall, purposeSlots: Set<String>): List<String> {
        val problems = ArrayList<String>()
        val spec = specs[call.id] ?: return listOf("未知操作 ${call.id}")
        if (call.args.size != spec.arity) {
            problems.add("${call.id} 需要 ${spec.arity} 個參數，實際 ${call.args.size}")
        }
        for (arg in call.slotArgs) {
            if (arg !in purposeSlots) problems.add("${call.id} 引用未宣告的槽位 \$$arg")
        }
        return problems
    }

    /** 解析 `INTENT_SEARCH($app,$query)` → OpCall。 */
    fun parse(text: String): OpCall? {
        val t = text.trim()
        val open = t.indexOf('(')
        if (open < 0) {
            return if (specs.containsKey(t)) OpCall(t, emptyList()) else null
        }
        val id = t.substring(0, open).trim()
        if (!specs.containsKey(id)) return null
        val inner = t.substring(open + 1).removeSuffix(")").trim()
        val args = if (inner.isEmpty()) emptyList() else inner.split(',').map { it.trim() }
        return OpCall(id, args)
    }
}
