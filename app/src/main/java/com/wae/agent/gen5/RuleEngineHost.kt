package com.wae.agent.gen5

import android.content.Context
import android.util.Log
import com.wae.core.config.BundledData
import com.wae.core.rule.CommandRuleTable
import com.wae.core.rule.MethodParser
import com.wae.core.rule.OperationTable
import com.wae.core.rule.PurposeTable
import com.wae.core.rule.RuleEngine
import com.wae.core.rule.SpecificityTable

/**
 * 規則引擎的 App 層宿主：載入五張表、提供決策、控制開關。
 *
 * 為什麼要宿主：core 的規則引擎是純函式；載入資源、快取、全域開關屬 App 層。
 *
 * 開關（`!rules on|off`）：
 *  - on  ：先問規則引擎；它說「模糊／沒命中」才退回舊分支
 *  - off ：完全走舊分支（行為 100% 不變，作為退路）
 */
object RuleEngineHost {

    private const val TAG = "WAE_RULES"

    /** 全域開關；預設開，但舊分支永遠在後面接住。 */
    @Volatile
    var enabled: Boolean = true

    /** 操作層開關（預設關）：off 時仍走 `cmd:`（行為與上一版一樣）。 */
    @Volatile
    var opsEnabled: Boolean = false

    /** 執行計畫時設 true，避免「規則 → 指令 → 規則」遞迴。 */
    @Volatile
    var bypass: Boolean = false

    @Volatile
    private var engine: RuleEngine? = null

    fun engine(context: android.content.Context): RuleEngine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val purposes = PurposeTable.parse(BundledData.purposeLines())
            val ops = OperationTable.parse(BundledData.operationLines())
            val rules = CommandRuleTable.parse(BundledData.commandPurposeLines())
            val methods = MethodParser.parse(BundledData.taskMethodLines())
                .groupBy { it.purpose }
            val spec = SpecificityTable.parse(BundledData.specificityLines())
            val built = RuleEngine(
                purposes = purposes.associateBy { it.id },
                operations = ops.associateBy { it.id },
                rules = rules,
                methodsByPurpose = methods,
                specificity = spec,
                tradMap = RuleEngine.tradMapOf(BundledData.tradMapLines()),
                simpTrad = RuleEngine.simpTradOf(BundledData.simpTradLines()),
                capabilities = com.wae.agent.gen5.AppCapabilityStore.table(context),
                probe = com.wae.agent.gen5.DeviceProbe(context)
            )
            Log.i(TAG, "loaded purposes=${purposes.size} rules=${rules.size} methods=${methods.values.sumOf { it.size }}")
            engine = built
            return built
        }
    }

    /** 決策統計（給 `!rules status` 看規則實際被用到幾次）。 */
    @Volatile var plans = 0
    @Volatile var needsConfirmation = 0
    @Volatile var needsModel = 0
    @Volatile var noMatch = 0

    /** 決策；任何例外都回 null（規則引擎壞掉不能拖垮整個 App）。 */
    fun decide(
        context: android.content.Context,
        command: String,
        facts: Map<String, String>,
        preset: Map<String, String> = emptyMap()
    ): RuleEngine.Decision? =
        com.wae.core.util.Degrade.orNull("rules:decide") {
            engine(context).decide(command, facts, preset, allowOps = opsEnabled)
        }

    /**
     * 補槽：模糊指令缺 `app` 時，用「目的型路由表 → 類別 → 已安裝 App」補。
     *
     * 只有「剛好一個候選」才補（多個留給 VagueRunner 反問，不擅自選）。
     * 回傳補到的槽位（可能空）。
     */
    fun fillSlots(context: Context, command: String, decision: RuleEngine.Decision): Map<String, String> {
        val need = decision as? RuleEngine.Decision.NeedsModel ?: return emptyMap()
        val missingApp = need.purpose?.requiredSlots?.contains("app") == true &&
            need.missingSlots.any { it.contains("app") }
        if (!missingApp) return emptyMap()
        ensureRoutes()
        val categories = com.wae.core.util.Degrade.orNull("rules:categories") {
            com.wae.core.intent.IntentRouter.route(command)?.categories
        } ?: return emptyMap()
        val apps = com.wae.core.util.Degrade.orEmpty("rules:appsByCategory") {
            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)
                .filter { it.category in categories }
        }
        if (apps.size != 1) {
            Log.i(TAG, "fillSlots categories=$categories apps=${apps.size} (leave to router)")
            return emptyMap()
        }
        Log.i(TAG, "fillSlots app='${apps.first().label}' via categories=$categories")
        return mapOf("app" to apps.first().label)
    }

    @Volatile
    private var routesLoaded = false

    private fun ensureRoutes() {
        if (routesLoaded) return
        com.wae.core.util.Degrade.orNull("rules:loadRoutes") {
            com.wae.core.intent.IntentRouter.loadFromLines(BundledData.intentRoutes())
        }
        routesLoaded = true
    }

    /** 用操作層執行計畫；回傳 null 表示「做不了」，上層退回舊分支。 */
    fun executeOps(context: android.content.Context, plan: RuleEngine.Decision.Plan): OpExecutor.Result? {
        if (!opsEnabled || plan.ops.isEmpty()) return null
        val service = com.wae.core.util.Degrade.orNull("rules:activeService") {
            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance
        }
        val result = OpExecutor.run(context, service, plan.purpose, plan.ops)
        Log.i(TAG, "ops ${plan.purpose.id} ${result.detail}")
        return result
    }

    fun noteDecision(decision: RuleEngine.Decision?) {
        when (decision) {
            is RuleEngine.Decision.Plan -> plans++
            is RuleEngine.Decision.NeedsConfirmation -> needsConfirmation++
            is RuleEngine.Decision.NeedsModel -> needsModel++
            is RuleEngine.Decision.NoMatch -> noMatch++
            null -> Unit
        }
    }

    /** 一句話的可讀描述（log／probe 用）。 */
    fun describe(decision: RuleEngine.Decision?): String = when (decision) {
        null -> "none"
        is RuleEngine.Decision.Plan ->
            "PLAN purpose=${decision.purpose.id} method=${decision.method.id} " +
                "spec=${decision.specificity.verdict}(${"%.2f".format(decision.specificity.score)}) " +
                "commands=${decision.commands.size} via=${decision.purposesUsed.joinToString(",")}"
        is RuleEngine.Decision.NeedsConfirmation ->
            "CONFIRM purpose=${decision.purpose.id} risk=${decision.purpose.risk}"
        is RuleEngine.Decision.NeedsModel ->
            "NEED_MODEL reason=${decision.reason} " +
                "purpose=${decision.purpose?.id ?: "-"} missing=${decision.missingSlots.joinToString("|")}"
        is RuleEngine.Decision.NoMatch ->
            "NO_MATCH spec=${decision.specificity.verdict}"
    }

    /** 執行後回寫能力表（讓「這支手機做得到什麼」越用越準）。 */
    fun recordOutcome(context: android.content.Context, purposeId: String, ok: Boolean) {
        val pkg = com.wae.core.util.Degrade.orElse("rules:foregroundPkg", "") {
            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance
                ?.rootInActiveWindow?.packageName?.toString() ?: ""
        }
        if (pkg.isNotEmpty()) {
            com.wae.agent.gen5.AppCapabilityStore.record(context, pkg, purposeId, ok)
        }
    }

    /** `!facts`：把「這支手機有什麼」講清楚（模型與人看的是同一份事實）。 */
    fun factsText(context: android.content.Context): String {
        val apps = com.wae.core.util.Degrade.orEmpty("rules:installedApps") {
            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)
        }
        val cats = apps.groupBy { it.category }.mapValues { it.value.size }
            .toList().sortedByDescending { it.second }.take(6)
            .joinToString(", ") { it.first + "=" + it.second }
        val probe = com.wae.agent.gen5.DeviceProbe(context)
        val perms = listOf(
            "android.permission.WRITE_SETTINGS",
            "android.permission.READ_MEDIA_IMAGES"
        ).joinToString(" ") { it.substringAfterLast('.') + "=" + probe.hasPermission(it) }
        val features = listOf("android.hardware.camera", "android.hardware.nfc")
            .joinToString(" ") { it.substringAfterLast('.') + "=" + probe.hasFeature(it) }
        val fgPkg = com.wae.core.util.Degrade.orElse("rules:fgPkg", "") {
            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance
                ?.rootInActiveWindow?.packageName?.toString() ?: ""
        }
        val family = if (fgPkg.isEmpty()) "-" else com.wae.agent.gen5.AppCategoryResolver.familyOf(context, fgPkg)
        return "FACTS apps=" + apps.size + " [" + cats + "]\n" +
            "foreground=" + fgPkg.ifEmpty { "-" } + " family=" + family + "\n" +
            "perms: " + perms + "\n" +
            "features: " + features + "\n" +
            com.wae.agent.gen5.AppCapabilityStore.summary(context)
    }

    /** 給 probe／log 用的一行統計。 */
    fun status(context: android.content.Context): String {
        val e = com.wae.core.util.Degrade.orNull("rules:engine") { engine(context) }
            ?: return "RULES error"
        return "RULES " + (if (enabled) "on" else "off") +
            " purposes=" + e.purposeIds().size +
            " rules=" + e.ruleCount() +
            " methods=" + e.methodCount() +
            " operations=" + e.operationCount() +
            " bypass=" + bypass + " ops=" + (if (opsEnabled) "on" else "off") +
            " | plans=$plans confirm=$needsConfirmation needsModel=$needsModel noMatch=$noMatch"
    }

    /** 已安裝 App 標籤（給方法條件的 `app_installed($app)` 用）。 */
    fun installedAppsFacts(context: Context): Map<String, String> {
        val labels = com.wae.core.util.Degrade.orEmpty("rules:installedLabels") {
            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)
                .map { it.label }
        }
        return mapOf("installed_apps" to labels.joinToString("|"))
    }
}
