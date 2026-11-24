# 程式碼整理（Codebase Cleanup）實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改變任何使用者可見行為的前提下，把 46k 行、357 個檔案的專案整理到「新增功能時不必再繞過既有債務」的狀態。

**Architecture:** 先建立可量測的債務指標（Task 1），再依「低風險且規則明確」→「結構性拆分」的順序分批處理。每一批都以單元測試與 10 題真機抽樣作為關卡；任何一批造成行為退步就回退該批，不累積。

**Tech Stack:** Kotlin（Android，Gradle 8.6）、JUnit4、PowerShell 工具腳本、`ui_tunables.tsv` 資料驅動層。

**Spec:** `AGENTS.md`（專案硬性規則）＋ `docs/README.md`（現行文件索引）＋ 使用者要求：「為了後續的發展我們必須先把整個代碼整理整理」。

## 執行進度（每完成一批就更新這張表）

| Task | 狀態 | 證據 |
|---|---|---|
| 1 債務指標 | 完成 | `tools/debt_report.ps1` 四項 ok；commit `e6aa792` |
| 2 清 emoji | 完成 | 32 → 0；`tools/strip_emoji.py`；commit `f8d02ad` |
| 3 系統目標進 TSV | 完成 | `ui_system_targets.tsv` ＋ 5 個新測試；commit（Task 3） |
| 4 runCatching | 進行中 | 593 → **581**（3 批共 17 處）；ratchet 558 → 544 |
| 5 拆 Service | 進行中 | 3977 → **3784**（已抽 BaselineRecovery、ScreenCapture）|
| 6 拆 MainActivity | 未開始 | 目前 2078 行 |
| 7 10 題回歸腳本 | 完成（待真機驗證） | `tools/run_smoke_10.ps1` |
| 8 死碼／TODO | 完成（TODO） | 實測 **0 筆**：原 46 筆是 `-match 'TODO'` 誤命中 `.toDouble()`，腳本已修 |
| 9 語音入口統一 | 未開始 | 仍是第三份實作 |
## Global Constraints

- 一律 `$env:JAVA_HOME='C:\Users\User\.jdks\jdk-21.0.13.1+2'`、`$env:GRADLE_USER_HOME='E:\WAE\gradle-home'`。
- 單元測試指令：`.gradlew.bat :core:test :app:testFullDebugUnitTest --console=plain --no-daemon --offline`。
- **不得 `force-stop com.wae.agent`**（會殺掉無障礙服務）。
- 安裝後必須重新綁定：`adb shell settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService` 與 `settings put secure accessibility_enabled 1`。
- UI 不得出現 emoji（`AGENTS.md` §2）。
- 詞表、門檻、逾時一律放 `app/src/main/assets/open_source_ai_databases/*.tsv`；程式不得新增硬編碼清單或魔術數字。
- 可降級的失敗用 `com.wae.core.util.Degrade`，不要 `runCatching { }.getOrNull()`。
- TSV 一律 UTF-8 **無 BOM**。
- 每一批都要能被獨立回退（一個 commit 或一組 commit）。

## Review Focus

1. **行為不變**：整理不得改變任何指令的判定結果；抽樣 10 題（001–010）必須全過，且與整理前一致。
2. **吞錯不增**：`runCatching` 數量只能下降，不得上升（Task 1 的腳本會擋）。
3. **資料驅動不回退**：把硬編碼搬進 TSV 後，載入失敗要能降級（空表 ⇒ 舊行為不可用時要明確記錄，不可靜默）。
4. **大檔拆分後可尋**：拆完之後每個新檔要有單一職責與檔頭說明，否則等於只是搬家。
5. **測試不得為了通過而放寬**：修改既有測試的期望值時，必須在 commit message 說明「契約變更」的理由。

---

### Task 1: 債務指標腳本（先量測，再整理）

**Files:**
- Create: `tools/debt_report.ps1`
- Modify: `docs/README.md`（加一行指向這支腳本）

**Interfaces:**
- Produces: `tools/debt_report.ps1` 輸出固定欄位（`metric`、`value`、`baseline`、`status`），退出碼 0＝全數未退步，1＝有指標退步。

- [ ] **Step 1: 寫腳本**

量測並輸出四項：`runCatching` 行數、`TODO|FIXME` 行數、含 emoji 的行數、單檔最大行數。基準值寫在腳本內的 `$baseline` 雜湊表（runCatching 593、TODO 46、emoji 32、最大檔 3546）。

- [ ] **Step 2: 跑一次確認基準**

Run: `powershell -File tools\debt_report.ps1`
Expected: 四列 `status=ok`，退出碼 0。

- [ ] **Step 3: Commit**

```bash
git add tools/debt_report.ps1 docs/README.md
git commit -m "chore(debt): 加入可量測的技術債指標腳本"
```

---

### Task 2: 清除 UI emoji（規則明確、風險最低）

**Files:**
- Modify: `app/src/main/java/com/wae/agent/MainActivity.kt`、`app/src/main/java/com/wae/agent/core/WaeVisionAccessibilityService.kt`、`app/src/main/java/com/wae/agent/voice/AssistActivity.kt`（實際清單以腳本輸出為準）

**Interfaces:**
- Consumes: Task 1 的 `emoji` 指標。
- Produces: `emoji` 指標降為 0。

- [ ] **Step 1: 列出所有含 emoji 的行**

Run: `powershell -File tools\debt_report.ps1 -List emoji`
Expected: 32 行檔案:行號清單。

- [ ] **Step 2: 逐行移除 emoji，保留文字**

規則：只刪 emoji 字元與其後多餘空白，不改任何中文文案；`✓`、`⚠`、`⛔` 這類符號屬既有風格，**保留**（它們不是 emoji）。

- [ ] **Step 3: 驗證**

Run: `powershell -File tools\debt_report.ps1`；Expected: `emoji 0`。
Run: `.gradlew.bat :app:testFullDebugUnitTest --console=plain --no-daemon --offline`；Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore(ui): 移除畫面字串中的 emoji（AGENTS §2）"
```

---

### Task 3: 把硬編碼詞表搬進 TSV（三處）

**Files:**
- Modify: `core/src/main/kotlin/com/wae/core/system/SystemTargets.kt`
- Modify: `app/src/main/java/com/wae/agent/core/predicate/DecisionRanker.kt`（`PREFIX/SUFFIX` 正則若仍在使用）
- Modify: `app/src/main/java/com/wae/agent/core/system/SystemActions.kt`（時間／長度正則）
- Create: `app/src/main/assets/open_source_ai_databases/ui_system_targets.tsv`
- Test: `core/src/test/kotlin/com/wae/core/SystemTargetsDataTest.kt`

**Interfaces:**
- Produces: `SystemTargets.installFrom(lines: List<String>)`；`SystemTargets.normalize(text): Target?` 行為不變。
- Produces: `SystemActions.installFrom(lines: List<String>)` 既有簽名不變（已存在）。

- [ ] **Step 1: 建資料檔**

`ui_system_targets.tsv` 格式：`target <TAB> 別名（| 分隔）`。內容由現有 `ALIASES` 逐筆搬移，**一字不改**。

- [ ] **Step 2: 寫失敗測試**

```kotlin
@Test fun `aliases come from the shipped tsv`() {
    val lines = listOf("WIFI\twifi|無線網路", "VOLUME\t音量|volume")
    SystemTargets.installFrom(lines)
    assertEquals(SystemTargets.Target.WIFI, SystemTargets.normalize("無線網路"))
    assertEquals(SystemTargets.Target.VOLUME, SystemTargets.normalize("volume"))
}
```

- [ ] **Step 3: 跑測試確認失敗**

Run: `.gradlew.bat :core:test --tests "com.wae.core.SystemTargetsDataTest" --console=plain --no-daemon --offline`
Expected: FAIL（`installFrom` 未定義）。

- [ ] **Step 4: 實作 `installFrom` 並讓 App 啟動時載入**

在 `WaeApp` 或 `WaeOpenSourceDatabaseRegistry.ensureLoaded` 內呼叫載入器；載入失敗時 `Degrade` 記錄並保留空表（`normalize` 回 null）。

- [ ] **Step 5: 驗證**

Run: `.gradlew.bat :core:test :app:testFullDebugUnitTest`；Expected: 全綠，且 `PlannerSystemTargetsTest` 不需修改。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(data): 系統目標別名改由 TSV 提供（移除硬編碼清單）"
```

---

### Task 4: 償還 `runCatching` 債務（分批，每批 ≤ 60 處）

**Files:**
- Modify: 依 `tools/debt_report.ps1 -List runCatching` 輸出分批
- Modify: `app/src/test/java/com/wae/agent/core/SilentFailureRatchetTest.kt`（每批下調 baseline）

**Interfaces:**
- Produces: `runCatching` 指標單調下降；`SilentFailureRatchetTest` 的 baseline 同步下調。

- [ ] **Step 1: 分類第一批（60 處）**

挑選規則（不要憑感覺）：powershell -File tools\debt_report.ps1 -List runCatching 的輸出具備
「檔案路徑升冪 → 行號升冪」排序，取前 60 筆。下一批再取新的前 60 筆（已改過的不會再出現）。

判準：catch 後回傳預設值且不影響正確性 → `Degrade.orNull/orElse/orEmpty`；catch 後吞掉但錯誤會造成錯誤行為 → 改為讓例外往上丟（或加明確 log 後重丟）。

- [ ] **Step 2: 改完跑測試**

Run: `.gradlew.bat :core:test :app:testFullDebugUnitTest`；Expected: 全綠。

- [ ] **Step 3: 下調 ratchet 並驗證**

把 `SilentFailureRatchetTest` 的 baseline 減去本批數量，重跑；Expected: 綠。
Run: `powershell -File tools\debt_report.ps1`；Expected: `runCatching` 下降。

- [ ] **Step 4: Commit（每批一個）**

```bash
git commit -m "refactor(error): 第 N 批 runCatching 改為 Degrade／明確拋出"
```

- [ ] **Step 5: 重複直到指標 ≤ 300**

每批結束都跑一次 10 題真機抽樣（Task 7 的腳本），確認行為不變。

---

### Task 5: 拆 `WaeVisionAccessibilityService`（3546 行 → 五個檔）

**Files:**
- Create: `app/src/main/java/com/wae/agent/core/service/ScreenCapture.kt`（畫面擷取與快照）
- Create: `app/src/main/java/com/wae/agent/core/service/BaselineRecovery.kt`（`backOutToRootIfNested`、`looksLikeBrowser`、`hasTopBarBackAffordance`）
- Create: `app/src/main/java/com/wae/agent/core/service/DebugHudBridge.kt`（HUD 與 debug watcher 接線）
- Create: `app/src/main/java/com/wae/agent/core/service/PipelineEntry.kt`（`executeUserCommandPipelineAsync` 與其前置檢查）
- Modify: `app/src/main/java/com/wae/agent/core/WaeVisionAccessibilityService.kt`（只留生命週期與委派）

**Interfaces:**
- Produces: `BaselineRecovery.backOutToRootIfNested(service, tag: String)`（與現有私有方法同語意）。
- Produces: `PipelineEntry.run(service, rawCommand: String, onStepUpdate: ((String) -> Unit)?)`。
- 既有外部呼叫點（`MainActivity`、`UserCommandEntry`）簽名不變。

- [ ] **Step 1: 先搬 `BaselineRecovery`（最小、可測）**

把三個方法原封不動搬過去（改成 `object` + `service` 參數），原檔改成委派一行。

- [ ] **Step 2: 跑測試與真機 10 題**

Run: `.gradlew.bat :core:test :app:testFullDebugUnitTest`；Expected: 綠。
Run: `powershell -File tools\run_100.ps1 -From 1 -To 10 -WaitSeconds 20 -Out E:\WAE\cleanup_10.tsv`；Expected: 10/10 SUCCESS。

- [ ] **Step 3: 依序搬 `DebugHudBridge`、`ScreenCapture`、`PipelineEntry`**

每個檔案搬完都重跑 Step 2 的兩道關卡；任何一關失敗就回退該檔。

- [ ] **Step 4: 驗證檔案大小**

Run: `powershell -File tools\debt_report.ps1`
Expected: 最大檔行數 ≤ 2200。

- [ ] **Step 5: Commit（每搬一個檔一個 commit）**

```bash
git commit -m "refactor(service): 抽出 BaselineRecovery／ScreenCapture／PipelineEntry"
```

---

### Task 6: 拆 `MainActivity`（2078 行 → UI 建構抽離）

**Files:**
- Create: `app/src/main/java/com/wae/agent/ui/main/MainScreenBuilder.kt`
- Create: `app/src/main/java/com/wae/agent/ui/main/SettingsDrawerBuilder.kt`
- Modify: `app/src/main/java/com/wae/agent/MainActivity.kt`

**Interfaces:**
- Produces: `MainScreenBuilder.build(activity): MainScreenViews`，其中 `MainScreenViews` 持有 `goalInputBox`、`statusLineView`、`ambiguityContainer`、`executeBtnView`。
- `MainActivity` 只保留生命週期、事件處理與 `UserCommandEntry.run` 呼叫。

- [ ] **Step 1: 建 `MainScreenViews` 與建構器，先不改邏輯**
- [ ] **Step 2: 跑測試與 10 題抽樣（同 Task 5 Step 2）**
- [ ] **Step 3: 把設定抽屜搬到 `SettingsDrawerBuilder`**
- [ ] **Step 4: 驗證 `MainActivity` 行數 ≤ 900**
- [ ] **Step 5: Commit**

```bash
git commit -m "refactor(ui): 主畫面與設定抽屜抽離 MainActivity"
```

---

### Task 7: 整理驗證工具（讓「跑一次」變便宜）

**Files:**
- Create: `tools/run_smoke_10.ps1`
- Modify: `benchmark/README.md`（補一段「整理期間的驗證流程」）

**Interfaces:**
- Produces: `tools/run_smoke_10.ps1` 執行 001–010、輸出 `E:\WAE\smoke.tsv`、退出碼 0 代表 10/10。

- [ ] **Step 1: 腳本化 001–010**（沿用 `run_100.ps1` 的協定：force-stop 目標 App → HOME → 注入 → 按執行 → 等訊號）
- [ ] **Step 2: 實測**：`powershell -File tools\run_smoke_10.ps1`；Expected: 10/10、約 3 分鐘。
- [ ] **Step 3: Commit**

```bash
git commit -m "test(smoke): 10 題快速回歸腳本"
```

---

### Task 8: 死碼與 TODO 清理

**Files:**
- Modify: 依 `Select-String -Pattern 'TODO|FIXME'` 輸出
- Modify: `docs/README.md`（記錄已移除的殘留引擎）

**Interfaces:**
- Produces: `TODO|FIXME` ≤ 10，且每一筆保留的都附上「為什麼現在不做」。

- [ ] **Step 1: 列出 46 筆，分成「立刻刪／補說明／保留」**
- [ ] **Step 2: 刪除確認無用的程式與註解（每刪一組就跑測試）**
- [ ] **Step 3: 保留的改成 `TODO(原因): …` 格式**
- [ ] **Step 4: Commit**

```bash
git commit -m "chore: 清理死碼與 TODO，保留項補上理由"
```

---

### Task 9: 語音入口接上共用入口（目前是第三份實作）

**Files:**
- Modify: `app/src/main/java/com/wae/agent/voice/AssistActivity.kt:640-680`
- Test: `app/src/test/java/com/wae/agent/core/UserCommandEntryRoutingTest.kt`（新增）

**Interfaces:**
- Consumes: `UserCommandEntry.run(activity, raw, onStatus, onGen5)`（既有）。
- Produces: `AssistActivity` 不再直接呼叫 `agentSvc.runAgentCommand`；所有指令都經過同一條路由。

- [ ] **Step 1: 寫測試（純邏輯）**

```kotlin
@Test fun `voice and typing share the same route`() {
    // 同樣一句話，不論從哪個入口進來，都必須得到同一個 Route
    assertEquals(ExecutionRouter.route(ctx, "打開設定"), ExecutionRouter.route(ctx, "打開設定"))
}
```

- [ ] **Step 2: 把 `AssistActivity` 的分支改成呼叫 `UserCommandEntry.run`**

保留語音專屬行為（`finish()`、`HANDOFF_DELAY_MS`、TTS），只把「路由＋啟動＋管線」換成共用入口。

- [ ] **Step 3: 驗證**

Run: `.gradlew.bat :app:testFullDebugUnitTest`；Expected: 綠。
Run: `powershell -File tools\run_smoke_10.ps1`；Expected: 10/10。

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(voice): 語音入口改用共用指令入口"
```

---

## 驗證總結（每個 Task 結束都要跑）

| 關卡 | 指令 | 通過標準 |
|---|---|---|
| 單元測試 | `.gradlew.bat :core:test :app:testFullDebugUnitTest` | BUILD SUCCESSFUL |
| 債務指標 | `powershell -File tools\debt_report.ps1` | 四項皆 `ok` |
| 真機抽樣 | `powershell -File tools\run_smoke_10.ps1` | 10/10 SUCCESS |
| APK 大小 | `Get-Item app\build\outputs\apk\full\debug\*.apk` | 不增加超過 5% |

## 風險與回退

- **Task 5／6 是結構性搬移**：每個檔案一個 commit，任何關卡失敗只回退該 commit。
- **Task 4 可能暴露既有 bug**：把吞錯改成拋出時若測試失敗，代表原本就有錯被藏住——先修 bug，再繼續該批。
- **Task 3 的資料搬移**：別名一字不改；若 `PlannerSystemTargetsTest` 需要修改，代表搬移不忠實，回退重做。
