package com.wae.agent.core.omega

import android.content.Context

/**
 * WAE-Ω 第 3 層：意圖編譯器（IntentCompiler）。
 *
 * 自然語言 → 型別化 [TaskIrPlan]：
 *  - 模板（`ui_ir_templates.tsv`）提供「任務骨架」與 IR 步驟 DSL
 *  - 槽位（重用 Task OS 的 SlotFiller）把參數抽出來
 *  - App Model 用於**編譯期靜態檢查**：
 *      * 目標型別在模型裡存在嗎（`model.supports(type)`）
 *      * 欄位在 schema 裡嗎（`model.fieldByName`）
 *      * 資料流完備嗎（READ/COMPUTE 產生的變數能否滿足 WRITE）
 *
 * 編譯失敗時不猜：回 [Result.Unsupported] 或 [Result.MissingVars]，
 * 由上層決定「反問使用者」或「fallback 到即時感知路徑」。
 *
 * 純 Kotlin（除了 ensureLoaded），JVM 可測。
 */
object IntentCompiler {

    private const val DB_PATH = "open_source_ai_databases/ui_ir_templates.tsv"

    sealed class Result {
        data class Compiled(val plan: TaskIrPlan) : Result()

        /** 缺變數（模板要的參數抽不到）→ 應反問使用者 */
        data class MissingVars(val templateId: String, val missing: Set<String>) : Result()

        /** 型別/欄位在 App Model 裡不存在，或模板不支援這個指令 */
        data class Unsupported(val templateId: String, val reason: String) : Result()
    }

    private data class Template(val id: String, val pattern: Regex, val stepsSpec: String)

    private val templates = ArrayList<Template>()

    @Volatile
    private var loadAttempted = false

    private val DEFAULT_ROWS = listOf(
        "ir\tread-version\t(?i)(?:讀|查看|查).*版本\tREAD:version->\$version",
        "ir\tread-battery\t(?i)(?:電池|電量|battery).*(?:多少|查看|讀)\t" +
            "READ:battery->\$battery",
        "ir\tfill-and-submit\t(?i)(?:填寫|填入|輸入).*(?:並|然後).*(?:送出|提交|確定)\t" +
            "READ:formTarget->\$value|WRITE:\$value->formTarget|COMMIT:送出"
    )

    fun ensureLoaded(context: Context?) {
        if (loadAttempted) return
        loadAttempted = true
        try {
            val lines = context?.assets?.open(DB_PATH)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
            loadFromLines(lines ?: DEFAULT_ROWS)
        } catch (_: Throwable) {
            loadFromLines(DEFAULT_ROWS)
        }
    }

    /** 每行：`ir<TAB>id<TAB>pattern<TAB>steps`；壞行跳過 */
    fun loadFromLines(lines: List<String>) {
        val parsed = ArrayList<Template>()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val c = line.split('\t')
            if (c.size < 4 || c[0].trim() != "ir") continue
            val id = c[1].trim()
            val pattern = runCatching { Regex(c[2].trim()) }.getOrNull() ?: continue
            val steps = c[3].trim()
            if (id.isEmpty() || steps.isEmpty()) continue
            parsed.add(Template(id, pattern, steps))
        }
        synchronized(templates) {
            templates.clear()
            templates.addAll(parsed)
        }
    }

    fun resetForTest() {
        synchronized(templates) { templates.clear() }
        loadAttempted = false
    }

    fun templateCount(): Int = synchronized(templates) { templates.size }

    /**
     * 編譯。
     *
     * @param command 使用者指令
     * @param model App Model（null＝沒有模型，跳過型別檢查但標記 no_model）
     * @param providedVars 呼叫端已提供的變數（例如使用者 profile）
     * @param extractedSlots 額外槽位（預設用 SlotFiller 抽）
     */
    fun compile(
        command: String,
        model: AppModel? = null,
        providedVars: Map<String, String> = emptyMap(),
        extractedSlots: Map<String, String> = SlotFillerProxy.extract(command)
    ): Result {
        if (command.isBlank()) return Result.Unsupported("", "empty_command")
        val tpl = synchronized(templates) { templates.firstOrNull { it.pattern.containsMatchIn(command) } }
            ?: return Result.Unsupported("", "no_template")
        return compileTemplate(tpl, command, model, providedVars, extractedSlots)
    }

    private fun compileTemplate(
        tpl: Template,
        command: String,
        model: AppModel?,
        providedVars: Map<String, String>,
        slots: Map<String, String>
    ): Result {
        val vars = HashMap<String, String>(providedVars)
        vars.putAll(slots)

        val steps = ArrayList<TaskIrStep>()
        val produced = HashSet<String>()
        val consumed = HashSet<String>()
        var pendingGuard = ""
        var commits = 0

        for (token in tpl.stepsSpec.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
            val upper = token.uppercase()
            when {
                upper.startsWith("GUARD:") -> {
                    pendingGuard = token.substringAfter(':')
                    continue
                }
                upper.startsWith("READ:") -> {
                    // READ:field->$var
                    val rest = token.removePrefix("READ:")
                    val arrow = rest.indexOf("->")
                    if (arrow <= 0) return Result.Unsupported(tpl.id, "bad_read:$token")
                    val fieldName = rest.substring(0, arrow).trim()
                    val varName = rest.substring(arrow + 2).trim().removePrefix("$")
                    if (varName.isEmpty()) return Result.Unsupported(tpl.id, "bad_read_var:$token")
                    val schema = model?.fieldByName(fieldName)
                    val type = schema?.type ?: UiOntology.UiType.TEXT_LABEL
                    if (model != null && schema == null && !model.supports(type)) {
                        return Result.Unsupported(tpl.id, "field_not_in_model:$fieldName")
                    }
                    steps.add(
                        TaskIrStep(
                            intent = TaskIrStep.Intent.READ,
                            target = TargetRef(type, textHint = schema?.readPattern ?: fieldName, fieldName = fieldName),
                            value = ValueRef.variable(varName),
                            guard = pendingGuard,
                            goalHint = "讀取$fieldName"
                        )
                    )
                    produced.add(varName)
                    pendingGuard = ""
                }
                upper.startsWith("WRITE:") -> {
                    // WRITE:$var->target / WRITE:'literal'->target
                    val rest = token.removePrefix("WRITE:")
                    val arrow = rest.indexOf("->")
                    if (arrow <= 0) return Result.Unsupported(tpl.id, "bad_write:$token")
                    val src = rest.substring(0, arrow).trim()
                    val fieldName = rest.substring(arrow + 2).trim()
                    val value = if (src.startsWith("$")) {
                        val v = src.removePrefix("$")
                        consumed.add(v)
                        ValueRef.variable(v)
                    } else {
                        ValueRef.literal(src.removeSurrounding("'"))
                    }
                    val schema = model?.fieldByName(fieldName)
                    val type = schema?.type ?: UiOntology.UiType.TEXT_FIELD
                    if (model != null && schema == null && !model.supports(type)) {
                        return Result.Unsupported(tpl.id, "field_not_in_model:$fieldName")
                    }
                    steps.add(
                        TaskIrStep(
                            intent = TaskIrStep.Intent.WRITE,
                            target = TargetRef(type, textHint = fieldName, fieldName = fieldName),
                            value = value,
                            guard = pendingGuard,
                            goalHint = "填入$fieldName"
                        )
                    )
                    pendingGuard = ""
                }
                upper.startsWith("COMPUTE:") -> {
                    // COMPUTE:$var=expr
                    val rest = token.removePrefix("COMPUTE:")
                    val eq = rest.indexOf('=')
                    if (eq <= 0) return Result.Unsupported(tpl.id, "bad_compute:$token")
                    val varName = rest.substring(0, eq).trim().removePrefix("$")
                    val expr = rest.substring(eq + 1).trim()
                    steps.add(
                        TaskIrStep(
                            intent = TaskIrStep.Intent.COMPUTE,
                            value = ValueRef(KindHolder.COMPUTE, name = varName, expr = expr),
                            goalHint = "計算$varName"
                        )
                    )
                    produced.add(varName)
                }
                upper.startsWith("COMMIT:") -> {
                    val hint = token.substringAfter(':')
                    commits += 1
                    steps.add(
                        TaskIrStep(
                            intent = TaskIrStep.Intent.COMMIT,
                            target = TargetRef(UiOntology.UiType.BUTTON, textHint = hint),
                            guard = pendingGuard,
                            goalHint = "送出"
                        )
                    )
                    pendingGuard = ""
                }
                upper.startsWith("NAV:") -> {
                    val parts = token.substringAfter(':').split(':')
                    val type = runCatching {
                        UiOntology.UiType.valueOf(parts[0].trim().uppercase())
                    }.getOrNull() ?: UiOntology.UiType.BUTTON
                    val hint = parts.getOrElse(1) { "" }.trim()
                    if (model != null && !model.supports(type)) {
                        return Result.Unsupported(tpl.id, "type_not_in_model:${type.name}")
                    }
                    steps.add(
                        TaskIrStep(
                            intent = TaskIrStep.Intent.NAVIGATE,
                            target = TargetRef(type, textHint = hint),
                            guard = pendingGuard,
                            goalHint = "導覽"
                        )
                    )
                    pendingGuard = ""
                }
                else -> return Result.Unsupported(tpl.id, "unknown_token:$token")
            }
        }
        if (steps.isEmpty()) return Result.Unsupported(tpl.id, "no_steps")

        // 資料流檢查：WRITE 用到的變數必須有來源（READ/COMPUTE 產生，或呼叫端提供）
        val provided = vars.keys
        val missing = consumed.filter { it !in produced && it !in provided && vars[it].isNullOrBlank() }
        if (missing.isNotEmpty()) return Result.MissingVars(tpl.id, missing.toSet())

        val plan = TaskIrPlan(
            id = "ir-" + tpl.id,
            command = command,
            steps = steps,
            reads = produced,
            writes = consumed,
            commits = countCommits(steps)
        )
        return Result.Compiled(plan)
    }

    private fun countCommits(steps: List<TaskIrStep>): Int =
        steps.count { it.intent == TaskIrStep.Intent.COMMIT }

    /** 隔離 SlotFiller（core/taskos）的依賴，讓編譯器可獨立測試 */
    private object SlotFillerProxy {
        fun extract(command: String): Map<String, String> = runCatching {
            com.wae.agent.core.taskos.SlotFiller.extract(command)
        }.getOrDefault(emptyMap())
    }

    private object KindHolder {
        val COMPUTE: ValueRef.Kind = ValueRef.Kind.COMPUTE
    }
}
