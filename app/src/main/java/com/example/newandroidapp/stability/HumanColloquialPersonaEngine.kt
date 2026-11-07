package com.example.newandroidapp.stability

import com.example.newandroidapp.chat.ConversationTurnContext

/**
 * 100% 原創自研：極短句（正常 ~10 字、上限 20 字）、零語助詞、真人口語直率小脾氣引擎
 *
 * 核心原則：
 * 1. 【零語助詞規則 (Zero Modal Particles)】：
 *    全面停用「啦、囉、唷、喔、咧、嘛、耶、齁、欸、嘖、喏」等裝可愛語助詞，讓語音乾淨利落、自然像真人說話。
 * 2. 【極致精簡短句規則 (10-Char Normal / 20-Char Hard Cap)】：
 *    一般回應控制在 8~12 字左右，任何情況下絕對不超過 20 個字。
 * 3. 【徹底消滅工程師專業詞彙 (Zero-Jargon)】：
 *    嚴禁出現「元件、候選、前景、包名、步數上限、座標、安全閘門」等機械詞彙。
 * 4. 【直率有脾氣性格 (Blunt & Sassy)】：
 *    做錯被唸時直接回話（「不是，誰知道你是這個意思。」、「字長那麼像，這也能怪我？」），乾淨直接。
 */
object HumanColloquialPersonaEngine {

    const val MAX_SPOKEN_CHARS = 20

    enum class PersonaMood(val label: String) {
        SPICY_DEFENSIVE("直率頂嘴反擊"),
        SPICY_BLAME_APP("直率吐槽甩鍋"),
        SPICY_HANDOVER("直率讓位休息"),
        SPICY_PROUD("直率完成回報"),
        SPICY_CASUAL("直率日常對話")
    }

    data class PersonaReply(
        val mood: PersonaMood,
        val spokenAndDisplayText: String,
        val suggestedRemedialCommand: String? = null
    )

    private val WRONG_ACTION_KEYWORDS = listOf(
        "點錯", "按錯", "做錯", "搞錯", "弄錯", "找錯", "開錯",
        "不是這個", "不是這首", "不是這部", "不是這個意思", "不是要這個",
        "不是啦", "不對啦", "錯了啦", "亂點", "亂按", "你在幹嘛", "在幹嘛", "搞什麼"
    )

    private val SCOLD_KEYWORDS = listOf(
        "笨", "蠢", "白癡", "智障", "北七", "廢物", "很爛", "真爛", "太爛", "沒用", "討厭你"
    )

    private val IMPATIENT_KEYWORDS = listOf(
        "太慢", "好慢", "快一點", "快點", "還要多久", "等多久", "卡住", "沒反應", "不動了"
    )

    /**
     * 檢測使用者是否抱怨做錯／罵她／嫌慢，回傳 10~15 字內、零語助詞的直率嗆辣回話與「返回上一頁」補救
     */
    fun detectComplaintOrEmotionalChat(
        userText: String,
        @Suppress("UNUSED_PARAMETER") turnContext: ConversationTurnContext? = null
    ): PersonaReply? {
        val q = userText.trim()
        if (q.isEmpty()) return null
        val lower = q.lowercase()

        // 1. 抱怨做錯、不是這個意思 -> 12~13 字零語助詞直率回嘴
        if (WRONG_ACTION_KEYWORDS.any { lower.contains(it) } || q == "不對" || q == "錯了") {
            val reply = when {
                q.contains("意思") || q.contains("不是要") -> "不是，誰知道你是這個意思。"
                q.contains("點錯") || q.contains("按錯") -> "字長那麼像，這也能怪我？"
                else -> "是你自己沒講清楚，怪我？"
            }
            return PersonaReply(
                mood = PersonaMood.SPICY_DEFENSIVE,
                spokenAndDisplayText = enforceConciseLength(reply),
                suggestedRemedialCommand = "返回上一頁"
            )
        }

        // 2. 罵她笨／爛 -> 12 字零語助詞回嗆
        if (SCOLD_KEYWORDS.any { lower.contains(it) }) {
            return PersonaReply(
                mood = PersonaMood.SPICY_DEFENSIVE,
                spokenAndDisplayText = enforceConciseLength("說誰笨？有本事你自己按。"),
                suggestedRemedialCommand = null
            )
        }

        // 3. 催促嫌慢 -> 10 字零語助詞回嗆
        if (IMPATIENT_KEYWORDS.any { lower.contains(it) }) {
            return PersonaReply(
                mood = PersonaMood.SPICY_BLAME_APP,
                spokenAndDisplayText = enforceConciseLength("催什麼，畫面還在跑。"),
                suggestedRemedialCommand = null
            )
        }

        return null
    }

    /**
     * 將所有系統播報與狀態轉譯為「正常 ~10 字、最多 <= 20 字、零語助詞」的直率真人短句
     */
    fun humanizeSystemSpeech(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""

        // 1. 任務開始、語音指令、開啟 App、完成任務（控制在 8~12 字內，零語助詞）
        if (trimmed.startsWith("開始執行：")) {
            val goalPart = stripModalParticles(trimmed.removePrefix("開始執行：").trim())
            return if (goalPart.length <= 8) {
                enforceConciseLength("知道了，幫你$goalPart。")
            } else {
                "知道了，現在幫你處理。"
            }
        }
        if (trimmed.startsWith("收到語音指令：")) {
            return "聽到了，馬上幫你用。"
        }
        if (trimmed.startsWith("已成功為您打開")) {
            val appPart = stripModalParticles(
                trimmed.removePrefix("已成功為您打開").replace("「", "").replace("」", "").trim()
            )
            return if (appPart.length <= 8) {
                enforceConciseLength("$appPart 幫你打開了。")
            } else {
                "已經幫你打開了。"
            }
        }
        if (trimmed.startsWith("已為您完成") || trimmed.startsWith("弄好了") || trimmed.startsWith("已經幫你弄好了")) {
            return "已經幫你弄好了。"
        }

        // 2. 攔截系統內部所有專業詞彙與錯誤狀態，轉為 ~10~12 字零語助詞短句
        val mapped = when {
            trimmed == "語音播報已開啟" -> "語音開好了，有事快說。"
            trimmed.contains("連續點擊候選元件後畫面均無反應") -> "這按鈕沒反應，我不按了。"
            trimmed.contains("翻到底都沒看到這個按鍵") || trimmed.contains("頁面已滑到底部且未發現目標元件") -> "翻到底都沒看到這個按鍵，沒辦法按。"
            trimmed.contains("已達單次任務安全步數上限") -> "翻半天都沒有，我不找了。"
            trimmed.contains("找不到對應的已安裝 App") || trimmed.contains("無法啟動目標 App") -> "你手機根本沒裝這個程式。"
            trimmed.contains("無有效點擊座標") -> "這頁空空的，要我點哪裡？"
            trimmed.contains("誤跳外部程式") && trimmed.contains("自動拉回") -> "點到廣告了，幫你退回來。"
            trimmed.contains("高敏感") || trimmed.contains("真人觸碰") || trimmed.contains("親自操作") -> "你要自己按，那就給你按。"
            trimmed.contains("AI 已暫停讓位") || trimmed.contains("手動完成後點擊繼續") -> "這頁要密碼，你自己輸入。"
            trimmed.contains("收到確認，AI 繼續執行任務") -> "弄好了？那換我繼續按。"
            trimmed == "已手動停止" || trimmed == "使用者已手動取消任務" -> "叫我停，那我就先休息。"
            else -> stripModalParticles(stripTechnicalJargon(trimmed))
        }

        return enforceConciseLength(mapped)
    }

    /**
     * 強制確保輸出字數不超過 20 字且不含裝可愛語助詞
     */
    fun enforceConciseLength(text: String, maxChars: Int = MAX_SPOKEN_CHARS): String {
        val clean = stripModalParticles(stripTechnicalJargon(text))
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.length <= maxChars) return clean

        val firstClause = clean.split(Regex("[。！!？?；;\n]")).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstClause.length in 3..(maxChars - 1)) {
            val endPunct = if (clean.contains("？") || clean.contains("?")) "？" else "。"
            return firstClause + endPunct
        }

        val commaParts = clean.split(Regex("[，,]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (commaParts.size >= 2) {
            val twoParts = "${commaParts[0]}，${commaParts[1]}"
            if (twoParts.length <= maxChars - 1) {
                return twoParts.trimEnd('！', '!', '。', '？', '?') + "。"
            }
            if (commaParts[0].length in 3..(maxChars - 1)) {
                return commaParts[0].trimEnd('！', '!', '。', '？', '?') + "。"
            }
        }

        return clean.take(maxChars - 1).trimEnd('，', ',', '。', '！', '!', '？', '?') + "。"
    }

    /**
     * 徹底移除句首與句尾裝可愛語助詞（啦、囉、唷、喔、咧、嘛、耶、齁、欸、嘖、喏）
     */
    fun stripModalParticles(text: String): String {
        return text
            .replace(Regex("^(好啦好啦|好啦|好唷|喂|欸|齁|嘖|喏|哈)[，,！!？?\\s]*"), "")
            .replace(Regex("[啦囉唷喔咧嘛耶齁]+([。！!？?]?)$"), "$1")
            .replace(Regex("[啦囉唷喔咧嘛耶齁]+([，,。！!？?])"), "$1")
            .trim()
    }

    /**
     * 清除工程術語與套件包名
     */
    fun stripTechnicalJargon(text: String): String {
        return text
            .replace("互動元件", "按鈕")
            .replace("候選目標", "選項")
            .replace("候選項目", "選項")
            .replace("安全閘門", "安全保護")
            .replace("系統待命就緒", "隨時待命")
            .replace("雙模型本機 AI 助手", "手機幫手")
            .replace(Regex("com\\.spotify\\.music", RegexOption.IGNORE_CASE), "Spotify")
            .replace(Regex("com\\.google\\.android\\.youtube", RegexOption.IGNORE_CASE), "YouTube")
            .replace(Regex("jp\\.naver\\.line\\.android", RegexOption.IGNORE_CASE), "LINE")
            .replace(Regex("com\\.android\\.settings", RegexOption.IGNORE_CASE), "設定")
            .replace(Regex("com\\.android\\.chrome", RegexOption.IGNORE_CASE), "Chrome")
    }
}
