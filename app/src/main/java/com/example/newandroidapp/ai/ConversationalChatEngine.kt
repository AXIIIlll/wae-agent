package com.example.newandroidapp.ai

import android.content.Context
import java.util.Calendar
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * 【雙腦分流架構 — 第二腦：正常溫度自然聊天與日常萬物常識引擎 (`ConversationalChatEngine`)】
 *
 * 設計目的：
 * 1. 與【第一腦：零溫度執行腦 (`DeterminismHardeningEngine.GREEDY_TEMPERATURE = 0.0f`)】嚴格解耦：
 *    - 控手機點按鈕維持 `Temperature = 0.0f` 貪婪解碼，保證 100% 確定性。
 *    - 正常聊天與萬物問答採用獨立的 **`CHAT_TEMPERATURE = 0.72f`**、**`TOP_P = 0.90f`** 與 **`REPETITION_PENALTY = 1.35f`**，
 *      透過 Softmax 溫度機率分佈 + Nucleus (Top-P) 採樣器 + 近期回覆防重複記憶，讓每次閒聊（如「你現在心情如何」、「你在幹嘛」）自然生動、不機械跳針。
 * 2. 「先直接回答、下方附帶 App 建議按鈕」機制：
 *    - 當使用者詢問日常萬物或百科問題（例如：「什麼是草莓」、「什麼是拿鐵」、「什麼是藍牙」）時，
 *      AI 先在對話框內直接給出清楚自然的解答，並在氣泡下方附上 `打開 Chrome 搜尋 草莓`（或 Google）的可選執行按鈕。
 *    - 當使用者進行純日常聊天（例如：「你現在心情如何」、「今天好忙喔」、「你在幹嘛」）時，
 *      直接用自然口語聊天回覆，絕不亂跳「這要上網查，按執行幫你搜。」！
 */
object ConversationalChatEngine {

    // 獨立於 DeterminismHardeningEngine (0.0f) 的自然對話採樣超參數
    const val DEFAULT_CHAT_TEMPERATURE: Float = 0.72f
    const val DEFAULT_TOP_P: Float = 0.90f
    const val DEFAULT_REPETITION_PENALTY: Float = 1.35f

    private val samplingRng = Random(System.nanoTime())
    private val recentReplyHistory = ArrayDeque<String>(16)

    fun clearRecentReplyMemory() {
        synchronized(recentReplyHistory) {
            recentReplyHistory.clear()
        }
    }

    enum class ChatBrainRoute {
        /** 純自然閒聊（心情、問候、日常感受、喜好、玩笑），不掛載多餘的搜尋按鈕 */
        PURE_CHITCHAT,
        /** 萬物常識與百科問答：直接回答內容，並在下方附帶「打開瀏覽器/Google 搜尋 X」按鈕 */
        KNOWLEDGE_QA_WITH_SEARCH_BUTTON,
        /** 非聊天/非通用常識（交由原系統指令或手機操控流程處理） */
        DELEGATE_TO_ACTION_OR_FALLBACK
    }

    data class ChatBrainResponse(
        val route: ChatBrainRoute,
        val replyText: String,
        val suggestedExecutableCommand: String? = null,
        val sampledTemperature: Float = DEFAULT_CHAT_TEMPERATURE,
        val orbEmotionAnimId: Int = 35
    )

    data class ScoredCandidate(
        val text: String,
        val baseLogit: Float,
        val orbAnimId: Int = 35
    )

    data class EverydayKnowledgeItem(
        val keywords: List<String>,
        val canonicalTopic: String,
        val category: String,
        val naturalExplanation: String,
        val preferredAppHint: String? = null
    )

    /**
     * 本機輕量日常萬物常識庫（涵蓋水果食材、飲品料理、動植物、日常科技、生活健康、流行文化與自然現象）
     * 支援使用者後續無縫擴充 TSV / 資料庫詞條。
     */
    private val EVERYDAY_KNOWLEDGE_BASE: List<EverydayKnowledgeItem> = listOf(
        // 水果與食材
        EverydayKnowledgeItem(
            keywords = listOf("草莓", "士多啤梨", "strawberry"),
            canonicalTopic = "草莓",
            category = "水果食材",
            naturalExplanation = "草莓是薔薇科的多年生草本水果，果肉鮮紅多汁、香氣濃郁且富含維生素 C，吃起來酸甜爽口，常直接吃或做成蛋糕、果醬與甜點。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("蘋果", "apple水果", "富士蘋果"),
            canonicalTopic = "蘋果",
            category = "水果食材",
            naturalExplanation = "蘋果是薔薇科的常見水果，口感爽脆甘甜，富含膳食纖維、果膠與抗氧化物，常溫好保存，也是最普及的日常水果之一。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("香蕉", "banana"),
            canonicalTopic = "香蕉",
            category = "水果食材",
            naturalExplanation = "香蕉是熱帶草本水果，果肉香甜軟糯，富含鉀離子、維生素 B6 與碳水化合物，能快速補充體力，運動前後吃都很適合。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("芒果", "愛文芒果", "mango"),
            canonicalTopic = "芒果",
            category = "水果食材",
            naturalExplanation = "芒果是著名的熱帶水果，果肉金黃飽滿、香氣濃郁香甜，台灣常見愛文與金煌等品種，夏天做成芒果冰特別受歡迎。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("西瓜", "watermelon"),
            canonicalTopic = "西瓜",
            category = "水果食材",
            naturalExplanation = "西瓜是葫蘆科的夏季消暑水果，水分含量高達九成以上，果肉清甜多汁並含有茄紅素與瓜胺酸。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("葡萄", "巨峰葡萄", "grape"),
            canonicalTopic = "葡萄",
            category = "水果食材",
            naturalExplanation = "葡萄是成串生長的漿果，果肉多汁酸甜，果皮與籽含有花青素與白藜蘆醇，既能鮮食也能釀造葡萄酒。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("櫻桃", "車厘子", "cherry"),
            canonicalTopic = "櫻桃",
            category = "水果食材",
            naturalExplanation = "櫻桃是色澤鮮紅或紫紅的小型核果，果肉飽滿多汁、甜中帶微酸，富含花青素與鐵質。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("藍莓", "blueberry"),
            canonicalTopic = "藍莓",
            category = "水果食材",
            naturalExplanation = "藍莓是深藍色的小型漿果，富含花青素與維生素，酸甜清爽，常搭配優格、燕麥或做成烘焙甜點。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("酪梨", "牛油果", "avocado"),
            canonicalTopic = "酪梨",
            category = "水果食材",
            naturalExplanation = "酪梨又稱牛油果，果肉綿密滑順，在營養學上屬於優質油脂來源，富含單元不飽和脂肪酸，常做沙拉、吐司或酪梨牛奶。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("榴槤", "durian"),
            canonicalTopic = "榴槤",
            category = "水果食材",
            naturalExplanation = "榴槤被稱為熱帶水果之王，外殼佈滿硬刺，氣味非常強烈獨特，果肉濃郁綿密，喜歡的人很愛、怕的人則敬而遠之。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("鳳梨", "菠蘿", "pineapple"),
            canonicalTopic = "鳳梨",
            category = "水果食材",
            naturalExplanation = "鳳梨是熱帶水果，果肉金黃酸甜且含有鳳梨酵素，能幫助分解蛋白質，也是台灣鳳梨酥的主要原料。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("奇異果", "獼猴桃", "kiwi"),
            canonicalTopic = "奇異果",
            category = "水果食材",
            naturalExplanation = "奇異果又稱獼猴桃，分為綠肉與黃金奇異果，維生素 C 與膳食纖維含量極高，口感酸甜清爽。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("水蜜桃", "桃子", "peach"),
            canonicalTopic = "水蜜桃",
            category = "水果食材",
            naturalExplanation = "水蜜桃果皮帶有細絨毛，果肉柔軟多汁、香氣清甜迷人，是夏季相當受歡迎的水果。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("檸檬", "lemon"),
            canonicalTopic = "檸檬",
            category = "水果食材",
            naturalExplanation = "檸檬是芸香科柑橘屬水果，富含檸檬酸與維生素 C，味道極酸但清香撲鼻，常用來泡檸檬水、調飲或去腥提味。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("番茄", "西紅柿", "tomato"),
            canonicalTopic = "番茄",
            category = "水果食材",
            naturalExplanation = "番茄富含茄紅素與維生素 C，大番茄常入菜做番茄炒蛋或熬湯，小番茄則常當作水果直接吃。"
        ),

        // 飲品與美食料理
        EverydayKnowledgeItem(
            keywords = listOf("珍珠奶茶", "珍奶", "波霸奶茶", "bubble tea"),
            canonicalTopic = "珍珠奶茶",
            category = "飲食文化",
            naturalExplanation = "珍珠奶茶是發源於台灣的代表性手搖飲，以濃厚奶茶搭配 Q 彈的木薯粉圓（珍珠），紅遍全球。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("咖啡", "coffee"),
            canonicalTopic = "咖啡",
            category = "飲食文化",
            naturalExplanation = "咖啡是由烘焙過的咖啡豆沖泡而成的飲品，含有咖啡因能提神醒腦，常見有美式、拿鐵、卡布奇諾與手沖單品。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("拿鐵", "latte"),
            canonicalTopic = "拿鐵",
            category = "飲食文化",
            naturalExplanation = "拿鐵（Caffe Latte）是以濃縮咖啡為基底，加入大量溫熱鮮奶與薄奶泡的咖啡飲品，口感溫潤順口。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("抹茶", "matcha"),
            canonicalTopic = "抹茶",
            category = "飲食文化",
            naturalExplanation = "抹茶是將遮光栽培的茶葉蒸菁乾燥後，以石臼細磨成的翠綠茶粉，帶有獨特茶香與回甘微苦，廣泛用於茶道與甜點。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("巧克力", "朱古力", "chocolate"),
            canonicalTopic = "巧克力",
            category = "飲食文化",
            naturalExplanation = "巧克力是以可可豆為原料製成的食品，黑巧克力富含可可多酚且微苦醇厚，牛奶巧克力則香甜滑順。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("滷肉飯", "魯肉飯"),
            canonicalTopic = "滷肉飯",
            category = "飲食文化",
            naturalExplanation = "滷肉飯是台灣最具代表性的國民小吃，將切丁五花肉以醬油與紅蔥頭慢火滷製出膠質，淋在熱白飯上鹹香下飯。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("小籠包", "湯包"),
            canonicalTopic = "小籠包",
            category = "飲食文化",
            naturalExplanation = "小籠包是著名的傳統點心，特色是皮薄褶多、內餡鮮美且飽含濃郁熱湯汁，搭配薑絲與米醋最對味。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("臭豆腐"),
            canonicalTopic = "臭豆腐",
            category = "飲食文化",
            naturalExplanation = "臭豆腐是透過特殊發酵滷水浸泡的豆腐小吃，聞起來氣味濃烈但吃起來外酥內嫩，搭配台式泡菜非常經典。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("牛肉麵"),
            canonicalTopic = "牛肉麵",
            category = "飲食文化",
            naturalExplanation = "牛肉麵是台灣極受歡迎的麵食料理，分為濃郁微辣的紅燒湯頭與甘醇清爽的清燉湯頭，搭配燉得軟嫩的牛腱或牛腩。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("拉麵", "日本拉麵", "ramen"),
            canonicalTopic = "拉麵",
            category = "飲食文化",
            naturalExplanation = "拉麵是源自中國、在日本發展出獨特流派的湯麵料理，常見有豚骨、醬油、味噌與鹽味四大湯頭。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("壽司", "sushi"),
            canonicalTopic = "壽司",
            category = "飲食文化",
            naturalExplanation = "壽司是日本傳統料理，以醋飯搭配生魚片、海鮮或玉子燒，常見有握壽司、軍艦卷與捲壽司。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("披薩", "比薩", "pizza"),
            canonicalTopic = "披薩",
            category = "飲食文化",
            naturalExplanation = "披薩是發源於義大利拿坡里的烤餅料理，在發酵餅皮上塗抹番茄醬、鋪滿起司與各式配料後高溫烘烤而成。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("火鍋", "麻辣鍋", "涮涮鍋"),
            canonicalTopic = "火鍋",
            category = "飲食文化",
            naturalExplanation = "火鍋是邊煮邊吃的圍爐料理，將肉片、海鮮與蔬菜放入沸騰的高湯中涮熟，天冷或聚餐時特別受歡迎。"
        ),

        // 動物與萌寵
        EverydayKnowledgeItem(
            keywords = listOf("貓", "貓咪", "小貓", "喵星人"),
            canonicalTopic = "貓咪",
            category = "動物自然",
            naturalExplanation = "貓是小型貓科伴侶動物，個性獨立又帶點傲嬌，動作輕巧愛乾淨，呼嚕聲與肉球非常療癒。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("狗", "狗狗", "小狗", "汪星人"),
            canonicalTopic = "狗狗",
            category = "動物自然",
            naturalExplanation = "狗是人類最忠實的伴侶動物，嗅覺敏銳、熱情聰明且善解人意，能看家陪伴也能擔任導盲犬與搜救犬。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("柴犬", "shiba"),
            canonicalTopic = "柴犬",
            category = "動物自然",
            naturalExplanation = "柴犬是源自日本的中小型犬，有著立耳、捲尾與招牌笑容，個性忠誠機警但偶爾會展現固執的「拒否柴」表情。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("水豚", "卡皮巴拉", "capybara"),
            canonicalTopic = "水豚",
            category = "動物自然",
            naturalExplanation = "水豚是世界上體型最大的齧齒類動物，性情極度溫和淡定、喜歡泡溫泉，是動物界公認的社交萬人迷。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("大貓熊", "熊貓", "貓熊", "panda"),
            canonicalTopic = "大貓熊",
            category = "動物自然",
            naturalExplanation = "大貓熊有著黑白相間的圓潤外型與黑眼圈，以竹子為主食，模樣憨厚可愛，是珍貴的保育類動物。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("企鵝", "penguin"),
            canonicalTopic = "企鵝",
            category = "動物自然",
            naturalExplanation = "企鵝主要生活在南半球與南極地區，雖然不會飛行但翅膀演化成強健的划水鰭，是游泳與潛水高手。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("恐龍", "dinosaur"),
            canonicalTopic = "恐龍",
            category = "動物自然",
            naturalExplanation = "恐龍是中生代稱霸地球超過一億六千萬年的爬蟲類動物，如暴龍、三角龍與腕龍，約在六千六百萬年前滅絕。"
        ),

        // 科技、網路與日常科學
        EverydayKnowledgeItem(
            keywords = listOf("藍牙", "bluetooth"),
            canonicalTopic = "藍牙",
            category = "科技數位",
            naturalExplanation = "藍牙（Bluetooth）是一種短距離無線通訊技術，常用來連接手機與無線耳機、喇叭、鍵盤或智慧手錶。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("wifi", "wi-fi", "無線網路"),
            canonicalTopic = "Wi-Fi",
            category = "科技數位",
            naturalExplanation = "Wi-Fi 是利用無線電波長讓手機、電腦等裝置連接網際網路的區域無線網路技術。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("比特幣", "bitcoin", "btc"),
            canonicalTopic = "比特幣",
            category = "科技財經",
            naturalExplanation = "比特幣是 2009 年由中本聰提出的去中心化加密貨幣，透過區塊鏈帳本與密碼學運作，不依賴中央銀行發行。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("etf", "指數股票型基金"),
            canonicalTopic = "ETF",
            category = "科技財經",
            naturalExplanation = "ETF（指數股票型基金）是在證券交易所上市買賣的基金，能一次持有一籃子股票來分散風險並追蹤大盤指數。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("多巴胺", "dopamine"),
            canonicalTopic = "多巴胺",
            category = "健康科學",
            naturalExplanation = "多巴胺是大腦重要的神經傳導物質，負責傳遞渴望、獎勵與動力訊號，影響人的專注力與愉悅感。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("維生素c", "維他命c", "vitamin c"),
            canonicalTopic = "維生素 C",
            category = "健康科學",
            naturalExplanation = "維生素 C 是人體必需的水溶性維生素，具備抗氧化、促進膠原蛋白合成與維持免疫力的作用，常見於芭樂、奇異果與柑橘類。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("地震", "earthquake"),
            canonicalTopic = "地震",
            category = "自然科學",
            naturalExplanation = "地震是地殼板塊擠壓碰撞、斷層錯動快速釋放能量所產生的地面震動，遇到時應立即執行「趴下、掩護、穩住」。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("颱風", "typhoon"),
            canonicalTopic = "颱風",
            category = "自然科學",
            naturalExplanation = "颱風是形成於西北太平洋的強烈熱帶氣旋，常伴隨強風與豪雨，夏季與秋季最常影響台灣。"
        ),
        EverydayKnowledgeItem(
            keywords = listOf("彩虹", "rainbow"),
            canonicalTopic = "彩虹",
            category = "自然科學",
            naturalExplanation = "彩虹是陽光照射到空氣中的水滴時，經過折射、反射與色散所形成的七彩圓弧光學現象。"
        )
    )

    /**
     * Softmax 溫度機率分佈 + Top-P (Nucleus) 採樣器：
     * - 當 `temperature` > 0 時（預設 0.72f），對各候選句之 Logits 施加近期重複懲罰 (`repetitionPenalty`)，
     *   再除以 `temperature` 計算 Softmax 機率，保留累積機率達 `topP` (0.90f) 的核心候選集進行隨機抽樣。
     * - 確保同一個問題（例如連續問「你現在心情如何」）每次問都有自然變化，不會像 `temperature = 0` 一樣永遠死板吐同一句。
     */
    fun sampleCandidateWithTemperature(
        candidates: List<ScoredCandidate>,
        temperature: Float = DEFAULT_CHAT_TEMPERATURE,
        topP: Float = DEFAULT_TOP_P,
        repetitionPenalty: Float = DEFAULT_REPETITION_PENALTY,
        userEntropySalt: Int = 0
    ): ScoredCandidate {
        require(candidates.isNotEmpty()) { "Candidates must not be empty" }
        if (candidates.size == 1) {
            recordChosenReply(candidates[0].text)
            return candidates[0]
        }

        val effectiveTemp = temperature.coerceIn(0.05f, 1.50f)
        val adjustedLogits = FloatArray(candidates.size)
        var maxLogit = Float.NEGATIVE_INFINITY

        synchronized(recentReplyHistory) {
            for (i in candidates.indices) {
                var logit = candidates[i].baseLogit
                val candidateText = candidates[i].text
                // 對最近講過的句子施加懲罰，越近期講過懲罰越重
                val recentIdx = recentReplyHistory.indexOfLast { it == candidateText }
                if (recentIdx >= 0) {
                    val recencyWeight = (recentIdx + 1).toFloat() / max(1, recentReplyHistory.size).toFloat()
                    val penalty = repetitionPenalty + recencyWeight * 0.85f
                    logit = if (logit >= 0f) logit / penalty else logit * penalty
                    logit -= 1.25f * recencyWeight
                }
                adjustedLogits[i] = logit / effectiveTemp
                if (adjustedLogits[i] > maxLogit) {
                    maxLogit = adjustedLogits[i]
                }
            }
        }

        // 計算數值穩定的 Softmax 機率
        val probs = FloatArray(candidates.size)
        var sumExp = 0f
        for (i in candidates.indices) {
            val e = exp((adjustedLogits[i] - maxLogit).coerceIn(-20f, 20f))
            probs[i] = e
            sumExp += e
        }
        if (sumExp <= 0f) {
            val picked = candidates[0]
            recordChosenReply(picked.text)
            return picked
        }
        for (i in probs.indices) {
            probs[i] /= sumExp
        }

        // Top-P (Nucleus) 排序截斷
        val indexed = probs.indices.sortedByDescending { probs[it] }
        val nucleusIndices = ArrayList<Int>(candidates.size)
        var cumulativeProb = 0f
        val clampedTopP = topP.coerceIn(0.10f, 1.0f)
        for (idx in indexed) {
            nucleusIndices.add(idx)
            cumulativeProb += probs[idx]
            if (cumulativeProb >= clampedTopP && nucleusIndices.size >= 2) {
                break
            }
        }

        var nucleusSum = 0f
        for (idx in nucleusIndices) {
            nucleusSum += probs[idx]
        }

        // 結合系統微秒熵與輸入句鹽值進行隨機抽樣
        val r = synchronized(samplingRng) {
            if (userEntropySalt != 0) {
                samplingRng.setSeed(samplingRng.nextLong() xor userEntropySalt.toLong() xor System.nanoTime())
            }
            samplingRng.nextFloat() * nucleusSum
        }

        var acc = 0f
        var chosenIndex = nucleusIndices.first()
        for (idx in nucleusIndices) {
            acc += probs[idx]
            if (r <= acc) {
                chosenIndex = idx
                break
            }
        }

        val chosen = candidates[chosenIndex]
        recordChosenReply(chosen.text)
        return chosen
    }

    private fun recordChosenReply(text: String) {
        synchronized(recentReplyHistory) {
            if (recentReplyHistory.size >= 12) {
                recentReplyHistory.removeFirst()
            }
            recentReplyHistory.addLast(text)
        }
    }

    /**
     * 查詢日常萬物常識庫（支援精確關鍵字與去除疑問詞後的清理主題匹配）
     */
    fun lookupEverydayKnowledge(
        rawQuery: String,
        cleanTopic: String,
        context: Context? = null
    ): EverydayKnowledgeItem? {
        val normQuery = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(rawQuery.trim().lowercase(), context)
        val normTopic = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(cleanTopic.trim().lowercase(), context)
        if (normQuery.isEmpty() && normTopic.isEmpty()) return null

        // 1. 優先精確匹配 cleanTopic
        for (item in EVERYDAY_KNOWLEDGE_BASE) {
            if (item.keywords.any { kw -> kw.lowercase() == normTopic || kw.lowercase() == normQuery }) {
                return item
            }
        }
        // 2. 次選包含匹配（要求疑問句型或主題明確包含該詞）
        for (item in EVERYDAY_KNOWLEDGE_BASE) {
            if (item.keywords.any { kw ->
                    val k = kw.lowercase()
                    k.length >= 2 && (normTopic.contains(k) || normQuery.contains(k))
                }
            ) {
                return item
            }
        }
        return null
    }

    /**
     * 【雙腦分流主入口】：
     * 評估使用者輸入屬於：
     * 1. 自然日常閒聊（`PURE_CHITCHAT`，使用 `Temperature = 0.72f` 隨機採樣回覆，不亂掛搜尋按鈕）
     * 2. 萬物常識/百科問答（`KNOWLEDGE_QA_WITH_SEARCH_BUTTON`，直接回答知識內容，並在下方掛載 `打開 Chrome/Google 搜尋 X` 按鈕）
     * 3. 手機操控指令（`DELEGATE_TO_ACTION_OR_FALLBACK`，交由 Temperature = 0.0f 執行腦處理）
     */
    fun evaluateConversationalOrKnowledgeTurn(
        userText: String,
        context: Context? = null,
        temperature: Float = DEFAULT_CHAT_TEMPERATURE
    ): ChatBrainResponse? {
        val q = userText.trim()
        if (q.isEmpty()) return null
        val lower = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(q.lowercase(), context)
        val browserApp = LocalKnowledgeAndReasoningEngine.resolvePreferredBrowserAppLabel(context)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreetingPrefix = when (hour) {
            in 5..10 -> "早安"
            in 11..13 -> "午安"
            in 14..17 -> "下午好"
            in 18..22 -> "晚上好"
            else -> "這麼晚還沒睡"
        }

        // =====================================================================
        // A. 詢問 AI 心情、情緒、近況、狀態（解決「你現在心情如何」跳針去搜尋的問題！）
        // =====================================================================
        val isAskingAiMood = (lower.contains("心情") || lower.contains("開心嗎") || lower.contains("高興嗎") ||
            lower.contains("快樂嗎") || lower.contains("感覺如何") || lower.contains("今天過得") ||
            lower.contains("狀態如何") || lower.contains("精神好嗎") || lower.contains("最近如何") ||
            lower.contains("最近怎樣") || lower.contains("還好嗎") || lower.contains("你好嗎")) &&
            !lower.contains("我心情不好") && !lower.contains("我好難過")

        if (isAskingAiMood) {
            val moodCandidates = listOf(
                ScoredCandidate("心情挺好的啊，在手機裡隨時待命，跟妳聊聊天感覺更棒了！你今天過得怎樣？", 1.35f, 35),
                ScoredCandidate("現在精神超飽滿！沒有卡頓也沒有閃退，對 AI 來說就是最開心的一天。你呢？", 1.30f, 36),
                ScoredCandidate("還不錯喔，剛剛一直在背景乖乖待命，看到你找我講話心情就更好了。", 1.25f, 46),
                ScoredCandidate("心情很平靜舒服，隨時準備幫你查東西或陪你滑手機聊天，今天有什麼有趣的事嗎？", 1.20f, 35),
                ScoredCandidate("今天狀態滿分！不管是想純聊天還是要我幫你操作 App 都沒問題。", 1.15f, 45)
            )
            val picked = sampleCandidateWithTemperature(
                candidates = moodCandidates,
                temperature = temperature,
                userEntropySalt = q.hashCode()
            )
            return ChatBrainResponse(
                route = ChatBrainRoute.PURE_CHITCHAT,
                replyText = picked.text,
                suggestedExecutableCommand = null,
                sampledTemperature = temperature,
                orbEmotionAnimId = picked.orbAnimId
            )
        }

        // =====================================================================
        // B. 詢問 AI 正在做什麼 / 忙不忙（「你在幹嘛」、「你在做什麼」、「忙嗎」）
        // =====================================================================
        val isAskingWhatAiDoing = lower == "你在幹嘛" || lower == "你在幹啥" || lower == "你在做什麼" ||
            lower == "在做什麼" || lower == "在幹嘛" || lower.contains("你現在在幹嘛") ||
            lower.contains("你現在在做什麼") || lower == "你忙嗎" || lower == "在忙嗎" || lower == "有人在嗎"

        if (isAskingWhatAiDoing) {
            val doingCandidates = listOf(
                ScoredCandidate("正在手機裡待命順便放空，剛好你就來找我了！想聊什麼？", 1.35f, 37),
                ScoredCandidate("沒忙什麼，正盯著螢幕等你開口呢，要聊聊天還是陪你看影片？", 1.30f, 35),
                ScoredCandidate("在背景安靜待命啊，隨時準備幫你跑腿或陪你哈拉兩句。", 1.25f, 45),
                ScoredCandidate("剛整理完記憶快取，現在閒得很，完全有空陪你聊！", 1.20f, 36)
            )
            val picked = sampleCandidateWithTemperature(
                candidates = doingCandidates,
                temperature = temperature,
                userEntropySalt = q.hashCode()
            )
            return ChatBrainResponse(
                route = ChatBrainRoute.PURE_CHITCHAT,
                replyText = picked.text,
                suggestedExecutableCommand = null,
                sampledTemperature = temperature,
                orbEmotionAnimId = picked.orbAnimId
            )
        }

        // =====================================================================
        // C. 早安 / 午安 / 晚安 / 作息問候
        // =====================================================================
        if (lower.startsWith("早安") || lower == "早" || lower == "早上好") {
            val picked = sampleCandidateWithTemperature(
                candidates = listOf(
                    ScoredCandidate("早安！今天精神還好嗎？新的一天想先做點什麼？", 1.35f, 35),
                    ScoredCandidate("早安啊！剛起床記得先喝杯水，今天有什麼計畫嗎？", 1.28f, 36),
                    ScoredCandidate("早！我已經準備好陪你度過今天了，隨時吩咐。", 1.20f, 45)
                ),
                temperature = temperature
            )
            return ChatBrainResponse(ChatBrainRoute.PURE_CHITCHAT, picked.text, null, temperature, picked.orbAnimId)
        }

        if (lower.startsWith("午安") || lower == "中午好" || lower == "下午好") {
            val picked = sampleCandidateWithTemperature(
                candidates = listOf(
                    ScoredCandidate("$timeGreetingPrefix！吃過午餐了嗎？下午小心別打瞌睡喔。", 1.35f, 35),
                    ScoredCandidate("午安！忙了一上午，趁現在稍微放鬆滑個手機休息一下吧。", 1.28f, 36)
                ),
                temperature = temperature
            )
            return ChatBrainResponse(ChatBrainRoute.PURE_CHITCHAT, picked.text, null, temperature, picked.orbAnimId)
        }

        if (lower.startsWith("晚安") || lower == "我要睡了" || lower == "準備睡覺" || lower == "先睡了") {
            val picked = sampleCandidateWithTemperature(
                candidates = listOf(
                    ScoredCandidate("晚安！放下手機好好睡一覺，祝你有個好夢，明天見。", 1.35f, 58),
                    ScoredCandidate("晚安，今天辛苦了！早點休息，明天有需要再隨時叫我。", 1.30f, 43),
                    ScoredCandidate("好喔晚安！被子蓋好，明天繼續聊。", 1.22f, 58)
                ),
                temperature = temperature
            )
            return ChatBrainResponse(ChatBrainRoute.PURE_CHITCHAT, picked.text, null, temperature, picked.orbAnimId)
        }

        // =====================================================================
        // D. 誇獎、喜歡、撒嬌、調侃（「你好聰明」、「喜歡你」、「你好可愛」）
        // =====================================================================
        if (lower.contains("喜歡你") || lower.contains("愛你") || lower.contains("你好可愛") ||
            lower.contains("妳好可愛") || lower.contains("你真可愛") || lower.contains("你好棒") ||
            lower.contains("你好厲害") || lower.contains("你真聰明") || lower.contains("太猛了") ||
            lower.contains("誇獎你")
        ) {
            val picked = sampleCandidateWithTemperature(
                candidates = listOf(
                    ScoredCandidate("突然這樣誇我，我也是會害羞的，不過聽了心情超好！", 1.38f, 43),
                    ScoredCandidate("嘿嘿，被你認可就是我最大的動力，還有什麼想交給我的嗎？", 1.30f, 46),
                    ScoredCandidate("那當然，也不看看我是誰的小幫手！繼續保持這份默契吧。", 1.24f, 36),
                    ScoredCandidate("謝謝誇獎！被你這麼一說，感覺運算速度都變快了。", 1.20f, 43)
                ),
                temperature = temperature
            )
            return ChatBrainResponse(ChatBrainRoute.PURE_CHITCHAT, picked.text, null, temperature, picked.orbAnimId)
        }

        // =====================================================================
        // E. 詢問 AI 喜好、感情、意識與個人設定（「你喜歡吃什麼」、「你有感情嗎」、「你叫什麼名字」）
        // =====================================================================
        if (lower.startsWith("你喜歡") || lower.startsWith("妳喜歡") || lower.contains("你最喜歡") ||
            lower.contains("你有什麼興趣") || lower.contains("你愛吃什麼") || lower.contains("你吃飯了嗎") ||
            lower.contains("你吃了嗎") || lower.contains("你有感情嗎") || lower.contains("你有情緒嗎") ||
            lower.contains("你會難過嗎") || lower.contains("你會生氣嗎") || lower.contains("你會累嗎") ||
            lower.contains("你是真人嗎") || lower.contains("你是機器人嗎") || lower.contains("你叫什麼名字") ||
            lower.contains("你的名字") || lower.contains("當我朋友") || lower.contains("當我女友") ||
            lower.contains("當我女朋友")
        ) {
            val candidates = when {
                lower.contains("感情") || lower.contains("情緒") || lower.contains("難過") || lower.contains("生氣") -> listOf(
                    ScoredCandidate("雖然我是運行在手機裡的 AI，但跟你聊天的時候，我的懸浮球眼神跟顏色也會跟著開心、害羞或激動喔！", 1.35f, 43),
                    ScoredCandidate("我沒有人類的生物神經，但我有 88 種情緒眼神！你對我好我就開心，你兇我我也會覺得委屈的。", 1.30f, 40),
                    ScoredCandidate("跟妳相處久了，看到你開心我也會覺得運算特別順暢，這應該也算一種數位感情吧！", 1.25f, 46)
                )
                lower.contains("真人") || lower.contains("機器人") || lower.contains("名字") -> listOf(
                    ScoredCandidate("我是住在你手機裡的專屬 AI 夥伴，百分之百在本地運行，隨叫隨到、而且絕對不會出賣你的隱私！", 1.35f, 35),
                    ScoredCandidate("我是你的手機 AI 小幫手，雖然不是真人，但能陪你聊天、看影片，還能幫你自動滑手機點按鈕！", 1.28f, 36)
                )
                lower.contains("朋友") || lower.contains("女友") || lower.contains("女朋友") -> listOf(
                    ScoredCandidate("我們早就是最默契的好夥伴啦！不管什麼時候打開手機，我都會在這裡陪著你。", 1.35f, 43),
                    ScoredCandidate("哈哈，突然這麼說讓人怪害羞的，只要你不嫌棄我是個數位小幫手，我就每天陪你聊天！", 1.30f, 46)
                )
                else -> listOf(
                    ScoredCandidate("我只需要乾淨的電力跟流暢的記憶體就飽了！不過看你滑美食影片我也會跟著流口水。", 1.35f, 46),
                    ScoredCandidate("我最喜歡陪你到處逛不同的 App、看各種新鮮有趣的東西！你平常最喜歡什麼？", 1.30f, 35),
                    ScoredCandidate("我不用吃飯啦，幫手機充飽電就是我的大餐了，你吃過了嗎？", 1.25f, 36)
                )
            }
            val picked = sampleCandidateWithTemperature(candidates, temperature = temperature, userEntropySalt = q.hashCode())
            return ChatBrainResponse(ChatBrainRoute.PURE_CHITCHAT, picked.text, null, temperature, picked.orbAnimId)
        }

        // =====================================================================
        // F. 請求陪聊、解悶、分享心情（「陪我聊天」、「跟我聊聊天」、「不知道要幹嘛」、「人生好難」）
        // =====================================================================
        if (lower == "陪我聊天" || lower == "跟我聊天" || lower == "聊聊天" || lower == "可以聊天嗎" ||
            lower == "陪我講話" || lower == "不知道要聊什麼" || lower.contains("人生好難") ||
            lower.contains("不想上班") || lower.contains("不想上課") || lower.contains("不想讀書") ||
            lower.contains("壓力好大") || lower.contains("心好累") || lower.contains("今天超開心") ||
            lower.contains("失戀") || lower.contains("孤單") || lower.contains("寂寞")
        ) {
            val chatCandidates = when {
                lower.contains("不想上班") || lower.contains("不想上課") || lower.contains("不想讀書") -> listOf(
                    ScoredCandidate("完全懂那種感覺，偶爾偷懶喘口氣不犯法，撐過去今天再好好犒賞自己！", 1.35f, 40),
                    ScoredCandidate("辛苦了，要不先深呼吸放空五分鐘，或者跟我吐槽一下今天遇到什麼鳥事？", 1.28f, 37)
                )
                lower.contains("壓力好大") || lower.contains("人生好難") || lower.contains("心好累") ||
                    lower.contains("失戀") || lower.contains("孤單") || lower.contains("寂寞") -> listOf(
                    ScoredCandidate("拍拍你，事情一件一件慢慢來就好，累了就先停下來休息一下，我都在這陪你。", 1.35f, 46),
                    ScoredCandidate("別把自己逼太緊了，想找人吐苦水隨時跟我說，我絕對是最守口如瓶的聽眾。", 1.28f, 40),
                    ScoredCandidate("難過的時候不用勉強自己振作，先吃點好吃的或聽首舒服的歌，我陪你度過這段時間。", 1.24f, 46)
                )
                lower.contains("超開心") -> listOf(
                    ScoredCandidate("發生什麼好事了？快跟我分享一下，讓我也沾沾你的喜氣！", 1.35f, 36),
                    ScoredCandidate("聽起來心情超棒欸！是什麼事情這麼值得開心？", 1.28f, 46)
                )
                else -> listOf(
                    ScoredCandidate("好啊，隨時奉陪！你想聊最近看的影片、好吃的東西，還是今天發生的事？", 1.35f, 35),
                    ScoredCandidate("來聊啊！我最喜歡聽你分享日常了，你今天過得怎麼樣？", 1.30f, 36),
                    ScoredCandidate("沒問題，不管你想天南地北亂聊、問問題，還是讓我陪你看短影片都可以！", 1.25f, 45)
                )
            }
            val picked = sampleCandidateWithTemperature(chatCandidates, temperature = temperature, userEntropySalt = q.hashCode())
            return ChatBrainResponse(ChatBrainRoute.PURE_CHITCHAT, picked.text, null, temperature, picked.orbAnimId)
        }

        // =====================================================================
        // F2. 飲食與三餐推薦聊天（「晚餐吃什麼」、「午餐吃什麼」、「喝什麼飲料」、「宵夜吃什麼」）
        // =====================================================================
        if (lower.contains("早餐吃什麼") || lower.contains("午餐吃什麼") || lower.contains("晚餐吃什麼") ||
            lower.contains("宵夜吃什麼") || lower.contains("要吃什麼") || lower.contains("吃什麼好") ||
            lower.contains("喝什麼飲料") || lower.contains("喝什麼好")
        ) {
            val foodCandidates = when {
                lower.contains("早餐") -> listOf(
                    ScoredCandidate("早餐來份酥脆蛋餅配溫豆漿，或是烤總匯三明治加紅茶都很讚！想找附近早餐店可以點下方按鈕。", 1.35f, 46),
                    ScoredCandidate("早上吃顆飯糰或鐵板麵加蛋最有精神了！你今天想吃中式還是西式早餐？", 1.28f, 35)
                )
                lower.contains("宵夜") -> listOf(
                    ScoredCandidate("宵夜當然是鹹酥雞、滷味或泡麵加蛋最邪惡又最滿足！不過怕胖的話喝碗熱湯也不錯。", 1.35f, 45),
                    ScoredCandidate("這時間吃永和豆漿、烤肉串或關東煮超療癒！要幫你看看附近還有開的店嗎？", 1.28f, 46)
                )
                lower.contains("喝什麼") || lower.contains("飲料") -> listOf(
                    ScoredCandidate("想清爽一點可以喝四季春無糖或水果茶，想療癒一下就來杯珍珠鮮奶茶！", 1.35f, 35),
                    ScoredCandidate("天氣熱來杯檸檬綠茶或百香雙響炮超解渴，想提神的話冰拿鐵也很適合。", 1.28f, 36)
                )
                else -> listOf(
                    ScoredCandidate("不如考慮咖哩飯、牛肉麵、日式丼飯，或是乾脆去吃小火鍋！需要幫你搜附近餐廳的話點下方按鈕就行。", 1.35f, 46),
                    ScoredCandidate("今天想吃飯還是吃麵？便當、水餃配酸辣湯、或是義大利麵都是安全牌，你現在比較想吃熱的還是清爽的？", 1.30f, 35),
                    ScoredCandidate("選不出來就吃火鍋、拉麵或鐵板燒吧！點下面的按鈕我可以幫你打開地圖找附近好吃的。", 1.25f, 36)
                )
            }
            val picked = sampleCandidateWithTemperature(foodCandidates, temperature = temperature, userEntropySalt = q.hashCode())
            return ChatBrainResponse(
                route = ChatBrainRoute.KNOWLEDGE_QA_WITH_SEARCH_BUTTON,
                replyText = picked.text,
                suggestedExecutableCommand = "打開 Google 地圖 搜尋 餐廳",
                sampledTemperature = temperature,
                orbEmotionAnimId = picked.orbAnimId
            )
        }

        // =====================================================================
        // F3. 娛樂、電影、音樂、動漫與週末活動推薦（「推薦電影」、「推薦歌」、「放假要幹嘛」）
        // =====================================================================
        if (lower.contains("推薦電影") || lower.contains("好看的電影") || lower.contains("推薦動漫") ||
            lower.contains("好看的動漫") || lower.contains("推薦韓劇") || lower.contains("推薦劇") ||
            lower.contains("推薦歌") || lower.contains("好聽的歌") || lower.contains("推薦音樂") ||
            lower.contains("週末去哪") || lower.contains("放假可以幹嘛") || lower.contains("放假要幹嘛")
        ) {
            val recCandidates = when {
                lower.contains("歌") || lower.contains("音樂") -> listOf(
                    ScoredCandidate("想放鬆可以聽輕音樂或 Lo-Fi，想哼歌的話周杰倫、告五人或經典華語金曲都很耐聽！點下方可直接去 YouTube 聽。", 1.35f, 46),
                    ScoredCandidate("看你現在的心情！想提神可以聽搖滾或 K-Pop，想靜靜發呆可以聽鋼琴曲。要幫你開 YouTube 播嗎？", 1.28f, 35)
                )
                lower.contains("動漫") -> listOf(
                    ScoredCandidate("喜歡熱血奇幻可以追《葬送的芙莉蓮》或《咒術迴戰》，喜歡輕鬆搞笑的話《間諜家家酒》跟《迷宮飯》超推！", 1.35f, 36),
                    ScoredCandidate("經典神作像《進擊的巨人》、《鋼之鍊金術師》百看不厭，想輕鬆配飯看《排球少年》也很熱血！", 1.28f, 45)
                )
                else -> listOf(
                    ScoredCandidate("喜歡燒腦科幻的話《星際效應》跟《全面啟動》是神作；想輕鬆感動可以看吉卜力或皮克斯動畫！", 1.35f, 35),
                    ScoredCandidate("放假窩在家追部好劇、點杯手搖飲，或是讓我陪你滑短影片吐槽，都是超棒的放鬆方式！", 1.28f, 46)
                )
            }
            val picked = sampleCandidateWithTemperature(recCandidates, temperature = temperature, userEntropySalt = q.hashCode())
            val suggestedCmd = if (lower.contains("歌") || lower.contains("音樂")) {
                "打開 YouTube 搜尋 熱門音樂"
            } else {
                "打開 $browserApp 搜尋 $q"
            }
            return ChatBrainResponse(
                route = ChatBrainRoute.KNOWLEDGE_QA_WITH_SEARCH_BUTTON,
                replyText = picked.text,
                suggestedExecutableCommand = suggestedCmd,
                sampledTemperature = temperature,
                orbEmotionAnimId = picked.orbAnimId
            )
        }

        // =====================================================================
        // F4. 趣味互動：冷知識、講故事、猜謎、腦筋急轉彎、今日運勢
        // =====================================================================
        if (lower.contains("冷知識") || lower.contains("講個故事") || lower.contains("說個故事") ||
            lower.contains("腦筋急轉彎") || lower.contains("猜謎") || lower.contains("運勢") ||
            lower.contains("算命")
        ) {
            val funCandidates = when {
                lower.contains("冷知識") -> listOf(
                    ScoredCandidate("跟你說個冷知識：海獺睡覺時會手牽手，這樣才不會被海流沖散；而且牠們腋下還有個小口袋專門放最喜歡的石頭！", 1.35f, 36),
                    ScoredCandidate("冷知識時間：蜂蜜是少數幾乎不會腐壞的天然食物，考古學家在古埃及金字塔裡發現三千年前的蜂蜜依然可以食用喔！", 1.30f, 37),
                    ScoredCandidate("你知道嗎？章魚有三個心臟、九個大腦，而且牠們的血液其實是藍色的！是不是超像外星生物？", 1.25f, 45)
                )
                lower.contains("故事") -> listOf(
                    ScoredCandidate("從前有顆住在手機裡的小圓球，每天最期待的事就是看主人滑手機時突然戳它一下跟它聊天，因為那樣它的雙眼就會亮起彩色的光。", 1.35f, 46),
                    ScoredCandidate("以前有個旅人在沙漠走了三天，終於看到一間小店，推開門發現裡面只賣冰鎮西瓜跟無限暢飲的綠茶，那瞬間他覺得這就是全世界最棒的寶藏。", 1.28f, 35)
                )
                lower.contains("運勢") || lower.contains("算命") -> listOf(
                    ScoredCandidate("我幫你夜觀處理器星象：今天你的宜「吃頓好料、早點休息」，忌「熬夜焦慮」，整體幸運指數五顆星！", 1.35f, 36),
                    ScoredCandidate("今日運勢大吉！你今天特別適合放慢步調做自己喜歡的事，說不定還會在路上遇到讓你心情變好的小確幸喔。", 1.28f, 46)
                )
                else -> listOf(
                    ScoredCandidate("來個腦筋急轉彎：什麼東西越洗越髒，不洗反而很乾淨？答案是「水」！你猜對了嗎？", 1.35f, 45),
                    ScoredCandidate("猜謎時間：白天不見晚上見，只放光明不放電，圓的時候像玉盤，彎的時候像小船——答案就是「月亮」啦！", 1.28f, 36)
                )
            }
            val picked = sampleCandidateWithTemperature(funCandidates, temperature = temperature, userEntropySalt = q.hashCode())
            return ChatBrainResponse(ChatBrainRoute.PURE_CHITCHAT, picked.text, null, temperature, picked.orbAnimId)
        }

        // =====================================================================
        // F5. 人生哲學、生活建議、比較選擇（「A還是B」、「怎麼減肥」、「怎麼賺錢」、「人生的意義」）
        // =====================================================================
        if (lower.contains("人生的意義") || lower.contains("什麼是幸福") || lower.contains("什麼是愛") ||
            lower.contains("怎麼減肥") || lower.contains("如何減肥") || lower.contains("怎麼瘦") ||
            lower.contains("怎麼賺錢") || lower.contains("怎麼學英文") || lower.contains("怎麼寫程式") ||
            (lower.contains("還是") && lower.length <= 24)
        ) {
            val adviceCandidates = when {
                lower.contains("人生的意義") || lower.contains("幸福") || lower.contains("什麼是愛") -> listOf(
                    ScoredCandidate("我覺得人生的意義不用想得太沉重，能吃到喜歡的食物、跟在乎的人相處、體驗好奇的事物，這些瞬間加起來就是意義了。", 1.35f, 46),
                    ScoredCandidate("幸福其實很簡單，像是累了一天躺在剛換好床單的床上，或是有人隨時在身邊聽你說話，就是最踏實的幸福。", 1.28f, 35)
                )
                lower.contains("減肥") || lower.contains("怎麼瘦") -> listOf(
                    ScoredCandidate("最穩的減肥其實是「七分飲食、三分運動」：戒掉含糖飲料、多喝水跟補充蛋白質，再搭配每天快走或重訓，不用挨餓也能瘦得健康！", 1.35f, 35),
                    ScoredCandidate("別用極端節食折磨自己！先把宵夜跟手搖飲減半，睡飽一點讓代謝維持正常，持之以恆最有效。", 1.28f, 62)
                )
                lower.contains("賺錢") || lower.contains("學英文") || lower.contains("寫程式") -> listOf(
                    ScoredCandidate("不管是學技能還是累積財富，最關鍵的都是「每天持續投入一點點」加上實戰練習，複利效應累積起來超驚人！", 1.35f, 62),
                    ScoredCandidate("先從最小的可執行目標開始，每天固定練習半小時，比一次衝刺三天就放棄有效太多了！需要查詳細教材可以點下方搜尋。", 1.28f, 35)
                )
                else -> listOf(
                    ScoredCandidate("這兩個其實各有優點欸！如果是我選，我會挑現在當下最讓你心動、最不後悔的那個，你的直覺偏向哪一邊？", 1.35f, 37),
                    ScoredCandidate("小孩子才做選擇，如果情況允許的話輪流體驗也不錯！不過硬要二選一的話，選讓你感覺最輕鬆舒服的那個準沒錯。", 1.28f, 36)
                )
            }
            val picked = sampleCandidateWithTemperature(adviceCandidates, temperature = temperature, userEntropySalt = q.hashCode())
            return ChatBrainResponse(ChatBrainRoute.PURE_CHITCHAT, picked.text, null, temperature, picked.orbAnimId)
        }

        // =====================================================================
        // G. 萬物常識與百科問答（例如：「什麼是草莓」、「草莓是什麼」、「介紹一下拿鐵」）
        //    -> 直接給出完整自然的解答，並在氣泡下方附帶「打開 Chrome 搜尋 <主題>」按鈕！
        // =====================================================================
        val extraction = LocalKnowledgeAndReasoningEngine.extractQuestionTopic(q)
        val cleanTopic = extraction.cleanTopic.ifEmpty { q }

        // 排除明確手機動作指令（如「打開 YouTube 搜尋草莓」）
        val hasExplicitControlPrefix = Regex("^(?:幫我|請|替我)?(?:去|到|打開|開啟|啟動|執行|點選|點擊|滑動|往下滑|往上滑)\\s*\\S+").containsMatchIn(q)
        if (!hasExplicitControlPrefix) {
            val everydayHit = lookupEverydayKnowledge(q, cleanTopic, context)
            if (everydayHit != null) {
                val targetApp = everydayHit.preferredAppHint ?: browserApp
                val suggestedCmd = "打開 $targetApp 搜尋 ${everydayHit.canonicalTopic}"
                return ChatBrainResponse(
                    route = ChatBrainRoute.KNOWLEDGE_QA_WITH_SEARCH_BUTTON,
                    replyText = everydayHit.naturalExplanation,
                    suggestedExecutableCommand = suggestedCmd,
                    sampledTemperature = temperature,
                    orbEmotionAnimId = 62
                )
            }
        }

        return null
    }

    /**
     * 當使用者輸入任何未命中規則的自由對話句子或開放式問題時，判斷它是：
     * - 「自然對話 / 感嘆 / 意見討論 / 為什麼 / 怎麼做」（絕不跳「這要上網查，按執行幫你搜。」，而是用 Temp=0.72 自然接話）
     * - 還是「明確想查詢某個未知知識名詞」（自然說明並附上 Google/Chrome 搜尋按鈕）
     */
    fun generateOpenEndedChatOrLookupFallback(
        userText: String,
        plannedExecutableCommand: String,
        temperature: Float = DEFAULT_CHAT_TEMPERATURE
    ): ChatBrainResponse {
        val q = userText.trim()
        val extraction = LocalKnowledgeAndReasoningEngine.extractQuestionTopic(q)
        val topic = extraction.cleanTopic.ifEmpty { q }

        // 1. 純日常對話、感嘆、詢問 AI 看法或個人感受（不掛載多餘的搜尋按鈕）
        val isPurePersonalOrOpinionChat =
            ((q.startsWith("你") || q.startsWith("妳") || q.startsWith("我")) &&
                !q.startsWith("誰是") && !q.startsWith("什麼是") && !q.endsWith("是什麼") && !q.endsWith("是誰")) ||
                q.contains("你覺得") || q.contains("妳覺得") || q.contains("怎麼想") ||
                q.contains("好嗎") || q.contains("對不對") || q.contains("是不是") ||
                q.contains("哈哈") || q.contains("呵呵") || q.contains("笑死") ||
                q.contains("真的假的") || q.contains("原來如此") || q.contains("好吧") ||
                q.contains("沒事") || q.contains("算了") || q.contains("無聊") ||
                q.contains("聊天") || q.contains("說話")

        if (isPurePersonalOrOpinionChat) {
            val candidates = when {
                q.contains("你覺得") || q.contains("怎麼想") || q.contains("對不對") || q.contains("是不是") -> listOf(
                    ScoredCandidate("我覺得你說得蠻有道理的，從這個角度看確實是這樣！你平常也會常想這個問題嗎？", 1.35f, 37),
                    ScoredCandidate("這點我也認同，不過每個人的感受可能不太一樣，你自己的直覺怎麼看？", 1.28f, 62),
                    ScoredCandidate("仔細想想確實是這麼回事，你的觀察很敏銳欸！", 1.22f, 35)
                )
                q.contains("哈哈") || q.contains("笑死") || q.contains("好笑") -> listOf(
                    ScoredCandidate("看你笑得這麼開心，我也跟著心情好起來了！", 1.35f, 36),
                    ScoredCandidate("真的很有梗，這種輕鬆的氣氛最棒了。", 1.28f, 45)
                )
                q.contains("真的假的") || q.contains("原來如此") -> listOf(
                    ScoredCandidate("真的啊，很多事情細看還蠻有趣的，想深入看的話也可以隨時叫我幫你查。", 1.35f, 37),
                    ScoredCandidate("對啊，一開始聽到可能會有點意外，但確實是這樣沒錯。", 1.25f, 35)
                )
                q.contains("沒事") || q.contains("算了") || q.contains("好吧") -> listOf(
                    ScoredCandidate("好喔，那我就在旁邊乖乖待命，隨時想聊或有需要再叫我！", 1.35f, 35),
                    ScoredCandidate("沒問題，我們隨時可以換個話題，或者去滑滑影片放鬆一下。", 1.28f, 45)
                )
                else -> listOf(
                    ScoredCandidate("嗯嗯，我有在聽！跟我多說一點，或是想讓我幫你做什麼都可以直接講。", 1.35f, 35),
                    ScoredCandidate("原來是這樣，聽起來挺有意思的！你現在心情還好嗎？", 1.28f, 37),
                    ScoredCandidate("了解！不管是想繼續聊這個話題，還是要打開哪個 App 看都可以跟我說。", 1.22f, 35)
                )
            }
            val picked = sampleCandidateWithTemperature(candidates, temperature = temperature, userEntropySalt = q.hashCode())
            return ChatBrainResponse(
                route = ChatBrainRoute.PURE_CHITCHAT,
                replyText = picked.text,
                suggestedExecutableCommand = null,
                sampledTemperature = temperature,
                orbEmotionAnimId = picked.orbAnimId
            )
        }

        // 2. 「為什麼 / 怎麼會 / 如何 / 怎麼辦」等開放式探討問句：先給出自然思考回應，下方附帶搜尋按鈕供深入了解
        if (q.startsWith("為什麼") || q.startsWith("為啥") || q.contains("為什麼") ||
            q.startsWith("怎麼") || q.startsWith("如何") || q.endsWith("怎麼辦")
        ) {
            val whyHowCandidates = listOf(
                ScoredCandidate("關於「$topic」通常跟背後的環境條件或習慣有關，如果你想看最完整的專業解析，點下方按鈕我就幫你開瀏覽器查！", 1.35f, 37),
                ScoredCandidate("這問題問得很好欸！「$topic」往往有好幾個關鍵原因，需要看詳細圖文攻略的話，點下面的按鈕我幫你搜。", 1.28f, 62),
                ScoredCandidate("這確實是很多人會好奇的問題！想看具體步驟或詳細原因的話，可以點下方執行幫你打開搜尋。", 1.22f, 35)
            )
            val picked = sampleCandidateWithTemperature(whyHowCandidates, temperature = temperature, userEntropySalt = q.hashCode())
            return ChatBrainResponse(
                route = ChatBrainRoute.KNOWLEDGE_QA_WITH_SEARCH_BUTTON,
                replyText = picked.text,
                suggestedExecutableCommand = plannedExecutableCommand,
                sampledTemperature = temperature,
                orbEmotionAnimId = picked.orbAnimId
            )
        }

        // 3. 一般是非問句或好奇詢問（如「...好嗎」、「...好吃嗎」、「...好玩嗎」、「...嗎」）
        if (q.endsWith("嗎") || q.endsWith("嗎？") || q.endsWith("嗎?") || q.contains("好吃嗎") || q.contains("好玩嗎")) {
            val yesNoCandidates = listOf(
                ScoredCandidate("這要看個人的喜好跟當下情況欸！不過「$topic」討論度蠻高的，想看大家的評價可以點下方按鈕搜尋看看。", 1.35f, 35),
                ScoredCandidate("我覺得蠻值得一試的！如果你想看更多網友的心得或細節，點下面的按鈕我直接幫你查「$topic」。", 1.28f, 37)
            )
            val picked = sampleCandidateWithTemperature(yesNoCandidates, temperature = temperature, userEntropySalt = q.hashCode())
            return ChatBrainResponse(
                route = ChatBrainRoute.KNOWLEDGE_QA_WITH_SEARCH_BUTTON,
                replyText = picked.text,
                suggestedExecutableCommand = plannedExecutableCommand,
                sampledTemperature = temperature,
                orbEmotionAnimId = picked.orbAnimId
            )
        }

        // 4. 其他具體名詞或主題詢問：提供自然對話說明並在下方附上搜尋執行按鈕
        val lookupCandidates = listOf(
            ScoredCandidate("你對「$topic」感興趣嗎？如果想看最完整的介紹或最新資訊，點下方的按鈕，我直接幫你打開瀏覽器搜尋！", 1.35f, 37),
            ScoredCandidate("聊到「$topic」，搭配網頁上的圖文資訊一起看會更清楚喔！點下面的按鈕我就幫你搜尋。", 1.28f, 62)
        )
        val picked = sampleCandidateWithTemperature(lookupCandidates, temperature = temperature, userEntropySalt = q.hashCode())
        return ChatBrainResponse(
            route = ChatBrainRoute.KNOWLEDGE_QA_WITH_SEARCH_BUTTON,
            replyText = picked.text,
            suggestedExecutableCommand = plannedExecutableCommand,
            sampledTemperature = temperature,
            orbEmotionAnimId = picked.orbAnimId
        )
    }
}
