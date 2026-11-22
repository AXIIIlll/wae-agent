package com.wae.core.rule

/**
 * 規則引擎（v1）：句子 → 目的 → 具體性 → 方法 → 要執行的句子。
 *
 * 這一層**不碰 Android、不碰模型**，所以行為可以完全離線測試。
 *
 * 流程：
 * ```
 * 正規化 → 句型匹配（可能多筆）→ 取最高分
 *        → 判斷器（具體／模糊）
 *        → 具體：選方法 → 展開 → 檢查目的存在與高風險政策
 *        → 模糊：回 NeedsModel（交給大模型補槽／拆解）
 *        → 沒命中：回 NoMatch（上層退回舊分支）
 * ```
 */
class RuleEngine(
    private val purposes: Map<String, Purpose>,
    private val operations: Map<String, Operation>,
    private val rules: List<CommandRule>,
    private val methodsByPurpose: Map<String, List<Method>>,
    private val specificity: SpecificityTable,
    /** 詞級繁簡對照（簡體→繁體）；沒有就用原句。 */
    private val tradMap: Map<String, String> = emptyMap(),
    /** 字級繁簡對照（補詞表沒收錄的字，例：厅→廳）。 */
    private val simpTrad: Map<String, String> = emptyMap(),
    /** 每 App 能力表（從結果學來的）。 */
    private val capabilities: AppCapabilityTable = AppCapabilityTable(),
    /** 裝置能力探測（App 層實作；沒有就全部視為可行）。 */
    private val probe: CapabilityProbe = CapabilityProbe.ALLOW_ALL
) {

    sealed class Decision {
        /** 可以直接執行（已展開成句子）。 */
        data class Plan(
            val purpose: Purpose,
            val method: Method,
            val commands: List<String>,
            /** 操作層序列（有值時優先於 commands）。 */
            val ops: List<OpCall>,
            val purposesUsed: List<String>,
            val specificity: Specificity,
            val notes: List<String>,
            /** 有序步驟（cmd／op／wait 可混用）。 */
            val steps: List<com.wae.core.rule.MethodExpander.Step> = emptyList(),
            /** 這次綁到的槽位（驗證 post 時要用實際值，不能拿槽位名比對）。 */
            val slots: Map<String, String> = emptyMap()
        ) : Decision()

        /** 需要模型（模糊或缺槽）。 */
        data class NeedsModel(
            val reason: String,
            val match: Match?,
            val purpose: Purpose?,
            val specificity: Specificity,
            val missingSlots: List<String>
        ) : Decision()

        /** 沒有句型命中（上層退回舊分支）。 */
        data class NoMatch(val specificity: Specificity) : Decision()

        /** 高風險目的：只導覽、不執行最後一步（或需要確認）。 */
        data class NeedsConfirmation(
            val purpose: Purpose,
            val commands: List<String>,
            val specificity: Specificity
        ) : Decision()
    }

    fun decide(
        command: String,
        facts: Map<String, String> = emptyMap(),
        preset: Map<String, String> = emptyMap(),
        /** 是否允許用操作層（`op:`）。關閉時只挑「有 cmd 步驟」的方法。 */
        allowOps: Boolean = true
    ): Decision {
        val text = normalize(command)
        val matches = TemplateMatcher.matchAll(text, rules)
        val best = matches.firstOrNull()
        val purpose = best?.let { purposes[it.rule.purpose] }
        // 槽位的唯一組裝點（句型抓到的 + 句型固定的 + 外部補的），全部轉 canonical
        val boundSlots = Slots.canonical(
            (best?.slots.orEmpty()) + (best?.rule?.fixedSlots.orEmpty()) + preset
        )
        val spec = specificity.judge(text, best, purpose, boundSlots)

        if (best == null || purpose == null) {
            return Decision.NoMatch(spec)
        }

        // 高風險或「只能導覽」：不自動執行最後一步
        if (purpose.highRisk || purpose.navigateOnly) {
            val method = pickMethod(purpose, facts, allowOps)
            val commands = method?.let {
                MethodExpander.expand(it, best.slots + best.rule.fixedSlots, methodsByPurpose).commands
            }.orEmpty()
            return Decision.NeedsConfirmation(purpose, commands, spec)
        }

        if (!spec.isSpecific) {
            return Decision.NeedsModel(
                reason = "vague_or_missing_slots",
                match = best,
                purpose = purpose,
                specificity = spec,
                missingSlots = spec.missing
            )
        }

        val method = pickMethod(purpose, facts, allowOps)
            ?: return Decision.NeedsModel("no_method", best, purpose, spec, emptyList())

        val slots = boundSlots
        val expanded = MethodExpander.expand(method, slots, methodsByPurpose)
        if (expanded.commands.isEmpty() && expanded.ops.isEmpty()) {
            return Decision.NeedsModel("empty_plan", best, purpose, spec, emptyList())
        }
        // 槽位沒綁完 → 不能硬執行（會送出「打開$app」這種句子）
        val unbound = expanded.commands.any { it.contains('$') } ||
            expanded.ops.any { call -> call.args.any { it.contains('$') } }
        if (unbound) {
            return Decision.NeedsModel("unbound_slot", best, purpose, spec, emptyList())
        }
        // 操作層 schema：每個 op 都要合法、參數只能是宣告過的槽位
        val opProblems = expanded.ops.flatMap { OpCatalog.validate(it, purpose.slots.toSet()) }
        if (opProblems.isNotEmpty()) {
            return Decision.NeedsModel("bad_ops:" + opProblems.joinToString(";"), best, purpose, spec, emptyList())
        }
        return Decision.Plan(
            purpose, method, expanded.commands, expanded.ops, expanded.purposes, spec, expanded.notes, expanded.steps, slots
        )
    }

    /**
     * 選方法：`allowOps=false` 時**跳過含 `op:` 的方法**（改用 cmd 版本）。
     *
     * 為什麼：操作層預設關閉。若仍選到 op 方法，展開後沒有 cmd 可執行，
     * 就會落回舊管線 → 具體指令也驚動大模型（實測「開啟紅果短劇」被送去思考）。
     */
    private fun pickMethod(purpose: Purpose, facts: Map<String, String>, allowOps: Boolean): Method? {
        val candidates = methodsByPurpose[purpose.id].orEmpty()
            .filter { applies(it.appliesWhen, facts, purpose.id) }
        if (allowOps) return candidates.firstOrNull()
        // 只有「純操作層」的方法才需要跳過；混合方法（cmd + op）仍可執行，
        // 它的 cmd 步驟交舊管線、op 步驟交操作層（Gen5Runtime 會照順序跑）。
        return candidates.firstOrNull { hasCmd(it.steps) } ?: candidates.firstOrNull()
    }

    private fun hasCmd(node: TaskNode): Boolean = when (node) {
        is TaskNode.Cmd -> true
        is TaskNode.Seq -> node.children.any { hasCmd(it) }
        is TaskNode.Choice -> node.options.any { hasCmd(it) }
        else -> false
    }

    /** `app_installed($app)` 這種條件；空字串＝永遠成立。 */
    private fun argOf(expr: String): String =
        expr.substringAfter('(', "").substringBefore(')').trim()

    private fun applies(expr: String, facts: Map<String, String>, purpose: String): Boolean {
        val e = expr.trim()
        if (e.isEmpty()) return true
        return when {
            e.startsWith("app_installed(") -> {
                val app = e.substringAfter('(').substringBefore(')').removePrefix("$")
                val known = facts["installed_apps"].orEmpty()
                app.isEmpty() || known.split('|').any { it.equals(app, ignoreCase = true) }
            }
            e.startsWith("fact:") -> facts.containsKey(e.removePrefix("fact:").trim())
            e.startsWith("capability(") -> {
                val app = argOf(e).removePrefix("$")
                capabilities.supports(app, purpose).let { it == null || it }
            }
            e.startsWith("lacks(") -> {
                val app = argOf(e).removePrefix("$")
                capabilities.lacks(app, purpose)
            }
            e.startsWith("permission(") -> probe.hasPermission(argOf(e))
            e.startsWith("feature(") -> probe.hasFeature(argOf(e))
            e.startsWith("can_open_panel") -> probe.canOpenPanel()
            e.startsWith("intent(") -> {
                val parts = argOf(e).split(',')
                probe.intentResolvable(parts.getOrNull(0)?.trim().orEmpty(), parts.getOrNull(1)?.trim().orEmpty())
            }
            else -> true
        }
    }

    /**
     * 正規化：禮貌詞 → 條件句 → 繁簡。
     *
     * 這三步都在 core 完成，規則引擎才不會「換個 App 層就聽不懂簡體」。
     */
    fun normalize(command: String): String {
        var text = com.wae.core.intent.Politeness.strip(command).trim()
        text = com.wae.core.intent.Conditional.strip(text)
        // 填充詞：不影響語意，但會讓字面比對失敗（「找一下餐廳」→「找餐廳」）
        for (filler in FILLERS) {
            if (text.contains(filler)) text = text.replace(filler, "")
        }
        text = text.trim()
        // 先套詞表（長詞優先），再用單字表補漏
        if (tradMap.isNotEmpty()) {
            for ((from, to) in tradMap.entries.sortedByDescending { it.key.length }) {
                if (text.contains(from)) text = text.replace(from, to)
            }
        }
        if (simpTrad.isNotEmpty()) {
            val sb = StringBuilder(text.length)
            for (ch in text) sb.append(simpTrad[ch.toString()] ?: ch.toString())
            text = sb.toString()
        }
        return text
    }

    companion object {
        /** 口語填充詞：拿掉不影響語意，但會讓模板比對 miss。 */
        val FILLERS = listOf(
            "一下下", "一下", "看看", "找找", "試試", "幫我看看",
            "然後", "現在", "麻煩", "請", "好嗎", "可以嗎", "吧", "啦", "喔", "啊", "嘛", "呢"
        )

        fun tradMapOf(lines: Sequence<String>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size >= 2) out[c[0].trim()] = c[1].trim()
            }
            return out
        }

        fun simpTradOf(lines: Sequence<String>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size >= 2 && c[0].trim().length == 1) out[c[0].trim()] = c[1].trim()
            }
            return out
        }
    }

    /**
     * 資料 schema 自檢：一次抓出「整類」設定錯誤，而不是等到某一句話失敗。
     *
     * 檢查項目：
     *  1. 句型固定槽位必須是目的宣告過的槽位（轉 canonical 後）
     *  2. 目的的必要槽位必須有地方會提供（句型或固定值）——否則永遠缺
     *  3. 目的的 post 必須可解析
     *  4. 方法裡引用的目的必須存在
     */
    fun problems(): List<String> = schemaProblems().first

    fun warnings(): List<String> = schemaProblems().second

    private fun schemaProblems(): Pair<List<String>, List<String>> {
        val out = ArrayList<String>()
        val warningsList = ArrayList<String>()
        val fixedByPurpose = HashMap<String, MutableSet<String>>()
        for (rule in rules) {
            val purpose = purposes[rule.purpose]
            if (purpose == null) {
                out.add("句型 '${rule.pattern}' 引用不存在的目的 ${rule.purpose}")
                continue
            }
            for (key in rule.fixedSlots.keys) {
                if (key !in purpose.slots) {
                    out.add("句型 '${rule.pattern}' 給了未宣告的槽位 $key（${purpose.id}）")
                }
                fixedByPurpose.getOrPut(purpose.id) { HashSet() }.add(key)
            }
            for (slot in rule.slotNames) {
                if (slot in Slots.WILDCARDS) continue
                if (slot !in purpose.slots) {
                    out.add("句型 '${rule.pattern}' 抓了未宣告的槽位 \$$slot（${purpose.id}）")
                }
                fixedByPurpose.getOrPut(purpose.id) { HashSet() }.add(slot)
            }
        }
        for (purpose in purposes.values) {
            if (PostSpec.parse(purpose.post) == null) {
                out.add("目的 ${purpose.id} 的 post 無法解析：${purpose.post}")
            }
            // 必要槽位沒有句型提供 → 這是「提醒」不是「錯誤」：
            // 目的目錄是能力空間，不是每一項都必須有現成句型（可由模型補槽或之後補句型）。
            val provided = fixedByPurpose[purpose.id].orEmpty()
            for (required in purpose.requiredSlots) {
                val covered = provided.contains(required) ||
                    provided.any { Slots.compatible(it, required) }
                if (!covered) {
                    warningsList.add("目的 ${purpose.id} 的必要槽位 $required 目前沒有句型提供（會走補槽／反問）")
                }
            }
        }
        for (method in methodsByPurpose.values.flatten()) {
            val ids = collectCalls(method.steps)
            for (id in ids) if (id !in purposes) out.add("方法 ${method.id} 呼叫不存在的目的 $id")
        }
        return out to warningsList
    }

    private fun collectCalls(node: TaskNode): List<String> = when (node) {
        is TaskNode.Call -> listOf(node.purposeId)
        is TaskNode.Seq -> node.children.flatMap { collectCalls(it) }
        is TaskNode.Choice -> node.options.flatMap { collectCalls(it) }
        else -> emptyList()
    }

    fun purposeIds(): Set<String> = purposes.keys
    fun ruleCount(): Int = rules.size
    fun methodCount(): Int = methodsByPurpose.values.sumOf { it.size }
    fun operationCount(): Int = operations.size
}
