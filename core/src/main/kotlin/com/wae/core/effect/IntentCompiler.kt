package com.wae.core.effect

import com.wae.core.model.Effect
import com.wae.core.model.Op

/**
 * 動詞規則（`verb<TAB>op<TAB>targetKind`）。
 *
 * `targetKind` 決定「剩下那串字要變成什麼 target」：
 *  - `app`        → `app:<名稱>`
 *  - `toggle`     → `toggle:<名稱>`
 *  - `query`      → `query:<字串>`
 *  - `contact`    → `contact:<名稱>`
 *  - `home`       → `page:home`
 *  - `volume_up`  → `volume:master`（value=up）
 *  - `volume_down`→ `volume:master`（value=down）
 *  - `play`       → `media:playing`（value=play）
 *  - `pause`      → `media:playing`（value=pause）
 */
data class VerbRule(val verb: String, val op: Op, val targetKind: String)

/**
 * 文字 → 效果（Gen-5 §4.3）。**只做編譯，不做執行、不做猜測。**
 *
 * 三條紀律：
 *  1. 長動詞優先（「打開」先於「開」），避免前綴吃掉。
 *  2. 編不出來就回空清單 → 上層 `ABSTAIN(unknown_verb)`。
 *  3. 不含任何 App 名稱；App 別名解析在 App 層（綠區：PackageManager）。
 */
class IntentCompiler(
    private val rules: List<VerbRule> = DEFAULT_RULES
) : com.wae.core.pipeline.EffectSource {

    private val sorted: List<VerbRule> by lazy { rules.sortedByDescending { it.verb.length } }

    override fun compile(text: String): List<Effect> {
        val input = text.trim()
        if (input.isEmpty()) return emptyList()

        for (rule in sorted) {
            if (!input.startsWith(rule.verb)) continue
            val rest = input.removePrefix(rule.verb).trim()
            val target = targetOf(rule.targetKind, rest)
            if (target.isEmpty()) continue
            return listOf(Effect(target = target, op = rule.op, value = valueOf(rule.targetKind)))
        }
        return emptyList()
    }

    private fun targetOf(kind: String, rest: String): String = when (kind) {
        KIND_APP -> if (rest.isEmpty()) "" else PREFIX_APP + rest
        KIND_TOGGLE -> if (rest.isEmpty()) "" else PREFIX_TOGGLE + rest
        KIND_QUERY -> if (rest.isEmpty()) "" else PREFIX_QUERY + rest
        KIND_CONTACT -> if (rest.isEmpty()) "" else PREFIX_CONTACT + rest
        KIND_HOME -> PREFIX_PAGE + PAGE_HOME
        KIND_VOLUME_UP, KIND_VOLUME_DOWN -> PREFIX_VOLUME + STREAM_MASTER
        KIND_PLAY, KIND_PAUSE -> PREFIX_MEDIA + MEDIA_PLAYING
        KIND_PICKER -> PREFIX_PICKER + PICKER_IMAGE
        else -> ""
    }

    private fun valueOf(kind: String): String = when (kind) {
        KIND_VOLUME_UP -> VALUE_UP
        KIND_VOLUME_DOWN -> VALUE_DOWN
        KIND_PLAY -> VALUE_PLAY
        KIND_PAUSE -> VALUE_PAUSE
        else -> ""
    }

    companion object {
        const val KIND_APP = "app"
        const val KIND_TOGGLE = "toggle"
        const val KIND_QUERY = "query"
        const val KIND_CONTACT = "contact"
        const val KIND_HOME = "home"
        const val KIND_VOLUME_UP = "volume_up"
        const val KIND_VOLUME_DOWN = "volume_down"
        const val KIND_PLAY = "play"
        const val KIND_PAUSE = "pause"
        const val KIND_PICKER = "picker"

        const val PREFIX_APP = "app:"
        const val PREFIX_TOGGLE = "toggle:"
        const val PREFIX_QUERY = "query:"
        const val PREFIX_CONTACT = "contact:"
        const val PREFIX_PAGE = "page:"
        const val PREFIX_VOLUME = "volume:"
        const val PREFIX_MEDIA = "media:"
        const val PREFIX_PICKER = "picker:"
        const val PICKER_IMAGE = "image"

        const val PAGE_HOME = "home"
    const val PAGE_BACK = "back"
        const val STREAM_MASTER = "master"
        const val MEDIA_PLAYING = "playing"

        const val VALUE_UP = "up"
        const val VALUE_DOWN = "down"
        const val VALUE_PLAY = "play"
        const val VALUE_PAUSE = "pause"

        /** 最後防線（正式資料在 assets/ui/intent_verbs.tsv）。 */
        val DEFAULT_RULES: List<VerbRule> = listOf(
            VerbRule("回到主頁", Op.NAVIGATE, KIND_HOME),
            VerbRule("打開", Op.NAVIGATE, KIND_APP),
            VerbRule("開啟", Op.NAVIGATE, KIND_APP),
            VerbRule("關閉", Op.SET, KIND_TOGGLE),
            VerbRule("搜尋", Op.SELECT, KIND_QUERY),
            VerbRule("尋找", Op.SELECT, KIND_QUERY),
            VerbRule("播放", Op.SUBMIT, KIND_PLAY),
            VerbRule("暫停", Op.SET, KIND_PAUSE),
            VerbRule("調高", Op.SET, KIND_VOLUME_UP),
            VerbRule("調低", Op.SET, KIND_VOLUME_DOWN),
            VerbRule("傳訊息給", Op.SUBMIT, KIND_CONTACT)
        )

        fun parseRules(lines: Sequence<String>): List<VerbRule> {
            val out = ArrayList<VerbRule>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 3) continue
                val verb = c[0].trim()
                val op = runCatching { Op.valueOf(c[1].trim()) }.getOrNull() ?: continue
                val kind = c[2].trim()
                if (verb.isNotEmpty() && kind.isNotEmpty()) out.add(VerbRule(verb, op, kind))
            }
            return out
        }
    }
}
