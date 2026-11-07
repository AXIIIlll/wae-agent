package com.example.newandroidapp.vision.dataset

import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 第三階段：整張畫面「全域姿勢庫 (Whole-Screen Posture)」與狀態轉移分類器
 *
 * 100% 離線運行於手機端，零第三方依賴。
 * 訓練數據來源（於電腦端離線掃描蒸餾，零動用實機）：
 * 1. AMEX 102K 全域標註語料（`page_caption` 整頁語義描述 + `clickable_elements` + `scrollable_elements` 空間拓樸）
 * 2. MobileViews 401 款真實 App 之 `actions.csv` + `states/state_*.json` 狀態轉移軌跡（UTG State Transitions）
 *
 * 匯出資產：`app/src/main/assets/ui_phase3_screen_posture.json`
 */
object Phase3ScreenPostureClassifier {

    enum class ScreenPostureArchetype(
        val code: String,
        val displayNameZh: String,
        val defaultRecommendedRoles: List<String>
    ) {
        HOME_FEED_HUB(
            code = "HOME_FEED_HUB",
            displayNameZh = "首頁探索動態流",
            defaultRecommendedRoles = listOf("CONTENT_CARD_ITEM", "SEARCH_ENTRY", "SEARCH_INPUT_BOX", "FILTER_SORT_CHIP", "NAV_EXPLORE_DISCOVER_TAB")
        ),
        SEARCH_ENTRY_PORTAL(
            code = "SEARCH_ENTRY_PORTAL",
            displayNameZh = "搜尋入口與分類探索頁",
            defaultRecommendedRoles = listOf("SEARCH_INPUT_BOX", "VOICE_OR_CAMERA_INPUT", "CONTENT_CARD_ITEM", "NAV_HOME_TAB")
        ),
        SEARCH_ACTIVE_SUGGESTIONS(
            code = "SEARCH_ACTIVE_SUGGESTIONS",
            displayNameZh = "搜尋輸入與自動完成建議頁",
            defaultRecommendedRoles = listOf("SEARCH_INPUT_BOX", "SEARCH_SUBMIT", "CONTENT_CARD_ITEM", "CLEAR_INPUT")
        ),
        SEARCH_RESULTS_LIST(
            code = "SEARCH_RESULTS_LIST",
            displayNameZh = "搜尋結果與篩選列表頁",
            defaultRecommendedRoles = listOf("CONTENT_CARD_ITEM", "FILTER_SORT_CHIP", "MEDIA_PLAY_PAUSE", "ADD_TO_CART_BUY")
        ),
        CONTENT_DETAIL_ARTICLE(
            code = "CONTENT_DETAIL_ARTICLE",
            displayNameZh = "內容詳情與文章內頁",
            defaultRecommendedRoles = listOf("LIKE_FAVORITE_BOOKMARK", "SHARE_EXPORT", "NAV_BACK", "CONTENT_CARD_ITEM")
        ),
        MEDIA_PLAYER_VIEW(
            code = "MEDIA_PLAYER_VIEW",
            displayNameZh = "影音與音樂播放控制頁",
            defaultRecommendedRoles = listOf("MEDIA_PLAY_PAUSE", "MEDIA_NEXT_PREV_CONTROL", "LIKE_FAVORITE_BOOKMARK", "SHARE_EXPORT")
        ),
        ECOMMERCE_PRODUCT_DETAIL(
            code = "ECOMMERCE_PRODUCT_DETAIL",
            displayNameZh = "商品詳情與購買頁",
            defaultRecommendedRoles = listOf("ADD_TO_CART_BUY", "LIKE_FAVORITE_BOOKMARK", "SHARE_EXPORT", "NAV_CART_ORDERS_TAB")
        ),
        CART_CHECKOUT_ORDER(
            code = "CART_CHECKOUT_ORDER",
            displayNameZh = "購物車與結帳確認頁",
            defaultRecommendedRoles = listOf("ADD_TO_CART_BUY", "SEND_SUBMIT_CONFIRM", "DELETE_REMOVE_TRASH", "NAV_BACK")
        ),
        MESSAGING_INBOX_LIST(
            code = "MESSAGING_INBOX_LIST",
            displayNameZh = "通訊與郵件收件匣列表",
            defaultRecommendedRoles = listOf("CONTENT_CARD_ITEM", "COMPOSE_CREATE_ADD", "SEARCH_INPUT_BOX", "SEARCH_ENTRY")
        ),
        CHAT_OR_COMPOSE_EDITOR(
            code = "CHAT_OR_COMPOSE_EDITOR",
            displayNameZh = "聊天室或信件撰寫頁",
            defaultRecommendedRoles = listOf("SEND_SUBMIT_CONFIRM", "SEARCH_INPUT_BOX", "VOICE_OR_CAMERA_INPUT", "NAV_BACK")
        ),
        MAP_NAVIGATION_EXPLORE(
            code = "MAP_NAVIGATION_EXPLORE",
            displayNameZh = "地圖導航與周邊地點頁",
            defaultRecommendedRoles = listOf("SEARCH_INPUT_BOX", "CONTENT_CARD_ITEM", "SEND_SUBMIT_CONFIRM", "NAV_BACK")
        ),
        SETTINGS_PREFERENCES_LIST(
            code = "SETTINGS_PREFERENCES_LIST",
            displayNameZh = "系統與應用設定選單頁",
            defaultRecommendedRoles = listOf("CONTENT_CARD_ITEM", "SEARCH_ENTRY", "NAV_BACK")
        ),
        USER_PROFILE_ACCOUNT(
            code = "USER_PROFILE_ACCOUNT",
            displayNameZh = "個人檔案與帳戶中心頁",
            defaultRecommendedRoles = listOf("SETTINGS_MENU_MORE", "CONTENT_CARD_ITEM", "NAV_HOME_TAB")
        ),
        AUTH_LOGIN_VERIFICATION(
            code = "AUTH_LOGIN_VERIFICATION",
            displayNameZh = "登入註冊與身分驗證頁",
            defaultRecommendedRoles = listOf("SEND_SUBMIT_CONFIRM", "CLOSE_DISMISS", "NAV_BACK")
        ),
        MODAL_POPUP_PERMISSION_AD(
            code = "MODAL_POPUP_PERMISSION_AD",
            displayNameZh = "彈窗對話框與權限提示",
            defaultRecommendedRoles = listOf("CLOSE_DISMISS", "SEND_SUBMIT_CONFIRM", "NAV_BACK")
        ),
        GENERAL_APP_SCREEN(
            code = "GENERAL_APP_SCREEN",
            displayNameZh = "一般應用操作畫面",
            defaultRecommendedRoles = listOf("CONTENT_CARD_ITEM", "SEARCH_ENTRY", "GENERIC_ACTION_BUTTON")
        );

        companion object {
            fun fromCode(code: String): ScreenPostureArchetype {
                return entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
                    ?: GENERAL_APP_SCREEN
            }
        }
    }

    data class PostureLearnedProfile(
        val posture: ScreenPostureArchetype,
        val sampleCount: Int,
        val meanInteractiveCount: Float,
        val meanScrollableCount: Float,
        val bottomNavPrevalence: Float,
        val topSearchBoxPrevalence: Float,
        val topFilterChipPrevalence: Float,
        val signatureRoleWeights: Map<String, Float>,
        val topKeywords: Map<String, Float>,
        val recommendedPrimaryRoles: List<String>
    )

    data class ScreenPostureClassificationResult(
        val posture: ScreenPostureArchetype,
        val confidence: Float,
        val secondaryPosture: ScreenPostureArchetype?,
        val recommendedRoles: List<String>,
        val expectedTransitions: Map<String, String>
    )

    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "from", "that", "this", "are", "was", "were",
        "have", "has", "had", "not", "but", "what", "all", "can", "when", "your",
        "screenshot", "shows", "screen", "display", "displays", "displayed", "interface",
        "page", "app", "application", "mobile", "phone", "button", "buttons", "icon",
        "icons", "top", "bottom", "left", "right", "center", "below", "above", "under",
        "there", "which", "including", "includes", "such", "also", "various", "several",
        "different", "multiple", "section", "sections", "area", "view", "views", "image",
        "images", "text", "label", "labels", "user", "users", "click", "tap", "select",
        "option", "options", "item", "items", "list", "bar", "menu", "tab", "tabs",
        "to", "in", "or", "on", "of", "at", "by", "an", "as", "is", "it", "be"
    )

    @Volatile
    private var trainedAmexScreensCount: Int = 0

    @Volatile
    private var trainedMobileViewsTransitionsCount: Int = 0

    private val postureProfiles = LinkedHashMap<ScreenPostureArchetype, PostureLearnedProfile>()

    // 狀態轉移機率矩陣：Key = "FROM_POSTURE|ACTION_ROLE", Value = Map<TO_POSTURE, Probability>
    private val transitionMatrix = LinkedHashMap<String, Map<String, Float>>()

    private val seedPostureRoleWeights: Map<ScreenPostureArchetype, Map<String, Float>> = mapOf(
        ScreenPostureArchetype.HOME_FEED_HUB to mapOf(
            "NAV_HOME_TAB" to 3.4f,
            "NAV_EXPLORE_DISCOVER_TAB" to 2.6f,
            "NAV_LIBRARY_SAVED_TAB" to 2.2f,
            "CONTENT_CARD_ITEM" to 1.8f,
            "FILTER_SORT_CHIP" to 1.6f,
            "SEARCH_ENTRY" to 1.5f
        ),
        ScreenPostureArchetype.SEARCH_ENTRY_PORTAL to mapOf(
            "SEARCH_INPUT_BOX" to 4.2f,
            "NAV_HOME_TAB" to 2.4f,
            "NAV_EXPLORE_DISCOVER_TAB" to 2.4f,
            "VOICE_OR_CAMERA_INPUT" to 2.2f,
            "CONTENT_CARD_ITEM" to 1.4f
        ),
        ScreenPostureArchetype.SEARCH_ACTIVE_SUGGESTIONS to mapOf(
            "SEARCH_INPUT_BOX" to 4.5f,
            "CLEAR_INPUT" to 3.6f,
            "SEARCH_SUBMIT" to 3.4f,
            "VOICE_OR_CAMERA_INPUT" to 2.5f,
            "NAV_BACK" to 1.8f
        ),
        ScreenPostureArchetype.SEARCH_RESULTS_LIST to mapOf(
            "SEARCH_INPUT_BOX" to 3.2f,
            "FILTER_SORT_CHIP" to 3.4f,
            "CONTENT_CARD_ITEM" to 2.5f,
            "NAV_BACK" to 1.8f
        ),
        ScreenPostureArchetype.CONTENT_DETAIL_ARTICLE to mapOf(
            "NAV_BACK" to 3.0f,
            "SHARE_EXPORT" to 3.4f,
            "LIKE_FAVORITE_BOOKMARK" to 3.2f,
            "SETTINGS_MENU_MORE" to 1.6f
        ),
        ScreenPostureArchetype.MEDIA_PLAYER_VIEW to mapOf(
            "MEDIA_PLAY_PAUSE" to 4.6f,
            "MEDIA_NEXT_PREV_CONTROL" to 4.8f,
            "LIKE_FAVORITE_BOOKMARK" to 2.2f,
            "SHARE_EXPORT" to 1.8f
        ),
        ScreenPostureArchetype.ECOMMERCE_PRODUCT_DETAIL to mapOf(
            "ADD_TO_CART_BUY" to 4.8f,
            "NAV_CART_ORDERS_TAB" to 2.8f,
            "LIKE_FAVORITE_BOOKMARK" to 2.2f,
            "SHARE_EXPORT" to 2.0f,
            "NAV_BACK" to 1.8f
        ),
        ScreenPostureArchetype.CART_CHECKOUT_ORDER to mapOf(
            "ADD_TO_CART_BUY" to 4.2f,
            "SEND_SUBMIT_CONFIRM" to 3.6f,
            "DELETE_REMOVE_TRASH" to 3.2f
        ),
        ScreenPostureArchetype.MESSAGING_INBOX_LIST to mapOf(
            "COMPOSE_CREATE_ADD" to 4.4f,
            "SEARCH_INPUT_BOX" to 2.4f,
            "CONTENT_CARD_ITEM" to 2.2f
        ),
        ScreenPostureArchetype.CHAT_OR_COMPOSE_EDITOR to mapOf(
            "SEND_SUBMIT_CONFIRM" to 4.4f,
            "VOICE_OR_CAMERA_INPUT" to 2.4f,
            "NAV_BACK" to 2.2f
        ),
        ScreenPostureArchetype.MAP_NAVIGATION_EXPLORE to mapOf(
            "SEARCH_INPUT_BOX" to 2.6f,
            "VOICE_OR_CAMERA_INPUT" to 2.0f,
            "NAV_BACK" to 2.0f,
            "CONTENT_CARD_ITEM" to 2.2f
        ),
        ScreenPostureArchetype.SETTINGS_PREFERENCES_LIST to mapOf(
            "NAV_BACK" to 2.8f,
            "SEARCH_ENTRY" to 1.8f
        ),
        ScreenPostureArchetype.USER_PROFILE_ACCOUNT to mapOf(
            "NAV_PROFILE_ACCOUNT_TAB" to 4.2f,
            "SETTINGS_MENU_MORE" to 2.8f
        ),
        ScreenPostureArchetype.AUTH_LOGIN_VERIFICATION to mapOf(
            "SEND_SUBMIT_CONFIRM" to 3.6f,
            "CLOSE_DISMISS" to 2.0f
        ),
        ScreenPostureArchetype.MODAL_POPUP_PERMISSION_AD to mapOf(
            "CLOSE_DISMISS" to 4.8f,
            "SEND_SUBMIT_CONFIRM" to 2.0f
        )
    )

    private val seedPostureKeywords: Map<ScreenPostureArchetype, Map<String, Float>> = mapOf(
        ScreenPostureArchetype.HOME_FEED_HUB to mapOf(
            "首頁" to 3.6f, "推薦" to 3.2f, "為你推薦" to 3.8f, "for you" to 3.6f,
            "home" to 2.8f, "trending" to 2.8f, "feed" to 3.0f, "discover" to 2.6f
        ),
        ScreenPostureArchetype.SEARCH_ENTRY_PORTAL to mapOf(
            "想聽什麼" to 4.8f, "搜尋或輸入網址" to 4.4f, "探索分類" to 4.2f, "熱門搜尋" to 4.2f,
            "browse all" to 4.2f, "categories" to 3.4f, "trending searches" to 4.2f,
            "心情與類型" to 4.4f, "新發行" to 3.8f
        ),
        ScreenPostureArchetype.SEARCH_ACTIVE_SUGGESTIONS to mapOf(
            "recent searches" to 4.6f, "search history" to 4.6f, "搜尋紀錄" to 4.6f,
            "最近搜尋" to 4.6f, "清除搜尋紀錄" to 4.4f, "suggestions" to 3.8f
        ),
        ScreenPostureArchetype.SEARCH_RESULTS_LIST to mapOf(
            "search results" to 4.6f, "搜尋結果" to 4.6f, "sort by" to 4.0f, "filter" to 3.6f,
            "篩選" to 3.8f, "排序" to 3.8f, "綜合排序" to 4.2f, "全部結果" to 4.0f
        ),
        ScreenPostureArchetype.CONTENT_DETAIL_ARTICLE to mapOf(
            "article" to 3.8f, "comments" to 3.6f, "留言" to 3.6f, "評論" to 3.4f,
            "published" to 3.4f, "author" to 3.2f, "閱讀全文" to 4.0f
        ),
        ScreenPostureArchetype.MEDIA_PLAYER_VIEW to mapOf(
            "now playing" to 4.8f, "正在播放" to 4.8f, "下一首" to 4.5f, "上一首" to 4.5f,
            "隨機播放" to 4.4f, "lyrics" to 4.2f, "歌詞" to 4.2f, "episode" to 3.6f, "podcast" to 3.4f
        ),
        ScreenPostureArchetype.ECOMMERCE_PRODUCT_DETAIL to mapOf(
            "add to cart" to 4.8f, "add to bag" to 4.8f, "buy now" to 4.8f, "加入購物車" to 4.8f,
            "立即購買" to 4.8f, "product details" to 4.2f, "商品詳情" to 4.4f, "選擇規格" to 4.2f,
            "in stock" to 3.8f, "free shipping" to 3.6f
        ),
        ScreenPostureArchetype.CART_CHECKOUT_ORDER to mapOf(
            "shopping cart" to 4.8f, "my bag" to 4.6f, "checkout" to 4.8f, "購物車" to 4.8f,
            "去買單" to 4.8f, "結帳" to 4.6f, "訂單確認" to 4.6f, "subtotal" to 4.4f, "小計" to 4.2f
        ),
        ScreenPostureArchetype.MESSAGING_INBOX_LIST to mapOf(
            "inbox" to 4.8f, "收件匣" to 4.8f, "在郵件中搜尋" to 4.8f, "search in mail" to 4.8f,
            "撰寫" to 4.5f, "compose" to 4.5f, "unread" to 4.0f, "未讀" to 4.0f, "messages" to 3.8f
        ),
        ScreenPostureArchetype.CHAT_OR_COMPOSE_EDITOR to mapOf(
            "收件者" to 4.6f, "主旨" to 4.6f, "撰寫郵件" to 4.8f, "輸入訊息" to 4.6f,
            "type a message" to 4.8f, "subject" to 4.0f, "recipient" to 4.2f
        ),
        ScreenPostureArchetype.MAP_NAVIGATION_EXPLORE to mapOf(
            "around you" to 4.6f, "directions" to 4.4f, "路線" to 4.4f, "開始導航" to 4.8f,
            "nearby" to 4.0f, "附近" to 3.8f, "1828 mi" to 4.2f, " mi" to 3.6f, " km" to 3.6f,
            "map" to 3.4f, "地圖" to 3.8f, "oxford, ms" to 4.0f
        ),
        ScreenPostureArchetype.SETTINGS_PREFERENCES_LIST to mapOf(
            "settings" to 4.6f, "設定" to 4.6f, "偏好設定" to 4.6f, "notifications" to 3.8f,
            "privacy" to 3.8f, "隱私權" to 4.0f, "帳號設定" to 4.2f, "一般設定" to 4.4f
        ),
        ScreenPostureArchetype.USER_PROFILE_ACCOUNT to mapOf(
            "profile" to 4.2f, "my account" to 4.4f, "個人檔案" to 4.6f, "我的帳號" to 4.6f,
            "會員中心" to 4.6f, "followers" to 3.8f, "following" to 3.8f, "粉絲" to 3.8f
        ),
        ScreenPostureArchetype.AUTH_LOGIN_VERIFICATION to mapOf(
            "sign in" to 4.8f, "log in" to 4.8f, "login" to 4.6f, "register" to 4.4f,
            "sign up" to 4.6f, "password" to 4.6f, "forgot password" to 4.6f, "verification code" to 4.6f,
            "登入" to 4.8f, "註冊" to 4.6f, "密碼" to 4.6f, "驗證碼" to 4.6f
        ),
        ScreenPostureArchetype.MODAL_POPUP_PERMISSION_AD to mapOf(
            "allow" to 4.2f, "deny" to 4.2f, "while using the app" to 4.8f, "not now" to 4.2f,
            "no thanks" to 4.2f, "skip ad" to 4.8f, "允許" to 4.2f, "拒絕" to 4.2f,
            "僅在使用該應用程式時允許" to 4.8f, "略過廣告" to 4.8f, "以後再說" to 4.4f
        )
    )

    private val defaultTransitionPriors: Map<String, Map<String, Float>> = mapOf(
        "HOME_FEED_HUB|SEARCH_ENTRY" to mapOf("SEARCH_ENTRY_PORTAL" to 0.55f, "SEARCH_ACTIVE_SUGGESTIONS" to 0.45f),
        "HOME_FEED_HUB|SEARCH_INPUT_BOX" to mapOf("SEARCH_ACTIVE_SUGGESTIONS" to 0.85f, "SEARCH_RESULTS_LIST" to 0.15f),
        "HOME_FEED_HUB|CONTENT_CARD_ITEM" to mapOf("CONTENT_DETAIL_ARTICLE" to 0.45f, "MEDIA_PLAYER_VIEW" to 0.30f, "ECOMMERCE_PRODUCT_DETAIL" to 0.25f),
        "HOME_FEED_HUB|NAV_PROFILE_ACCOUNT_TAB" to mapOf("USER_PROFILE_ACCOUNT" to 0.92f),
        "HOME_FEED_HUB|NAV_CART_ORDERS_TAB" to mapOf("CART_CHECKOUT_ORDER" to 0.90f),
        "SEARCH_ENTRY_PORTAL|SEARCH_INPUT_BOX" to mapOf("SEARCH_ACTIVE_SUGGESTIONS" to 0.88f, "SEARCH_RESULTS_LIST" to 0.12f),
        "SEARCH_ENTRY_PORTAL|CONTENT_CARD_ITEM" to mapOf("SEARCH_RESULTS_LIST" to 0.75f, "CONTENT_DETAIL_ARTICLE" to 0.25f),
        "SEARCH_ACTIVE_SUGGESTIONS|SEARCH_SUBMIT" to mapOf("SEARCH_RESULTS_LIST" to 0.92f),
        "SEARCH_ACTIVE_SUGGESTIONS|CONTENT_CARD_ITEM" to mapOf("SEARCH_RESULTS_LIST" to 0.78f, "CONTENT_DETAIL_ARTICLE" to 0.22f),
        "SEARCH_RESULTS_LIST|CONTENT_CARD_ITEM" to mapOf("CONTENT_DETAIL_ARTICLE" to 0.40f, "ECOMMERCE_PRODUCT_DETAIL" to 0.35f, "MEDIA_PLAYER_VIEW" to 0.25f),
        "SEARCH_RESULTS_LIST|FILTER_SORT_CHIP" to mapOf("SEARCH_RESULTS_LIST" to 0.90f),
        "ECOMMERCE_PRODUCT_DETAIL|ADD_TO_CART_BUY" to mapOf("CART_CHECKOUT_ORDER" to 0.82f, "MODAL_POPUP_PERMISSION_AD" to 0.18f),
        "MESSAGING_INBOX_LIST|COMPOSE_CREATE_ADD" to mapOf("CHAT_OR_COMPOSE_EDITOR" to 0.94f),
        "MESSAGING_INBOX_LIST|CONTENT_CARD_ITEM" to mapOf("CONTENT_DETAIL_ARTICLE" to 0.65f, "CHAT_OR_COMPOSE_EDITOR" to 0.35f),
        "MODAL_POPUP_PERMISSION_AD|CLOSE_DISMISS" to mapOf("HOME_FEED_HUB" to 0.60f, "GENERAL_APP_SCREEN" to 0.40f)
    )

    init {
        resetToDefaults()
    }

    private fun resetToDefaults() {
        postureProfiles.clear()
        for (p in ScreenPostureArchetype.entries) {
            postureProfiles[p] = PostureLearnedProfile(
                posture = p,
                sampleCount = 100,
                meanInteractiveCount = 12f,
                meanScrollableCount = 1.2f,
                bottomNavPrevalence = if (p == ScreenPostureArchetype.HOME_FEED_HUB || p == ScreenPostureArchetype.SEARCH_ENTRY_PORTAL || p == ScreenPostureArchetype.USER_PROFILE_ACCOUNT) 0.82f else 0.15f,
                topSearchBoxPrevalence = if (p in setOf(ScreenPostureArchetype.SEARCH_ENTRY_PORTAL, ScreenPostureArchetype.SEARCH_ACTIVE_SUGGESTIONS, ScreenPostureArchetype.SEARCH_RESULTS_LIST)) 0.88f else 0.20f,
                topFilterChipPrevalence = if (p == ScreenPostureArchetype.SEARCH_RESULTS_LIST || p == ScreenPostureArchetype.HOME_FEED_HUB) 0.65f else 0.10f,
                signatureRoleWeights = seedPostureRoleWeights[p] ?: emptyMap(),
                topKeywords = seedPostureKeywords[p] ?: emptyMap(),
                recommendedPrimaryRoles = p.defaultRecommendedRoles
            )
        }
        transitionMatrix.clear()
        transitionMatrix.putAll(defaultTransitionPriors)
    }

    /**
     * 從 AMEX 單頁紀錄（`page_caption` + `clickable_elements` + `scrollable_elements`）推斷 Ground-Truth 全域畫面姿勢
     */
    fun inferGroundTruthPostureFromAmexRecord(
        rec: ExternalGuiDatasetBridge.AmexScreenKnowledgeRecord
    ): ScreenPostureArchetype {
        val cap = rec.pageCaption.lowercase()
        val elemText = rec.clickableElements.joinToString(" ") {
            (it.xmlDescriptions.joinToString(" ") + " " + it.functionality).lowercase()
        }
        val combined = "$cap $elemText"

        var maxRight = 1080
        var maxBottom = 2340
        for (el in rec.clickableElements) {
            if (el.bounds.right > maxRight) maxRight = el.bounds.right
            if (el.bounds.bottom > maxBottom) maxBottom = el.bounds.bottom
        }
        val sw = maxRight.toFloat().coerceAtLeast(320f)
        val sh = maxBottom.toFloat().coerceAtLeast(480f)

        var bottomBarCount = 0
        var topSearchBoxCount = 0
        var topFilterChipCount = 0
        val roleCounts = HashMap<Phase2ButtonFunctionalityClassifier.ButtonFunctionRole, Int>()

        for (el in rec.clickableElements) {
            val bw = (el.bounds.right - el.bounds.left).coerceAtLeast(1).toFloat()
            val bh = (el.bounds.bottom - el.bounds.top).coerceAtLeast(1).toFloat()
            val normCx = (((el.bounds.left + el.bounds.right) * 0.5f) / sw).coerceIn(0f, 1f)
            val normCy = (((el.bounds.top + el.bounds.bottom) * 0.5f) / sh).coerceIn(0f, 1f)
            val normW = (bw / sw).coerceIn(0.005f, 1f)
            val normH = (bh / sh).coerceIn(0.005f, 1f)
            val aspect = (bw / bh).coerceIn(0.1f, 15f)

            if (normCy >= 0.85f && normH <= 0.12f) bottomBarCount++
            val role = Phase2ButtonFunctionalityClassifier.inferGroundTruthRoleFromAmexAnnotation(
                xmlDescs = el.xmlDescriptions,
                functionality = el.functionality,
                normCx = normCx,
                normCy = normCy,
                normW = normW,
                normH = normH,
                pixelAspect = aspect
            )
            roleCounts[role] = (roleCounts[role] ?: 0) + 1
            if (role == Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.SEARCH_INPUT_BOX) topSearchBoxCount++
            if (role == Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.FILTER_SORT_CHIP) topFilterChipCount++
        }

        if (cap.contains("pop-up") || cap.contains("popup") || cap.contains("dialog") || cap.contains("permission") ||
            (rec.clickableElements.size in 1..5 && (roleCounts[Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.CLOSE_DISMISS] ?: 0) >= 1)
        ) {
            return ScreenPostureArchetype.MODAL_POPUP_PERMISSION_AD
        }
        if (cap.contains("sign in") || cap.contains("log in") || cap.contains("login") || cap.contains("registration") || cap.contains("create an account")) {
            return ScreenPostureArchetype.AUTH_LOGIN_VERIFICATION
        }
        if (cap.contains("shopping cart") || cap.contains("checkout") || cap.contains("shopping bag") || cap.contains("order summary")) {
            return ScreenPostureArchetype.CART_CHECKOUT_ORDER
        }
        if ((roleCounts[Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.ADD_TO_CART_BUY] ?: 0) >= 1 ||
            cap.contains("product page") || cap.contains("product detail") || cap.contains("add to cart") || cap.contains("add to bag")
        ) {
            return ScreenPostureArchetype.ECOMMERCE_PRODUCT_DETAIL
        }
        if (cap.contains("map interface") || cap.contains("map view") || cap.contains("map centered") ||
            combined.contains("around you") && combined.contains(" mi")
        ) {
            return ScreenPostureArchetype.MAP_NAVIGATION_EXPLORE
        }
        if (cap.contains("music player") || cap.contains("audio player") || cap.contains("video player") ||
            cap.contains("now playing") || ((roleCounts[Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.MEDIA_NEXT_PREV_CONTROL] ?: 0) >= 1 &&
                (roleCounts[Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.MEDIA_PLAY_PAUSE] ?: 0) >= 1)
        ) {
            return ScreenPostureArchetype.MEDIA_PLAYER_VIEW
        }
        if (cap.contains("compose email") || cap.contains("new message") || cap.contains("chat interface") || cap.contains("conversation with")) {
            return ScreenPostureArchetype.CHAT_OR_COMPOSE_EDITOR
        }
        if (cap.contains("inbox") || cap.contains("email list") || cap.contains("message list") ||
            (combined.contains("inbox") && (roleCounts[Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.COMPOSE_CREATE_ADD] ?: 0) >= 1)
        ) {
            return ScreenPostureArchetype.MESSAGING_INBOX_LIST
        }
        if (cap.contains("settings") || cap.contains("preferences") || cap.contains("notification settings")) {
            return ScreenPostureArchetype.SETTINGS_PREFERENCES_LIST
        }
        if (cap.contains("user profile") || cap.contains("account page") || cap.contains("profile page")) {
            return ScreenPostureArchetype.USER_PROFILE_ACCOUNT
        }
        if (cap.contains("search suggestions") || cap.contains("recent searches") || cap.contains("autocomplete") ||
            (topSearchBoxCount >= 1 && (roleCounts[Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.CLEAR_INPUT] ?: 0) >= 1)
        ) {
            return ScreenPostureArchetype.SEARCH_ACTIVE_SUGGESTIONS
        }
        if (cap.contains("search results") || (topSearchBoxCount >= 1 && topFilterChipCount >= 2)) {
            return ScreenPostureArchetype.SEARCH_RESULTS_LIST
        }
        if (topSearchBoxCount >= 1 && bottomBarCount >= 3 && (cap.contains("categories") || cap.contains("search") || cap.contains("browse"))) {
            return ScreenPostureArchetype.SEARCH_ENTRY_PORTAL
        }
        if (bottomBarCount >= 3 || cap.contains("home screen") || cap.contains("home page") || cap.contains("main feed")) {
            return ScreenPostureArchetype.HOME_FEED_HUB
        }
        if (cap.contains("article") || cap.contains("news story") || cap.contains("details about") ||
            ((roleCounts[Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.NAV_BACK] ?: 0) >= 1 &&
                ((roleCounts[Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.SHARE_EXPORT] ?: 0) >= 1 ||
                    (roleCounts[Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.LIKE_FAVORITE_BOOKMARK] ?: 0) >= 1))
        ) {
            return ScreenPostureArchetype.CONTENT_DETAIL_ARTICLE
        }
        return ScreenPostureArchetype.GENERAL_APP_SCREEN
    }

    /**
     * 在本地電腦上聯合掃描 AMEX 102K 語料庫與 MobileViews 狀態轉移軌跡，
     * 訓練 16 大全域畫面姿勢與狀態轉移機率矩陣，並匯出至 `app/src/main/assets/ui_phase3_screen_posture.json`
     */
    fun trainFromDatasets(
        amexElementAnnoDir: File,
        mobileViewsDir: File? = null,
        maxAmexFiles: Int = 3000,
        maxMobileViewsApps: Int = 120,
        outputAssetFile: File? = null
    ): Boolean {
        if (!amexElementAnnoDir.exists() || !amexElementAnnoDir.isDirectory) return false
        val allAmexFiles = amexElementAnnoDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        if (allAmexFiles.isEmpty()) return false

        val namedFiles = allAmexFiles.filter { !it.name.startsWith("2024_") }
        val datedFiles = allAmexFiles.filter { it.name.startsWith("2024_") }
        val selectedFiles = ArrayList<File>(maxAmexFiles)

        val stepNamed = max(1, namedFiles.size / (maxAmexFiles * 2 / 3).coerceAtLeast(1))
        var idx = 0
        while (idx < namedFiles.size && selectedFiles.size < (maxAmexFiles * 2 / 3)) {
            selectedFiles.add(namedFiles[idx])
            idx += stepNamed
        }
        val stepDated = max(1, datedFiles.size / (maxAmexFiles - selectedFiles.size).coerceAtLeast(1))
        idx = 0
        while (idx < datedFiles.size && selectedFiles.size < maxAmexFiles) {
            selectedFiles.add(datedFiles[idx])
            idx += stepDated
        }

        class PostureAccumulator {
            var count = 0
            var sumInteractive = 0.0
            var sumScrollable = 0.0
            var bottomNavHits = 0
            var topSearchBoxHits = 0
            var topFilterChipHits = 0
            val roleHits = HashMap<String, Int>()
            val keywordHits = HashMap<String, Int>()
        }

        val accumulators = LinkedHashMap<ScreenPostureArchetype, PostureAccumulator>()
        for (p in ScreenPostureArchetype.entries) {
            accumulators[p] = PostureAccumulator()
        }

        var screensScanned = 0
        val wordRegex = Regex("[a-zA-Z\u4e00-\u9fa5]{3,18}")

        for (file in selectedFiles) {
            val rec = runCatching {
                ExternalGuiDatasetBridge.parseAmexElementAnnoJson(file.readText(Charsets.UTF_8))
            }.getOrNull() ?: continue
            if (rec.clickableElements.isEmpty()) continue

            screensScanned++
            val posture = inferGroundTruthPostureFromAmexRecord(rec)
            val acc = accumulators[posture] ?: continue
            acc.count++
            acc.sumInteractive += rec.clickableElements.size.toDouble()
            acc.sumScrollable += rec.scrollableElements.size.toDouble()

            var maxRight = 1080
            var maxBottom = 2340
            for (el in rec.clickableElements) {
                if (el.bounds.right > maxRight) maxRight = el.bounds.right
                if (el.bounds.bottom > maxBottom) maxBottom = el.bounds.bottom
            }
            val sw = maxRight.toFloat().coerceAtLeast(320f)
            val sh = maxBottom.toFloat().coerceAtLeast(480f)

            var bottomPeers = 0
            var hasSearchBox = false
            var filterChipPeers = 0

            for (el in rec.clickableElements) {
                val bw = (el.bounds.right - el.bounds.left).coerceAtLeast(1).toFloat()
                val bh = (el.bounds.bottom - el.bounds.top).coerceAtLeast(1).toFloat()
                val normCx = (((el.bounds.left + el.bounds.right) * 0.5f) / sw).coerceIn(0f, 1f)
                val normCy = (((el.bounds.top + el.bounds.bottom) * 0.5f) / sh).coerceIn(0f, 1f)
                val normW = (bw / sw).coerceIn(0.005f, 1f)
                val normH = (bh / sh).coerceIn(0.005f, 1f)
                val aspect = (bw / bh).coerceIn(0.1f, 15f)

                if (normCy >= 0.85f && normH <= 0.12f) bottomPeers++
                val role = Phase2ButtonFunctionalityClassifier.inferGroundTruthRoleFromAmexAnnotation(
                    xmlDescs = el.xmlDescriptions,
                    functionality = el.functionality,
                    normCx = normCx,
                    normCy = normCy,
                    normW = normW,
                    normH = normH,
                    pixelAspect = aspect
                )
                if (role != Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.GENERIC_ACTION_BUTTON) {
                    acc.roleHits[role.code] = (acc.roleHits[role.code] ?: 0) + 1
                }
                if (role == Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.SEARCH_INPUT_BOX) hasSearchBox = true
                if (role == Phase2ButtonFunctionalityClassifier.ButtonFunctionRole.FILTER_SORT_CHIP) filterChipPeers++
            }

            if (bottomPeers in 3..6) acc.bottomNavHits++
            if (hasSearchBox) acc.topSearchBoxHits++
            if (filterChipPeers >= 2) acc.topFilterChipHits++

            val captionWords = wordRegex.findAll(rec.pageCaption.lowercase()).map { it.value }.toSet()
            for (w in captionWords) {
                if (w in STOPWORDS) continue
                acc.keywordHits[w] = (acc.keywordHits[w] ?: 0) + 1
            }
        }

        trainedAmexScreensCount = screensScanned

        for (p in ScreenPostureArchetype.entries) {
            val acc = accumulators[p] ?: continue
            val n = acc.count
            val meanInter = if (n >= 5) (acc.sumInteractive / n).toFloat() else 12f
            val meanScroll = if (n >= 5) (acc.sumScrollable / n).toFloat() else 1.2f
            val botPrev = if (n >= 5) (acc.bottomNavHits.toFloat() / n.toFloat()).coerceIn(0.02f, 0.98f) else 0.20f
            val searchPrev = if (n >= 5) (acc.topSearchBoxHits.toFloat() / n.toFloat()).coerceIn(0.02f, 0.98f) else 0.20f
            val chipPrev = if (n >= 5) (acc.topFilterChipHits.toFloat() / n.toFloat()).coerceIn(0.02f, 0.98f) else 0.12f

            val mergedRoles = LinkedHashMap<String, Float>()
            seedPostureRoleWeights[p]?.forEach { (r, w) -> mergedRoles[r] = w }
            val topEmpRoles = acc.roleHits.entries.sortedByDescending { it.value }.take(8)
            for ((roleCode, cnt) in topEmpRoles) {
                val empW = (1.0f + ln(1f + cnt.toFloat()) * 0.42f).coerceIn(1.0f, 4.2f)
                mergedRoles[roleCode] = max(mergedRoles[roleCode] ?: 0f, empW)
            }

            val mergedKw = LinkedHashMap<String, Float>()
            seedPostureKeywords[p]?.forEach { (k, w) -> mergedKw[k.lowercase()] = w }
            val topEmpKw = acc.keywordHits.entries.sortedByDescending { it.value }.take(12)
            for ((kw, cnt) in topEmpKw) {
                val empW = (1.1f + ln(1f + cnt.toFloat()) * 0.35f).coerceIn(1.1f, 3.6f)
                mergedKw[kw] = max(mergedKw[kw] ?: 0f, empW)
            }

            postureProfiles[p] = PostureLearnedProfile(
                posture = p,
                sampleCount = n,
                meanInteractiveCount = meanInter,
                meanScrollableCount = meanScroll,
                bottomNavPrevalence = botPrev,
                topSearchBoxPrevalence = searchPrev,
                topFilterChipPrevalence = chipPrev,
                signatureRoleWeights = mergedRoles,
                topKeywords = mergedKw,
                recommendedPrimaryRoles = p.defaultRecommendedRoles
            )
        }

        // 掃描 MobileViews actions.csv 狀態轉移軌跡
        val transitionCounts = HashMap<String, HashMap<String, Int>>()
        var transitionsScanned = 0

        if (mobileViewsDir != null && mobileViewsDir.exists()) {
            val candidateAppDirs = ArrayList<File>()
            val oneExample = File(mobileViewsDir, "OneExample/OneExample")
            if (oneExample.exists()) candidateAppDirs.add(oneExample)
            val extractedRoot = File(mobileViewsDir, "MultiAppTraces/extracted")
            if (extractedRoot.exists()) {
                extractedRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.let { candidateAppDirs.addAll(it) }
            }
            mobileViewsDir.listFiles { f -> f.isDirectory && f.name.startsWith("apk_") }?.forEach { shard ->
                shard.listFiles { f -> f.isDirectory }?.let { candidateAppDirs.addAll(it) }
            }
            val appDirs = candidateAppDirs.take(maxMobileViewsApps)

            val stateStrRegex = Regex("\"state_str\"\\s*:\\s*\"([^\"]+)\"")
            for (appDir in appDirs) {
                val actionsCsv = File(appDir, "actions.csv")
                val statesDir = File(appDir, "states")
                if (!actionsCsv.exists()) continue

                val transitions = runCatching {
                    ExternalGuiDatasetBridge.parseMobileViewsActionsCsv(actionsCsv)
                }.getOrDefault(emptyList())
                if (transitions.isEmpty()) continue

                val statePostureByHash = HashMap<String, ScreenPostureArchetype>()
                if (statesDir.exists()) {
                    val jsonFiles = statesDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().take(30)
                    for (jf in jsonFiles) {
                        val txt = runCatching { jf.readText(Charsets.UTF_8) }.getOrNull() ?: continue
                        val hash = stateStrRegex.find(txt)?.groupValues?.getOrNull(1).orEmpty()
                        if (hash.isNotEmpty()) {
                            statePostureByHash[hash] = inferQuickPostureFromStateJsonText(txt)
                        }
                    }
                }

                for (tr in transitions.take(25)) {
                    if (tr.fromState.isEmpty() || tr.toState.isEmpty() || tr.fromState == tr.toState) continue
                    val fromPosture = statePostureByHash[tr.fromState] ?: ScreenPostureArchetype.HOME_FEED_HUB
                    val toPosture = statePostureByHash[tr.toState] ?: inferPostureFromActionVerb(tr)
                    val actionRole = inferActionRoleFromTransition(tr)
                    val key = "${fromPosture.code}|$actionRole"
                    val destMap = transitionCounts.getOrPut(key) { HashMap() }
                    destMap[toPosture.code] = (destMap[toPosture.code] ?: 0) + 1
                    transitionsScanned++
                }
            }
        }

        trainedMobileViewsTransitionsCount = transitionsScanned
        transitionMatrix.clear()
        transitionMatrix.putAll(defaultTransitionPriors)

        for ((key, destCounts) in transitionCounts) {
            val total = destCounts.values.sum().coerceAtLeast(1).toFloat()
            if (total < 2f) continue
            val empDist = LinkedHashMap<String, Float>()
            val priorDist = defaultTransitionPriors[key]
            for ((dest, cnt) in destCounts.entries.sortedByDescending { it.value }.take(4)) {
                val empP = cnt.toFloat() / total
                val priorP = priorDist?.get(dest) ?: empP
                empDist[dest] = ((empP * 0.6f) + (priorP * 0.4f)).coerceIn(0.05f, 0.98f)
            }
            if (empDist.isNotEmpty()) {
                transitionMatrix[key] = empDist
            }
        }

        if (outputAssetFile != null) {
            outputAssetFile.parentFile?.mkdirs()
            outputAssetFile.writeText(exportToJsonString(), Charsets.UTF_8)
        }
        return true
    }

    private fun inferQuickPostureFromStateJsonText(text: String): ScreenPostureArchetype {
        val lower = text.lowercase()
        if (lower.contains("permission") || (lower.contains("allow") && lower.contains("deny"))) {
            return ScreenPostureArchetype.MODAL_POPUP_PERMISSION_AD
        }
        if (lower.contains("sign in") || lower.contains("log in") || lower.contains("password")) {
            return ScreenPostureArchetype.AUTH_LOGIN_VERIFICATION
        }
        if (lower.contains("add to cart") || lower.contains("buy now")) {
            return ScreenPostureArchetype.ECOMMERCE_PRODUCT_DETAIL
        }
        if (lower.contains("search_src_text") || lower.contains("search_edit_frame")) {
            return ScreenPostureArchetype.SEARCH_ACTIVE_SUGGESTIONS
        }
        if (lower.contains("settings") || lower.contains("preferences")) {
            return ScreenPostureArchetype.SETTINGS_PREFERENCES_LIST
        }
        return ScreenPostureArchetype.HOME_FEED_HUB
    }

    private fun inferPostureFromActionVerb(tr: ExternalGuiDatasetBridge.MobileViewsTransitionRecord): ScreenPostureArchetype {
        val lbl = tr.targetLabel.lowercase()
        if (lbl.contains("search") || lbl.contains("搜尋")) return ScreenPostureArchetype.SEARCH_ACTIVE_SUGGESTIONS
        if (lbl.contains("setting") || lbl.contains("設定")) return ScreenPostureArchetype.SETTINGS_PREFERENCES_LIST
        if (lbl.contains("cart") || lbl.contains("buy")) return ScreenPostureArchetype.CART_CHECKOUT_ORDER
        return ScreenPostureArchetype.CONTENT_DETAIL_ARTICLE
    }

    private fun inferActionRoleFromTransition(tr: ExternalGuiDatasetBridge.MobileViewsTransitionRecord): String {
        val lbl = tr.targetLabel.lowercase()
        if (tr.actionVerb.equals("SCROLL", ignoreCase = true)) return "SCROLL"
        if (tr.actionVerb.equals("KEY", ignoreCase = true) || lbl.contains("back")) return "NAV_BACK"
        if (lbl.contains("search") || lbl.contains("搜尋")) return "SEARCH_ENTRY"
        if (lbl.contains("close") || lbl.contains("dismiss") || lbl.contains("skip") || lbl.contains("allow")) return "CLOSE_DISMISS"
        if (lbl.contains("cart") || lbl.contains("buy")) return "ADD_TO_CART_BUY"
        return "CONTENT_CARD_ITEM"
    }

    /**
     * 即時判斷當前手機畫面的「全域姿勢 (Whole-Screen Posture)」與預期狀態轉移
     */
    fun classifyScreenPosture(
        nodes: List<Stage2ConvergedNode>,
        rawNodes: List<RawVisionNode> = emptyList(),
        screenWidth: Int = 1080,
        screenHeight: Int = 2340
    ): ScreenPostureClassificationResult {
        if (nodes.isEmpty()) {
            return ScreenPostureClassificationResult(
                posture = ScreenPostureArchetype.GENERAL_APP_SCREEN,
                confidence = 0.40f,
                secondaryPosture = null,
                recommendedRoles = ScreenPostureArchetype.GENERAL_APP_SCREEN.defaultRecommendedRoles,
                expectedTransitions = emptyMap()
            )
        }

        val sw = screenWidth.toFloat().coerceAtLeast(320f)
        val sh = screenHeight.toFloat().coerceAtLeast(480f)

        val roleCounts = HashMap<String, Int>()
        var bottomNavPeerCount = 0
        var hasTopSearchBox = false
        var isHomeTabChecked = false
        var isSearchTabChecked = false
        var topFilterChipCount = 0
        var contentCardCount = 0
        var hasBottomBuyButton = false
        var hasBottomRightFab = false
        var minY = sh
        var maxY = 0f

        val fullScreenTextSb = StringBuilder()
        for (n in nodes) {
            val role = n.predictedFunctionRole.ifEmpty {
                Phase2ButtonFunctionalityClassifier.classifyNode(n, nodes, screenWidth, screenHeight).role.code
            }
            if (role.isNotEmpty() && role != "GENERIC_ACTION_BUTTON") {
                roleCounts[role] = (roleCounts[role] ?: 0) + 1
            }

            val normCy = (n.finalTapPx.y.toFloat() / sh).coerceIn(0f, 1f)
            val normCx = (n.finalTapPx.x.toFloat() / sw).coerceIn(0f, 1f)
            val b = n.rawOriginalBounds.takeIf { (it.right - it.left) > 0 && (it.bottom - it.top) > 0 } ?: n.convergedBounds
            val normH = ((b.bottom - b.top).coerceAtLeast(1).toFloat() / sh).coerceIn(0.005f, 1f)
            val isCheckedOrSelected = n.inlineStatePrefix.contains("勾選") || n.inlineStatePrefix.contains("已選")

            if (b.top < minY) minY = b.top.toFloat()
            if (b.bottom > maxY) maxY = b.bottom.toFloat()

            if (normCy >= 0.84f && normH <= 0.12f && n.shortTypeTag != "TXT") {
                bottomNavPeerCount++
                if (isCheckedOrSelected) {
                    if (role == "NAV_HOME_TAB") isHomeTabChecked = true
                    if (role == "SEARCH_ENTRY" || n.label.contains("搜尋") || n.label.contains("Search", ignoreCase = true)) {
                        isSearchTabChecked = true
                    }
                }
            }
            val normW = ((b.right - b.left).coerceAtLeast(1).toFloat() / sw).coerceIn(0.005f, 1f)
            if (role == "SEARCH_INPUT_BOX" && normCy <= 0.25f && (n.shortTypeTag == "INP" || normW >= 0.35f)) {
                hasTopSearchBox = true
            }
            if (role == "FILTER_SORT_CHIP" && normCy <= 0.28f) {
                topFilterChipCount++
            }
            if (role == "CONTENT_CARD_ITEM") {
                contentCardCount++
            }
            if (role == "ADD_TO_CART_BUY" && normCy >= 0.80f) {
                hasBottomBuyButton = true
            }
            if (role == "COMPOSE_CREATE_ADD" && normCx >= 0.72f && normCy in 0.70f..0.92f) {
                hasBottomRightFab = true
            }

            fullScreenTextSb.append(n.inlineStatePrefix).append(' ').append(n.label).append(' ')
        }

        val hasBottomNavBar = bottomNavPeerCount in 3..6
        val ySpanRatio = ((maxY - minY).coerceAtLeast(0f) / sh).coerceIn(0f, 1f)
        val hasModalScrim = rawNodes.any { it.isModalScrim }
        val fullScreenText = fullScreenTextSb.toString().lowercase()

        var bestPosture = ScreenPostureArchetype.GENERAL_APP_SCREEN
        var bestScore = -999f
        var secondPosture: ScreenPostureArchetype? = null
        var secondScore = -999f

        for (posture in ScreenPostureArchetype.entries) {
            if (posture == ScreenPostureArchetype.GENERAL_APP_SCREEN) continue
            val prof = postureProfiles[posture] ?: continue
            var score = 0f

            // 1. 畫面內按鈕功用角色分佈加權
            for ((roleCode, weight) in prof.signatureRoleWeights) {
                val cnt = roleCounts[roleCode] ?: 0
                if (cnt > 0) {
                    val cappedCnt = min(cnt, 3).toFloat()
                    score += weight * (1.0f + (cappedCnt - 1f) * 0.25f)
                }
            }

            // 2. 畫面全域文字與情境關鍵字匹配
            for ((kw, weight) in prof.topKeywords) {
                if (kw.isNotEmpty() && fullScreenText.contains(kw)) {
                    score += weight
                }
            }

            // 3. 空間結構與拓樸不變量加權
            when (posture) {
                ScreenPostureArchetype.HOME_FEED_HUB -> {
                    if (hasBottomNavBar && !hasTopSearchBox && contentCardCount >= 1) score += 5.5f
                    if (hasBottomNavBar && topFilterChipCount >= 2 && !hasTopSearchBox) score += 3.8f
                    if (isHomeTabChecked && !hasTopSearchBox) score += 4.2f
                    if (isSearchTabChecked) score -= 6.5f
                    if (hasTopSearchBox) score -= 4.5f
                }
                ScreenPostureArchetype.SEARCH_ENTRY_PORTAL -> {
                    if (hasTopSearchBox && hasBottomNavBar) score += 6.2f
                    if (isSearchTabChecked && hasBottomNavBar) score += 8.5f
                    if (hasTopSearchBox && (fullScreenText.contains("想聽什麼") || fullScreenText.contains("心情與類型") || fullScreenText.contains("categories"))) {
                        score += 4.5f
                    }
                    if (!hasTopSearchBox && !isSearchTabChecked) score -= 5.0f
                }
                ScreenPostureArchetype.SEARCH_ACTIVE_SUGGESTIONS -> {
                    if (hasTopSearchBox && (roleCounts["CLEAR_INPUT"] ?: 0) >= 1 && !hasBottomNavBar) {
                        score += 5.5f
                    }
                    if (!hasTopSearchBox) score -= 5.0f
                }
                ScreenPostureArchetype.SEARCH_RESULTS_LIST -> {
                    if (hasTopSearchBox && topFilterChipCount >= 2 && contentCardCount >= 2 && !hasBottomNavBar) {
                        score += 5.8f
                    } else if (topFilterChipCount >= 2 && contentCardCount >= 3 && !hasBottomNavBar) {
                        score += 3.5f
                    }
                }
                ScreenPostureArchetype.MEDIA_PLAYER_VIEW -> {
                    if ((roleCounts["MEDIA_PLAY_PAUSE"] ?: 0) >= 1 && (roleCounts["MEDIA_NEXT_PREV_CONTROL"] ?: 0) >= 1) {
                        score += 6.5f
                    }
                }
                ScreenPostureArchetype.ECOMMERCE_PRODUCT_DETAIL -> {
                    if (hasBottomBuyButton || (roleCounts["ADD_TO_CART_BUY"] ?: 0) >= 1) {
                        score += 5.8f
                    }
                }
                ScreenPostureArchetype.MESSAGING_INBOX_LIST -> {
                    if (hasBottomRightFab && contentCardCount >= 2) {
                        score += 5.2f
                    }
                }
                ScreenPostureArchetype.MAP_NAVIGATION_EXPLORE -> {
                    if (fullScreenText.contains("around you") || (fullScreenText.contains(" mi") && contentCardCount >= 2)) {
                        score += 5.5f
                    }
                }
                ScreenPostureArchetype.MODAL_POPUP_PERMISSION_AD -> {
                    if ((hasModalScrim || (nodes.size in 1..6 && ySpanRatio <= 0.65f)) && (roleCounts["CLOSE_DISMISS"] ?: 0) >= 1) {
                        score += 6.0f
                    }
                }
                else -> {}
            }

            if (score > bestScore) {
                secondScore = bestScore
                secondPosture = bestPosture
                bestScore = score
                bestPosture = posture
            } else if (score > secondScore) {
                secondScore = score
                secondPosture = posture
            }
        }

        if (bestScore < 3.2f) {
            return ScreenPostureClassificationResult(
                posture = ScreenPostureArchetype.GENERAL_APP_SCREEN,
                confidence = 0.50f,
                secondaryPosture = null,
                recommendedRoles = ScreenPostureArchetype.GENERAL_APP_SCREEN.defaultRecommendedRoles,
                expectedTransitions = emptyMap()
            )
        }

        val conf = (0.60f + (bestScore - 3.2f) * 0.04f).coerceIn(0.60f, 0.99f)
        val expectedTrans = LinkedHashMap<String, String>()
        for (roleCode in bestPosture.defaultRecommendedRoles) {
            val key = "${bestPosture.code}|$roleCode"
            val topDest = transitionMatrix[key]?.entries?.maxByOrNull { it.value }?.key
            if (topDest != null) {
                expectedTrans[roleCode] = topDest
            }
        }

        return ScreenPostureClassificationResult(
            posture = bestPosture,
            confidence = conf,
            secondaryPosture = secondPosture?.takeIf { secondScore >= 3.0f },
            recommendedRoles = postureProfiles[bestPosture]?.recommendedPrimaryRoles
                ?: bestPosture.defaultRecommendedRoles,
            expectedTransitions = expectedTrans
        )
    }

    /**
     * 供 `InAppOperationPlanner.scoreCandidate` 呼叫的全域畫面姿勢戰術加權
     */
    fun computePostureTacticalBonus(
        screenPostureCode: String,
        candidateNode: Stage2ConvergedNode,
        hasExplicitSearchIntent: Boolean,
        hasExplicitCloseIntent: Boolean,
        hasExplicitBuyIntent: Boolean,
        hasExplicitPlayIntent: Boolean
    ): Pair<Float, String?> {
        val posture = ScreenPostureArchetype.fromCode(screenPostureCode)
        val role = candidateNode.predictedFunctionRole
        if (role.isEmpty()) return 0f to null

        when (posture) {
            ScreenPostureArchetype.HOME_FEED_HUB -> {
                if (hasExplicitSearchIntent) {
                    if (role == "SEARCH_INPUT_BOX") return 42f to "Phase3首頁姿勢直接命中搜尋輸入框"
                    if (role == "SEARCH_ENTRY") return 38f to "Phase3首頁姿勢導引點擊搜尋入口(HOME->SEARCH_PORTAL)"
                    if (role == "CONTENT_CARD_ITEM") return -18f to "Phase3首頁姿勢搜尋意圖避開一般動態卡片"
                } else {
                    if (role == "CONTENT_CARD_ITEM") return 18f to "Phase3首頁姿勢優先瀏覽內容卡片"
                }
            }
            ScreenPostureArchetype.SEARCH_ENTRY_PORTAL -> {
                if (hasExplicitSearchIntent) {
                    if (role == "SEARCH_INPUT_BOX") return 48f to "Phase3搜尋入口頁精準鎖定頂部搜尋輸入框"
                    if (role == "CONTENT_CARD_ITEM") return -24f to "Phase3搜尋入口頁避免誤點下方分類卡片"
                }
            }
            ScreenPostureArchetype.SEARCH_RESULTS_LIST -> {
                if (!hasExplicitSearchIntent || hasExplicitPlayIntent) {
                    if (role == "CONTENT_CARD_ITEM" || role == "MEDIA_PLAY_PAUSE") {
                        return 26f to "Phase3搜尋結果頁優先選擇結果項目"
                    }
                    if (role == "SEARCH_INPUT_BOX" || role == "CLEAR_INPUT") {
                        return -32f to "Phase3搜尋結果頁禁止重複點擊頂部搜尋框或清除鈕"
                    }
                }
            }
            ScreenPostureArchetype.MODAL_POPUP_PERMISSION_AD -> {
                if (role == "CLOSE_DISMISS") {
                    return (if (hasExplicitCloseIntent) 50f else 28f) to "Phase3彈窗姿勢優先解除遮擋彈窗"
                }
            }
            ScreenPostureArchetype.ECOMMERCE_PRODUCT_DETAIL -> {
                if (hasExplicitBuyIntent && role == "ADD_TO_CART_BUY") {
                    return 45f to "Phase3商品詳情頁精準鎖定購買/加入購物車鈕"
                }
            }
            ScreenPostureArchetype.MEDIA_PLAYER_VIEW -> {
                if (hasExplicitPlayIntent && role == "MEDIA_PLAY_PAUSE") {
                    return 42f to "Phase3播放器姿勢精準鎖定播放/暫停控制項"
                }
            }
            else -> {}
        }
        return 0f to null
    }

    /**
     * 匯出為輕量離線 JSON 字串（供存入 `app/src/main/assets/ui_phase3_screen_posture.json`）
     */
    fun exportToJsonString(): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"schema_version\": 1,")
        sb.appendLine("  \"phase\": \"Phase 3: Whole-Screen Posture Archetypes & State Transition Knowledge Base\",")
        sb.appendLine("  \"trained_corpus\": \"AMEX 102K Screen Captions & Layouts + MobileViews UTG State Transitions\",")
        sb.appendLine("  \"trained_amex_screens_count\": $trainedAmexScreensCount,")
        sb.appendLine("  \"trained_mobileviews_transitions_count\": $trainedMobileViewsTransitionsCount,")
        sb.appendLine("  \"postures_count\": ${ScreenPostureArchetype.entries.size},")
        sb.appendLine("  \"postures\": {")

        val entries = ScreenPostureArchetype.entries
        for ((idx, posture) in entries.withIndex()) {
            val prof = postureProfiles[posture] ?: continue
            val comma = if (idx < entries.size - 1) "," else ""
            sb.appendLine("    \"${posture.code}\": {")
            sb.appendLine("      \"display_name_zh\": \"${escapeJson(posture.displayNameZh)}\",")
            sb.appendLine("      \"sample_count\": ${prof.sampleCount},")
            sb.appendLine("      \"mean_interactive_count\": ${round3(prof.meanInteractiveCount)},")
            sb.appendLine("      \"mean_scrollable_count\": ${round3(prof.meanScrollableCount)},")
            sb.appendLine("      \"bottom_nav_prevalence\": ${round3(prof.bottomNavPrevalence)},")
            sb.appendLine("      \"top_search_box_prevalence\": ${round3(prof.topSearchBoxPrevalence)},")
            sb.appendLine("      \"top_filter_chip_prevalence\": ${round3(prof.topFilterChipPrevalence)},")

            sb.append("      \"signature_role_weights\": {")
            val roleEntries = prof.signatureRoleWeights.entries.take(10)
            for ((rIdx, rEntry) in roleEntries.withIndex()) {
                val rComma = if (rIdx < roleEntries.size - 1) ", " else ""
                sb.append("\"${escapeJson(rEntry.key)}\": ${round3(rEntry.value)}$rComma")
            }
            sb.appendLine("},")

            sb.append("      \"top_keywords\": {")
            val kwEntries = prof.topKeywords.entries.take(18)
            for ((kIdx, kEntry) in kwEntries.withIndex()) {
                val kComma = if (kIdx < kwEntries.size - 1) ", " else ""
                sb.append("\"${escapeJson(kEntry.key)}\": ${round3(kEntry.value)}$kComma")
            }
            sb.appendLine("},")

            sb.append("      \"recommended_primary_roles\": [")
            prof.recommendedPrimaryRoles.forEachIndexed { pIdx, roleCode ->
                val pComma = if (pIdx < prof.recommendedPrimaryRoles.size - 1) ", " else ""
                sb.append("\"${escapeJson(roleCode)}\"$pComma")
            }
            sb.appendLine("]")
            sb.appendLine("    }$comma")
        }
        sb.appendLine("  },")

        sb.appendLine("  \"transition_matrix\": {")
        val trEntries = transitionMatrix.entries.toList()
        for ((tIdx, trEntry) in trEntries.withIndex()) {
            val tComma = if (tIdx < trEntries.size - 1) "," else ""
            sb.append("    \"${escapeJson(trEntry.key)}\": {")
            val destEntries = trEntry.value.entries.toList()
            for ((dIdx, dEntry) in destEntries.withIndex()) {
                val dComma = if (dIdx < destEntries.size - 1) ", " else ""
                sb.append("\"${escapeJson(dEntry.key)}\": ${round3(dEntry.value)}$dComma")
            }
            sb.appendLine("}$tComma")
        }
        sb.appendLine("  }")
        sb.appendLine("}")
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun loadFromJsonString(jsonContent: String): Boolean {
        return runCatching {
            val root = ExternalGuiDatasetBridge.MiniJsonParser.parseObject(jsonContent)
            trainedAmexScreensCount = (root["trained_amex_screens_count"] as? Number)?.toInt() ?: trainedAmexScreensCount
            trainedMobileViewsTransitionsCount = (root["trained_mobileviews_transitions_count"] as? Number)?.toInt()
                ?: trainedMobileViewsTransitionsCount

            val posturesObj = root["postures"] as? Map<String, Any?> ?: return false
            for ((code, rawVal) in posturesObj) {
                val posture = ScreenPostureArchetype.fromCode(code)
                val pMap = rawVal as? Map<String, Any?> ?: continue
                val existing = postureProfiles[posture] ?: continue

                val sampleCount = (pMap["sample_count"] as? Number)?.toInt() ?: existing.sampleCount
                val meanInter = (pMap["mean_interactive_count"] as? Number)?.toFloat() ?: existing.meanInteractiveCount
                val meanScroll = (pMap["mean_scrollable_count"] as? Number)?.toFloat() ?: existing.meanScrollableCount
                val botPrev = (pMap["bottom_nav_prevalence"] as? Number)?.toFloat() ?: existing.bottomNavPrevalence
                val searchPrev = (pMap["top_search_box_prevalence"] as? Number)?.toFloat() ?: existing.topSearchBoxPrevalence
                val chipPrev = (pMap["top_filter_chip_prevalence"] as? Number)?.toFloat() ?: existing.topFilterChipPrevalence

                val roleMap = LinkedHashMap<String, Float>()
                seedPostureRoleWeights[posture]?.forEach { (k, v) -> roleMap[k] = v }
                val rawRoles = pMap["signature_role_weights"] as? Map<String, Any?>
                if (rawRoles != null) {
                    for ((rk, rv) in rawRoles) {
                        val w = (rv as? Number)?.toFloat() ?: continue
                        roleMap[rk] = max(roleMap[rk] ?: 0f, w)
                    }
                }

                val kwMap = LinkedHashMap<String, Float>()
                seedPostureKeywords[posture]?.forEach { (k, v) -> kwMap[k.lowercase()] = v }
                val rawKw = pMap["top_keywords"] as? Map<String, Any?>
                if (rawKw != null) {
                    for ((kk, kv) in rawKw) {
                        val w = (kv as? Number)?.toFloat() ?: continue
                        kwMap[kk.lowercase()] = max(kwMap[kk.lowercase()] ?: 0f, w)
                    }
                }

                val recRoles = (pMap["recommended_primary_roles"] as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.takeIf { it.isNotEmpty() }
                    ?: existing.recommendedPrimaryRoles

                postureProfiles[posture] = PostureLearnedProfile(
                    posture = posture,
                    sampleCount = sampleCount,
                    meanInteractiveCount = meanInter,
                    meanScrollableCount = meanScroll,
                    bottomNavPrevalence = botPrev,
                    topSearchBoxPrevalence = searchPrev,
                    topFilterChipPrevalence = chipPrev,
                    signatureRoleWeights = roleMap,
                    topKeywords = kwMap,
                    recommendedPrimaryRoles = recRoles
                )
            }

            val rawTrans = root["transition_matrix"] as? Map<String, Any?>
            if (rawTrans != null) {
                for ((tKey, tVal) in rawTrans) {
                    val destObj = tVal as? Map<String, Any?> ?: continue
                    val destMap = LinkedHashMap<String, Float>()
                    for ((dk, dv) in destObj) {
                        val p = (dv as? Number)?.toFloat() ?: continue
                        destMap[dk] = p
                    }
                    if (destMap.isNotEmpty()) {
                        transitionMatrix[tKey] = destMap
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    fun loadFromAssetStream(inputStream: InputStream): Boolean {
        return runCatching {
            val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            loadFromJsonString(content)
        }.getOrDefault(false)
    }

    fun loadFromAssets(context: android.content.Context): Boolean {
        return runCatching {
            context.assets.open("ui_phase3_screen_posture.json").use { stream ->
                loadFromAssetStream(stream)
            }
        }.getOrDefault(false)
    }

    fun getTrainedAmexScreensCount(): Int = trainedAmexScreensCount
    fun getTrainedMobileViewsTransitionsCount(): Int = trainedMobileViewsTransitionsCount
    fun getProfile(posture: ScreenPostureArchetype): PostureLearnedProfile? = postureProfiles[posture]
    fun getTransitionProbability(fromPosture: ScreenPostureArchetype, actionRole: String, toPosture: ScreenPostureArchetype): Float {
        return transitionMatrix["${fromPosture.code}|$actionRole"]?.get(toPosture.code) ?: 0f
    }

    data class MctsPriorPruningResult(
        val priorPolicy: FloatArray,
        val prunedLegalMask: ByteArray,
        val prunedBranchCount: Int,
        val currentPosture: ScreenPostureArchetype,
        val targetPosture: ScreenPostureArchetype
    )

    /**
     * 以內建的 Phase 1~3 先驗知識庫（UI 狀態轉移機率矩陣 + 幾何功能分類 + 免疫權重）
     * 直接作為 MCTS 的先驗策略 P(s, a)，並對低轉移機率分支進行搜尋樹剪枝（Pruning）。
     */
    fun computeMctsPriorPolicyAndPruneMask(
        currentPostureCode: String,
        intentCategory: String,
        targetSemanticPhrase: String,
        candidateRoles: List<String>,
        candidateBaseScores: FloatArray,
        baseLegalMask: ByteArray,
        pruneThreshold: Float = 0.08f
    ): MctsPriorPruningResult {
        val n = candidateRoles.size
        if (n == 0) {
            return MctsPriorPruningResult(
                priorPolicy = FloatArray(0),
                prunedLegalMask = ByteArray(0),
                prunedBranchCount = 0,
                currentPosture = ScreenPostureArchetype.HOME_FEED_HUB,
                targetPosture = ScreenPostureArchetype.SEARCH_RESULTS_LIST
            )
        }

        val fromPosture = ScreenPostureArchetype.fromCode(currentPostureCode)

        val lowerPhrase = targetSemanticPhrase.lowercase()
        val targetPosture = when {
            intentCategory == "INPUT_SEARCH" || lowerPhrase.contains("搜尋") || lowerPhrase.contains("搜索") -> {
                if (fromPosture == ScreenPostureArchetype.HOME_FEED_HUB || fromPosture == ScreenPostureArchetype.SEARCH_ENTRY_PORTAL) {
                    ScreenPostureArchetype.SEARCH_ACTIVE_SUGGESTIONS
                } else {
                    ScreenPostureArchetype.SEARCH_RESULTS_LIST
                }
            }
            lowerPhrase.contains("撰寫") || lowerPhrase.contains("新增筆記") || lowerPhrase.contains("傳給") ->
                ScreenPostureArchetype.CHAT_OR_COMPOSE_EDITOR
            lowerPhrase.contains("播放") || lowerPhrase.contains("聽") ->
                ScreenPostureArchetype.MEDIA_PLAYER_VIEW
            lowerPhrase.contains("設定") || lowerPhrase.contains("藍牙") || lowerPhrase.contains("wifi") ->
                ScreenPostureArchetype.SETTINGS_PREFERENCES_LIST
            fromPosture == ScreenPostureArchetype.MODAL_POPUP_PERMISSION_AD ->
                ScreenPostureArchetype.HOME_FEED_HUB
            else -> ScreenPostureArchetype.CONTENT_DETAIL_ARTICLE
        }

        val priors = FloatArray(n)
        val outMask = baseLegalMask.copyOf()
        val profile = postureProfiles[fromPosture]
        var maxPrior = 0f

        for (i in 0 until n) {
            if (i < outMask.size && outMask[i].toInt() == 0) {
                priors[i] = 0f
                continue
            }
            val role = candidateRoles[i].ifBlank { "CONTENT_CARD_ITEM" }
            val transProb = getTransitionProbability(fromPosture, role, targetPosture)
            val anyOutTrans = transitionMatrix["${fromPosture.code}|$role"]?.values?.maxOrNull() ?: 0.15f
            val roleSigWeight = ((profile?.signatureRoleWeights?.get(role) ?: 1.0f) / 5.0f).coerceIn(0.10f, 1.0f)
            val isRecommended = if (profile?.recommendedPrimaryRoles?.contains(role) == true) 0.20f else 0f
            val baseScore = if (i < candidateBaseScores.size) candidateBaseScores[i].coerceIn(0.05f, 0.99f) else 0.25f

            val effectiveTrans = if (transProb > 0f) transProb else (anyOutTrans * 0.5f)
            val p = (0.45f * baseScore + 0.35f * effectiveTrans + 0.20f * roleSigWeight + isRecommended).coerceIn(0.01f, 0.99f)
            priors[i] = p
            if (p > maxPrior) maxPrior = p
        }

        // 搜尋樹剪枝（Pruning）：剪除遠低於最高先驗或低於剪枝門檻的無效分支（至少保留 1 個合法分支）
        var legalRemaining = outMask.count { it.toInt() != 0 }
        var prunedCount = 0
        val relativeCutoff = max(pruneThreshold, maxPrior * 0.28f)
        for (i in 0 until n) {
            if (i < outMask.size && outMask[i].toInt() != 0 && legalRemaining > 1) {
                if (priors[i] < relativeCutoff) {
                    outMask[i] = 0
                    priors[i] = 0f
                    legalRemaining--
                    prunedCount++
                }
            }
        }

        var sum = 0f
        for (i in 0 until n) {
            if (i < outMask.size && outMask[i].toInt() != 0) sum += priors[i]
        }
        if (sum > 1e-6f) {
            for (i in 0 until n) {
                priors[i] = if (i < outMask.size && outMask[i].toInt() != 0) priors[i] / sum else 0f
            }
        }

        return MctsPriorPruningResult(
            priorPolicy = priors,
            prunedLegalMask = outMask,
            prunedBranchCount = prunedCount,
            currentPosture = fromPosture,
            targetPosture = targetPosture
        )
    }

    private fun round3(v: Float): String {
        val scaled = kotlin.math.round(v * 1000f) / 1000f
        return scaled.toString()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
