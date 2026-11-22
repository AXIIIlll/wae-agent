package com.wae.agent.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * WAE V2.6 生態整合層：Deep Link 直達快車道與能力文件管理器
 * - 零內建預設規則（Zero Hardcoded Presets）：初始狀態為空，僅載入由桌面端 APK 分析器產出並匯入之 `app_capability.json`
 * - 內建跳轉後 800ms 落地姿態驗證 (Landing Posture Verification)：識別靜默跳回首頁 (Silent Redirect)，
 *   自動將失效規則標記為 STALE 並無縫回落至 V2.6 GUI 管線
 */
object WaeDeepLinkCapabilityManager {

    enum class RuleStatus {
        ACTIVE,
        STALE,
        USER_OVERRIDDEN
    }

    data class DeepLinkCapabilityRule(
        val ruleId: String,
        val packageName: String,
        val appAliases: List<String>,
        val canonicalIntentTag: String,
        val uriTemplate: String,
        val queryParamKeys: List<String>,
        val targetActivity: String,
        val expectedLandingPosture: PagePosture,
        var status: RuleStatus = RuleStatus.ACTIVE,
        var failCount: Int = 0
    )

    data class LandingVerificationResult(
        val isSuccess: Boolean,
        val isSilentHomeRedirect: Boolean,
        val reason: String
    )

    private const val LOCAL_CAPABILITY_FILE = "app_capability_local.json"
    private val rulesMap = ConcurrentHashMap<String, DeepLinkCapabilityRule>()
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        initialized = true
        loadUserImportedCapabilities(context)
    }

    /**
     * 匯入桌面端 APK 分析器生成的 `app_capability.json` 字串並持久化至本地
     */
    fun importCapabilityJson(context: Context?, jsonContent: String): Int {
        return try {
            val root = JSONObject(jsonContent)
            val arr = root.optJSONArray("deepLinks") ?: JSONArray()
            var importedCount = 0
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val ruleId = item.optString("ruleId").ifEmpty { "custom_rule_$i" }
                val pkg = item.optString("packageName")
                val tag = item.optString("canonicalIntentTag")
                val tpl = item.optString("uriTemplate")
                if (pkg.isEmpty() || tpl.isEmpty()) continue

                val aliasesArr = item.optJSONArray("appAliases")
                val aliases = mutableListOf<String>()
                if (aliasesArr != null) {
                    for (j in 0 until aliasesArr.length()) {
                        aliases.add(aliasesArr.optString(j))
                    }
                }

                val paramsArr = item.optJSONArray("queryParamKeys")
                val params = mutableListOf<String>()
                if (paramsArr != null) {
                    for (j in 0 until paramsArr.length()) {
                        params.add(paramsArr.optString(j))
                    }
                }

                val postureStr = item.optString("expectedLandingPosture", "SEARCH_RESULTS")
                val posture = try {
                    PagePosture.valueOf(postureStr)
                } catch (_: Throwable) {
                    PagePosture.SEARCH_RESULTS
                }

                val statusStr = item.optString("status", "ACTIVE")
                val status = try {
                    RuleStatus.valueOf(statusStr)
                } catch (_: Throwable) {
                    RuleStatus.ACTIVE
                }

                rulesMap[ruleId] = DeepLinkCapabilityRule(
                    ruleId = ruleId,
                    packageName = pkg,
                    appAliases = aliases,
                    canonicalIntentTag = tag,
                    uriTemplate = tpl,
                    queryParamKeys = params,
                    targetActivity = item.optString("targetActivity", ""),
                    expectedLandingPosture = posture,
                    status = status
                )
                importedCount++
            }
            if (context != null && importedCount > 0) {
                saveCapabilitiesToDisk(context)
            }
            importedCount
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * 根據當前 LocalGoal 檢索已匯入的 ACTIVE Deep Link 規則
     */
    fun findMatchingDeepLink(goal: LocalGoal, activePackage: String = ""): DeepLinkCapabilityRule? {
        val goalLower = goal.goalText.lowercase()
        return rulesMap.values.firstOrNull { rule ->
            if (rule.status == RuleStatus.STALE) return@firstOrNull false
            val pkgMatched = (goal.targetPackageHint.isNotEmpty() && goal.targetPackageHint == rule.packageName) ||
                (activePackage.isNotEmpty() && activePackage == rule.packageName) ||
                rule.appAliases.any { alias -> alias.isNotEmpty() && goalLower.contains(alias.lowercase()) }
            val intentMatched = (goal.canonicalIntentTag == rule.canonicalIntentTag) ||
                (goal.inputPayload.isNotEmpty() && rule.queryParamKeys.isNotEmpty())
            pkgMatched && intentMatched
        }
    }

    fun buildResolvedUriString(rule: DeepLinkCapabilityRule, payload: String): String {
        val encoded = Uri.encode(payload.trim())
        return rule.uriTemplate
            .replace("{query}", encoded)
            .replace("{id}", encoded)
            .replace("{keyword}", encoded)
    }

    fun launchDeepLinkFastPath(
        context: Context,
        rule: DeepLinkCapabilityRule,
        payload: String
    ): Boolean {
        val resolvedUri = buildResolvedUriString(rule, payload)
        return try {
            val intent = if (resolvedUri.startsWith("intent:")) {
                Intent.parseUri(resolvedUri, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(resolvedUri))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (rule.packageName.isNotEmpty() && !resolvedUri.startsWith("intent:")) {
                intent.setPackage(rule.packageName)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            markRuleStale(context, rule.ruleId)
            false
        }
    }

    /**
     * 跳轉後 800ms 落地姿態驗證（檢測「靜默跳回首頁 Silent Redirect」）
     */
    fun verifyLandingPosture(
        context: Context?,
        rule: DeepLinkCapabilityRule,
        goal: LocalGoal,
        observedPosture: DualPostureState,
        visibleTexts: List<String>
    ): LandingVerificationResult {
        val effective = observedPosture.effectivePosture
        val payload = goal.inputPayload.trim()
        val hasPayloadOnScreen = payload.isNotEmpty() && visibleTexts.any {
            it.contains(payload, ignoreCase = true)
        }

        if (effective == PagePosture.NAV_HOME &&
            rule.expectedLandingPosture != PagePosture.NAV_HOME &&
            !hasPayloadOnScreen
        ) {
            markRuleStale(context, rule.ruleId)
            return LandingVerificationResult(
                isSuccess = false,
                isSilentHomeRedirect = true,
                reason = "SILENT_REDIRECT_TO_HOME: 規則 ${rule.ruleId} 已自動標記為 STALE，原地切換 V2.6 GUI 管線"
            )
        }

        return LandingVerificationResult(
            isSuccess = true,
            isSilentHomeRedirect = false,
            reason = "LANDING_VERIFIED ($effective)"
        )
    }

    fun markRuleStale(context: Context?, ruleId: String) {
        val rule = rulesMap[ruleId] ?: return
        rule.failCount += 1
        rule.status = RuleStatus.STALE
        if (context != null) {
            saveCapabilitiesToDisk(context)
        }
    }

    fun getActiveRulesCount(): Int = rulesMap.values.count { it.status != RuleStatus.STALE }
    fun getStaleRulesCount(): Int = rulesMap.values.count { it.status == RuleStatus.STALE }

    private fun loadUserImportedCapabilities(context: Context) {
        try {
            val f = File(context.filesDir, LOCAL_CAPABILITY_FILE)
            if (f.exists()) {
                importCapabilityJson(null, f.readText(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveCapabilitiesToDisk(context: Context) {
        try {
            val root = JSONObject()
            val arr = JSONArray()
            for (rule in rulesMap.values) {
                val obj = JSONObject().apply {
                    put("ruleId", rule.ruleId)
                    put("packageName", rule.packageName)
                    put("appAliases", JSONArray(rule.appAliases))
                    put("canonicalIntentTag", rule.canonicalIntentTag)
                    put("uriTemplate", rule.uriTemplate)
                    put("queryParamKeys", JSONArray(rule.queryParamKeys))
                    put("targetActivity", rule.targetActivity)
                    put("expectedLandingPosture", rule.expectedLandingPosture.name)
                    put("status", rule.status.name)
                }
                arr.put(obj)
            }
            root.put("deepLinks", arr)
            File(context.filesDir, LOCAL_CAPABILITY_FILE).writeText(root.toString(2), Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }
}
