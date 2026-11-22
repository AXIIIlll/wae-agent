package com.wae.agent.core.taskos

import android.content.Context
import java.io.File

/**
 * 技能編譯器（SkillCompiler）：把「成功跑完的任務」變成可重用的參數化技能。
 *
 * 這是 Task OS 的複利來源：同一個任務做第二次時，不需要重新規劃與摸索，
 * 直接把技能展開成步驟（並用當時的變數填參數）。
 *
 * 技能資料落盤：`filesDir/taskos/skills.txt`（逐行，避免 org.json 的 JVM 測試問題）。
 *
 * 純 Kotlin（Context 只是存檔位置），JVM 可測。
 */
object SkillCompiler {

    private const val HEADER = "# WAE_TASK_SKILLS v1"
    private const val MAX_SKILLS = 200

    data class Skill(
        val id: String,
        val name: String,
        val templateId: String,
        /** 參數化步驟 DSL（`LAUNCH_APP:設定|TAP:關於|TYPE:$version`） */
        val stepsSpec: String,
        /** 這條技能需要的參數（從 `$var` 抽出） */
        val params: Set<String>,
        val createdAtMs: Long,
        val uses: Int = 0
    ) {
        /** 把技能展開成步驟（呼叫端先填好參數到 context） */
        fun instantiate(planId: String): List<TaskStep> =
            TaskTemplates.parseSteps(planId, stepsSpec)
    }

    private val memory = ArrayList<Skill>()

    private var appContext: Context? = null

    fun ensureLoaded(context: Context?) {
        if (context != null && appContext == null) appContext = context.applicationContext
        synchronized(memory) {
            if (memory.isNotEmpty()) return
            memory.addAll(loadFromDisk())
        }
    }

    fun resetForTest() {
        synchronized(memory) { memory.clear() }
        appContext = null
    }

    fun count(): Int = synchronized(memory) { memory.size }

    fun all(): List<Skill> = synchronized(memory) { memory.toList() }

    /**
     * 從成功的會話編譯技能。
     *
     * 條件：至少 2 步、全部成功（由呼叫端保證）、有模板或可重建 DSL。
     * @return 技能（未寫入），寫入請用 [save]
     */
    fun compile(session: TaskSession, nowMs: Long = 0L): Skill? {
        val steps = session.plan.steps
        if (steps.size < 2) return null
        if (session.context.historySnapshot().none { it.ok } && steps.isNotEmpty()) return null

        val spec = toStepsSpec(steps)
        if (spec.isBlank()) return null
        val params = LinkedHashSet<String>()
        for (step in steps) {
            for (m in TASK_VAR_RE.findAll(step.goal.inputPayload)) params.add(m.groupValues[1])
        }
        return Skill(
            id = "sk-" + session.plan.planId.removePrefix("tp-").take(12),
            name = session.plan.command.take(40),
            templateId = session.plan.templateId,
            stepsSpec = spec,
            params = params,
            createdAtMs = if (nowMs > 0L) nowMs else System.currentTimeMillis()
        )
    }

    /** 把步驟反推回 DSL（可再被 [TaskTemplates.parseSteps] 展開） */
    fun toStepsSpec(steps: List<TaskStep>): String {
        val out = ArrayList<String>()
        for (step in steps) {
            val token = when (step.goal.actionKind) {
                com.wae.agent.core.ActionKind.LAUNCH_APP -> "LAUNCH_APP:" + step.goal.inputPayload
                com.wae.agent.core.ActionKind.TAP -> "TAP:" + step.goal.goalText.removePrefix("點選")
                com.wae.agent.core.ActionKind.TYPE -> "TYPE:" + step.goal.inputPayload
                com.wae.agent.core.ActionKind.SUBMIT -> "SUBMIT"
                com.wae.agent.core.ActionKind.BACK -> "BACK"
                com.wae.agent.core.ActionKind.SCROLL_DOWN -> "SCROLL_DOWN"
                com.wae.agent.core.ActionKind.SCROLL_UP -> "SCROLL_UP"
                com.wae.agent.core.ActionKind.WAIT -> "WAIT:" + step.goal.inputPayload
                com.wae.agent.core.ActionKind.CLEAR_TEXT -> "CLEAR_TEXT"
                else -> return "" // 有未知動作就不要編譯（寧可沒有技能，也不要錯技能）
            }
            out.add(token)
            step.collect?.let { c ->
                out.add("COLLECT:${c.mode.name}:${c.name}" + if (c.arg.isNotBlank()) ":${c.arg}" else "")
            }
            if (step.irreversible) out.add("IRREVERSIBLE")
        }
        return out.joinToString("|")
    }

    /** 寫入記憶體 + 磁碟（同 id 覆蓋 uses+1） */
    fun save(skill: Skill): Boolean {
        synchronized(memory) {
            val idx = memory.indexOfFirst { it.id == skill.id }
            if (idx >= 0) memory[idx] = skill.copy(uses = memory[idx].uses + 1)
            else memory.add(skill)
            while (memory.size > MAX_SKILLS) memory.removeAt(0)
            return writeToDisk()
        }
    }

    /** 用技能 id 找（診斷/測試） */
    fun find(id: String): Skill? = synchronized(memory) { memory.firstOrNull { it.id == id } }

    /** 依指令相似度找技能（簡單前綴/包含比對；找不到回 null） */
    fun findForCommand(command: String): Skill? {
        if (command.isBlank()) return null
        val snapshot = synchronized(memory) { memory.toList() }
        return snapshot.firstOrNull { command.contains(it.name) || it.name.contains(command) }
            ?: snapshot.firstOrNull { s ->
                s.templateId.isNotEmpty() && TaskTemplates.match(command)?.id == s.templateId
            }
    }

    // ── 磁碟 ──────────────────────────────────────────────────────

    private fun file(): File? = appContext?.let { File(it.filesDir, "taskos/skills.txt") }

    private fun writeToDisk(): Boolean {
        val f = file() ?: return true
        return runCatching {
            f.parentFile?.mkdirs()
            val sb = StringBuilder(HEADER).append('\n')
            synchronized(memory) {
                for (s in memory) {
                    sb.append("skill=")
                        .append(esc(s.id)).append('\t')
                        .append(esc(s.name)).append('\t')
                        .append(esc(s.templateId)).append('\t')
                        .append(s.uses).append('\t')
                        .append(esc(s.stepsSpec)).append('\t')
                        .append(s.createdAtMs).append('\n')
                }
            }
            f.writeText(sb.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    private fun loadFromDisk(): List<Skill> {
        val f = file() ?: return emptyList()
        if (!f.exists()) return emptyList()
        return runCatching {
            val out = ArrayList<Skill>()
            for (line in f.readText(Charsets.UTF_8).lineSequence()) {
                if (!line.startsWith("skill=")) continue
                val p = line.removePrefix("skill=").split('\t')
                if (p.size < 6) continue
                out.add(
                    Skill(
                        id = unesc(p[0]),
                        name = unesc(p[1]),
                        templateId = unesc(p[2]),
                        uses = p[3].toIntOrNull() ?: 0,
                        stepsSpec = unesc(p[4]),
                        params = TASK_VAR_RE.findAll(unesc(p[4]))
                            .map { it.groupValues[1] }.toSet(),
                        createdAtMs = p[5].toLongOrNull() ?: 0L
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun esc(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")

    private fun unesc(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
