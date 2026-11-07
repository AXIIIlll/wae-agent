package com.example.newandroidapp.ai

import android.content.Context
import com.example.newandroidapp.stability.HumanColloquialPersonaEngine
import com.example.newandroidapp.vision.VisionPipelineReport
import java.util.Calendar

/**
 * 100% 自研本機認知問答、常識推理與執行前搜尋規劃引擎 (`LocalKnowledgeAndReasoningEngine`)
 *
 * 解決兩大核心問題：
 * 1. 【對話具備真實思考與知識問答能力】：
 *    - 詢問人物、歷史、地理、科學、科技、時間日期或日常思考（例如：「誰是蔣介石」、「什麼是黑洞」、「現在幾點」、「我好累」）時，
 *      直接給出精煉直率（正常 ~10 字、嚴格 <= 20 字、零可愛語助詞）的回答，不再萬年跳針「什麼意思？要我查就按執行。」。
 * 2. 【按下「執行」前自動完成完整任務規劃】：
 *    - 當使用者輸入疑問句或知識查詢（例如：「誰是蔣介石」）並點擊「執行」（或點擊氣泡內嵌的執行鍵）時，
 *      自動剖析疑問句型、提煉核心實體「蔣介石」，並轉化為完整可執行的跨 App 搜尋命令：
 *      `打開 Chrome 搜尋 蔣介石`（或動態對應手機已安裝之 Chrome / Google），絕不把未規劃的原句直接當成命令。
 */
object LocalKnowledgeAndReasoningEngine {

    data class ConversationalReasoningResult(
        val replyText: String,
        val plannedExecutableCommand: String?
    )

    data class QuestionTopicExtraction(
        val isQuestionOrLookup: Boolean,
        val isLocationLookup: Boolean,
        val cleanTopic: String
    )

    private data class KnowledgeEntry(
        val keywords: List<String>,
        val canonicalTopic: String,
        val conciseAnswer: String
    )

    // 本機高頻百科與常識知識圖譜（每條回答嚴格 <= 20 字、零語助詞）
    private val KNOWLEDGE_BASE: List<KnowledgeEntry> = listOf(
        // 歷史與政治人物
        KnowledgeEntry(listOf("蔣介石", "蔣中正", "老蔣"), "蔣介石", "中華民國前總統，字中正。"),
        KnowledgeEntry(listOf("蔣經國", "小蔣"), "蔣經國", "中華民國前總統，推動十大建設。"),
        KnowledgeEntry(listOf("孫中山", "孫文", "孫逸仙", "國父"), "孫中山", "中華民國國父，字逸仙。"),
        KnowledgeEntry(listOf("李登輝"), "李登輝", "中華民國前總統，首位民選總統。"),
        KnowledgeEntry(listOf("陳水扁", "阿扁"), "陳水扁", "中華民國前總統，曾任台北市長。"),
        KnowledgeEntry(listOf("馬英九"), "馬英九", "中華民國前總統，曾任台北市長。"),
        KnowledgeEntry(listOf("蔡英文"), "蔡英文", "中華民國前總統，首位女總統。"),
        KnowledgeEntry(listOf("賴清德"), "賴清德", "現任中華民國總統。"),
        KnowledgeEntry(listOf("秦始皇", "嬴政"), "秦始皇", "中國歷史上第一位皇帝嬴政。"),
        KnowledgeEntry(listOf("漢武帝", "劉徹"), "漢武帝", "西漢皇帝劉徹，開拓絲路。"),
        KnowledgeEntry(listOf("唐太宗", "李世民"), "唐太宗", "唐朝皇帝李世民，開創貞觀之治。"),
        KnowledgeEntry(listOf("武則天"), "武則天", "中國歷史上唯一的女皇帝。"),
        KnowledgeEntry(listOf("成吉思汗", "鐵木真"), "成吉思汗", "蒙古帝國創立者鐵木真。"),
        KnowledgeEntry(listOf("康熙"), "康熙", "清朝定都北京後的第二位皇帝。"),
        KnowledgeEntry(listOf("乾隆"), "乾隆", "清朝皇帝弘曆，在位六十年。"),
        KnowledgeEntry(listOf("曹操"), "曹操", "三國時期曹魏奠基者。"),
        KnowledgeEntry(listOf("諸葛亮", "孔明"), "諸葛亮", "三國蜀漢丞相，字孔明。"),
        KnowledgeEntry(listOf("劉備"), "劉備", "三國時期蜀漢開國皇帝。"),
        KnowledgeEntry(listOf("關羽", "關公"), "關羽", "三國蜀漢名將，與劉備張飛結義。"),
        KnowledgeEntry(listOf("織田信長"), "織田信長", "日本戰國時代三英傑之一。"),
        KnowledgeEntry(listOf("拿破崙"), "拿破崙", "十九世紀法蘭西第一帝國皇帝。"),
        KnowledgeEntry(listOf("華盛頓"), "華盛頓", "美國第一任總統，開國元勳。"),
        KnowledgeEntry(listOf("林肯"), "林肯", "美國第16任總統，廢除奴隸制。"),
        KnowledgeEntry(listOf("邱吉爾"), "邱吉爾", "二戰時期英國首相。"),
        KnowledgeEntry(listOf("川普", "特朗普", "trump"), "川普", "美國總統暨企業家。"),
        KnowledgeEntry(listOf("拜登", "biden"), "拜登", "美國第46任總統。"),
        KnowledgeEntry(listOf("歐巴馬", "奧巴馬", "obama"), "歐巴馬", "美國第44任總統。"),

        // 思想家、文學家與藝術家
        KnowledgeEntry(listOf("孔子", "孔仲尼"), "孔子", "春秋時代思想家，儒家創始人。"),
        KnowledgeEntry(listOf("孟子"), "孟子", "戰國時代儒家代表人物，亞聖。"),
        KnowledgeEntry(listOf("老子", "李耳"), "老子", "道家學派創始人，著有道德經。"),
        KnowledgeEntry(listOf("莊子", "莊周"), "莊子", "戰國時代道家思想代表人物。"),
        KnowledgeEntry(listOf("李白", "李太白"), "李白", "唐代浪漫主義詩人，人稱詩仙。"),
        KnowledgeEntry(listOf("杜甫"), "杜甫", "唐代寫實主義詩人，人稱詩聖。"),
        KnowledgeEntry(listOf("蘇軾", "蘇東坡"), "蘇軾", "北宋文學家與書畫家，號東坡。"),
        KnowledgeEntry(listOf("莎士比亞"), "莎士比亞", "英國文藝復興時期劇作家。"),
        KnowledgeEntry(listOf("達文西"), "達文西", "文藝復興時期博學家與畫家。"),
        KnowledgeEntry(listOf("梵谷"), "梵谷", "荷蘭後印象派知名畫家。"),
        KnowledgeEntry(listOf("畢卡索"), "畢卡索", "二十世紀立體派現代藝術大師。"),
        KnowledgeEntry(listOf("貝多芬"), "貝多芬", "德國古典與浪漫主義音樂家。"),
        KnowledgeEntry(listOf("莫札特"), "莫札特", "奧地利古典時期音樂神童。"),

        // 科學家與科技領袖
        KnowledgeEntry(listOf("愛因斯坦"), "愛因斯坦", "提出相對論的現代物理學家。"),
        KnowledgeEntry(listOf("牛頓"), "牛頓", "提出萬有引力與三大運動定律。"),
        KnowledgeEntry(listOf("愛迪生"), "愛迪生", "改良電燈泡的美國發明家。"),
        KnowledgeEntry(listOf("特斯拉", "尼古拉特斯拉"), "特斯拉", "交流電系統先驅發明家。"),
        KnowledgeEntry(listOf("霍金"), "霍金", "研究黑洞與宇宙論的物理學家。"),
        KnowledgeEntry(listOf("居禮夫人"), "居禮夫人", "研究放射性的雙諾貝爾獎得主。"),
        KnowledgeEntry(listOf("達爾文"), "達爾文", "提出物競天擇演化論的學者。"),
        KnowledgeEntry(listOf("圖靈"), "圖靈", "現代電腦科學與人工智慧之父。"),
        KnowledgeEntry(listOf("張忠謀"), "張忠謀", "台積電創辦人，台灣半導體教父。"),
        KnowledgeEntry(listOf("黃仁勳"), "黃仁勳", "輝達共同創辦人暨執行長。"),
        KnowledgeEntry(listOf("蘇姿丰"), "蘇姿丰", "超微半導體執行長。"),
        KnowledgeEntry(listOf("郭台銘"), "郭台銘", "鴻海科技集團創辦人。"),
        KnowledgeEntry(listOf("賈伯斯", "喬布斯", "steve jobs"), "賈伯斯", "蘋果公司共同創辦人。"),
        KnowledgeEntry(listOf("比爾蓋茲", "bill gates"), "比爾蓋茲", "微軟共同創辦人。"),
        KnowledgeEntry(listOf("馬斯克", "elon musk"), "馬斯克", "特斯拉與SpaceX執行長。"),
        KnowledgeEntry(listOf("祖克柏", "扎克伯格", "zuckerberg"), "祖克柏", "Meta與臉書創辦人。"),

        // 影視與流行音樂人物
        KnowledgeEntry(listOf("周杰倫", "周董"), "周杰倫", "台灣華語流行音樂天王歌手。"),
        KnowledgeEntry(listOf("蔡依林", "jolin"), "蔡依林", "台灣華語流行音樂天后歌手。"),
        KnowledgeEntry(listOf("五月天", "mayday"), "五月天", "台灣知名搖滾樂團。"),
        KnowledgeEntry(listOf("張惠妹", "阿妹"), "張惠妹", "台灣華語流行樂壇天后歌手。"),
        KnowledgeEntry(listOf("林俊傑", "jj"), "林俊傑", "新加坡華語流行創作歌手。"),
        KnowledgeEntry(listOf("陳奕迅", "eason"), "陳奕迅", "香港著名流行音樂歌手。"),
        KnowledgeEntry(listOf("鄧麗君"), "鄧麗君", "華語樂壇極具影響力的傳奇歌手。"),
        KnowledgeEntry(listOf("李安"), "李安", "兩度獲奧斯卡最佳導演的台灣導演。"),
        KnowledgeEntry(listOf("宮﨑駿", "宮崎駿"), "宮崎駿", "日本吉卜力工作室動畫導演。"),
        KnowledgeEntry(listOf("大谷翔平"), "大谷翔平", "日本職棒與大聯盟二刀流球星。"),
        KnowledgeEntry(listOf("戴資穎"), "戴資穎", "台灣羽球女子單打名將。"),

        // 台灣與世界地理常識
        KnowledgeEntry(listOf("玉山", "台灣最高的山", "台灣第一高峰"), "玉山", "台灣最高峰玉山，3952公尺。"),
        KnowledgeEntry(listOf("聖母峰", "珠穆朗瑪峰", "世界最高的山", "世界第一高峰"), "聖母峰", "世界最高峰聖母峰，8848公尺。"),
        KnowledgeEntry(listOf("台北101", "101大樓"), "台北101", "台北信義區摩天大樓，高508公尺。"),
        KnowledgeEntry(listOf("濁水溪", "台灣最長的河", "台灣最長溪流"), "濁水溪", "台灣最長溪流濁水溪，長186公里。"),
        KnowledgeEntry(listOf("日月潭"), "日月潭", "位於南投魚池鄉的台灣最大天然湖。"),
        KnowledgeEntry(listOf("台灣人口", "台灣多少人"), "台灣人口", "台灣總人口約兩千三百多萬人。"),
        KnowledgeEntry(listOf("台灣面積", "台灣多大"), "台灣面積", "台灣總面積約三萬六千平方公里。"),
        KnowledgeEntry(listOf("日本首都"), "日本首都", "日本的實際首都是東京。"),
        KnowledgeEntry(listOf("美國首都"), "美國首都", "美國的首都是華盛頓特區。"),
        KnowledgeEntry(listOf("英國首都"), "英國首都", "英國的首都是倫敦。"),
        KnowledgeEntry(listOf("法國首都"), "法國首都", "法國的首都是巴黎。"),
        KnowledgeEntry(listOf("韓國首都"), "韓國首都", "南韓的首都是首爾。"),

        // 科學、科技與自然常識
        KnowledgeEntry(listOf("相對論"), "相對論", "愛因斯坦提出的時空與重力理論。"),
        KnowledgeEntry(listOf("量子力學"), "量子力學", "研究微觀粒子運動規律的物理學。"),
        KnowledgeEntry(listOf("黑洞"), "黑洞", "重力極強連光都逃不出的天體。"),
        KnowledgeEntry(listOf("人工智慧", "什麼是ai", "ai是什麼"), "人工智慧", "讓電腦具備人類學習與推理的技術。"),
        KnowledgeEntry(listOf("半導體", "晶片"), "半導體", "導電性介於導體與絕緣體間的材料。"),
        KnowledgeEntry(listOf("台積電", "tsmc"), "台積電", "台灣積體電路製造，全球晶圓代工龍頭。"),
        KnowledgeEntry(listOf("光速"), "光速", "真空中光速每秒約三十萬公里。"),
        KnowledgeEntry(listOf("音速", "聲速"), "音速", "空氣中音速每秒約340公尺。"),
        KnowledgeEntry(listOf("光合作用"), "光合作用", "植物利用光能將二氧化碳轉為養分。"),
        KnowledgeEntry(listOf("dna", "去氧核糖核酸"), "DNA", "攜帶生物遺傳基因資訊的分子。"),
        KnowledgeEntry(listOf("地球自轉", "為什麼有白天黑夜"), "地球自轉", "地球自轉一圈約24小時產生日夜。"),
        KnowledgeEntry(listOf("地球公轉", "為什麼有四季"), "地球公轉", "地球繞太陽公轉與地軸傾斜產生四季。"),
        KnowledgeEntry(listOf("天空為什麼是藍色", "天空為什麼是藍的"), "瑞利散射", "大氣散射陽光中波長較短的藍光。"),
        KnowledgeEntry(listOf("水的沸點", "水幾度沸騰"), "水的沸點", "標準大氣壓下水在攝氏100度沸騰。")
    )

    private val EXPLICIT_ACTION_REGEX = Regex(
        "(打開|開啟|啟動|進入|去|點選|點擊|點一下|按一下|按下|搜尋|搜索|查找|輸入|播放|觀看|往下滑|向下滑|往上滑|向上滑|下滑|上滑|往左滑|往右滑|返回|上一頁|關閉|開關|設定|設定頁|讀畫面|朗讀|唸這頁|youtube|line|chrome|maps|地圖|gmail|計算機|相機|電話|簡訊|音樂|spotify|netflix|instagram|facebook|threads|蝦皮|shopee|momo)",
        RegexOption.IGNORE_CASE
    )

    private val QUESTION_PREFIX_REGEX = Regex(
        "^(?:請問一下|請問|我想問|問一下|幫我查一下|幫我查|查一下|上網查|告訴我|你知道|你曉得|誰是|什麼是|啥是|哪裡是|哪個是|為什麼|為啥|怎麼|如何)\\s*"
    )

    private val QUESTION_SUFFIX_REGEX = Regex(
        "\\s*(?:是誰|是什麼意思|是什麼|是啥|在哪裡|在哪兒|在哪|多高|多長|多大|多重|幾歲|哪一年|多少錢|多少|怎麼做|怎麼辦|嗎|呢|吧|啊|[？?！!。，,])+\\s*$"
    )

    /**
     * 動態解析手機上優先使用的瀏覽器或搜尋 App 名稱（優先 Chrome，次選 Google）
     */
    fun resolvePreferredBrowserAppLabel(context: Context?): String {
        if (context == null) return "Chrome"
        return runCatching {
            val apps = InstalledAppLauncher.getInstalledLaunchableApps(context)
            val chrome = apps.firstOrNull {
                it.packageName.contains("chrome", ignoreCase = true) ||
                    it.label.equals("Chrome", ignoreCase = true)
            }
            if (chrome != null) return@runCatching chrome.label
            val google = apps.firstOrNull {
                it.packageName == "com.google.android.googlequicksearchbox" ||
                    it.label.equals("Google", ignoreCase = true)
            }
            if (google != null) return@runCatching google.label
            "Chrome"
        }.getOrDefault("Chrome")
    }

    /**
     * 剖析輸入句子是否屬於「疑問句 / 知識查詢 / 資訊檢索」，並提煉出純淨的搜尋核心詞
     */
    fun extractQuestionTopic(rawInput: String): QuestionTopicExtraction {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return QuestionTopicExtraction(isQuestionOrLookup = false, isLocationLookup = false, cleanTopic = "")
        }

        val isLocation = trimmed.contains("在哪裡") || trimmed.contains("在哪兒") ||
            trimmed.contains("怎麼去") || trimmed.contains("附近有什麼") || trimmed.contains("附近的")

        val isWeatherQuestion = (trimmed.endsWith("天氣") || trimmed.contains("天氣如何") ||
            trimmed.contains("天氣怎樣") || trimmed.contains("天氣預報") || trimmed.contains("會不會下雨")) &&
            !trimmed.contains("聽音樂")

        val hasInterrogativeMarker =
            trimmed.startsWith("誰是") || trimmed.endsWith("是誰") || trimmed.contains("是誰") ||
                trimmed.startsWith("什麼是") || trimmed.endsWith("是什麼") || trimmed.contains("是什麼") ||
                trimmed.startsWith("啥是") || trimmed.endsWith("是啥") ||
                trimmed.startsWith("為什麼") || trimmed.startsWith("為啥") ||
                trimmed.startsWith("請問") || trimmed.startsWith("你知道") ||
                trimmed.startsWith("幫我查") || trimmed.startsWith("查一下") ||
                trimmed.contains("多高") || trimmed.contains("多長") || trimmed.contains("幾歲") ||
                trimmed.contains("多少錢") || trimmed.contains("首都") || trimmed.contains("人口") ||
                isWeatherQuestion || trimmed.contains("股價") || trimmed.contains("匯率") ||
                trimmed.endsWith("？") || trimmed.endsWith("?") || trimmed.endsWith("嗎") ||
                isLocation

        var topic = trimmed
            .replace(QUESTION_PREFIX_REGEX, "")
            .replace(QUESTION_SUFFIX_REGEX, "")
            .trim()

        // 若剝離後還有「誰是」或「什麼是」殘留於中間（例如「請問誰是蔣介石」）
        topic = topic
            .replace(Regex("^(?:誰是|什麼是|啥是|幫我查|查一下)\\s*"), "")
            .replace(Regex("\\s*(?:是誰|是什麼|是啥)$"), "")
            .trim()

        if (topic.isEmpty()) {
            topic = trimmed.replace(Regex("[？?！!。，,\\s]+$"), "").trim()
        }

        return QuestionTopicExtraction(
            isQuestionOrLookup = hasInterrogativeMarker,
            isLocationLookup = isLocation,
            cleanTopic = topic
        )
    }

    /**
     * 【對話思考與知識問答主引擎】：
     * 供 `AndroidNativeAiEngine.generateConversationalReply` 與 `checkZeroTapQuery` 呼叫。
     * 若命中本機時間/日期、知識圖譜、日常思考或疑問句，直接回傳：
     * - `replyText`: 精煉直率（正常 ~10 字、嚴格 <= 20 字、零語助詞）的回答
     * - `plannedExecutableCommand`: 已經規劃完成的具體可執行指令（例如 `"打開 Chrome 搜尋 蔣介石"`），絕不回傳未規劃的原句！
     */
    fun evaluateConversationalThinking(
        userText: String,
        context: Context? = null
    ): ConversationalReasoningResult? {
        val q = userText.trim()
        if (q.isEmpty()) return null
        val lower = q.lowercase()
        val browserApp = resolvePreferredBrowserAppLabel(context)

        // 1. 即時時間、日期、星期、年份推理（0ms 系統時鐘直算）
        val timeDateReply = evaluateTimeAndDateQuery(q)
        if (timeDateReply != null) {
            return ConversationalReasoningResult(
                replyText = HumanColloquialPersonaEngine.enforceConciseLength(timeDateReply),
                plannedExecutableCommand = null
            )
        }

        // 2. 年份差距與年齡推理（例如：「1949年到現在幾年」、「1990年出生幾歲」）
        val yearMathReply = evaluateYearOrAgeReasoning(q)
        if (yearMathReply != null) {
            return ConversationalReasoningResult(
                replyText = HumanColloquialPersonaEngine.enforceConciseLength(yearMathReply),
                plannedExecutableCommand = null
            )
        }

        // 3. 日常情緒、狀態與生活對話推理（不亂掛無意義執行按鈕）
        val dailyChat = evaluateDailyColloquialReasoning(q, lower)
        if (dailyChat != null) {
            return dailyChat
        }

        // 4. 檢查本機百科與常識知識圖譜（例如：「誰是蔣介石」、「台灣最高的山」、「什麼是黑洞」）
        val extraction = extractQuestionTopic(q)
        val cleanTopic = extraction.cleanTopic.lowercase()

        // 避免攔截明確的 App 操控指令（例如「打開 YouTube 搜尋周杰倫」）
        val isExplicitAppControl = EXPLICIT_ACTION_REGEX.containsMatchIn(q) &&
            !q.startsWith("誰是") && !q.endsWith("是誰") &&
            !q.startsWith("什麼是") && !q.endsWith("是什麼") &&
            !q.startsWith("幫我查") && !q.startsWith("查一下")

        if (!isExplicitAppControl) {
            for (entry in KNOWLEDGE_BASE) {
                val matched = entry.keywords.any { kw ->
                    val kwLower = kw.lowercase()
                    cleanTopic == kwLower || q.lowercase().contains(kwLower)
                }
                if (matched) {
                    val plannedSearch = "打開 $browserApp 搜尋 ${entry.canonicalTopic}"
                    return ConversationalReasoningResult(
                        replyText = HumanColloquialPersonaEngine.enforceConciseLength(entry.conciseAnswer),
                        plannedExecutableCommand = plannedSearch
                    )
                }
            }
            // 查詢 GitHub 開源 120,000 條百科、詞語與成語常識問答庫 (chinese_encyclopedia_qa_corpus.tsv)
            if (cleanTopic.length >= 2) {
                val openSourceHit = OpenSourceAiDatabaseManager.lookupEncyclopediaOrLexicalQa(cleanTopic, context)
                if (openSourceHit != null) {
                    val (canonicalTopic, answerText) = openSourceHit
                    val plannedSearch = "打開 $browserApp 搜尋 $canonicalTopic"
                    return ConversationalReasoningResult(
                        replyText = HumanColloquialPersonaEngine.enforceConciseLength(answerText),
                        plannedExecutableCommand = plannedSearch
                    )
                }
            }
        }

        // 5. 若屬於疑問句或查詢意圖，但不在本機離線百科內：
        //    誠實告知需要上網查，並「預先規劃好完整的 Chrome/Google 搜尋指令」！
        if (!isExplicitAppControl && extraction.isQuestionOrLookup && extraction.cleanTopic.isNotEmpty()) {
            val plannedCmd = if (extraction.isLocationLookup) {
                "打開 Google 地圖 搜尋 ${extraction.cleanTopic}"
            } else {
                "打開 $browserApp 搜尋 ${extraction.cleanTopic}"
            }
            return ConversationalReasoningResult(
                replyText = HumanColloquialPersonaEngine.enforceConciseLength("這要上網查，按執行幫你搜。"),
                plannedExecutableCommand = plannedCmd
            )
        }

        return null
    }

    /**
     * 【執行前命令自動規劃器 (`ensurePlannedExecutableGoal`)】：
     * 無論使用者是：
     * 1. 在輸入框輸入「誰是蔣介石」後直接按「執行」
     * 2. 先按「發送」再點氣泡上的「執行」
     * 都會確保將疑問句或裸名詞查詢轉化為完整可執行的跨 App 計畫（如 `"打開 Chrome 搜尋 蔣介石"`），
     * 除非當前畫面上已經有同名元件可直接點擊。
     */
    fun ensurePlannedExecutableGoal(
        rawGoal: String,
        context: Context? = null,
        currentReport: VisionPipelineReport? = null
    ): String {
        val clean = rawGoal.trim()
        if (clean.isEmpty()) return clean

        // 0. 若屬於 Android 原生系統指令（調整音量、設定鬧鐘/計時器、手電筒、全域動作、系統設定子頁直達），直接原句保留交由 AndroidNativeSystemActionExecutor 瞬間執行
        if (com.example.newandroidapp.action.AndroidNativeSystemActionExecutor.parseCommand(clean) != null) {
            return clean
        }

        // 1. 先經過口語常識轉譯（「太亮了」、「我想聽晴天」等）
        val colloquial = CognitiveReasoningEngine.resolveColloquialAndContextualGoal(clean)
        if (colloquial.normalizedGoal != clean) {
            return colloquial.normalizedGoal
        }

        // 2. 若已經包含明確操作動詞或目標 App（如「打開...」、「點擊...」、「搜尋...」、「往下滑」），保持原規劃
        if (EXPLICIT_ACTION_REGEX.containsMatchIn(clean)) {
            // 特例：「幫我查 X」或「查一下 X」但未指定 App 時，若當前畫面無搜尋框，補上瀏覽器啟動
            val lookupPrefixMatch = Regex("^(?:幫我查一下|幫我查|查一下|上網查)\\s*(.+)$").find(clean)
            if (lookupPrefixMatch != null) {
                val topic = lookupPrefixMatch.groupValues[1].trim()
                if (topic.isNotEmpty()) {
                    val browserApp = resolvePreferredBrowserAppLabel(context)
                    return "打開 $browserApp 搜尋 $topic"
                }
            }
            return clean
        }

        // 3. 若當前畫面上已經有文字高度重疊的可點擊元件，代表使用者想直接點畫面上的東西，不強制跳去瀏覽器
        if (currentReport != null && currentReport.finalNodes.isNotEmpty()) {
            val hasOnScreenTarget = currentReport.finalNodes.any { node ->
                val lbl = node.label.trim()
                lbl.isNotEmpty() && (lbl.contains(clean, ignoreCase = true) || clean.contains(lbl, ignoreCase = true))
            }
            if (hasOnScreenTarget) {
                return clean
            }
        }

        // 4. 剖析疑問句或知識查詢（例如：「誰是蔣介石」、「什麼是黑洞」、「玉山多高」）
        val extraction = extractQuestionTopic(clean)
        val browserApp = resolvePreferredBrowserAppLabel(context)
        if (extraction.isLocationLookup && extraction.cleanTopic.isNotEmpty()) {
            return "打開 Google 地圖 搜尋 ${extraction.cleanTopic}"
        }
        if (extraction.isQuestionOrLookup && extraction.cleanTopic.isNotEmpty()) {
            return "打開 $browserApp 搜尋 ${extraction.cleanTopic}"
        }

        // 5. 若使用者輸入純粹的知識名詞/主題（例如直接輸入「蔣介石」並按執行），也自動規劃為開啟瀏覽器搜尋
        if (clean.length in 2..18) {
            return "打開 $browserApp 搜尋 $clean"
        }

        return clean
    }

    private fun evaluateTimeAndDateQuery(q: String): String? {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val rocYear = year - 1911
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val weekDayStr = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "日"
            Calendar.MONDAY -> "一"
            Calendar.TUESDAY -> "二"
            Calendar.WEDNESDAY -> "三"
            Calendar.THURSDAY -> "四"
            Calendar.FRIDAY -> "五"
            Calendar.SATURDAY -> "六"
            else -> ""
        }

        if (q.contains("幾點") || q.contains("現在時間") || q == "時間") {
            return "現在是 ${hour}點${"%02d".format(minute)}分。"
        }
        if (q.contains("今天幾號") || q.contains("幾月幾號") || q.contains("今天日期") ||
            q.contains("星期幾") || q.contains("禮拜幾") || q.contains("周幾") || q.contains("週幾")
        ) {
            return "今天${month}月${day}日，星期$weekDayStr。"
        }
        if (q.contains("今年幾年") || q.contains("西元幾年") || q.contains("民國幾年")) {
            return "今年${year}年，民國${rocYear}年。"
        }
        return null
    }

    private fun evaluateYearOrAgeReasoning(q: String): String? {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val yearMatch = Regex("(19\\d{2}|20\\d{2})\\s*年").find(q) ?: return null
        val targetYear = yearMatch.groupValues[1].toIntOrNull() ?: return null
        val diff = currentYear - targetYear
        if (diff >= 0 && (q.contains("幾歲") || q.contains("多大") || q.contains("幾年"))) {
            return if (q.contains("幾歲") || q.contains("多大")) {
                "今年大約 $diff 歲。"
            } else {
                "距離現在 $diff 年。"
            }
        }
        return null
    }

    private fun evaluateDailyColloquialReasoning(
        q: String,
        lower: String
    ): ConversationalReasoningResult? {
        return when {
            q.contains("好累") || q.contains("累死") || q.contains("好疲倦") || q == "累" -> {
                ConversationalReasoningResult("累就去睡，別滑手機。", null)
            }
            q.contains("好無聊") || q.contains("真無聊") || q == "無聊" -> {
                ConversationalReasoningResult("無聊就去看影片。", "打開 YouTube")
            }
            q.contains("肚子餓") || q.contains("好餓") || q.contains("吃什麼") || q.contains("推薦好吃") -> {
                ConversationalReasoningResult("隨便吃，或幫你搜附近餐廳。", "打開 Google 地圖 搜尋 餐廳")
            }
            q.contains("睡不著") || q.contains("失眠") || q.contains("想睡覺") -> {
                ConversationalReasoningResult("放下手機閉眼就睡著了。", null)
            }
            q.contains("心情不好") || q.contains("好難過") || q.contains("好煩") || q.contains("煩死") -> {
                ConversationalReasoningResult("別想太多，去聽首歌。", "打開 YouTube 搜尋 放鬆音樂")
            }
            q.contains("會思考嗎") || q.contains("有腦袋嗎") || q.contains("聰明嗎") || q.contains("有智商嗎") -> {
                ConversationalReasoningResult("廢話，不然誰在回你話。", null)
            }
            q.contains("你幾歲") || q.contains("幾歲了") || q.contains("你生日") -> {
                ConversationalReasoningResult("我是AI，沒有年齡。", null)
            }
            q.contains("男生還是女生") || q.contains("男的還是女的") || q.contains("你是女生嗎") || q.contains("你是女的嗎") -> {
                ConversationalReasoningResult("聽聲音也知道是女生。", null)
            }
            q.contains("笑話") -> {
                ConversationalReasoningResult("照一下鏡子就很好笑了。", null)
            }
            q.contains("好熱") || q.contains("熱死") -> {
                ConversationalReasoningResult("熱就去開冷氣。", null)
            }
            q.contains("好冷") || q.contains("冷死") -> {
                ConversationalReasoningResult("冷就多穿件衣服。", null)
            }
            lower == "為什麼" || lower == "為啥" -> {
                ConversationalReasoningResult("沒有為什麼，照做就對了。", null)
            }
            else -> null
        }
    }
}
