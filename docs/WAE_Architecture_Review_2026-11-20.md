# WAE 底層架構現況（2026-11-20 盤點）

> 目的：UI 換完之後要回到「功能」。這份只回答三件事：**現在長什麼樣、哪裡會痛、先修哪裡**。
> 數字是用 `tools/` 之外的掃描腳本算的（不含 build/ 與測試）。

## 1. 規模

| 模組 | 檔案 | 行數 | 說明 |
|---|---:|---:|---|
| `core` | 98 | 8,606 | 純 JVM：規則、意圖、規劃、效果、記憶、收據 |
| `app` | 217 | 39,179 | Android 層：服務、UI、語音、裝置 |
| 測試 | `core` 35 檔／`app` 94 檔 | — | `core:test` 195、`app` 758（多為 Robolectric） |
| 資料 | 110 個 TSV／JSON | — | `app/assets` 69、`core/resources` 41 |

**最大的五個檔案**

| 行數 | 檔案 |
|---:|---|
| 3,951 | `app/.../core/WaeVisionAccessibilityService.kt` |
| 2,274 | `app/.../MainActivity.kt` |
| 1,743 | `app/.../core/WaeV26PipelineOrchestrator.kt` |
| 852 | `app/.../gen5/Gen5Runtime.kt` |
| 648 | `core/.../pipeline/Agent.kt` |

## 2. 分層與依賴方向

```
┌──────────────────────────── app（Android）────────────────────────────┐
│  UI：MainActivity / ChatActivity / AssistActivity / FloatingOverlay   │
│  服務：WaeVisionAccessibilityService（a11y 事件、感知、執行）          │
│  裝置：DeviceIO（唯一出口，2 個操作點）→ AndroidActuator / NodeOps     │
│  路由：ExecutionRouter                                                  │
│  兩條管線：V26（舊） / Gen5（新，含 LLM 與 TaskOS）                    │
└───────────────────────────────┬───────────────────────────────────────┘
                                │ 只依賴 core 的介面
┌───────────────────────────────┴─────── core（純 JVM）─────────────────┐
│  rule 1.5k｜pipeline 0.9k｜memory 0.8k｜effect 0.8k｜intent 0.7k       │
│  step｜routing｜receipt｜frame｜learn｜safety｜verify …                  │
└───────────────────────────────────────────────────────────────────────┘
```

- `core` **零 `import android.*`**（掃描 98 檔，0 命中）——這條線守得很乾淨，是整個專案最重要的邊界。
- 資料驅動：110 個資料檔 ＋ `Tunables`，改行為多數不用改程式。

## 3. 執行路徑（實際有幾條？）

```
使用者一句話
   └─ ExecutionRouter.route()
        ├─ FAST  → WaeV26PipelineOrchestrator（規則拆解 → 步驟）
        ├─ AGENT → Gen5Runtime →（有模型）LLM planner /（無模型）OpenEndedPlanner
        └─ ASK   → 反問
   service 內另有 TaskOS（長任務，預設關閉）與 Gen5Probe/自測入口
```

- 真正「會執行動作」的路徑是 **2 條**（FAST／AGENT），TaskOS 是第 3 條但預設關閉。
- 裝置出口已收斂：`DeviceIO` 只有 2 個操作點（perform／dispatch），其餘都呼叫它。

## 4. 五個會痛的地方（依嚴重度）

| # | 問題 | 為什麼痛 | 跡象 |
|---|---|---|---|
| 1 | **服務 3,951 行** | 感知、決策、執行、UI 更新全在同一個類別；改任何一段都可能弄壞別的 | 已抽 7 塊仍近 4,000 行 |
| 2 | **兩套管線並存** | V26（1,743 行）與 Gen5（852 行）做同一件事；同一個 bug 要修兩次，行為要靠 router 猜 | `ExecutionRouter` 用「目標結構完整」決定走哪條 |
| 3 | **UI 與邏輯綁死** | `MainActivity` 2,274 行含設定、開發工具、系統檢查、執行入口；換 UI 就是在動邏輯 | 這次改玻璃介面必須逐段 patch |
| 4 | **狀態碎片化** | 19 個 JSON 儲存、152 個 `object` 單例、94 處直接寫 `filesDir` | 備份／遷移／測試都難 |
| 5 | **測試偏單元** | 758 個 app 測試多為畫面／元件；「一句話 → 真的做到」的契約測試只有少數腳本 | 改完只能靠 `gen5_eval` 手動跑 |

## 5. 三個要守住的優點

1. **`core` 不碰 Android**：讓規則與規劃可以在 JVM 上測，速度與穩定度都靠這個。
2. **資料驅動**：110 個資料檔讓「加能力」多數時候是加一行 TSV，不是加一段 if。
3. **單一裝置出口**：`DeviceIO` 收斂後，I1 守門從 30 個點降到 2 個點。

## 6. 回到功能的建議順序

**里程碑 A：把「一句話 → 做到」變成可回歸的契約**
- 每個能力一條端到端測試（開 App／搜尋／音量／分享／通知…），跑在 `gen5_eval` 之外，可進 CI
- 目的：之後改任何底層，都能在 5 分鐘內知道有沒有壞

**里程碑 B：收掉第二條管線**
- 決定 V26 與 Gen5 留哪一條（建議 Gen5 為主、V26 只留「拆解器」當輸入層）
- 把 `ExecutionRouter` 的三條路徑縮成「有模型／沒模型」兩種

**里程碑 C：拆服務與 MainActivity**
- `WaeVisionAccessibilityService` 只留 a11y 事件與委派；感知／決策／執行各自成類別
- `MainActivity` 只留主畫面；設定與開發工具搬去 `SettingsActivity`

> **C-1 進度（2026-11-20）**：已新增 `settings/SettingsActivity.kt`（玻璃版設定頁：系統檢查 4 列、
> 助理資訊、自我測試、診斷包、關於），主頁「設定」膠囊改為開這一頁。
> 還沒做：把 `MainActivity` 裡舊的 `settingsDrawer`（約 800 行）刪掉、服務拆分。

> 判準：**能讓「驗證過的次數」變多的先做**，能讓「版面變大」的先不做。
