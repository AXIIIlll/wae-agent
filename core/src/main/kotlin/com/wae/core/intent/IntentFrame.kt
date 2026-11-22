package com.wae.core.intent

import com.wae.core.model.Effect
import com.wae.core.model.Op

/** 角色：一句話裡「這個詞在句中扮演什麼」。角色是封閉集合，詞是開放的。 */
enum class Role { ACTION, THEME, RECIPIENT, QUANTITY }

/**
 * 意圖框架（B6）：句子 → 五個角色。
 *
 * 這不是「動詞表」。動詞表是「句子 → 動作」，每多一種說法就要多一列；
 * 這裡只判斷「句子裡哪個詞是動作、哪個是被操作的對象」，
 * 真正的效果由 `EffectCompiler` 從角色**組出來**。
 */
data class IntentFrame(
    val raw: String,
    val action: Op,
    val targetKind: String,
    val theme: String = "",
    val recipient: String = "",
    val quantity: Int? = null,
    val missing: List<Role> = emptyList()
) {
    fun describe(): String = buildString {
        append(action.name).append('/').append(targetKind)
        if (theme.isNotEmpty()) append(" theme=").append(theme)
        if (recipient.isNotEmpty()) append(" to=").append(recipient)
        if (quantity != null) append(" n=").append(quantity)
        if (missing.isNotEmpty()) append(" missing=").append(missing.joinToString(",") { it.name })
    }
}

/** 動作詞的一列：詞 → (效果操作, 目標命名空間)。 */
data class ActionWord(val word: String, val op: Op, val targetKind: String)

/**
 * 角色詞庫（B7）。
 *
 * 檔案格式（`role_lexicon.tsv`）：
 * ```
 * # word<TAB>kind<TAB>op<TAB>targetKind
 * 打開	ACTION	NAVIGATE	app
 * 給	RECIPIENT
 * 幫我	NOISE
 * ```
 * 注意：新增的是「詞」，不是「句子」。詞可以組合，句子不行。
 */
class RoleLexicon(
    private val actions: List<ActionWord>,
    private val recipientMarkers: List<String>,
    private val noiseWords: List<String>
) {

    val sortedActions: List<ActionWord> by lazy { actions.sortedByDescending { it.word.length } }
    val sortedRecipients: List<String> by lazy { recipientMarkers.sortedByDescending { it.length } }
    val sortedNoise: List<String> by lazy { noiseWords.sortedByDescending { it.length } }

    companion object {
        const val KIND_ACTION = "ACTION"
        const val KIND_RECIPIENT = "RECIPIENT"
        const val KIND_NOISE = "NOISE"
        const val COLUMNS = 4

        /** 最後防線（正式資料在 assets/ui/role_lexicon.tsv）。 */
        val DEFAULT: RoleLexicon = RoleLexicon(
            actions = listOf(
                ActionWord("回到主頁", Op.NAVIGATE, "home"),
                ActionWord("返回", Op.NAVIGATE, "back"),
                ActionWord("回首頁", Op.NAVIGATE, "home"),
                ActionWord("回桌面", Op.NAVIGATE, "home"),
                ActionWord("調大聲", Op.SET, "volume_up"),
                ActionWord("大聲一點", Op.SET, "volume_up"),
                ActionWord("調小聲", Op.SET, "volume_down"),
                ActionWord("小聲一點", Op.SET, "volume_down"),
                ActionWord("亮一點", Op.SET, "brightness_up"),
                ActionWord("暗一點", Op.SET, "brightness_down"),
                ActionWord("上一頁", Op.NAVIGATE, "back"),
                ActionWord("打開通知列", Op.NAVIGATE, "notifications"),
                ActionWord("拉下通知列", Op.NAVIGATE, "notifications"),
                ActionWord("最近任務", Op.NAVIGATE, "recents"),
                ActionWord("切換應用程式", Op.NAVIGATE, "recents"),
                ActionWord("傳訊息給", Op.SUBMIT, "contact"),
                ActionWord("打開", Op.NAVIGATE, "app"),
                ActionWord("開啟", Op.NAVIGATE, "app"),
                ActionWord("關閉", Op.SET, "toggle"),
                ActionWord("關掉", Op.SET, "toggle"),
                ActionWord("搜尋", Op.SELECT, "query"),
                ActionWord("尋找", Op.SELECT, "query"),
                ActionWord("查一下", Op.SELECT, "query"),
                ActionWord("播放", Op.SUBMIT, "play"),
                ActionWord("暫停", Op.SET, "pause"),
                ActionWord("調高", Op.SET, "volume_up"),
                ActionWord("調低", Op.SET, "volume_down"),
                ActionWord("調亮", Op.SET, "brightness_up"),
                ActionWord("調暗", Op.SET, "brightness_down"),
                ActionWord("拍照", Op.SUBMIT, "capture"),
                ActionWord("照相", Op.SUBMIT, "capture"),
                ActionWord("拍一張", Op.SUBMIT, "capture"),
                ActionWord("傳給", Op.SUBMIT, "contact"),
                ActionWord("發給", Op.SUBMIT, "contact")
            ),
            recipientMarkers = listOf("給", "to"),
            noiseWords = listOf("幫我", "請", "麻煩", "一下", "我想要", "我要", "那個", "這個", "把", "將")
        )

        fun parse(lines: Sequence<String>): RoleLexicon {
            val actions = ArrayList<ActionWord>()
            val recipients = ArrayList<String>()
            val noise = ArrayList<String>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cells = line.split('\t')
                if (cells.size < 2) continue
                val word = cells[0].trim()
                val kind = cells[1].trim()
                if (word.isEmpty()) continue
                when (kind) {
                    KIND_ACTION -> {
                        val op = runCatching { Op.valueOf(cells[2].trim()) }.getOrNull() ?: continue
                        val target = cells.getOrNull(3)?.trim().orEmpty()
                        if (target.isNotEmpty()) actions.add(ActionWord(word, op, target))
                    }
                    KIND_RECIPIENT -> recipients.add(word)
                    KIND_NOISE -> noise.add(word)
                    else -> Unit
                }
            }
            return RoleLexicon(actions, recipients, noise)
        }
    }
}

/**
 * 句子 → `IntentFrame`（B7／B11）。
 *
 * 流程：去噪 → 找動作詞 → 切出「給X」→ 抓數量 → 剩下的就是 theme。
 * 缺角色**不猜**：回 `missing`，由呼叫端反問（`ABSTAIN(need=…)`）。
 */
class FrameParser(private val lexicon: RoleLexicon = RoleLexicon.DEFAULT) {

    fun parse(text: String): IntentFrame? {
        var work = normalize(text)
        if (work.isEmpty()) return null
        work = stripNoise(work)

        val action = lexicon.sortedActions.firstOrNull { work.contains(it.word) } ?: return null

        // 兩種語序都要能處理：「打開設定」（動作在前）與「把藍牙關掉」（動作在後）
        val beforeAction = work.substringBefore(action.word).trim()
        val afterAction = work.substringAfter(action.word).trim()
        var rest = when {
            afterAction.isNotEmpty() -> afterAction
            beforeAction.isNotEmpty() -> beforeAction
            else -> ""
        }

        var recipient = ""
        val marker = lexicon.sortedRecipients.firstOrNull { rest.contains(it) }
        if (marker != null) {
            val before = rest.substringBefore(marker).trim()
            val after = rest.substringAfter(marker).trim()
            recipient = after
            rest = before
        }

        val quantity = extractQuantity(rest)
        var theme = cleanTheme(rest)

        // 「傳給媽媽」：動作詞本身已含「給」，剩下的就是收件人
        if (action.targetKind == KIND_CONTACT && recipient.isEmpty() && theme.isNotEmpty()) {
            recipient = theme
            theme = ""
        }

        val missing = ArrayList<Role>()
        if (needsTheme(action.targetKind) && theme.isEmpty()) missing.add(Role.THEME)
        if (action.targetKind == KIND_CONTACT && recipient.isEmpty()) missing.add(Role.RECIPIENT)

        return IntentFrame(
            raw = text,
            action = action.op,
            targetKind = action.targetKind,
            theme = theme,
            recipient = recipient,
            quantity = quantity,
            missing = missing
        )
    }

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ")

    private fun stripNoise(text: String): String {
        var out = text
        for (word in lexicon.sortedNoise) out = out.replace(word, "")
        return out.trim()
    }

    private fun extractQuantity(text: String): Int? {
        val m = QUANTITY.find(text) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun cleanTheme(text: String): String =
        text.replace(QUANTITY, "").trim().removeSuffix("的").trim()

    private fun needsTheme(targetKind: String): Boolean =
        targetKind in setOf(KIND_APP, KIND_TOGGLE, KIND_QUERY, KIND_CONTACT)

    companion object {
        const val KIND_APP = "app"
        const val KIND_TOGGLE = "toggle"
        const val KIND_QUERY = "query"
        const val KIND_CONTACT = "contact"
        const val KIND_HOME = "home"
        /**
         * 數量詞：**必須有量詞**才算（「三張照片」）。
         * 不加這個限制的話，「台北101」會被吃成「台北」——實測踩過。
         */
        private val QUANTITY = Regex("(\\d+)\\s*[張個則次件筆]")
    }
}

/**
 * `IntentFrame` → 效果（B6 的下半段）。
 *
 * 這裡也不列舉句子：只做「角色 → 命名空間」的映射。
 */
object EffectCompiler {

    fun compile(frame: IntentFrame, count: Int = 1): List<Effect> {
        if (frame.missing.isNotEmpty()) return emptyList()

        // 系統設定類：「打開 Wi-Fi」不是開一支叫 Wi-Fi 的 App
        val systemTarget = com.wae.core.system.SystemTargets.normalize(frame.theme)
        if (systemTarget != null) {
            val key = com.wae.core.system.SystemTargets.keyOf(systemTarget)
            return if (com.wae.core.system.SystemTargets.needsPanel(systemTarget)) {
                listOf(Effect("panel:$key", Op.NAVIGATE, desiredValue(frame.action)))
            } else {
                listOf(Effect("brightness:screen", Op.SET, desiredValue(frame.action)))
            }
        }

        return when (frame.targetKind) {
            FrameParser.KIND_APP -> listOf(Effect("app:" + frame.theme, Op.NAVIGATE))
            FrameParser.KIND_TOGGLE -> listOf(Effect("toggle:" + frame.theme, frame.action))
            FrameParser.KIND_QUERY -> listOf(Effect("query:" + frame.theme, frame.action))
            FrameParser.KIND_CONTACT -> listOf(Effect("contact:" + frame.recipient, Op.SUBMIT, frame.theme))
            FrameParser.KIND_HOME -> listOf(Effect("page:home", Op.NAVIGATE))
            KIND_BACK -> listOf(Effect("page:back", Op.NAVIGATE))
            KIND_VOLUME_UP -> repeated(Effect("volume:master", Op.SET, "up"), frame.quantity)
            KIND_VOLUME_DOWN -> repeated(Effect("volume:master", Op.SET, "down"), frame.quantity)
            KIND_PLAY -> listOf(Effect("media:playing", Op.SUBMIT, "play"))
            KIND_PAUSE -> listOf(Effect("media:playing", Op.SET, "pause"))
            KIND_BRIGHTNESS_UP -> repeated(Effect("brightness:screen", Op.SET, "up"), frame.quantity)
            KIND_BRIGHTNESS_DOWN -> repeated(Effect("brightness:screen", Op.SET, "down"), frame.quantity)
            KIND_CAPTURE -> listOf(Effect("capture:photo", Op.SUBMIT))
            KIND_NOTIFICATIONS -> listOf(Effect("global:notifications", Op.NAVIGATE))
            KIND_RECENTS -> listOf(Effect("global:recents", Op.NAVIGATE))
            else -> emptyList()
        }
    }

    /**
     * 批次（C9）：「調高音量 3 次」＝同一個效果重複 N 次。
     * 只對「可重複且安全」的動作用（音量、亮度）；開 App、傳訊息不重複。
     */
    private fun repeated(effect: Effect, quantity: Int?): List<Effect> {
        val times = (quantity ?: 1).coerceIn(1, MAX_REPEAT)
        return List(times) { effect }
    }

    private const val MAX_REPEAT = 10

    private const val KIND_BRIGHTNESS_UP = "brightness_up"
    private const val KIND_BRIGHTNESS_DOWN = "brightness_down"
    private const val KIND_CAPTURE = "capture"
    private const val KIND_BACK = "back"
    private const val KIND_NOTIFICATIONS = "notifications"
    private const val KIND_RECENTS = "recents"

    private const val VALUE_OFF = "off"
    private const val KIND_VOLUME_UP = "volume_up"
    private const val KIND_VOLUME_DOWN = "volume_down"
    private const val KIND_PLAY = "play"
    private const val KIND_PAUSE = "pause"

    /** 「打開 Wi-Fi」是 on、「關掉 Wi-Fi」是 off；調亮/調暗給 brightness。 */
    private fun desiredValue(action: Op): String = when (action) {
        Op.SET -> "off"
        else -> "on"
    }
}
