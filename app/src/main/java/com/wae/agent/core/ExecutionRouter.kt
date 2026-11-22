package com.wae.agent.core

import android.content.Context
import com.wae.agent.core.runtime.RuntimeFlags

/**
 * 執行路由（S61）：**用規則決定**要走快速管線還是 LLM Agent。
 *
 * 不寫死任何詞表；規則是：
 *  1. 先用既有的指令拆解器把句子拆成 LocalGoal（它本身就是資料驅動的通用解析器）。
 *  2. 拆得出「結構完整」的目標（有解析到 App、或輸入目標帶 App 與內容）→ 快速管線。
 *  3. 拆不出完整目標（只有畫面文字點擊、或完全沒有目標）→ 交給 LLM Agent。
 *  4. Agent 未開啟時 → 沿用舊行為（快速管線），不要讓純點擊指令因開關而失效。
 *
 * 這樣「打開設定」「打開 YouTube 搜尋貓咪」走快速路徑；
 * 「我想看劇」「幫我找一部電影」拆不出完整目標 → Agent 依畫面自行規劃。
 */
object ExecutionRouter {

    enum class Route { FAST, AGENT, ASK }

    fun route(context: Context, text: String): Route {
        val t = text.trim()
        if (t.isEmpty()) return Route.ASK

        // 規則 0（述詞）：只要能把這句話編譯成合法述詞，就走快速管線。
        // 判準是「述詞是否 well-formed」，不是「有沒有命中句型」——這樣沒列舉過的說法也能走快路徑。
        if (predicateWellFormed(context, t)) return Route.FAST

        val goals = runCatching {
            WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(t, context)
        }.getOrDefault(emptyList())

        // 規則 1：有「結構完整」的目標 → 快速管線
        if (goals.any { isStructurallyComplete(it) }) return Route.FAST

        // 規則 2：拆不出完整目標 → Agent（前提是這個 flavor 真的有模型）
        // 「我想看劇」「幫我找間餐廳」這類開放式目標，規則路徑永遠拆不出完整目標；
        // 以前要手動開 AGENT_ENABLED 才會走 Agent，等於預設功能不存在。
        // 現在改成：只要 APK 內有 GGUF（full flavor）就交給 Agent。
        if (RuntimeFlags.isAgentEnabled(context) || isAgentModelAvailable(context)) return Route.AGENT

        // 規則 2b：**沒有模型**也可能處理開放式意圖——只要意圖類別命中、且唯一選得出 App，
        // 就走 `OpenEndedPlanner` 的決定性模板（看劇／聽歌／導航／外送／查詢都不需要 LLM）。
        // 這是 lite flavor（157MB、無 GGUF）仍能「我想看劇」的關鍵。
        if (canResolveWithTemplate(context, t)) return Route.AGENT

        // 規則 3：拆不出完整目標、Agent 未開 → 保留舊行為（快速管線會失敗並回報）
        return if (goals.isNotEmpty()) Route.FAST else Route.ASK
    }


    /**
     * 這句話能否編譯成**合法**的目標述詞。
     *
     * 這是路由的「結構完整」新判準：不看句型、不看 App 名稱，
     * 只看「目標狀態能不能被寫成一個可判定的述詞」。
     */
    fun predicateWellFormed(context: Context, text: String): Boolean = runCatching {
        val (lexicon, _) = com.wae.agent.core.predicate.PredicateLexiconLoader.of(context)
        val compiled = com.wae.core.predicate.NlPredicateCompiler(lexicon).compile(text)
        if (!compiled.operable) return false
        val phi = compiled.goal ?: return true  // 相對目標（調亮一點）也是可執行述詞
        com.wae.core.predicate.PredicateEval.isWellFormed(phi)
    }.getOrDefault(false)

    /** 沒有模型時，這句話能不能靠「意圖類別 + App 選擇 + 模板」直接做完。 */
    fun canResolveWithTemplate(context: Context, text: String): Boolean = runCatching {
        val catalog = com.wae.agent.core.app.InstalledAppCatalog.load(context)
        val cats = com.wae.agent.core.app.AppIntentCategory.match(text).map { it.first }
        if (cats.isEmpty()) return false
        if (com.wae.agent.core.app.OpenEndedPlanner.kindFor(cats) == null) return false
        val decision = com.wae.agent.core.app.AppSelector.select(
            text,
            catalog,
            com.wae.agent.core.app.InstalledAppCatalog.usageCounts(context)
        )
        decision.confident || decision.best != null
    }.getOrDefault(false)

    /** 這個 APK 有沒有內建 planner 模型（full flavor 有、lite flavor 沒有）。 */
    fun isAgentModelAvailable(context: Context): Boolean = runCatching {
        context.assets.openFd(com.wae.agent.llm.GgufModel.ASSET_NAME).use { true }
    }.getOrDefault(false)

    /** 對外：這句話能不能拆出「結構完整」的目標（給語音閘門等地方用，不改變路由）。 */
    fun hasCompleteGoal(context: Context, text: String): Boolean {
        val goals = runCatching {
            WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(text.trim(), context)
        }.getOrDefault(emptyList())
        return goals.any { isStructurallyComplete(it) }
    }

    /** 目標是否結構完整：有解析到 App，或輸入類目標同時有 App 與內容。 */
    private fun isStructurallyComplete(g: LocalGoal): Boolean = when (g.actionKind) {
        ActionKind.LAUNCH_APP -> g.targetPackageHint.isNotBlank()

        ActionKind.DEEP_LINK -> g.inputPayload.isNotBlank()

        ActionKind.TYPE -> g.inputPayload.isNotBlank() && g.targetPackageHint.isNotBlank()

        ActionKind.SUBMIT -> g.inputPayload.isNotBlank()

        ActionKind.ADJUST_VALUE -> g.inputPayload.isNotBlank()

        else -> false
    }
}
