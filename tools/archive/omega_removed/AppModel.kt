package com.wae.agent.core.omega

import java.security.MessageDigest

/**
 * WAE-Ω 第 2 層：App Model（本體論型別的 App 行為模型）。
 *
 * 與既有 automaton（被動累積軌跡）不同，App Model 是一等公民：
 *  - **本體論型別**（[UiOntology.UiType]）描述狀態與元素 → 跨 App 同構
 *  - **資料 schema**（[FieldSchema]）：哪些欄位可讀/可寫
 *  - **風險標註**（[RiskMark]）：付款/刪除/公開發布/權限/私密讀取
 *  - **內容定址**（[contentHash]）：模型可離線分享、可驗證未被竄改
 *
 * 序列化用逐行文字（不用 org.json，JVM 可測）：
 * ```
 * # WAE_APP_MODEL v1
 * pkg=com.android.settings
 * version=3
 * state=<id>|<fingerprint1,fingerprint2>|<TYPE1,TYPE2>
 * trans=<from>|<ACTION>|<TYPE>|<selector>|<to>|<ok>|<fail>
 * field=<name>|<TYPE>|<readPattern>|<writePattern>
 * risk=<KIND>|<selector>
 * hash=<sha256>
 * ```
 */
data class AppModel(
    val pkg: String,
    val version: Int = 1,
    val states: List<AppState> = emptyList(),
    val transitions: List<Transition> = emptyList(),
    val fields: List<FieldSchema> = emptyList(),
    val risks: List<RiskMark> = emptyList(),
    /** 內容雜湊（由 [computeHash] 產生；空字串代表未計算） */
    val hash: String = ""
) {
    fun stateById(id: String): AppState? = states.firstOrNull { it.id == id }

    fun transitionsFrom(stateId: String): List<Transition> =
        transitions.filter { it.fromId == stateId }

    fun fieldByName(name: String): FieldSchema? = fields.firstOrNull { it.name == name }

    fun fieldsOf(type: UiOntology.UiType): List<FieldSchema> = fields.filter { it.type == type }

    /** 某 selector 的風險標註（支援 selector 包含/前綴比對） */
    fun risksOf(selector: String): List<RiskMark> {
        if (selector.isBlank()) return emptyList()
        return risks.filter { r ->
            r.selector.isNotEmpty() &&
                (selector.contains(r.selector) || r.selector.contains(selector))
        }
    }

    /** 是否包含某個型別的欄位/狀態（意圖編譯器的靜態檢查用） */
    fun supports(type: UiOntology.UiType): Boolean =
        fields.any { it.type == type } || states.any { type in it.types }

    /**
     * 有界廣度搜尋：從 [from] 到 [to] 是否可達（給「計畫靜態檢查」用）。
     *
     * 不存在的 state 直接回 false；深度上限避免大模型卡住。
     */
    fun reachable(from: String, to: String, maxDepth: Int = 12): Boolean {
        if (from == to) return true
        if (stateById(from) == null || stateById(to) == null) return false
        val visited = HashSet<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(from to 0)
        visited.add(from)
        while (queue.isNotEmpty()) {
            val (cur, depth) = queue.removeFirst()
            if (depth >= maxDepth) continue
            for (t in transitionsFrom(cur)) {
                if (t.toId == to) return true
                if (visited.add(t.toId)) queue.addLast(t.toId to depth + 1)
            }
        }
        return false
    }

    /** 重新計算內容雜湊（不含 hash 行本身） */
    fun computeHash(): String {
        val body = bodyLines().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun withComputedHash(): AppModel = copy(hash = computeHash())

    /** 驗證內容完整性（雜湊不符＝被改過或壞檔） */
    fun verifyIntegrity(): Boolean = hash.isNotEmpty() && hash == computeHash()

    fun toLineFormat(): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (line in bodyLines()) sb.append(line).append('\n')
        sb.append("hash=").append(hash.ifEmpty { computeHash() }).append('\n')
        return sb.toString()
    }

    private fun bodyLines(): List<String> {
        val out = ArrayList<String>()
        out.add("pkg=" + esc(pkg))
        out.add("version=" + version)
        for (s in states) {
            out.add(
                "state=" + esc(s.id) + "|" +
                    s.fingerprints.joinToString(",") + "|" +
                    s.types.joinToString(",") { it.name }
            )
        }
        for (t in transitions) {
            out.add(
                "trans=" + esc(t.fromId) + "|" + t.actionKind.name + "|" +
                    (t.targetType?.name ?: "") + "|" + esc(t.selector) + "|" +
                    esc(t.toId) + "|" + t.ok + "|" + t.fail
            )
        }
        for (f in fields) {
            out.add(
                "field=" + esc(f.name) + "|" + f.type.name + "|" +
                    esc(f.readPattern) + "|" + esc(f.writePattern)
            )
        }
        for (r in risks) {
            out.add("risk=" + r.kind.name + "|" + esc(r.selector))
        }
        return out
    }

    companion object {
        const val HEADER = "# WAE_APP_MODEL v1"

        fun fromLineFormat(text: String): AppModel? {
            if (!text.startsWith(HEADER)) return null
            var pkg = ""
            var version = 1
            var hash = ""
            val states = ArrayList<AppState>()
            val transitions = ArrayList<Transition>()
            val fields = ArrayList<FieldSchema>()
            val risks = ArrayList<RiskMark>()

            for (raw in text.lineSequence()) {
                val line = raw.trimEnd()
                when {
                    line.startsWith("pkg=") -> pkg = unesc(line.removePrefix("pkg="))
                    line.startsWith("version=") -> version = line.removePrefix("version=").toIntOrNull() ?: 1
                    line.startsWith("state=") -> {
                        val p = line.removePrefix("state=").split('|')
                        if (p.size >= 3) {
                            val fps = p[1].split(',').mapNotNull { it.trim().toLongOrNull() }
                            val types = p[2].split(',').mapNotNull { n ->
                                runCatching { UiOntology.UiType.valueOf(n.trim()) }.getOrNull()
                            }.toSet()
                            states.add(AppState(unesc(p[0]), fps, types))
                        }
                    }
                    line.startsWith("trans=") -> {
                        val p = line.removePrefix("trans=").split('|')
                        if (p.size >= 7) {
                            val kind = runCatching {
                                com.wae.agent.core.ActionKind.valueOf(p[1].trim())
                            }.getOrNull() ?: continue
                            val type = if (p[2].isBlank()) null else runCatching {
                                UiOntology.UiType.valueOf(p[2].trim())
                            }.getOrNull()
                            transitions.add(
                                Transition(
                                    fromId = unesc(p[0]),
                                    actionKind = kind,
                                    targetType = type,
                                    selector = unesc(p[3]),
                                    toId = unesc(p[4]),
                                    ok = p[5].toIntOrNull() ?: 0,
                                    fail = p[6].toIntOrNull() ?: 0
                                )
                            )
                        }
                    }
                    line.startsWith("field=") -> {
                        val p = line.removePrefix("field=").split('|')
                        if (p.size >= 4) {
                            val type = runCatching {
                                UiOntology.UiType.valueOf(p[1].trim())
                            }.getOrNull() ?: continue
                            fields.add(
                                FieldSchema(
                                    name = unesc(p[0]),
                                    type = type,
                                    readPattern = unesc(p[2]),
                                    writePattern = unesc(p[3])
                                )
                            )
                        }
                    }
                    line.startsWith("risk=") -> {
                        val p = line.removePrefix("risk=").split('|')
                        if (p.size >= 2) {
                            val kind = runCatching {
                                RiskKind.valueOf(p[0].trim())
                            }.getOrNull() ?: continue
                            risks.add(RiskMark(kind, unesc(p[1])))
                        }
                    }
                    line.startsWith("hash=") -> hash = line.removePrefix("hash=").trim()
                }
            }
            if (pkg.isBlank()) return null
            return AppModel(pkg, version, states, transitions, fields, risks, hash)
        }

        private fun esc(raw: String): String = raw
            .replace("\\", "\\\\")
            .replace("|", "\\p")
            .replace("\t", "\\t")
            .replace("\n", "\\n")

        private fun unesc(raw: String): String {
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    sb.append(
                        when (val n = raw[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'p' -> '|'
                            '\\' -> '\\'
                            else -> n
                        }
                    )
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }
}

/** 一個畫面狀態：指紋（MinHash 16 lane）＋出現在該狀態的 UI 型別 */
data class AppState(
    val id: String,
    val fingerprints: List<Long> = emptyList(),
    val types: Set<UiOntology.UiType> = emptySet()
)

/** 狀態轉移：本體論型別的動作 → 目標狀態（含成功率統計） */
data class Transition(
    val fromId: String,
    val actionKind: com.wae.agent.core.ActionKind,
    val targetType: UiOntology.UiType?,
    val selector: String,
    val toId: String,
    val ok: Int = 0,
    val fail: Int = 0
) {
    val successRate: Float
        get() {
            val n = ok + fail
            return if (n == 0) 0.5f else ok.toFloat() / n
        }
}

/** 資料欄位 schema：名稱＋型別＋讀/寫用的 selector 樣式（可空） */
data class FieldSchema(
    val name: String,
    val type: UiOntology.UiType,
    val readPattern: String = "",
    val writePattern: String = ""
)

/** 風險標註：哪個 selector 屬於哪種風險 */
data class RiskMark(val kind: RiskKind, val selector: String)

enum class RiskKind {
    /** 金流（付款/購買/轉帳） */
    PAYMENT,

    /** 破壞性（刪除/清空/移除） */
    DESTRUCTIVE,

    /** 公開發布（貼文/留言/分享） */
    PUBLIC_WRITE,

    /** 權限/驗證碼等風控頁面 */
    PERMISSION,

    /** 私密資料讀取（帳號、地址、身分證…） */
    PRIVATE_READ
}
