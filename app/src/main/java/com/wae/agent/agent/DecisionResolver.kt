package com.wae.agent.agent

/** 元素打分器（M0：簡易文字相似度；M2：cross-encoder reranker）。 */
interface ElementScorer {
    fun score(target: String, element: ObservedElement, observation: Observation): Float
}

data class ResolverConfig(
    val autoActThreshold: Float = 0.55f,
    val askOrReplanThreshold: Float = 0.25f,
    val shortlist: Int = 20,
    val maxScrolls: Int = 2
)

/**
 * 決策層：把「這一步要做什麼」＋「當前畫面」變成一個通用動作。
 * 不做任何 App 專屬判斷；只看元素文字/可點性與分數。
 */
class DecisionResolver(
    private val scorer: ElementScorer,
    private val config: ResolverConfig = ResolverConfig()
) {
    private var scrollCount = 0

    fun resetScrollCounter() {
        scrollCount = 0
    }

    fun decide(step: AgentStep, obs: Observation, vars: Map<String, String> = emptyMap()): AgentDecision {
        // 1) 需要反問的步驟
        if (!step.ask.isNullOrBlank()) {
            val question = step.ask
            return AgentDecision(
                action = AgentAction.ASK_USER,
                confidence = 0.9f,
                reason = "step asks user",
                question = question,
                options = step.options
            )
        }

        var target = step.target?.trim().orEmpty()
        // 「返回／上一頁」這種步驟沒有 target，但一定要先於「no target → DONE」處理。
        if (target.isEmpty() &&
            (step.doText.contains("返回") || step.doText.contains("上一頁") || step.doText.contains("回上一層"))
        ) {
            return AgentDecision(
                action = AgentAction.BACK,
                confidence = 0.85f,
                reason = "back step"
            )
        }
        // 變數替換（例如 $choice → 使用者剛選的答案）
        if (target.startsWith("$")) {
            val key = target.removePrefix("$")
            vars[key]?.let { target = it }
        }
        if (target.isEmpty()) {
            return AgentDecision(action = AgentAction.DONE, confidence = 0.6f, reason = "no target")
        }

        // 2b) $role:<角色>：CMGUI 訓練出來的通用計畫用角色而不是文字（見 ui_role_keywords.tsv）。
        if (target.startsWith("\$role:")) {
            val role = target.removePrefix("\$role:").trim().lowercase()
            val hit = com.wae.agent.core.app.RoleResolver.resolve(role, obs.elements)
            if (hit != null) {
                return AgentDecision(
                    action = AgentAction.TAP,
                    index = hit,
                    confidence = 0.7f,
                    reason = "role:$role"
                )
            }
            if (com.wae.agent.core.app.RoleResolver.isContentFallback(role)) {
                val content = pickContentCard(obs)
                if (content != null) {
                    return AgentDecision(
                        action = AgentAction.TAP,
                        index = content.index,
                        confidence = 0.6f,
                        reason = "role:$role → content card"
                    )
                }
            }
            return AgentDecision(
                action = AgentAction.SCROLL,
                direction = "down",
                confidence = 0.4f,
                reason = "role:$role not found, scroll"
            )
        }

        // 2a) $any_content：使用者只說「找一部劇/挑一個」，沒有具體名稱 →
        //     選畫面上「文字較長、可點、非導航列」的內容卡片（通用，不依賴任何 App）。
        if (target.startsWith("\$any")) {
            val content = pickContentCard(obs)
            if (content != null) {
                return AgentDecision(
                    action = AgentAction.TAP,
                    index = content.index,
                    confidence = 0.6f,
                    reason = "generic content pick"
                )
            }
            return AgentDecision(
                action = AgentAction.SCROLL,
                direction = "down",
                confidence = 0.45f,
                reason = "no content card visible, scroll"
            )
        }

        // 2) 從可見元素找最適合的目標
        val candidates = obs.elements.asSequence()
            .filter { it.visible }
            .map { e -> e to scorer.score(target, e, obs) }
            .sortedByDescending { it.second }
            .take(config.shortlist)
            .toList()

        // 精確/包含比對：直接視為高信心（避免 MiniLM 分數把正確元素排太低）
        val containsHit = candidates.firstOrNull { (e, _) ->
            e.text == target || e.text.contains(target) || target.contains(e.text)
        }
        val best = containsHit ?: candidates.firstOrNull()
        val bestScore = if (containsHit != null) 1f else (best?.second ?: 0f)
        if (containsHit == null && scrollCount < config.maxScrolls && canScroll(obs)) {
            scrollCount += 1
            return AgentDecision(
                action = AgentAction.SCROLL,
                direction = "down",
                confidence = 0.45f,
                reason = "no element contains target, scroll to find"
            )
        }
        if (best != null && bestScore >= config.autoActThreshold) {
            val element = best.first
            return if (element.editable) {
                AgentDecision(
                    action = AgentAction.TYPE,
                    index = element.index,
                    text = target,
                    confidence = bestScore,
                    reason = "editable target"
                )
            } else {
                AgentDecision(
                    action = AgentAction.TAP,
                    index = element.index,
                    confidence = bestScore,
                    reason = "best text match"
                )
            }
        }

        // 2.4) 步驟是「用 package 開啟某個 App」→ 直接用 package，不必再靠 label 解析。
        //      （模板計畫優先用這條：label → package 的解析在實機上會失敗，例如名稱有版本後綴。）
        if (target.startsWith("\$pkg:")) {
            val pkg = target.removePrefix("\$pkg:").trim()
            if (pkg.isNotEmpty()) {
                return AgentDecision(
                    action = AgentAction.OPEN_APP,
                    appPackage = pkg,
                    confidence = 0.8f,
                    reason = "open package"
                )
            }
        }

        // 2.5) 步驟是「開啟某個 App」→ 直接 OPEN_APP（不依賴畫面元素）
        val openVerb = step.doText.contains("開啟") || step.doText.contains("打開") ||
            step.doText.contains("啟動") || step.doText.contains("開")
        if (openVerb && (target == "\$app" || target.startsWith("\$app") ||
                best == null || best.second < config.autoActThreshold)) {
            val appName = target
                .removePrefix("\$app")
                .trimStart(':', '：', ' ')
                .removePrefix("$")
                .replace("App", "")
                .replace("應用程式", "")
                .trim()
                .ifBlank { step.doText }
            return AgentDecision(
                action = AgentAction.OPEN_APP,
                appName = appName,
                confidence = 0.7f,
                reason = "open app step"
            )
        }

        // 2c) 開屏廣告／權限彈窗：目標還找不到時，先按「略過/跳過/稍後」再繼續。
        //     只認明確的略過字樣（不含「取消/關閉」，避免把正常頁面關掉），且每次任務只用一次。
        if (scrollCount == 0) {
            val dismissWords = listOf("略過", "略过", "跳过", "跳過", "稍後", "稍后", "关闭广告", "關閉廣告")
            val dismiss = obs.elements.firstOrNull { e ->
                e.visible && e.clickable && dismissWords.any { w -> e.text.contains(w) }
            }
            if (dismiss != null) {
                return AgentDecision(
                    action = AgentAction.TAP,
                    index = dismiss.index,
                    confidence = 0.55f,
                    reason = "dismiss popup"
                )
            }
        }

        // 3) 沒有明顯目標 → 先捲動找，再問/重規劃
        if (scrollCount < config.maxScrolls && canScroll(obs)) {
            scrollCount += 1
            return AgentDecision(
                action = AgentAction.SCROLL,
                direction = "down",
                confidence = 0.5f,
                reason = "target not visible, scroll to search"
            )
        }

        val topScore = best?.second ?: 0f
        return if (topScore >= config.askOrReplanThreshold) {
            AgentDecision(
                action = AgentAction.TAP,
                index = best!!.first.index,
                confidence = topScore,
                reason = "low-confidence best match"
            )
        } else {
            AgentDecision(
                action = AgentAction.ASK_USER,
                confidence = 0.3f,
                reason = "cannot locate target",
                question = "我找不到「" + target + "」，可以描述它在畫面的哪裡嗎？"
            )
        }
    }

    private fun canScroll(obs: Observation): Boolean = obs.scrollBottom < 0.98f

    /**
     * 通用「內容卡片」挑選：文字較長、可點、不是導航列的字。
     * 給 `$any_content` 與 `$role:first_result/next/list` 的 fallback 共用。
     */
    private fun pickContentCard(obs: Observation): ObservedElement? {
        val navWords = listOf(
            "首頁", "首页", "推薦", "推荐", "訂閱", "订阅", "我的",
            "搜尋", "搜索", "播放", "會員", "设置", "設定", "返回", "更多"
        )
        val clickable = obs.elements.asSequence()
            .filter { it.visible && it.clickable && !it.editable }
            .filter { e -> navWords.none { e.text == it || e.text.startsWith(it) } }
            .toList()
        if (clickable.isEmpty()) return null
        // 第一層：文字較長的內容卡（原本的規則）
        clickable.filter { it.text.length >= 6 }.maxByOrNull { it.text.length }?.let { return it }
        // 第二層：有文字但較短（例如只有標題兩個字）
        clickable.firstOrNull { it.text.isNotBlank() }?.let { return it }
        // 第三層：沒有任何文字（純圖片卡）→ 取第一個可點元素
        return clickable.firstOrNull()
    }
}
