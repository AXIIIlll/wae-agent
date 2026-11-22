package com.wae.agent.core.app

import com.wae.agent.agent.AgentPlan
import com.wae.agent.agent.AgentStep

/**
 * 開放式意圖的**決定性計畫模板**。
 *
 * 為什麼不交給 LLM：
 * 微調後的 planner 在「看劇」這種句子會退化成「到設定查看劇」（訓練資料以設定查詢為主，
 * 實測見 docs/WAE_Planner_SFT.md）。這一類意圖的形狀其實固定——開 App → 找內容 → 播放——
 * 用模板比用 0.6B 生成可靠得多，也快得多。
 *
 * LLM 只留給模板接不住的長尾意圖。
 *
 * `verify` 的選字刻意用「畫面上真的會出現的字」：
 *  - 內容頁：會出現「播放」按鈕
 *  - 播放中：會出現「暫停」按鈕
 */
object OpenEndedPlanner {

    enum class Kind { VIDEO, MUSIC, MAPS, FOOD, SHOPPING }

    fun kindFor(categories: List<String>): Kind? {
        val cats = categories.map { it.uppercase() }.toSet()
        return when {
            cats.contains("VIDEO_MEDIA") || cats.contains("SHORT_DRAMA") || cats.contains("GALLERY") -> Kind.VIDEO
            cats.contains("MUSIC_MEDIA") -> Kind.MUSIC
            cats.contains("MAPS_NAV") -> Kind.MAPS
            cats.contains("FOOD_DELIVERY") -> Kind.FOOD
            cats.contains("SHOPPING") -> Kind.SHOPPING
            else -> null
        }
    }

    /**
     * 產生計畫。
     *
     * @param slots 從 `FuzzyIntent` 抽出的槽位（destination/query/food…）；
     *              maps/food/web 會把值當成 TYPE 的 payload，缺值時退回通用搜尋。
     */
    fun plan(
        goal: String,
        appLabel: String,
        categories: List<String>,
        slots: Map<String, String> = emptyMap(),
        appPkg: String = ""
    ): AgentPlan? {
        if (appLabel.isBlank()) return null
        return when (kindFor(categories)) {
            Kind.VIDEO -> videoPlan(appLabel, appPkg)
            Kind.MUSIC -> musicPlan(appLabel, appPkg)
            Kind.MAPS -> searchPlan(appLabel, "目的地", "導航", slots["destination"].orEmpty(), appPkg)
            Kind.FOOD -> searchPlan(appLabel, "餐點", "菜單", slots["food"].orEmpty(), appPkg)
            Kind.SHOPPING -> searchPlan(appLabel, "商品", "商品", slots["query"].orEmpty(), appPkg)
            null -> null
        }
    }

    /** 開 App 的步驟：有 package 就用 `$pkg:`（不靠 label 解析），沒有才退回 `$app:<label>`。 */
    private fun openStep(appLabel: String, appPkg: String): AgentStep =
        step(
            1,
            "開啟$appLabel",
            if (appPkg.isNotBlank()) "\$pkg:$appPkg" else "\$app:$appLabel",
            appLabel
        )

    private fun plan(summary: String, steps: List<AgentStep>) = AgentPlan(
        version = 1,
        goalKind = "open_ended",
        summary = summary,
        steps = steps
    )

    private fun step(id: Int, doText: String, target: String?, verify: String, options: List<String> = emptyList()) =
        AgentStep(
            id = id,
            doText = doText,
            target = target,
            verify = verify,
            ask = null,
            options = options,
            optional = false
        )

    /** 看劇：開 App → 選第一個結果 → 按播放 → 看到「暫停」或音訊在播才算真的在播。 */
    fun videoPlan(appLabel: String, appPkg: String = ""): AgentPlan = plan(
        "開啟$appLabel 並開始播放",
        listOf(
            openStep(appLabel, appPkg),
            // 第 2 步只要「畫面有變化」就算過：內容頁不一定有「播放」這兩個字（常常只有圖示），
            // 用文字當條件會把成功誤判成失敗。
            step(2, "挑一部推薦內容", "\$role:first_result", ""),
            step(3, "按下播放", "\$role:play", "暫停")
        )
    )

    /** 聽歌：開 App → 直接播放（音樂 App 首頁通常就有播放鍵）。 */
    fun musicPlan(appLabel: String, appPkg: String = ""): AgentPlan = plan(
        "開啟$appLabel 並播放音樂",
        listOf(
            openStep(appLabel, appPkg),
            step(2, "按下播放", "\$role:play", "暫停")
        )
    )

    /**
     * 導航／外送／購物：開 App → 點輸入框 → 輸入（有槽位就用真的值）→ 選第一個結果。
     *
     * @param payload 從指令抽出的值（例如「士林夜市」）；空白時改用通用搜尋字。
     */
    fun searchPlan(
        appLabel: String,
        targetWord: String,
        verifyWord: String,
        payload: String = "",
        appPkg: String = ""
    ): AgentPlan {
        val value = payload.ifBlank { targetWord }
        return plan(
            "開啟$appLabel 並搜尋$value",
            listOf(
                openStep(appLabel, appPkg),
                step(2, "點搜尋框", "\$role:input", "搜尋"),
                step(3, "輸入$value", value, "輸入框"),
                step(4, "選第一個結果", "\$role:first_result", verifyWord)
            )
        )
    }
}
