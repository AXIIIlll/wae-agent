package com.example.newandroidapp.ai

import android.graphics.Point
import com.example.newandroidapp.vision.Stage2ConvergedNode

enum class LocalAiBackendMode(val displayName: String) {
    DUAL_FUSION("自研雙核四階協同流水線 (預設)"),
    ANDROID_NATIVE_AI("僅用自研結構語義核"),
    NEEDLE_3("僅用自研 C++17 向量核 (LexiVec-1024)")
}

enum class AiActionType(val label: String) {
    LAUNCH_APP("啟動應用程式 (OPEN_APP)"),
    TAP("點擊元件 (TAP)"),
    INPUT_TEXT("輸入文字 (INPUT)"),
    SCROLL_DOWN("向下滑動 (SCROLL_DOWN)"),
    SCROLL_UP("向上滑動 (SCROLL_UP)"),
    NAVIGATE_BACK("返回上一頁 (BACK)"),
    CONFIRM_HIGH_RISK("高風險安全攔截 (CONFIRM)"),
    FINISH_DONE("任務完成 (DONE)"),
    ANSWER_QUESTION("直接問答 (ANSWER)"),
    LONG_PRESS("長按元件 (LONG_PRESS)"),
    DOUBLE_TAP("雙擊元件 (DOUBLE_TAP)"),
    SWIPE_LEFT("向左滑動 (SWIPE_LEFT)"),
    SWIPE_RIGHT("向右滑動 (SWIPE_RIGHT)")
}

/**
 * 三階段指令編譯管線（Instruction Compiler Pipeline）之第二階段：
 * 受限的「6 大標準動作原語（Canonical Action Primitives）」枚舉。
 * 系統嚴格僅支援以下 6 種標準化動作積木，徹底消除自然語言的模糊性：
 * 1. LAUNCH_APP(targetApp)：啟動或切換至指定應用程式。
 * 2. SEARCH_IN_APP(keyword)：封裝好的複合搜尋巨集動作（定位頂部/導覽列搜尋框 -> 注入關鍵字並送出 -> 驗證非輸入框之結果區出現該關鍵字）。
 * 3. TAP_ELEMENT(targetTextOrIcon)：尋找並點擊特定畫面元件。
 * 4. INPUT_TEXT(contentOrMemoryRef)：將指定文字或跨 App 暫存區（$MEMORY）內容填入輸入框。
 * 5. EXTRACT_TO_MEMORY(extractRule)：純內部資料擷取動作（不點擊 UI，直接從當前畫面樹提取目標文字清單寫入跨 App 暫存區與系統剪貼簿，若為最後一步則自動返回主畫面輸出至對話框）。
 * 6. SYSTEM_NAV(backOrScroll)：返回或滑動探索。
 */
enum class CanonicalActionPrimitiveType(val signatureSpec: String) {
    LAUNCH_APP("LAUNCH_APP(targetApp)"),
    SEARCH_IN_APP("SEARCH_IN_APP(keyword)"),
    TAP_ELEMENT("TAP_ELEMENT(targetTextOrIcon)"),
    INPUT_TEXT("INPUT_TEXT(contentOrMemoryRef)"),
    EXTRACT_TO_MEMORY("EXTRACT_TO_MEMORY(extractRule)"),
    SYSTEM_NAV("SYSTEM_NAV(backOrScroll)")
}

/**
 * 編譯後之單一標準動作原語積木（Canonical Action Primitive）
 */
data class CompiledActionPrimitive(
    val stepIndex: Int,
    val totalSteps: Int,
    val type: CanonicalActionPrimitiveType,
    val argument: String,
    val appPhaseIndex: Int = 1,
    val totalAppBlocks: Int = 1,
    val targetAppHint: String? = null,
    val desiredToggleState: Boolean? = null,
    val isHighRiskHint: Boolean = false,
    val sourceClause: String = ""
) {
    /** 標準原語呼叫表示式，例如 SEARCH_IN_APP("6767")、LAUNCH_APP("Chrome")、INPUT_TEXT("$MEMORY") */
    val formalCall: String
        get() = "${type.name}(\"$argument\")"
}

/**
 * 第一階拆解出之結構化子目標（內嵌受限 6 大標準動作原語積木）
 */
data class CooperativeSubGoal(
    val stepIndex: Int,
    val totalSteps: Int,
    val intentCategory: String,       // LAUNCH_APP, TAP_TARGET, INPUT_SEARCH, EXTRACT_SCREEN_INFO, TOGGLE_SWITCH, SCROLL, SYSTEM_BACK
    val targetAppHint: String? = null,// 欲開啟之目標 App 名稱（若此步需開啟 App）
    val targetSemanticPhrase: String, // 欲尋找的核心目標詞或語義描述
    val inputPayload: String? = null, // 若為輸入步驟，欲填入的文字（支援 $MEMORY 與 {{CROSS_APP_CLIPBOARD}} 跨 App 語義剪貼簿佔位符）
    val desiredToggleState: Boolean? = null,
    val isHighRiskHint: Boolean = false,
    val appPhaseIndex: Int = 1,       // 跨 App 大塊編號（第 1 個 App 大塊為 1，第 2 個 App 大塊為 2...）
    val totalAppBlocks: Int = 1,      // 總共切分為幾個 App 大塊（1 個 App = 1 大塊，2 個 App = 2 大塊，N 個 App = N 大塊）
    val simplifiedMotorGoal: String = targetSemanticPhrase, // 傳給底層動手模型的單步極簡短句（完全不含其他 App 或後續步驟連接詞）
    val canonicalPrimitive: CompiledActionPrimitive? = null // 編譯後對應之 6 大標準動作原語積木
)

/**
 * 四階雙模型合作流水線之逐階診斷紀錄
 */
data class CooperativePipelineLog(
    val stage1Decomposition: String, // 第一階：任務拆解與意圖路由 (原生語法 + Needle 3 ROLE_GOAL_EXTRACTOR)
    val stage2NativeFunnel: String,  // 第二階：視覺拓樸快篩與反射弧 (原生 AI 0ms 快篩或收斂至 Top-6 漏斗)
    val stage3NeedleNeural: String,  // 第三階：Needle 3 Top-6 1024維向量精排 + Schema Tool-Call 終極二選一
    val stage4SafetyAndCoord: String,// 第四階：原生安全閘門 (C!) + 四階段視覺未遮擋像素落點驗證
    val fastPathTriggered: Boolean   // 是否於第二階 >=0.85 直接 0ms 秒過（免喚醒生成式推論）
)

data class AiActionDecision(
    val engineName: String,
    val actionType: AiActionType,
    val targetAppPackage: String? = null,
    val targetAppLabel: String? = null,
    val targetNode: Stage2ConvergedNode?,
    val targetMarkId: Int?,
    val tapPixel: Point?,
    val tapGrid0to999: Point?,
    val inputTextValue: String? = null,
    val compactOpcode: String,
    val confidence: Float,
    val latencyMs: Long,
    val reasoningTrace: String,
    val topCandidates: List<Pair<Stage2ConvergedNode, Float>> = emptyList(),
    val cooperativeLog: CooperativePipelineLog? = null,
    val decomposedSubGoals: List<CooperativeSubGoal> = emptyList()
)
