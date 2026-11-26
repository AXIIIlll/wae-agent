package com.wae.agent.core.taskos

import android.content.Context
import com.wae.agent.core.data.AssetTable

/**
 * 任務模板（HTN 的「方法」層）：把常見長任務的骨架資料化。
 *
 * 檔案：`assets/open_source_ai_databases/ui_task_templates.tsv`
 * 格式：`template<TAB>pattern<TAB>steps`
 *  - pattern：比對使用者指令的正則（大小寫依需求自行加 `(?i)`）
 *  - steps：`|` 分隔的步驟 DSL（見下方 [parseSteps] 支援的 token）
 *
 * 設計原則：
 *  - **命中不了就回 null**，由上層 fallback 到既有 `decomposeCommandIntoLocalGoals`，
 *    所以新增模板永遠不會讓原有指令變差。
 *  - 模板是資料不是程式；新增能力＝加一行 TSV。
 *  - 步驟 DSL 的參數可含 `$var` 佔位符，由 [TaskContext] 在執行時代入（資料流）。
 *
 * 純 Kotlin：除了 `ensureLoaded(context)` 不碰 Android API，JVM 可測。
 */
object TaskTemplates {

    private const val DB_PATH = "open_source_ai_databases/ui_task_templates.tsv"

    data class Template(
        val id: String,
        val pattern: Regex,
        val stepsSpec: String
    )

    private val templates = ArrayList<Template>()

    /** 動詞 → ActionKind（資料驅動；TSV 是 `verb<TAB>動詞<TAB>ActionKind`） */
    private val verbs = HashMap<String, com.wae.agent.core.ActionKind>()

    private val DEFAULT_VERBS = listOf(
        "launch_app" to com.wae.agent.core.ActionKind.LAUNCH_APP,
        "open_app" to com.wae.agent.core.ActionKind.LAUNCH_APP,
        "open" to com.wae.agent.core.ActionKind.LAUNCH_APP,
        "tap" to com.wae.agent.core.ActionKind.TAP,
        "click" to com.wae.agent.core.ActionKind.TAP,
        "select" to com.wae.agent.core.ActionKind.TAP,
        "type" to com.wae.agent.core.ActionKind.TYPE,
        "input" to com.wae.agent.core.ActionKind.TYPE,
        "fill" to com.wae.agent.core.ActionKind.TYPE,
        "submit" to com.wae.agent.core.ActionKind.SUBMIT,
        "back" to com.wae.agent.core.ActionKind.BACK,
        "scroll_down" to com.wae.agent.core.ActionKind.SCROLL_DOWN,
        "scroll" to com.wae.agent.core.ActionKind.SCROLL_DOWN,
        "scroll_up" to com.wae.agent.core.ActionKind.SCROLL_UP,
        "wait" to com.wae.agent.core.ActionKind.WAIT,
        "deep_link" to com.wae.agent.core.ActionKind.DEEP_LINK,
        "open_url" to com.wae.agent.core.ActionKind.DEEP_LINK,
        "clear_text" to com.wae.agent.core.ActionKind.CLEAR_TEXT
    )

    private val table = AssetTable(DB_PATH)

    private val DEFAULT_ROWS = listOf(
        // 讀資訊：打開設定的關於頁 → 收集版本號
        "settings-version\t(?i)(?:讀|查看|查).*(?:版本)|(?:版本|version).*(?:多少|號)|version check\t" +
            "LAUNCH_APP:設定|TAP:關於|COLLECT:LONGEST_NUMBER:version|BACK",
        // 讀電池/儲存資訊
        "settings-battery\t(?i)(?:電池|電量|battery).*(?:多少|查看|讀)\t" +
            "LAUNCH_APP:設定|TAP:電池|COLLECT:REGEX:battery:\\d+%|BACK",
        // 搜尋並開啟第一筆結果（通用）
        "search-open-first\t(?i)(?:搜尋|搜索|查).{1,20}(?:第一個|第一筆|第一項)\t" +
            "LAUNCH_APP:\$app|TAP:搜尋|TYPE:\$query|SUBMIT|TAP:\$first",
        // 跨頁資料流：讀版本號 → 回到聊天貼上
        "version-to-chat\t(?i)(?:版本號?|version).*(?:貼|回報|告訴我)\t" +
            "LAUNCH_APP:設定|TAP:關於|COLLECT:LONGEST_NUMBER:version|BACK|BACK|LAUNCH_APP:聊天|TYPE:\$version"
    )

    fun ensureLoaded(context: Context?) {
        loadFromLines(table.lines(context, DEFAULT_ROWS))
    }

    /** 每行：`template<TAB>pattern<TAB>steps`；`#` 註解；壞行跳過（不 panic） */
    fun loadFromLines(lines: List<String>) {
        val parsed = ArrayList<Template>()
        val parsedVerbs = HashMap<String, com.wae.agent.core.ActionKind>()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val cols = line.split('\t')
            if (cols.size < 3) continue
            when (cols[0].trim()) {
                "template" -> {
                    val id = cols[1].trim()
                    val pattern = cols[2].trim()
                    val steps = cols.getOrElse(3) { "" }.trim()
                    if (id.isEmpty() || pattern.isEmpty() || steps.isEmpty()) continue
                    val regex = runCatching { Regex(pattern) }.getOrNull() ?: continue
                    parsed.add(Template(id, regex, steps))
                }
                "verb" -> {
                    // verb<TAB>動詞<TAB>ActionKind
                    val word = cols[1].trim().lowercase()
                    val kind = runCatching {
                        com.wae.agent.core.ActionKind.valueOf(cols[2].trim().uppercase())
                    }.getOrNull() ?: continue
                    if (word.isNotEmpty()) parsedVerbs[word] = kind
                }
            }
        }
        synchronized(templates) {
            templates.clear()
            templates.addAll(parsed)
            verbs.clear()
            verbs.putAll(parsedVerbs)
            if (verbs.isEmpty()) verbs.putAll(DEFAULT_VERBS)
        }
    }

    fun resetForTest() {
        synchronized(templates) { templates.clear() }
        synchronized(verbs) { verbs.clear() }
        table.reset()
    }

    /** 動詞數量（診斷） */
    fun verbCount(): Int = synchronized(verbs) { verbs.size }

    private fun kindOf(verb: String): com.wae.agent.core.ActionKind? =
        synchronized(verbs) { verbs[verb.lowercase()] }

    fun count(): Int = synchronized(templates) { templates.size }

    /** 列出所有模板 id（診斷用） */
    fun ids(): List<String> = synchronized(templates) { templates.map { it.id } }

    /** 命中第一個模板（順序即優先序；先加載的優先） */
    fun match(command: String): Template? {
        val snapshot = synchronized(templates) { templates.toList() }
        return snapshot.firstOrNull { it.pattern.containsMatchIn(command) }
    }

    /**
     * 解析步驟 DSL。
     *
     * 支援 token（大小寫不敏感）：
     * ```
     * LAUNCH_APP:設定      TAP:關於            TYPE:$version
     * SUBMIT               BACK                SCROLL_DOWN / SCROLL_UP
     * WAIT:800             DEEP_LINK:app://x
     * COLLECT:<mode>:<name>[:<arg>]   （掛在「前一個動作」成功後收集）
     * IRREVERSIBLE                     （下一個動作標記為不可逆，需確認）
     * GUARD:TEXT:<文字>                （下一個動作的守衛：畫面出現該文字才做）
     * ```
     *
     * @param planId 計畫 id（節點 id 前綴）
     * @param stepsSpec DSL 字串
     * @param stepIdxStart 起始 stepIdx（多段指令接續時用）
     */
    fun parseSteps(
        planId: String,
        stepsSpec: String,
        stepIdxStart: Int = 0
    ): List<TaskStep> {
        val out = ArrayList<TaskStep>()
        var stepIdx = stepIdxStart
        var pendingGuard: TaskGuard = TaskGuard.Always

        fun goal(
            kind: com.wae.agent.core.ActionKind,
            text: String,
            payload: String = ""
        ): com.wae.agent.core.LocalGoal = com.wae.agent.core.LocalGoal(
            stepIdx = stepIdx++,
            goalId = "$planId-${out.size}",
            goalText = text,
            canonicalIntentTag = kind.name + ":" + text.take(24),
            actionKind = kind,
            inputPayload = payload
        )

        val tokens = stepsSpec.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        for (token in tokens) {
            val upper = token.uppercase()
            when {
                upper.startsWith("COLLECT:") -> {
                    // COLLECT 寫在「動作之後」：掛到前一個動作的成功結果上
                    val parts = token.split(':', limit = 4)
                    if (parts.size >= 3 && out.isNotEmpty()) {
                        val mode = runCatching {
                            CollectSpec.Mode.valueOf(parts[1].trim().uppercase())
                        }.getOrDefault(CollectSpec.Mode.LONGEST_NUMBER)
                        val name = parts[2].trim()
                        val arg = parts.getOrElse(3) { "" }.trim()
                        if (name.isNotEmpty()) {
                            out[out.size - 1] = out.last().copy(collect = CollectSpec(name, mode, arg))
                        }
                    }
                    continue
                }
                upper == "IRREVERSIBLE" -> {
                    // 同樣寫在動作之後：標記前一個動作不可逆
                    if (out.isNotEmpty()) {
                        out[out.size - 1] = out.last().copy(irreversible = true)
                    }
                    continue
                }
                upper.startsWith("GUARD:TEXT:") -> {
                    // GUARD 寫在動作之前：下一個動作的守衛
                    pendingGuard = TaskGuard.TextContains(token.substring(11).trim())
                    continue
                }
                else -> Unit
            }

            val colon = token.indexOf(':')
            val verb = if (colon > 0) token.substring(0, colon).trim().uppercase() else upper
            val arg = if (colon > 0) token.substring(colon + 1).trim() else ""

            // 動詞對應表資料驅動（ui_task_verbs.tsv）；查不到就跳過（不讓一個錯字毀掉整個模板）
            val kind = kindOf(verb) ?: continue
            val goalText: String
            val payload: String
            when (kind) {
                com.wae.agent.core.ActionKind.LAUNCH_APP -> {
                    goalText = "打開" + arg
                    payload = arg
                }
                com.wae.agent.core.ActionKind.TAP -> {
                    goalText = "點選" + arg
                    payload = ""
                }
                com.wae.agent.core.ActionKind.TYPE,
                com.wae.agent.core.ActionKind.REPLACE_TEXT -> {
                    goalText = "輸入" + arg
                    payload = arg
                }
                com.wae.agent.core.ActionKind.SUBMIT -> {
                    goalText = "送出"
                    payload = ""
                }
                com.wae.agent.core.ActionKind.BACK -> {
                    goalText = "返回"
                    payload = ""
                }
                com.wae.agent.core.ActionKind.SCROLL_DOWN -> {
                    goalText = "向下捲動"
                    payload = ""
                }
                com.wae.agent.core.ActionKind.SCROLL_UP -> {
                    goalText = "向上捲動"
                    payload = ""
                }
                com.wae.agent.core.ActionKind.WAIT -> {
                    goalText = "等待"
                    payload = arg.ifEmpty { "800" }
                }
                com.wae.agent.core.ActionKind.DEEP_LINK -> {
                    goalText = "開啟連結"
                    payload = arg
                }
                com.wae.agent.core.ActionKind.CLEAR_TEXT -> {
                    goalText = "清除輸入內容"
                    payload = ""
                }
                else -> {
                    goalText = arg.ifEmpty { verb.lowercase() }
                    payload = arg
                }
            }

            val step = TaskStep(
                nodeId = "$planId-${out.size}",
                goal = goal(kind, goalText, payload),
                guard = pendingGuard
            )
            out.add(step)
            pendingGuard = TaskGuard.Always
        }
        return out
    }
}
