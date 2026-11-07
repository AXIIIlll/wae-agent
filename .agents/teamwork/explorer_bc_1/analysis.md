# 逆向目標回推思維引擎 (Backward-Chaining Goal-Conditioned Planner) 架構與整合調查報告

## 1. 執行摘要與根因診斷 (Executive Summary & Root Cause Analysis)

### 1.1 問題現象
在使用者下達語音或口語指令時（例如「去spotify買茶歌struct」或「去spotify點播晴天」）：
- **現象 A（目標在首頁）**：若使用者事先把歌曲「struct」釘選或顯示在 Spotify 首頁，AI 能順利點擊「struct」並播放成功。
- **現象 B（目標不在首頁）**：若將「struct」從首頁移開，AI 在首頁找不到「struct」，卻完全不會點擊底部的「搜尋」按鈕，而是在首頁發呆或隨機點擊其他無關推薦項目。

### 1.2 根因診斷
追蹤既有決策管線 (`InAppOperationPlanner.kt` 與 `DualLocalAiCoordinator.kt`)，確認核心問題出在「**單向正向正規表達式匹配 (Forward Regex Matching)**」與「**缺乏逆向目標因果回推機制 (Missing Backward-Chaining Precondition Deduction)**」：

1. **實體提煉與語音同音詞斷鏈**：
   - 語音辨識經常將口語「查歌」辨識為同音字「買茶歌」，或在歌曲名稱前夾帶口語贅字（如「一首歌叫晴天」、「查詢歌曲struct」）。
   - 現有 `InAppOperationPlanner.extractSearchQueryAndFollowUp` 僅支援硬編碼的搜尋關鍵字正規表達式（`"(?:搜尋|搜索|查找|查詢)..."`），對於「去spotify買茶歌struct」，正規表達式匹配全部失敗，回傳空字串 `("" to "")`。
   - 導致 `DualLocalAiCoordinator.decomposeUserGoalCooperatively` 無法建立 `INPUT_SEARCH` 子目標，退化為單一的 `TAP_TARGET`。

2. **缺少「目標未出現 -> 逆向回推前置條件」的認知閉環**：
   - 處於 `TAP_TARGET` 狀態時，AI 僅檢查當前畫面是否有「struct」；當畫面上沒有「struct」時，系統沒有思考：「要讓 struct 出現在畫面上，前置條件是什麼？ $\rightarrow$ 必須先搜尋 struct $\rightarrow$ 要搜尋必須在輸入框打字 $\rightarrow$ 要叫出輸入框必須點開搜尋入口」。
   - 因為缺少這個逆向推演，AI 只能把視野內的非目標元件當成低分候選，最終執行盲目下滑或誤點。

3. **現代 App 兩段式搜尋架構斷鏈**：
   - Spotify、Instagram、Threads、蝦皮等現代 App 普遍採用兩段式搜尋架構：
     - **第一段（首頁底部導覽列）**：首頁沒有搜尋框，只有底部導覽列的「搜尋」Tab（`gridTapPoint.y >= 780`）。點擊後切換至「搜尋分頁」，但**並不會直接彈出鍵盤與輸入框**。
     - **第二段（搜尋分頁頂部假搜尋框）**：進入搜尋分頁後，頂部顯示的是一個靜態按鈕或文字標籤（如「想聽什麼？」、「藝人、歌曲或 Podcast」），此時底部的「搜尋」Tab 依然存在於畫面上。
   - 現有 `InAppOperationPlanner.SEARCH_ENTRY_KEYWORDS` 僅包含「搜尋」、「搜索」、「search」等 9 個詞彙，完全沒有「想聽什麼」、「藝人、歌曲」等假框提示詞；且未區分「底部導覽列分頁 Tab」與「頂部假搜尋按鈕」，導致點入搜尋頁後無法接續點開頂部輸入框。

---

## 2. 既有元件深度調查 (Investigation of Existing Components)

### 2.1 `InAppOperationPlanner.kt` 調查分析

#### A. `SEARCH_ENTRY_KEYWORDS` 現狀 (Line 413-415)
```kotlin
private val SEARCH_ENTRY_KEYWORDS = listOf(
    "搜尋", "搜索", "search", "查找", "輸入關鍵字", "找商品", "找影片", "找音樂", "找地點"
)
```
- **不足點**：
  1. 缺少 Spotify 等現代影音/社群 App 的假搜尋按鈕標籤：`"想聽什麼"`, `"what do you want to listen to"`, `"藝人、歌曲"`, `"podcast"`, `"想找什麼"`, `"探索"`。
  2. 未將「頂部假搜尋列（`gridTapPoint.y <= 360`）」與「底部導覽列 Tab（`gridTapPoint.y >= 780`）」做階層區分。

#### B. `extractSearchQueryAndFollowUp` 現狀 (Line 533-576)
- 使用 `explicitRegex` 與 `colloquialRegex` 進行前綴切分。
- **不足點**：
  1. 無法處理語音辨識同音詞（「買茶歌」->「查歌」）。
  2. 無法自動剝離語義載體贅詞（如「一首歌叫」、「歌曲叫」、「名叫」）。
  3. 指令若帶有 App 名稱前綴（如「去spotify買茶歌struct」），因開頭不是搜尋動詞而完全失效。

#### C. `evaluateInAppStateOverrideOrAdjustScores` 現狀 (Line 692-1088)
- 決策流程：
  1. 優先檢查頂層彈窗 (`detectTopLayerBlocker`)。
  2. 套用重複點擊懲罰 (-45f) 與嚴禁語音輸入禁令 (-200f)。
  3. 執行認知推理大腦 (`CognitiveReasoningEngine.diagnoseAndReason`)。
  4. 判斷 `query.isNotBlank()`：
     - 若為真：進入 Stage A (展開搜尋入口) -> Stage B (填入關鍵字並送出) -> Stage C (鎖定結果列表)。
     - 若為假：退化至純字串匹配與 `LookaheadPlannerEngine`。
- **不足點**：
  只要 `query` 為空，完全不會進入搜尋三階段狀態機；即使 `query` 有值，若在 Spotify 首頁點擊底部搜尋 Tab 後，下一幀在搜尋分頁因缺少「想聽什麼」關鍵字，也會中斷搜尋鏈。

### 2.2 `DualLocalAiCoordinator.kt` 調查分析

#### A. `decomposeUserGoalCooperatively` 現狀 (Line 861-992)
- 流程：
  1. 呼叫 `CrossAppWorkflowEngine.buildCrossAppSubGoalChain`。
  2. 呼叫 `InAppOperationPlanner.buildCooperativeInAppSubGoals`。
  3. 若 `buildCooperativeInAppSubGoals` 回傳 null，依據連接詞（「然後」、「接著」、「並」）硬切分，分類出 `LAUNCH_APP`、`INPUT_SEARCH`、`TAP_TARGET` 等。
- **整合點**：
  在拆解子目標前，應先透過 `BackwardChainingPlanner.extractTerminalGoalSpec` 提煉出 `TerminalGoalSpec(appHint, terminalVerb, targetEntity, followUpAction)`。若包含目標 App 與核心實體，可自動確保任務拆解包含完整的搜尋操作鏈。

#### B. `executeFourStageCooperativePipeline` 現狀 (Line 122-855)
- 四階段協同決策：
  - 第一階：任務拆解與 App 匹配。
  - 第二階：原生快篩 + 彈窗排除 + In-App 規劃器 + 自動返回。
  - 第三階：Needle 3 神經向量精排。
  - 第四階：安全閘門與座標綁定。
- **日誌與 HUD 輸出**：
  決策結果透過 `CooperativePipelineLog` 與 `AiActionDecision.reasoningTrace` 傳遞，並即時渲染於 HUD 懸浮視窗。
- **整合點**：
  在第二階評估時，若目標不在當前畫面，呼叫 `BackwardChainingPlanner.evaluateBackwardChain`，並將回推思考軌跡（例如 `[逆向目標回推] 終極目標「struct」不在當前首頁 -> 回推前置條件 Level 3B：先點擊底部「搜尋」分頁`）注入 `stage1Decomposition` 與 `reasoningTrace`。

### 2.3 `ClosedLoopVerifier.kt` 調查分析 (Line 161-173)
- 閉環驗證中的 `INPUT_SEARCH` 判斷：
  ```kotlin
  val payloadAppearsOnScreen = afterReport.finalNodes.any {
      it.label.lowercase().contains(payload)
  }
  hasTypedTextInStep && (screenChanged || payloadAppearsOnScreen)
  ```
- **重要保護**：
  必須確保在 Level 3B（點擊底部 Tab）與 Level 3A（點擊頂部假框）的過渡期間，不會因為畫面改變而提前結束 `INPUT_SEARCH` 子目標；唯有真正完成文字輸入 (`hasTypedTextInStep == true`) 且搜尋結果載入完成，才允許推進至下一個子目標。

---

## 3. 逆向目標回推思維引擎 (`BackwardChainingPlanner.kt`) 架構與 API 設計

### 3.1 五階逆向推演模型 (Five-Level Backward-Chaining Model)

從終極目標狀態往回推演前置條件缺口：
- **Level 0 (終極目標已在畫面)**：
  當前畫面主內容區已出現包含 `targetEntity` 的項目（例如歌曲已釘選在首頁，或已在搜尋結果頁），直接執行終極動作（TAP 目標項目）。
- **Level 1 (搜尋已送出，等待選取結果)**：
  已對 `targetEntity` 完成送出搜尋，於主內容區挑選最佳結果項目或篩選籤。
- **Level 2 (畫面已有輸入框，填寫關鍵字)**：
  畫面中出現真實 `EditText` / `INP` 輸入框，直接填入 `targetEntity` 並觸發搜尋送出。
- **Level 3A (畫面無輸入框，但有頂部假搜尋列/搜尋按鈕)**：
  畫面無 `INP`，但中上方 (`gridTapPoint.y <= 360`) 存在假搜尋列或放大鏡按鈕（如 Spotify「想聽什麼？」、NanoDet `UI_SEARCH_BAR`），點擊以展開真實 `INP` 輸入框。
- **Level 3B (畫面無搜尋框，但有底部搜尋分頁 Tab)**：
  畫面無 `INP` 且無頂部假搜尋列，但底部導覽列 (`gridTapPoint.y >= 780`) 存在「搜尋 / 探索」分頁 Tab，點擊切換進入搜尋主頁（下一幀自動觸發 Level 3A）。
- **Level 4 (無搜尋入口，階層式父分類回推)**：
  純階層選單（如系統設定子頁），回推並點擊所屬父分類。

### 3.2 核心資料結構設計 (Data Structures)

```kotlin
package com.example.newandroidapp.stability

import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport

/**
 * 終極操作意圖動詞枚舉
 */
enum class TerminalVerb {
    PLAY,          // 聽 / 放 / 播 / 查歌 / 買茶歌 / 點播
    SEARCH_FIND,   // 搜尋 / 搜索 / 查找 / 查詢 / 找
    BUY,           // 買 / 購買 / 下單 / 購物
    TOGGLE,        // 開啟 / 關閉 / 切換
    NAVIGATE,      // 前往 / 進入 / 打開
    UNKNOWN        // 未知 / 一般操作
}

/**
 * 終極目標結構化提煉規格
 */
data class TerminalGoalSpec(
    val originalPrompt: String,
    val appHint: String?,
    val terminalVerb: TerminalVerb,
    val targetEntity: String,
    val followUpAction: String = "",
    val requiresSearchChain: Boolean = false,
    val confidence: Float = 0.95f
)

/**
 * 逆向回推階級枚舉
 */
enum class BackwardChainLevel(val levelIndex: Int, val description: String) {
    LEVEL_0_DIRECT_TARGET(0, "Level 0: 畫面已有目標實體，直接點擊"),
    LEVEL_1_SELECT_RESULT(1, "Level 1: 搜尋已送出，從結果列表選取"),
    LEVEL_2_INPUT_KEYWORD(2, "Level 2: 畫面已有輸入框，填入關鍵字並送出"),
    LEVEL_3A_TOP_FAKE_SEARCH_BOX(3, "Level 3A: 畫面無輸入框，點擊頂部假搜尋框/搜尋鍵展開輸入框"),
    LEVEL_3B_BOTTOM_NAV_SEARCH_TAB(4, "Level 3B: 畫面無搜尋框，點擊底部導覽列「搜尋」分頁"),
    LEVEL_4_HIERARCHICAL_CATEGORY(5, "Level 4: 無搜尋入口，回推父分類導航")
}

/**
 * 逆向回推評估決策成果
 */
data class BackwardChainEvaluation(
    val level: BackwardChainLevel,
    val targetEntity: String,
    val targetNode: Stage2ConvergedNode? = null,
    val actionType: AiActionType? = null,
    val inputText: String? = null,
    val confidence: Float = 0.95f,
    val reasoningLog: String = "",
    val candidateScoreDeltas: Map<String, Float> = emptyMap()
)
```

### 3.3 核心函式簽名與演算法邏輯 (Function Signatures & Algorithms)

#### 1. 終極目標與實體結構化提煉 (`extractTerminalGoalSpec`)
```kotlin
fun extractTerminalGoalSpec(prompt: String): TerminalGoalSpec
```
- **消去法處理流程**：
  1. **目標 App 提煉 (`appHint`)**：
     掃描常見 App 名稱表（`spotify`、`youtube`、`蝦皮`、`netflix`、`設定` 等），若命中則提取為 `appHint` 並自字串中剝離。
  2. **意圖動詞與同音詞消解 (`terminalVerb`)**：
     - 語音辨識同音與音樂意圖：`買茶歌`、`茶歌`、`查歌`、`搜歌`、`點歌`、`聽歌`、`放歌`、`點播`、`播放`、`聽`、`放`、`播` $\rightarrow$ `TerminalVerb.PLAY`。
     - 搜尋意圖：`搜尋`、`搜索`、`查找`、`查詢`、`查一下`、`找` $\rightarrow$ `TerminalVerb.SEARCH_FIND`。
     - 購買意圖：`買`、`購買` $\rightarrow$ `TerminalVerb.BUY`。
  3. **載體贅詞過濾**：
     過濾 `一首歌叫`、`一首歌名為`、`歌曲叫`、`商品叫`、`影片叫`、`叫`、`這首` 等贅詞。
  4. **核心目標實體提煉 (`targetEntity`)**：
     分離出核心名稱（例如從「去spotify買茶歌struct」提煉出 `targetEntity = "struct"`；從「打開spotify查詢一首歌叫晴天」提煉出 `targetEntity = "晴天"`）。
  5. **後續動作提取 (`followUpAction`)**：
     切分「並播放第一個」、「點第一個」等後續動作。

#### 2. 五階逆向回推狀態判定 (`evaluateBackwardChain`)
```kotlin
fun evaluateBackwardChain(
    report: VisionPipelineReport,
    spec: TerminalGoalSpec,
    workingMemory: InAppWorkingMemory = InAppWorkingMemory,
    screenHash: String = ""
): BackwardChainEvaluation
```
- **逆向推演執行步驟**：
  1. **Level 0 檢查（目標是否已在當前主畫面？）**：
     - 檢查主內容區 (`gridTapPoint.y in 140..860`) 是否存在標籤包含 `spec.targetEntity` 的節點（排除頂部搜尋列與底部導覽列）。
     - 若存在，判定為 `LEVEL_0_DIRECT_TARGET`，直接點擊該節點。
  2. **Level 1 檢查（是否已送出搜尋？）**：
     - 檢查 `workingMemory.hasSubmittedQuery(spec.targetEntity)`。
     - 若已送出搜尋，判定為 `LEVEL_1_SELECT_RESULT`，在視窗結果列表挑選最佳符合項目。
  3. **Level 2 檢查（當前畫面是否有真實輸入框 INP？）**：
     - 篩選 `shortTypeTag == "INP"` 且非禁用語音節點。
     - 若有 `INP`：
       - 若輸入框已填入關鍵字，點擊送出按鈕或第一筆建議列；
       - 若尚未填入，判定為 `LEVEL_2_INPUT_KEYWORD`，產生 `INPUT_TEXT` 動作，寫入 `spec.targetEntity`。
  4. **Level 3A 檢查（畫面是否有頂部假搜尋列/搜尋按鈕？）**：
     - 檢查 `gridTapPoint.y <= 360` 區域之節點。
     - 標籤包含 `想聽什麼`、`what do you want to listen to`、`藝人、歌曲`、`podcast`、`想找什麼`、`輸入關鍵字`、`搜尋`、`搜索`、`search`，或 NanoDet 類別為 `UI_SEARCH_BAR`。
     - 且該元件在當前畫面指紋下未被點擊過。
     - 若符合，判定為 `LEVEL_3A_TOP_FAKE_SEARCH_BOX`，產生 `TAP` 動作展開真實輸入框。
  5. **Level 3B 檢查（畫面底部導覽列是否有「搜尋/探索」Tab？）**：
     - 檢查 `gridTapPoint.y >= 780` 區域之節點。
     - 標籤包含 `搜尋`、`搜索`、`search`、`探索`、`explore`。
     - 若符合，判定為 `LEVEL_3B_BOTTOM_NAV_SEARCH_TAB`，產生 `TAP` 動作切換進入搜尋分頁。
  6. **Level 4 檢查（無搜尋入口時之階層回推）**：
     - 交由 `LookaheadPlannerEngine` 計算父分類前瞻期望值。

---

## 4. 整合計畫與協同運作流程 (Integration Plan & Cooperative Flow)

```
[使用者口語輸入] (例如: "去spotify買茶歌struct")
          |
          v
[DualLocalAiCoordinator.decomposeUserGoalCooperatively]
  - 呼叫 BackwardChainingPlanner.extractTerminalGoalSpec("去spotify買茶歌struct")
  - 提煉出: appHint="spotify", terminalVerb=PLAY, targetEntity="struct"
  - 自動規劃子目標操作鏈:
      1. LAUNCH_APP (啟動目標 App: Spotify)
      2. INPUT_SEARCH (搜尋 struct)
      3. TAP_TARGET (點擊播放 struct)
          |
          v
[DualLocalAiCoordinator.executeFourStageCooperativePipeline]
  - 進入 Spotify 後，進入 InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores
  - 呼叫 BackwardChainingPlanner.evaluateBackwardChain(report, spec)
      - 當前畫面首頁無 struct -> 觸發 Level 3B：點擊底部「搜尋」分頁 Tab
      - HUD 輸出日誌: "[逆向目標回推] 終極目標「struct」不在當前首頁 -> 回推前置條件 Level 3B：點擊底部「搜尋」分頁"
          |
          v
[下一幀: 進入搜尋探索分頁]
  - 畫面依然無 struct，且尚無真實輸入框
  - BackwardChainingPlanner.evaluateBackwardChain 觸發 Level 3A：點擊頂部假搜尋框「想聽什麼？」
  - HUD 輸出日誌: "[逆向目標回推] 尚未展開輸入框 -> 回推前置條件 Level 3A：點擊頂部假搜尋框「想聽什麼？」"
          |
          v
[下一幀: 鍵盤與真實 EditText 彈出]
  - 畫面出現 INP
  - BackwardChainingPlanner.evaluateBackwardChain 觸發 Level 2：輸入 "struct" 並送出
          |
          v
[下一幀: 搜尋結果頁載入]
  - 畫面出現歌曲 "struct"
  - BackwardChainingPlanner.evaluateBackwardChain 觸發 Level 0：直接點擊歌曲 "struct" 播放！
```

---

## 5. 嚴格四約束合規性驗證 (Strict Constraints Verification)

| 約束項目 | 規範標準 | 本次調查與設計驗證狀態 |
|---|---|---|
| **1. 100% 自研原創代碼** | 禁止引入任何第三方 AI、CV、OCR、JSON 函式庫或框架 | 已核實 `build.gradle.kts` 僅包含標準協程與 JUnit 4。`BackwardChainingPlanner` 僅採用純 Kotlin 演算法與 Android 基礎類別 (`Point`, `Rect`)，零外部依賴。 |
| **2. 零 Emoji 規範** | 程式碼、註解、日誌、HUD 字串與說明文件全數禁止包含 Emoji | 已全面審查設計規格，所有 Log 與 Trace 採用純文字徽章格式（如 `[逆向目標回推 Level 3B]`、`[Stage A]`），嚴禁使用任何圖示符號。 |
| **3. 嚴格禁用語音輸入** | 鐵律禁令：嚴禁 AI 點擊任何語音搜尋/麥克風按鈕，強制純文字輸入 | 逆向推演全面遵循 `isForbiddenVoiceOrCameraNode` 檢驗，對麥克風與語音搜尋按鈕強制判定無效並賦予 `-200f` 懲罰，僅鎖定文字搜尋入口。 |
| **4. 唯讀調查規範** | 本 Agent (Explorer BC 1) 為唯讀探查角色，不修改原始碼 | 本 Agent 未更動任何專案原始碼，所有調研與架構規劃均記載於 `.agents/teamwork/explorer_bc_1/` 專用目錄中。 |

---

## 6. 編譯與測試環境確認 (Build & Toolchain Environment)

- **Java JDK 路徑**：`C:\Program Files\Android\Android Studio\jbr` (JDK 25.0.2)
- **Gradle 執行方式**：
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  .\gradlew.bat testDebugUnitTest --console=plain
  ```
  已在環境中驗證執行，既有 30 個單元測試套件全數通過 (BUILD SUCCESSFUL in 11s)。
- **ADB 工具路徑**：`C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- **實機序號**：Samsung Galaxy A55 (`R5CX71DTN7L`)
- **發行 APK 編譯與安裝命令**：
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  .\gradlew.bat assembleRelease --console=plain
  & "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s R5CX71DTN7L install -r app\build\outputs\apk\release\app-release.apk
  ```
