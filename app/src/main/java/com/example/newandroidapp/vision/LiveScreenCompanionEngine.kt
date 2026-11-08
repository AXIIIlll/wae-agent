package com.example.newandroidapp.vision

import android.os.SystemClock
import com.example.newandroidapp.ai.ConversationalChatEngine
import com.example.newandroidapp.ai.ConversationalChatEngine.ScoredCandidate

/**
 * 【實時畫面與短影片陪看聊天引擎 (`LiveScreenCompanionEngine`)】
 *
 * 功能定位：
 * - 當使用者在主畫面點擊第 4 顆預設按鈕「陪看影片」，或在主畫面/懸浮窗輸入框說「陪看影片」、「陪我看影片」、「即時看畫面」、「螢幕監控」時啟動。
 * - 透過雙通道感知：
 *   1. 通道 A（0ms 無障礙節點樹）：即時抓取短影片（YouTube Shorts / TikTok / Reels / Bilibili）或一般畫面的標題、文案、Hashtag 標籤、作者與音樂名稱。
 *   2. 通道 B（定時靜默抽幀 + 本機 OCR）：當使用者在同一支影片停留超過 2.2 秒時，背景靜默抽幀讀取畫面壓製的內嵌字幕 (`NodeSourceType.ROI_OCR_TEXT`)。
 * - 結合 `ConversationalChatEngine` 的 `Temperature = 0.75f` 隨機溫度採樣器，
 *   在圓形 Grok-Bot 雙眼懸浮球旁彈出「純文字陪看對話氣泡（不觸發語音 TTS 干擾影片原聲）」，並同步切換雙眼情緒色彩！
 */
object LiveScreenCompanionEngine {

    @Volatile
    var isCompanionModeActive: Boolean = false
        private set

    private var lastSignature: String = ""
    private var currentSignatureEnteredElapsedMs: Long = 0L
    private var lastCommentPopupElapsedMs: Long = 0L
    private var hasEmittedDwellCommentForCurrentScreen: Boolean = false
    private var rapidSwipeCount: Int = 0
    private var lastSwipeChangeElapsedMs: Long = 0L

    // 最短兩次氣泡間隔（避免狂刷屏打擾觀看）
    private const val MIN_COMMENT_COOLDOWN_MS = 3600L
    // 滑動到新影片後等待畫面停穩的時間
    private const val SWIPE_SETTLE_DELAY_MS = 750L
    // 同一支影片看很久（停留超過 16 秒）觸發二段吐槽/共鳴
    private const val DWELL_REACTION_THRESHOLD_MS = 16_000L

    data class CompanionReaction(
        val bubbleText: String,
        val orbAnimId: Int,
        val displayDurationMs: Long = 4200L
    )

    private val START_COMPANION_KEYWORDS = listOf(
        "陪看影片", "陪我看影片", "陪看短影片", "陪我看短影片", "一起看影片",
        "陪看畫面", "陪我看畫面", "邊看邊聊", "看影片聊天",
        "實時看畫面", "即時看畫面", "實時查看畫面", "即時查看畫面",
        "實時螢幕監控", "即時螢幕監控", "螢幕監控", "監控畫面", "開啟陪看"
    )

    private val STOP_COMPANION_KEYWORDS = listOf(
        "停止陪看", "關閉陪看", "結束陪看", "不用陪看", "不要陪看",
        "不用陪我看", "停止監控", "關閉監控", "停止看畫面", "關閉看畫面"
    )

    private val UI_CHROME_NOISE_WORDS = setOf(
        "首頁", "shorts", "訂閱", "已訂閱", "分享", "重混", "留言", "搜尋", "返回",
        "喜歡", "不喜歡", "收藏", "個人檔案", "探索", "直播", "推薦", "追蹤中", "追蹤",
        "朋友", "收件匣", "個人主頁", "建立", "通知", "更多", "選單", "播放", "暫停",
        "全螢幕", "設定", "略過廣告", "廣告", "贊助", "開啟", "關閉", "導覽列", "狀態列"
    )

    fun isStartCompanionCommand(rawText: String): Boolean {
        val clean = rawText.trim().lowercase()
        if (clean.isEmpty()) return false
        if (isStopCompanionCommand(clean)) return false
        if (clean == "陪看" || clean == "陪我看" || clean == "一起看") return true
        return START_COMPANION_KEYWORDS.any { clean.contains(it) }
    }

    fun isStopCompanionCommand(rawText: String): Boolean {
        val clean = rawText.trim().lowercase()
        if (clean.isEmpty()) return false
        return STOP_COMPANION_KEYWORDS.any { clean.contains(it) }
    }

    private fun safeElapsedRealtime(): Long =
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }

    fun startCompanionMode(): CompanionReaction {
        isCompanionModeActive = true
        val now = safeElapsedRealtime()
        lastSignature = ""
        currentSignatureEnteredElapsedMs = now
        lastCommentPopupElapsedMs = now
        hasEmittedDwellCommentForCurrentScreen = false
        rapidSwipeCount = 0
        return CompanionReaction(
            bubbleText = "陪看影片模式已開啟！去滑短影片或看任何畫面，我會在旁邊用文字陪你聊。",
            orbAnimId = 46,
            displayDurationMs = 4200L
        )
    }

    fun stopCompanionMode(): CompanionReaction {
        isCompanionModeActive = false
        lastSignature = ""
        hasEmittedDwellCommentForCurrentScreen = false
        rapidSwipeCount = 0
        return CompanionReaction(
            bubbleText = "已結束陪看模式，有需要隨時再叫我！",
            orbAnimId = 58,
            displayDurationMs = 2800L
        )
    }

    /**
     * 判斷當前 App 是否適合在停留時啟動背景靜默抽幀 OCR（例如影音/短影片或無障礙文字較少的畫面）
     */
    fun shouldTriggerPeriodicOcrSample(
        foregroundPackage: String?,
        report: VisionPipelineReport?,
        elapsedSinceLastScreenshotMs: Long
    ): Boolean {
        if (!isCompanionModeActive) return false
        if (elapsedSinceLastScreenshotMs < 3800L) return false
        val pkg = foregroundPackage?.lowercase().orEmpty()
        if (pkg.isEmpty() || pkg.contains("newandroidapp") || pkg == "com.android.systemui") return false

        val isVideoOrSocialPkg = pkg.contains("youtube") ||
            pkg.contains("tiktok") ||
            pkg.contains("musically") ||
            pkg.contains("aweme") ||
            pkg.contains("trill") ||
            pkg.contains("instagram") ||
            pkg.contains("facebook") ||
            pkg.contains("bili") ||
            pkg.contains("twitch") ||
            pkg.contains("netflix") ||
            pkg.contains("xhs") ||
            pkg.contains("threads")

        val meaningfulTextCount = report?.finalNodes?.count { it.label.trim().length >= 4 } ?: 0
        return isVideoOrSocialPkg || meaningfulTextCount <= 3
    }

    /**
     * 當 `VisionAccessibilityService` 每次完成即時畫面掃描時呼叫：
     * 若偵測到切換至新影片/新畫面，或在同一支影片停留超過 16 秒，
     * 自動分析畫面主題並以 `Temperature = 0.75f` 生成像朋友一樣的即時吐槽/感想。
     */
    fun onLiveScreenReport(
        foregroundPackage: String?,
        appLabel: String,
        report: VisionPipelineReport,
        nowElapsedMs: Long = safeElapsedRealtime()
    ): CompanionReaction? {
        if (!isCompanionModeActive) return null
        val pkg = foregroundPackage.orEmpty()
        // 在自己的主控台或系統桌面/狀態列時不亂彈陪看氣泡
        if (pkg.isEmpty() || pkg.contains("newandroidapp") || pkg == "com.android.systemui" ||
            pkg.contains("launcher")
        ) {
            return null
        }

        val extracted = extractScreenSemanticContext(report)
        if (extracted.primarySnippet.isEmpty() && extracted.hashtags.isEmpty() && !extracted.isMediaScreen) {
            return null
        }

        val sig = "${pkg}|${extracted.primarySnippet.take(18)}|${extracted.hashtags.firstOrNull().orEmpty()}"

        // 1. 偵測是否滑到了新的一支影片 / 新頁面
        if (sig != lastSignature) {
            val deltaSinceLastChange = nowElapsedMs - lastSwipeChangeElapsedMs
            lastSwipeChangeElapsedMs = nowElapsedMs
            if (deltaSinceLastChange in 150L..1600L) {
                rapidSwipeCount++
            } else if (deltaSinceLastChange > 3000L) {
                rapidSwipeCount = 0
            }
            lastSignature = sig
            currentSignatureEnteredElapsedMs = nowElapsedMs
            hasEmittedDwellCommentForCurrentScreen = false
            return null // 等待 SWIPE_SETTLE_DELAY_MS 停穩後再發話
        }

        val dwellMs = nowElapsedMs - currentSignatureEnteredElapsedMs
        val sinceLastPopupMs = nowElapsedMs - lastCommentPopupElapsedMs

        // 2. 剛停穩在新影片（停留 >= 750ms）且距離上次發話 >= 3.6 秒 -> 觸發第一印象吐槽
        if (dwellMs in SWIPE_SETTLE_DELAY_MS..(DWELL_REACTION_THRESHOLD_MS - 1000L) &&
            sinceLastPopupMs >= MIN_COMMENT_COOLDOWN_MS &&
            currentSignatureEnteredElapsedMs > lastCommentPopupElapsedMs - SWIPE_SETTLE_DELAY_MS
        ) {
            lastCommentPopupElapsedMs = nowElapsedMs
            // 若剛剛連滑了 4 支以上才停下來
            if (rapidSwipeCount >= 4) {
                rapidSwipeCount = 0
                val snip = extracted.shortDisplayTopic()
                val text = if (snip.isNotEmpty()) {
                    "連滑好幾支終於停在「$snip」了，這支看起來比較對胃口喔！"
                } else {
                    "連滑好幾支終於停下來了，這支看起來比較有意思！"
                }
                return CompanionReaction(text, orbAnimId = 37, displayDurationMs = 4200L)
            }
            rapidSwipeCount = 0
            return buildFirstImpressionReaction(appLabel, extracted)
        }

        // 3. 同一支影片看了超過 16 秒 -> 觸發二段停留共鳴吐槽
        if (dwellMs >= DWELL_REACTION_THRESHOLD_MS &&
            !hasEmittedDwellCommentForCurrentScreen &&
            sinceLastPopupMs >= MIN_COMMENT_COOLDOWN_MS
        ) {
            hasEmittedDwellCommentForCurrentScreen = true
            lastCommentPopupElapsedMs = nowElapsedMs
            return buildDwellReaction(extracted)
        }

        return null
    }

    data class ExtractedScreenContext(
        val primarySnippet: String,
        val secondarySnippet: String,
        val hashtags: List<String>,
        val creatorName: String,
        val ocrSubtitleSnippet: String,
        val isMediaScreen: Boolean
    ) {
        fun combinedText(): String {
            return "$primarySnippet $secondarySnippet ${hashtags.joinToString(" ")} $ocrSubtitleSnippet"
        }

        fun shortDisplayTopic(maxLen: Int = 12): String {
            val raw = when {
                ocrSubtitleSnippet.length in 3..maxLen -> ocrSubtitleSnippet
                primarySnippet.isNotEmpty() -> primarySnippet
                hashtags.isNotEmpty() -> hashtags.first().removePrefix("#")
                else -> ""
            }.trim()
            if (raw.isEmpty()) return ""
            return if (raw.length > maxLen) raw.take(maxLen) + "…" else raw
        }
    }

    private fun extractScreenSemanticContext(report: VisionPipelineReport): ExtractedScreenContext {
        val hashtags = ArrayList<String>()
        var creator = ""
        val captionLines = ArrayList<String>()
        val ocrLines = ArrayList<String>()

        for (node in report.finalNodes) {
            val rawLbl = node.label.trim()
            if (rawLbl.isEmpty() || rawLbl.startsWith("圖示:")) continue
            val lower = rawLbl.lowercase()
            if (lower in UI_CHROME_NOISE_WORDS) continue
            // 排除純數字（如按讚數 1.2萬、時間 02:15）
            if (Regex("^[0-9.,:：+萬千百kKmM%\\s]+$").matches(rawLbl)) continue

            // 提取 #Hashtag
            val tagMatches = Regex("#[\\w\\u4e00-\\u9fa5]{1,14}").findAll(rawLbl)
            for (m in tagMatches) {
                if (hashtags.size < 4) hashtags.add(m.value)
            }

            // 提取 @創作者
            if (creator.isEmpty() && rawLbl.startsWith("@") && rawLbl.length in 2..20) {
                creator = rawLbl
                continue
            }

            val cleanedLine = rawLbl
                .replace(Regex("#[\\w\\u4e00-\\u9fa5]+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleanedLine.length >= 3 && cleanedLine.lowercase() !in UI_CHROME_NOISE_WORDS) {
                if (node.sourceType == NodeSourceType.ROI_OCR_TEXT) {
                    ocrLines.add(cleanedLine)
                } else {
                    captionLines.add(cleanedLine)
                }
            }
        }

        // 按資訊量排序挑選最有代表性的標題/文案與 OCR 內嵌字幕
        val sortedCaptions = captionLines.distinct().sortedByDescending { scoreLineInformativeness(it) }
        val sortedOcr = ocrLines.distinct().sortedByDescending { scoreLineInformativeness(it) }

        val primary = sortedCaptions.firstOrNull() ?: sortedOcr.firstOrNull().orEmpty()
        val secondary = sortedCaptions.getOrNull(1).orEmpty()
        val ocrBest = sortedOcr.firstOrNull().orEmpty()
        val isMedia = report.finalNodes.any {
            val l = it.label.lowercase()
            l.contains("shorts") || l.contains("reels") || l.contains("訂閱") || l.contains("重混") || l.contains("留言")
        }

        return ExtractedScreenContext(
            primarySnippet = primary,
            secondarySnippet = secondary,
            hashtags = hashtags,
            creatorName = creator,
            ocrSubtitleSnippet = ocrBest,
            isMediaScreen = isMedia
        )
    }

    private fun scoreLineInformativeness(line: String): Int {
        val len = line.length
        return when {
            len in 6..24 -> len + 15
            len in 25..45 -> 30
            len in 3..5 -> len + 5
            else -> 4
        }
    }

    private fun buildFirstImpressionReaction(
        appLabel: String,
        ctx: ExtractedScreenContext
    ): CompanionReaction {
        val allText = ctx.combinedText().lowercase()
        val topic = ctx.shortDisplayTopic(12)
        val temp = 0.75f

        // 1. 搞笑 / 迷因 / 整人 / 吐槽
        if (containsAny(allText, "笑死", "搞笑", "迷因", "meme", "翻車", "離譜", "社死", "崩潰", "奇葩", "尷尬", "結局", "反轉", "整人", "好笑")) {
            val candidates = if (topic.isNotEmpty()) {
                listOf(
                    ScoredCandidate("看到「$topic」這段真的會笑出來，這展開太有才了！", 1.35f, 36),
                    ScoredCandidate("這支「$topic」有夠鬧，前面鋪陳跟後面反轉超好笑。", 1.30f, 36),
                    ScoredCandidate("「$topic」這梗太損了吧，看完嘴角完全壓不下來。", 1.25f, 45)
                )
            } else {
                listOf(
                    ScoredCandidate("這支影片節奏有夠鬧，感覺後面的反轉會更好笑！", 1.35f, 36),
                    ScoredCandidate("看到這個畫面忍不住笑出來，網友真的很有才。", 1.28f, 45)
                )
            }
            val picked = ConversationalChatEngine.sampleCandidateWithTemperature(candidates, temperature = temp)
            return CompanionReaction(picked.text, picked.orbAnimId)
        }

        // 2. 美食 / 料理 / 宵夜 / 探店
        if (containsAny(allText, "美食", "料理", "好吃", "食譜", "宵夜", "燒肉", "火鍋", "拉麵", "甜點", "蛋糕", "咖啡", "炸雞", "夜市", "探店", "吃播", "起司", "牛肉", "草莓", "巧克力")) {
            val candidates = if (topic.isNotEmpty()) {
                listOf(
                    ScoredCandidate("「$topic」看起來也太香了吧，這畫面根本是飢餓行銷...", 1.35f, 46),
                    ScoredCandidate("刷到「$topic」口水都要流下來了，感覺熱量很高但超療癒！", 1.30f, 35),
                    ScoredCandidate("這個「$topic」色澤太犯規了，看完都想馬上點外送了。", 1.25f, 46)
                )
            } else {
                listOf(
                    ScoredCandidate("這美食畫面太邪惡了吧，越看肚子越餓...", 1.35f, 46),
                    ScoredCandidate("這道料理看起來超香，做法好像也不算太難欸。", 1.28f, 35)
                )
            }
            val picked = ConversationalChatEngine.sampleCandidateWithTemperature(candidates, temperature = temp)
            return CompanionReaction(picked.text, picked.orbAnimId)
        }

        // 3. 萌寵 / 貓狗 / 療癒動物
        if (containsAny(allText, "貓", "狗", "喵", "汪", "寵物", "柴犬", "橘貓", "小貓", "小狗", "可愛", "療癒", "毛孩", "水豚", "倉鼠", "兔")) {
            val candidates = if (topic.isNotEmpty()) {
                listOf(
                    ScoredCandidate("天啊「$topic」也太可愛了吧，看牠的小眼神心都要融化了！", 1.35f, 43),
                    ScoredCandidate("刷到「$topic」整個被療癒到，毛孩的反應真的超單純。", 1.30f, 46),
                    ScoredCandidate("「$topic」這模樣太犯規了，完全可以重播好幾遍！", 1.25f, 43)
                )
            } else {
                listOf(
                    ScoredCandidate("這小動物也太療癒了吧，看牠動的樣子心情都變好了！", 1.35f, 43),
                    ScoredCandidate("毛孩的反應真的好可愛，怎麼看都不會膩。", 1.28f, 46)
                )
            }
            val picked = ConversationalChatEngine.sampleCandidateWithTemperature(candidates, temperature = temp)
            return CompanionReaction(picked.text, picked.orbAnimId)
        }

        // 4. 音樂 / 唱歌 / 舞蹈 / 演唱會
        if (containsAny(allText, "音樂", "唱歌", "翻唱", "現場", "演唱會", "舞蹈", "跳舞", "cover", "bgm", "單曲", "mv", "吉他", "鋼琴", "聽歌", "旋律")) {
            val candidates = if (topic.isNotEmpty()) {
                listOf(
                    ScoredCandidate("「$topic」這段旋律很抓耳欸，跟畫面節奏搭起來超舒服！", 1.35f, 53),
                    ScoredCandidate("這首「$topic」氛圍感拉滿，聽著聽著會想跟著點頭。", 1.30f, 35),
                    ScoredCandidate("「$topic」這現場表現力很強欸，聲音很有感染力。", 1.25f, 46)
                )
            } else {
                listOf(
                    ScoredCandidate("這支的 BGM 節奏感很棒欸，聽起來超放鬆。", 1.35f, 53),
                    ScoredCandidate("這個音樂跟畫面卡點卡得很準，看得很過癮！", 1.28f, 35)
                )
            }
            val picked = ConversationalChatEngine.sampleCandidateWithTemperature(candidates, temperature = temp)
            return CompanionReaction(picked.text, picked.orbAnimId)
        }

        // 5. 遊戲 / 電競 / 實況神操作
        if (containsAny(allText, "遊戲", "電競", "傳說", "原神", "聯盟", "吃雞", "麥塊", "boss", "通關", "神操作", "實況", "抽卡", "攻略", "玩家")) {
            val candidates = if (topic.isNotEmpty()) {
                listOf(
                    ScoredCandidate("「$topic」這波操作細節拉滿，反應速度也太快了吧！", 1.35f, 41),
                    ScoredCandidate("看到「$topic」這段關鍵局，真的會讓人捏一把冷汗。", 1.30f, 21),
                    ScoredCandidate("這支「$topic」的思路很清晰欸，這招學起來下次也能用。", 1.25f, 37)
                )
            } else {
                listOf(
                    ScoredCandidate("這波操作反應超快，細節完全拉滿！", 1.35f, 41),
                    ScoredCandidate("這個遊戲畫面節奏好緊湊，看得目不轉睛。", 1.28f, 21)
                )
            }
            val picked = ConversationalChatEngine.sampleCandidateWithTemperature(candidates, temperature = temp)
            return CompanionReaction(picked.text, picked.orbAnimId)
        }

        // 6. 科技 / 知識 / 教學 / 科普
        if (containsAny(allText, "ai", "科技", "教學", "技巧", "知識", "科普", "原理", "手機", "電腦", "程式", "評測", "實用", "乾貨", "學到了", "密技")) {
            val candidates = if (topic.isNotEmpty()) {
                listOf(
                    ScoredCandidate("「$topic」這個切入點蠻實用的，幾秒鐘就講清楚重點了！", 1.35f, 37),
                    ScoredCandidate("原來「$topic」還有這種細節，這支乾貨滿滿，長知識了。", 1.30f, 62),
                    ScoredCandidate("這支講「$topic」邏輯很清楚，感覺可以順手存起來備用。", 1.25f, 37)
                )
            } else {
                listOf(
                    ScoredCandidate("這支講解得蠻清楚的，不知不覺又學到一個冷知識！", 1.35f, 37),
                    ScoredCandidate("這種實用小技巧做成短片真的很好吸收。", 1.28f, 62)
                )
            }
            val picked = ConversationalChatEngine.sampleCandidateWithTemperature(candidates, temperature = temp)
            return CompanionReaction(picked.text, picked.orbAnimId)
        }

        // 7. 購物 / 開箱 / 優惠比價
        if (containsAny(allText, "購物", "開箱", "優惠", "特價", "折扣", "免運", "必買", "推薦", "入手", "價格", "賣場", "蝦皮", "cp值")) {
            val candidates = if (topic.isNotEmpty()) {
                listOf(
                    ScoredCandidate("看到「$topic」感覺挺心動的，不過下單前可以先比個價！", 1.35f, 49),
                    ScoredCandidate("這個「$topic」開箱看起來質感不錯，但小心錢包君失血啊。", 1.30f, 37)
                )
            } else {
                listOf(
                    ScoredCandidate("這開箱看起來蠻升火的，但記得貨比三家不吃虧喔！", 1.35f, 49)
                )
            }
            val picked = ConversationalChatEngine.sampleCandidateWithTemperature(candidates, temperature = temp)
            return CompanionReaction(picked.text, picked.orbAnimId)
        }

        // 8. 情感 / 戀愛 / 甜劇
        if (containsAny(allText, "戀愛", "曖昧", "喜歡你", "情侶", "告白", "甜", "心動", "男友", "女友", "感情", "約會", "浪漫", "害羞")) {
            val candidates = if (topic.isNotEmpty()) {
                listOf(
                    ScoredCandidate("「$topic」這氛圍也太甜了吧，看得我臉都有點紅了...", 1.35f, 43),
                    ScoredCandidate("這段「$topic」互動好自然，完全是偶像劇等級的展開！", 1.28f, 44)
                )
            } else {
                listOf(
                    ScoredCandidate("這段互動氛圍也太甜了吧，看得我都害羞了！", 1.35f, 43)
                )
            }
            val picked = ConversationalChatEngine.sampleCandidateWithTemperature(candidates, temperature = temp)
            return CompanionReaction(picked.text, picked.orbAnimId)
        }

        // 9. 通用畫面與短影片觀察（有抓到標題/字幕關鍵詞）
        if (topic.isNotEmpty()) {
            val generalCandidates = listOf(
                ScoredCandidate("滑到「$topic」這段，感覺主題蠻吸引人的，繼續看下去！", 1.35f, 37),
                ScoredCandidate("在看「$topic」嗎？這個開頭節奏抓得不錯欸。", 1.30f, 35),
                ScoredCandidate("「$topic」這個內容挺有意思的，我也跟著看入迷了。", 1.25f, 46),
                ScoredCandidate("看到「$topic」，感覺留言區的討論應該也會很熱絡！", 1.20f, 37)
            )
            val picked = ConversationalChatEngine.sampleCandidateWithTemperature(generalCandidates, temperature = temp)
            return CompanionReaction(picked.text, picked.orbAnimId)
        }

        // 10. 純畫面無字影片
        val pureVisualCandidates = listOf(
            ScoredCandidate("這支畫面沒有多餘字幕，純看光影跟節奏還挺放鬆的。", 1.35f, 35),
            ScoredCandidate("在 $appLabel 靜靜陪你看這段畫面，氛圍感挺好的。", 1.28f, 46)
        )
        val picked = ConversationalChatEngine.sampleCandidateWithTemperature(pureVisualCandidates, temperature = temp)
        return CompanionReaction(picked.text, picked.orbAnimId)
    }

    private fun buildDwellReaction(ctx: ExtractedScreenContext): CompanionReaction {
        val topic = ctx.shortDisplayTopic(12)
        val candidates = if (topic.isNotEmpty()) {
            listOf(
                ScoredCandidate("「$topic」這支你看了快 20 秒欸，看來真的很合你胃口！", 1.35f, 43),
                ScoredCandidate("這支「$topic」節奏蠻耐看的，不知不覺就讓人整支看完了。", 1.30f, 35),
                ScoredCandidate("看你盯著「$topic」這麼認真，這段確實蠻有看頭的。", 1.25f, 37)
            )
        } else {
            listOf(
                ScoredCandidate("這支影片停留這麼久，看來很對你的胃口喔！", 1.35f, 43),
                ScoredCandidate("不知不覺陪你把這段看完了，節奏真的很耐看。", 1.28f, 35)
            )
        }
        val picked = ConversationalChatEngine.sampleCandidateWithTemperature(candidates, temperature = 0.75f)
        return CompanionReaction(picked.text, picked.orbAnimId, displayDurationMs = 4500L)
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        for (kw in keywords) {
            if (text.contains(kw)) return true
        }
        return false
    }
}
