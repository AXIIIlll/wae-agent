# WAE 主線總表（Master Backlog）

> 日期：2026-11-17｜**這是唯一的工作清單**：舊的 S1–S9、這幾輪新增的（效果路由、世界模型探針、MWM 吸收項）全部合併在這裡。
> **不估時長**，只看順序、依賴、完成判準。狀態：✅ 已完成　🟡 部分　⬜ 未開始

---

## 0. 已完成（基線，供對照）

| ID | 項目 | 完成判準 | 狀態 |
|---|---|---|---|
| 0.1 | `:core` 純 JVM 模組（六個資料契約） | `:core:test` 綠；core 不 import Android | ✅ |
| 0.2 | 唯一管線 `Agent.run()`（含 Seek／Retreat／Replan） | 端到端測試可用假 Actuator 跑完 | ✅ |
| 0.3 | 反例庫 `RefutationLog` ＋ `FileMemory`（原子寫入、壞檔隔離） | 門檻升級／成功遞減／TTL／版本戳測試 | ✅ |
| 0.4 | 契約推導（kind → 效果／風險／證據規則） | 六種 kind 都有規則，`UNKNOWN` 不猜 | ✅ |
| 0.5 | 手段求解（成本排序＋反例約束） | 排序與禁用測試 | ✅ |
| 0.6 | 內建 TSV（參數／契約規則／動詞）＋空表即失敗 | `BundledDataTest` | ✅ |
| 0.7 | 目標解析階梯（精確→別名→語意→結構→反問） | 未見說法可解析、歧義不亂選 | ✅ |
| 0.8 | 廣度程序：Seek（捲動找）／Retreat（退回） | 兩個結構性程序測試 | ✅ |
| 0.9 | 效果路由 `Router` ＋ `CapabilityIndex` | 步數／可靠度／BAN／pinned 測試 | ✅（未接線） |
| 0.10 | 世界模型探針地基（baseline＋evaluator） | baseline 分數與靜默失敗 recall 可算 | ✅ |
| 0.11 | 靜默失敗計數（MWM §3.1） | 回報成功但後置條件不成立 → 計數 | ✅ |
| 0.12 | 版本戳（反例帶 `appVersion`，可落盤往返） | 存讀一致測試 | ✅ |
| 0.13 | Android 裝置層：`A11yFrameSource`／`AndroidActuator`／`Gen5Runtime`／`Gen5Probe` | **編譯通過、未在裝置驗證** | 🟡 |

> 目前 core 測試：**69 綠**；app 測試：**749 綠**；app 編譯通過。

---

## Phase A｜地基（把「能動」變成「可驗」）

| ID | 做什麼 | 完成判準 | 依賴 | 狀態 |
|---|---|---|---|---|
| A1 | S2 模擬器驗證：開設定／關藍牙／搜尋／回首頁／調音量 | 五個指令都回 `SUCCESS`，`Gen5Probe` 有結果檔 | 0.13 | ⬜ |
| A2 | S2 真機驗證（Samsung） | 同 A1，且記錄 OEM 差異 | A1 | ⬜ |
| A3 | 裝置層補齊：手勢捲動、長按、IME 送出 | 三個動作在模擬器可用 | A1 | ⬜ |
| A4 | I1 靜態守門：全樹只有一個 `dispatchGesture` 呼叫點 | CI 掃描綠 | 0.13 | ⬜ |
| A5 | 守門測試掃描根修正（`src/main/java` → 含 `core/src/main/kotlin`） | 搬檔後仍能抓到違規 | 0.1 | ⬜ |
| A6 | S3 Frame Ledger 落盤（`frames.jsonl`＋schema 版本） | 重啟後仍能命中舊畫面 | 0.2 | ⬜ |
| A7 | S3 感知攤銷量測 | 同畫面第二次感知成本 ≤ 20% | A6 | ⬜ |
| A8 | 畫面正規化規則（穩定 key、幾何量化、忽略時鐘類元素） | 指紋在同畫面重進時一致率 ≥ 99% | A6 | ⬜ |
| A9 | S4 `ControlKind` 特徵推導（class 名稱以外：checkable／editable／range／幾何／標籤歸屬） | 200 畫面推導一致率 ≥ 95% | A1 | ⬜ |
| A10 | S4 標註集：1,000 控制項 | 正確率 ≥ 95%；**< 85% 則契約路線作廢** | A9 | ⬜ |
| A11 | 契約規則改由 assets 載入（可換版） | 換 TSV 不需重編 | 0.4 | ⬜ |

**Phase A 出口**：A1、A4、A6、A10 全綠，否則不進 B。

---

## Phase B｜能力（廣度＝感知通道 × 手段 × 結構程序 × 組合深度）

### B-1 感知通道

| ID | 做什麼 | 完成判準 | 依賴 | 狀態 |
|---|---|---|---|---|
| B1 | OCR 通道接進新管線（`PpOcrEngine` → `Control.label`，帶 bounds） | WebView 頁面能取到文字元素 | A1 | ⬜ |
| B2 | OCR 框 → 座標手勢手段 | 無節點但有文字時可點中 | B1 | ⬜ |
| B3 | 圖示偵測（無文字按鈕）→ 座標候選 | 純圖按鈕可點中率 ≥ 70% | B2 | ⬜ |
| B4 | 捲動容器偵測（找出哪個區域可捲） | 捲動不再滑整個畫面 | A3 | ⬜ |
| B5 | 感知通道交叉驗證（a11y vs OCR 文字不一致 → 重感知） | 舊內容誤判率下降 | B1 | ⬜ |

### B-2 意圖（S5：不是加動詞）

| ID | 做什麼 | 完成判準 | 依賴 | 狀態 |
|---|---|---|---|---|
| B6 | `IntentFrame`（action／theme／recipient／quantity／condition） | 五角色可被填滿或明確標缺 | 0.7 | ⬜ |
| B7 | `FrameParser`＋角色詞庫（詞→角色，不是句子→動作） | 10 句不同說法都由同一組規則組出 | B6 | ⬜ |
| B8 | 目標解析階梯接 MiniLM（語意階） | 未見說法解析率提升；lite 可關 | B6 | ⬜ |
| B9 | 保留句測試集（20 句詞庫外說法） | 全數編對；編不出要反問 | B7 | ⬜ |
| B10 | 多效果 `EffectGraph`（含資料流：照片→分享→送出） | 三個多步任務可規劃 | B6 | ⬜ |
| B11 | 缺角色反問（`ABSTAIN(need=…)`） | 反問後補齊即完成，不重問 | B6 | ⬜ |

### B-3 手段與路由

| ID | 做什麼 | 完成判準 | 依賴 | 狀態 |
|---|---|---|---|---|
| B12 | 效果路由接線（`Goal` → `Router` → `Planner`） | 同一結果可由兩個以上 App 完成 | 0.9 | ⬜ |
| B13 | 路由失敗換路（reroute ＋記反例） | 首選失敗後第二條路成功率 ≥ 70% | B12 | ⬜ |
| B14 | 能力索引落盤（嘗試／成功／步數） | 重啟後路由決策不變 | B12 | ⬜ |
| B15 | 手段補齊：分享面板、深鏈、MediaSession | 三個手段各有一個可用案例 | A3 | ⬜ |
| B16 | 彈窗守衛（結構判斷：小視窗／無標題／只有按鈕，不靠字串清單） | 權限彈窗不再卡死任務 | A1 | ⬜ |
| B17 | 姿態分類（登入牆／風控／A-B）→ 停手＋教學 | 遇到即 `ABSTAIN`，不嘗試繞過 | B16 | ⬜ |
| B18 | 結果級評測（不看路徑，只看效果成立） | 20 App × 5 類 × 3 指令可產出覆蓋率 | B12 | ⬜ |

**Phase B 出口**：B1、B9、B10、B12、B16 完成，且 B18 有第一份覆蓋率。

---

## Phase C｜信任（敢交給陌生人）

| ID | 做什麼 | 完成判準 | 依賴 | 狀態 |
|---|---|---|---|---|
| C1 | `TaskReceipt`：每次任務一張本地收據（效果／手段／驗證／歸因／耗時，全為 hash 與結構） | 任一任務可離線重播決策 | 0.2 | ⬜ |
| C2 | 三通道驗證：a11y `typeNotificationStateChanged`（螢幕＋通知） | 通知可作為成功證據 | A2 | ⬜ |
| C3 | 靜默失敗率報表（依 App／版本） | 每次執行可列出：回報成功但未成立 | C1、C2 | ⬜ |
| C4 | 合約層擴充：budget／forbidden／blast radius | 超預算或踩禁用清單 → 中止並回滾 | 0.2 | ⬜ |
| C5 | 交易日誌＋反向效果 rollback（不是按 BACK） | 可撤銷率 100%（可逆操作） | C4 | ⬜ |
| C6 | 棄權卡（停→說清楚→兩個選項） | 陌生人看得懂率 ≥ 90% | C1 | ⬜ |
| C7 | 確認卡（高風險一律人工） | 敏感操作 100% 攔截 | C4 | ⬜ |
| C8 | 時間訊號（轉圈 vs 壞了）：畫面是否仍在變／重試次數／逾時 | 「還在跑」與「卡死」可區分 | A6 | ⬜ |
| C9 | 隱狀態被動推斷（購物車數／登入狀態，只讀畫面與通知） | 5 個 App 各 3 個變數可讀 | C2 | ⬜ |
| C10 | 反例庫檢視／刪除 UI（可讀、可刪、可解釋） | 使用者能自己移除一條禁令 | 0.3 | ⬜ |
| C11 | 約束可攜（簽章匯出／匯入，無內容） | 匯入後行為改變且可驗 | C10 | ⬜ |
| C12 | 前 N 次安全模式（新安裝保守） | 前 N 次高風險全確認 | C7 | ⬜ |

**Phase C 出口**：C1、C2、C3、C5、C6 完成——沒有這五項，不對外交付。

---

## Phase D｜收尾（減法與量測）

| ID | 做什麼 | 完成判準 | 依賴 | 狀態 |
|---|---|---|---|---|
| D1 | S9 刪舊：四條執行路徑 → 一條 | 全樹只有 `Executor` 能碰裝置 | B12 | ⬜ |
| D2 | 15+ 個 JSON 併入 `mem/`（四庫一 schema） | 只剩 4 個檔 | C1 | ⬜ |
| D3 | 141 個 `object` → 3 個（Memory／Tunables／Clock） | 靜態掃描通過 | D1 | ⬜ |
| D4 | 7 個捲動變體 → 1 個 `scroll(container, delta)` | 行為不變、程式 -80% | D1 | ⬜ |
| D5 | Service 瘦身（只留 a11y 事件與 device 實作） | 4,111 行 → ≤ 1,800 行 | D1 | ⬜ |
| D6 | 文件同步（README／Gen-5 現況／本表狀態） | 索引與現實一致 | — | ⬜ |
| D7 | 版本 3.0.0-wae／versionCode 6 | 安裝不衝突 | D1 | ⬜ |
| D8 | release 簽章＋APK 瘦身（full 519.8 MB） | 可交付大小、16KB 對齊不破 | D7 | ⬜ |
| D9 | 每週重跑存活率＋儀表板（版本腐爛預警） | 存活率 < 80% 會告警 | C1 | ⬜ |
| D10 | 100 題 benchmark 第一份**真實**基線 | 各層成功率可比較 | D1 | ⬜ |
| D11 | 陌生人測試（10 人 × 10 事，不教） | 拿到不指導成功率／放棄率 | C6 | ⬜ |
| D12 | 多機型矩陣（3 品牌 × 3 版本） | smoke 全綠 | A2 | ⬜ |

---

## Phase E｜探索（不卡主線，限時限裝置）

| ID | 做什麼 | 完成判準 | 依賴 | 狀態 |
|---|---|---|---|---|
| E1 | 世界模型探針：模擬器錄製 2 萬轉移 | baseline delta 準確率可算 | A1 | ⬜ |
| E2 | 遮蔽重建＋回環對比預訓練（5M，8GB） | 表徵可用於 delta 頭 | E1 | ⬜ |
| E3 | Gate：未見版本上打敗 baseline 5pp | 打不贏 → 只留異常偵測用途 | E2 | ⬜ |
| E4 | 異常偵測接線（預測差異 vs 實際差異） | 靜默失敗 recall ≥ 70% | E3 | ⬜ |
| E5 | 社群約束分享格式（簽章、無內容、可撤銷） | 兩台裝置間可交換且可驗 | C11 | ⬜ |
| E6 | 伴侶裝置驅動（`Actuator` 第二實作） | 一條驅動被切 24 小時內可換 | 0.13 | ⬜ |

---

## 關鍵路徑（只能照這個順序）

```
A1 → A9 → A10（契約生死線）
A1 → B1 → B2（看得到的 App）
B6 → B7 → B9 → B10（聽得懂的話）
0.9 → B12 → B13（多條路）
C1 → C2 → C3（不騙人）
D1 → D2 → D5（減法）
```

## 三個 Kill Gate（先寫好，免得以後自我安慰）

| Gate | 條件 | 沒過 |
|---|---|---|
| **G-契約** | A10 控制項正確率 < 85% | 契約退化成模板，路線收窄到 8 個高價值任務 |
| **G-意圖** | B9 保留句通過 < 70% | S5 作廢，改回「使用者選單式」互動 |
| **G-世界模型** | E3 打不贏 baseline 5pp | 世界模型只留異常偵測，不進主線 |

## 依賴原則（不要違反）

1. 沒有 A1，不寫任何裝置層新功能（否則又在盲改）。
2. 沒有 C1，不做任何「對外交付」的決定。
3. 沒有 D1，不加新功能——四條路徑之上再加一層，等於第五代沉積。
4. Phase E 永遠不阻塞 Phase A–D。

---

## 進度快照（2026-11-17 21:10，程式碼已寫、測試先跳過）

> 這一輪是「一次性寫完」的 spike：**只保證編譯通過、既有 core 測試仍綠**；
> 下表標 🟡 的項目都還沒有經過裝置或真機驗證。

| ID | 這輪做了什麼 | 檔案 |
|---|---|---|
| A3 | IME 送出（`ACTION_IME_ENTER`）、長按與滑動走手勢 | `AndroidActuator.kt` |
| A6 | 畫面帳本落盤：TSV、上限、壞行跳過 | `core/frame/FrameLedger.kt` |
| A9 | 型別推導獨立成層（特徵 → kind），感知層不再猜 class 名稱 | `core/frame/KindDeriver.kt`、`A11yFrameSource.kt` |
| A11 | 角色詞庫可從 assets 覆蓋，缺檔回內建並記 log | `assets/.../role_lexicon.tsv`、`Gen5Runtime.kt` |
| B1 | OCR 通道：`takeScreenshot`（API 30）＋ PP-OCR，稀疏畫面自動補 | `OcrChannel.kt`、`A11yFrameSource.kt` |
| B2 | 同一效果多一條「座標手勢」路；手段不支援 → 本任務封禁該手段並重規劃 | `Planner.kt`、`Agent.kt` |
| B6 | `IntentFrame`（五角色）＋ `EffectCompiler`（角色 → 效果，不列句子） | `core/intent/IntentFrame.kt` |
| B7 | 角色詞庫（ACTION／RECIPIENT／NOISE），加詞不加句 | 同上 |
| B11 | 缺角色 → 編不出效果 → `ABSTAIN(unknown_verb)`（尚未回 `need=`） | 同上 |
| C1 | 任務收據：每步效果／手段／結果／歸因／靜默失敗，append-only | `core/receipt/TaskReceipt.kt`、`Agent.kt` |
| B10 | `EffectGraph`：多效果＋資料流（查環、穩定拓撲、缺依賴） | `core/effect/EffectGraph.kt` |
| B14 | 能力索引落盤（原子寫入、可重載） | `core/routing/CapabilityStore.kt` |
| C3 | 靜默失敗報表（按 App／版本切開，含整體率） | `core/receipt/SilentFailureReport.kt` |
| C4 | 合約預算：步數／時間／高風險次數／禁用效果，執行前攔 | `core/safety/ContractBudget.kt`、`Agent.kt` |
| C5 | 交易日誌＋回滾計畫（推導可逆手段，不可逆另計） | `core/exec/TransactionLog.kt`、`Agent.kt` |
| C8 | 時間訊號：`SETTLED`／`STILL_CHANGING`／`STUCK` | `core/learn/TimingSignals.kt` |
| C11 | 約束可攜：匯出／匯入＋SHA-1 校驗，不含內容 | `core/memory/ConstraintPort.kt` |
| B12 | 效果路由接線：效果 → `Goal` → 選 App → 先啟動再計畫；成功／失敗寫回能力索引 | `core/routing/GoalExtractor.kt`、`RouteSelector.kt`、`Agent.kt` |
| B13 | 換路回饋：每個結果記錄該 App 的成功／失敗與步數（下次排序即改變） | `Agent.kt`、`CapabilityIndex` |
| B16 | 彈窗守衛：保守偵測（中央聚簇）→ **只反問、不自動關** | `core/learn/PopupGuard.kt`、`Agent.kt` |
| B18 | 結果級評測：效果種類覆蓋、每個效果的成功率 | `core/receipt/EffectEval.kt` |
| C12 | 暖機期：前 N 次使用，MEDIUM 以上一律確認 | `core/safety/Safety.kt` |
| B4 | 捲動容器選擇：優先「涵蓋畫面中央」的容器，其次面積最大 | `core/pipeline/Strategies.kt` |
| B5 | 輸入失敗退回「剪貼簿＋貼上」（自繪欄位不吃 SET_TEXT） | `AndroidActuator.kt` |
| B8 | 語意階：MiniLM 相似度注入解析階梯（模型沒載入自動退回前兩階） | `MiniLmSimilarity.kt` |
| B9 | 保留句評測：20 句詞庫外說法＋評測器（可離線跑） | `core/intent/HoldoutEvaluator.kt`、`assets/.../holdout_commands.tsv` |
| B17 | 姿態分類：登入／密碼頁 → 停手交還給人（不繞過） | `core/learn/PostureClassifier.kt`、`Agent.kt` |
| B7+ | 語序補強：「把藍牙關掉」也能解析（動作詞前後都看）、客套詞擴充 | `core/intent/IntentFrame.kt` |
| C2 | 三通道驗證：a11y 通知事件 → `NotificationInbox`（記憶體、30 則）→ `NotificationChannel`；成立時記 `CHANNEL:notification` | `NotificationInbox.kt`、`NotificationChannel.kt`、`wae_accessibility_service.xml`、`Agent.kt` |
| C10 | 反例可讀化＋可刪：`RefutationView` 一行人話、`Memory.removeRefutation`（落盤同步） | `core/memory/RefutationView.kt`、`Memory.kt` |
| B15 | 深鏈：`DeepLinkProbe` 問系統誰能開＋Actuator 支援 `ACTION_VIEW` | `DeepLinkProbe.kt`、`AndroidActuator.kt` |
| C9 | 隱狀態被動快照：開關／欄位／列數（不主動探測） | `core/learn/StateProbe.kt` |
| **A1** | **模擬器實測（Android 15 / x86_64）**：打開設定、回到主頁、調高音量、搜尋台北101 全部 SUCCESS；未知指令正確 `ABSTAIN(unknown_verb)` | 見下方「實測紀錄」 |
| A1+ | 可重跑的冒煙測試：`python tools/gen5_smoke.py`（案例在 UTF-8 TSV，結果含收據／轉移／反例行數） | `tools/gen5_smoke.py`、`gen5_smoke_cases.tsv` |
| D7 | 版本升級：`versionCode 6` / `3.0.0-wae` | `app/build.gradle.kts` |
| E1 | 轉移資料開始累積：每次執行自動寫 `mem/transitions.tsv`（只存指紋，不存內容） | `core/worldmodel/TransitionRecorder.kt`、`Agent.kt` |
| A1++ | 冒煙測試擴到 **8 個案例**（含高風險確認、兩個正確棄權）→ **8/8 PASS** | `tools/gen5_smoke_cases.tsv` |
| D9 | 存活率紀錄：`python tools/gen5_survival.py` → `bench/smoke_history.tsv`（可算近 10 輪存活率） | `tools/gen5_survival.py` |
| D2/D3 基線 | 盤點工具：**19 個 JSON 儲存庫、152 個 object、94 處 filesDir、I1 違規候選 30 處** | `tools/gen4_inventory.py` |
| D2 | 記憶體單一組裝點：`MemoryBundle`（四庫＋收據＋轉移一次建好、一次存） | `core/memory/MemoryBundle.kt`、`Gen5Runtime.kt` |
| C6/C7 | 卡片模型：把 `Outcome` 翻成「標題／說明／選項」（說卡在哪，不是說失敗） | `core/ui/CardFactory.kt` |
| I1 守門 | `tools/i1_allowlist.txt`：現有 30 個裝置操作點可留，**不准再增加** | `tools/gen4_inventory.py`（PASS） |
| **E5** | 效果層驗證擴充：query／toggle（可見開關）／app（pkg 比對）；多步計畫不再假成功 | `core/effect/EffectVerifier.kt` |
| **D10** | 系統設定：Wi-Fi／藍牙／飛航「開面板＋請你自己切」（`ABSTAIN(panel_opened)`）；亮度寫入＋讀回確認（需 WRITE_SETTINGS） | `core/system/SystemTargets.kt`、`SettingsActuator.kt`、`Planner.kt` |
| **C2** | 前置條件（系統設定類）：說「打開 Wi-Fi」時直接開到正確面板，不必先自己找到設定頁 | 同上 |
| — | 座標保護：a11y 負座標不再讓任務崩潰（`Path bounds must not be negative`） | `AndroidActuator.runGesture`、`Strategies.scrollStep` |

**實測（模擬器，2026-11-17 23:37）**

| 指令 | 結果 | 證據 |
|---|---|---|
| 打開Wi-Fi | **ABSTAIN(panel_opened)** | 前景 `com.android.settings/.panel.SettingsPanelActivity`；收據 `system_api:panel:wifi SUCCESS post=1` |
| 調亮 | ABSTAIN(no_candidate) | 卡在 **WRITE_SETTINGS 未授予**（appops 顯示 default）；程式路徑正確但被權限擋住 |
| 打開設定／相機／回首頁／音量／搜尋 | 全部 SUCCESS | 同前，8/8 冒煙 |

**已知問題（新）**

1. ~~亮度需要「修改系統設定」權限~~ → **已解**：manifest 宣告 `WRITE_SETTINGS`，執行前若沒授權會直接帶到授權頁並回 `ABSTAIN(permission_missing)`；授權後重按即成功（實測 102 → 132）。
2. **刪 `refutations.tsv` 不會清掉記憶體裡的禁令** → 已有開發指令 `!clear`（清 `files/mem/*` 與通知收件箱），回 `CLEARED files=N`。
3. **設定頁會回到上次的子頁**：`搜尋` 依賴「有可編輯欄位」，因此在某些狀態下會正確棄權。已把冒煙測試拆成兩份：
   - `gen5_smoke_cases.tsv`：**狀態無關**（開 App、回首頁、音量、亮度、面板、兩個棄權）→ 連 3 輪 9/9。
   - `gen5_bench_cases.tsv`：**有順序**（先開設定再搜尋、跨 4 支 App）。

**Round 10 實測**

| 項目 | 結果 |
|---|---|
| 調亮（已授權 WRITE_SETTINGS） | **SUCCESS**，`screen_brightness` 102 → 132，讀回確認 |
| 調亮（未授權） | `ABSTAIN(permission_missing)`，自動開到「修改系統設定」頁 |
| 打開Wi-Fi | `ABSTAIN(panel_opened)`，前景 = `SettingsPanelActivity` |
| 冒煙（9 例，狀態無關） | **3 輪 9/9 PASS** |
| 跨 App 基準（8 例） | 7/8（搜尋仍受設定頁狀態影響） |
| `!clear` | `CLEARED files=5` |
| 穩定度調整 | `settle_timeout_ms` 2.5s → **4s**；時序類失敗加退避（×3 間隔） |
| **F6** | 回滾：失敗且已動過手 → 反向操作（開關再點、清輸入、BACK 退回），記入收據；`panel_opened` 這種預期結果不回滾 | `core/exec/Rollback.kt`、`Agent.kt`（單元測試 3 個） |
| **C7** | 路徑快取：`programs.tsv`（指令＋App＋版本 → 上次成功的手段）；成功記錄、失敗遺忘、版本一變就失效 | `core/memory/ProgramCache.kt`、`Planner`（preferred hint） |
| **C2（一般）** | 前置條件表 `preconditions.tsv`：效果前綴 → 要先開的 App（資料驅動，不是硬編碼 App 名）；開完等一次載入再重規劃 | `core/pipeline/Preconditions.kt`、`Agent.kt` |
| **B7 前置** | 拍照：有標籤就點快門，沒有標籤就用「快門通用位置」（畫面中央、88% 高度）＋MEDIUM 風險 | `Planner.captureChain` |
| **A4** | 圖示通道：`takeScreenshot` → OmniParser YOLO → 無標籤 Control（`source=ICON`）；快門優先挑「底部中央」的圖示 | `IconChannel.kt`、`A11yFrameSource`、`Planner` |

**Round 13 實測**

| 項目 | 結果 |
|---|---|
| 拍照（第一次） | `SUCCESS steps=1`，但**相簿沒有照片**（`DCIM/Camera` 不存在）→ 這是假成功 |
| 修正後 | 拍照成功一律降級成 `ABSTAIN(capture_unverified)`：**按了快門，但沒有媒體讀取權限，不能確認存檔** |
| 冒煙（9 例）×2 | **9/9 PASS**（連兩輪） |
| 存活率紀錄 | 7 筆，最近一筆 PASS |

**這一輪抓到的假成功（重要）**

`frameChanged` 這種弱後置條件會把「相機 UI 有動畫」誤判成「照片拍好了」。實測驗證方式：`ls /sdcard/DCIM/Camera` **沒有檔案**。
修法：拍照類效果**一律要求外部證據**；沒有讀取媒體權限就拿不到證據，所以誠實回「我不確定」，而不是回成功。

**Round 14 實測**

| 項目 | 結果 |
|---|---|
| **App 內自我測試**（不用 adb） | `!selftest` → **9/9 通過**；設定抽屜多了「🧪 自我測試（9 項）」按鈕 |
| 自我測試內容 | 開設定／開相機／回首頁／音量×2／亮度／Wi-Fi 面板／兩個正確棄權（皆狀態無關） |
| **C9 批次** | 「調高音量3次」→ 產生 3 個效果 → **steps=3, tx=3, SUCCESS** |
| 重複上限 | `MAX_REPEAT=10`；只套用在音量／亮度（可安全重複），開 App、傳訊息不重複 |

**Round 15 實測**

| 項目 | 結果 |
|---|---|
| **H7 反例檢視** | 設定抽屜「🚫 反例（它學到的禁令）」→ 點一列即刪除；`!refs` 實測輸出 2 條人話（`這個畫面 ｜ SUBMIT:capture:photo ｜ gesture:TAP ｜ 型別判斷錯 ｜ 已停用 ×2 (v6)`） |
| **H3 語音接新管線** | `AssistActivity` 交棒前先試 Gen-5；有動作（Success 或 steps>0）就語音回「完成了／我停在這裡」，完全沒動作才退回舊路徑 |
| 自我測試（改動後） | **9/9 通過**（App 內跑完，無需 adb） |
| 尚未驗證 | 語音的**音訊**路徑（模擬器沒有麥克風）→ 需真機 |

**Round 16 實測**

| 項目 | 結果 |
|---|---|
| **B7 多效果（長任務）** | 「在設定裡搜尋藍牙」→ **SUCCESS steps=3**：開設定 →（focus 不支援→自動改用點搜尋列）→ 輸入；收據看得出中途換手段 |
| **歸因校準** | 「送出了但沒效果」不再誤標成「型別判斷錯」，改判 `LOCATION`（或畫面全空時 `PERCEPTION`） |
| **狀態機 bug** | 手段不支援時原本 `break` 沒設 failedStep → 從 PLANNING 直接跳 DONE 崩潰；已改成交給迴圈尾端統一重規劃 |
| **App 家族比對** | 開啟「設定」後前景可能是 `com.google.android.settings.intelligence`；改用「標籤包含 + 共用識別 token」判定，不再誤判失敗 |
| 自我測試 | 修正後 **9/9**（同一天另有 1 次 8/9，屬啟動時序抖動，已記在已知問題） |

**Round 17 實測**

| 項目 | 結果 |
|---|---|
| **D7 全域動作** | 「打開通知列」→ **SUCCESS**，前景 = `NotificationShade`；「最近任務」→ **SUCCESS**，前景 = launcher（recents） |
| 設計 | 通知列／最近任務用 `global:` 效果（與 `panel:` 分開），才不會被「面板已開、請你自己切」那條規則誤套 |
| 詞庫 | 加入「打開通知列／拉下通知列／最近任務／切換應用程式」 |
| 自我測試 | **9/9 通過**（無回歸） |
| 還沒做 | D11 分享面板（`ACTION_SEND` chooser）→ 下一輪 |

**Round 18 實測**

| 項目 | 結果 |
|---|---|
| **D11 分享（跨 App 傳遞）** | 「分享你好給訊息」→ **SUCCESS steps=1**，前景 = `com.google.android.apps.messaging/.multishare.MultiShareActivity` |
| 原理 | `ACTION_SEND` + `text/plain` + 指定套件 → **完全不需要知道對方 App 的畫面**；沒有指定就用系統 chooser |
| 意圖解析 | 支援「分享 X 給 Y」「把 X 分享到 Y」「傳送 X 到 Y」三種說法 |
| 驗證 | 用「前景是不是目標 App 家族」判定（沿用 Round 16 的 `AppResolver.matches`） |
| 自我測試 | **9/9 通過**（無回歸） |
| 下一步 | 同樣的 `share:` 路徑換 mime type 就能傳照片（`image/*` + `EXTRA_STREAM`） |

> 註：`Planner` 的 KDoc 一度寫了 `image/*`，把註解提前結束造成編譯失敗——已改成文字描述。這種鳥事只有編譯器會抓到。

**Round 19 實測**

| 項目 | 結果 |
|---|---|
| **分享截圖（跨 App 傳檔案）** | 「分享截圖給訊息」→ **SUCCESS steps=1**，前景 = messaging `MultiShareActivity` |
| 證據 | 截圖存到 `cache/shots/shot-*.png`（41 KB）；用 `content://com.wae.agent.shots/<name>` 交給目標 App |
| 為什麼不用 FileProvider | 專案沒有 `androidx.core`；自己寫 60 行唯讀 `ShotProvider`，只開檔案描述子、不列目錄 |
| 意圖解析 | 「分享截圖給 X」「把截圖傳給 X」「傳截圖給 X」 |
| 自我測試 | **9/9 通過**（無回歸） |
| 不需要的權限 | **零媒體權限**：自己拍的圖自己給，不讀使用者的相簿 |

**Round 20 實測**

| 項目 | 結果 |
|---|---|
| **F6 真機回滾（第一次真的跑到）** | 「在相簿裡搜尋貓」→ 開相簿成功、搜尋失敗 → `ABSTAIN(replans_exhausted)`，收據出現 **`rollback:3 ｜ rolled_back=3 ｜ SUCCESS`**，前景回到桌面 |
| 回滾內容 | 3 個反向操作（開 App 的反向是 BACK），全部成功 |
| **H5 進度顯示** | 多步任務執行時透過無障礙 overlay 顯示「第 n 步：開啟 App／搜尋／調整音量…」；結束自動收起 |
| 隱私 | 進度只顯示「做什麼」，不顯示使用者原文 |
| 自我測試 | **9/9 通過**（無回歸） |

> 這是 F6 從「只有單元測試」變成「真機驗證」的一輪。回滾成功的判準是：**收據裡有 rollback 列、而且前景真的離開那個 App**。

**Round 21：D1 刪舊路徑（失敗的一輪，但學到東西）**

| 步驟 | 結果 |
|---|---|
| 掃「0 引用」的類別 | `TwinRuntime` 看起來 0 引用（只有自己的檔案提到它） |
| 刪掉它（402 行） | 編譯失敗：`TwinStep`、`TwinRecorder`、`TwinStore`、`TwinTaskKey` 全都在**同一個檔案**裡，服務層大量使用 |
| 救援 | 從 Gradle 的 dex 產物用 `dexdump` 還原類別介面（欄位順序、方法簽章），再用既有 758 個單元測試當規格重寫 |
| 結果 | **758/758 單元測試通過**、App 內自我測試 **9/9**、`TwinStore` 的圖／重放／相似度語意與測試完全一致 |

**這次踩到的雷（寫下來避免再犯）**

1. **「0 引用」只代表這個名字沒被提到，不代表檔案沒用**：一個 `.kt` 可以定義 6 個類別，其他 5 個可能被大量使用。
   → 刪檔前要看「檔案裡所有頂層宣告」的引用，而不是檔名。
2. **這個 repo 有很大一塊程式碼沒進 git**（`git ls-files` 查不到），刪掉就真的沒了。
   → 刪檔前先確認 `git ls-files --error-unmatch <path>` 有輸出，否則先備份。
3. **dex 是有效的救援來源**：`dexdump -f` 能還原欄位名／型別／建構子參數順序，配合既有測試就能重建到規格等級。

**淨結果**：行數沒有減少（402 → 約 330 行），但**行為回到規格、測試全綠**。D1 的真正減法還沒開始——
下一次要用「先確認 git 追蹤、再確認整檔引用」的流程，從 `AgentRunner`（5 個裝置操作點）與 `WaeV26PipelineOrchestrator` 這種**確定有替代路徑**的地方下手。

**Round 22：D1 第一次真正的減法（I1 收斂）**

| 項目 | 之前 | 之後 |
|---|---|---|
| `AgentRunner` 的裝置操作點 | 5（click／gesture／focus／setText／scroll 各寫各的） | **0** |
| 新增 `NodeOps` | — | **2**（`perform` 節點動作 ＋ `gesture` 手勢，就是全部原語） |
| I1 允許清單總量 | 30 | **27**（-3） |

**順手修掉的一類 bug**：以前 `AgentRunner`、`Strategies`、`AndroidActuator` 各自處理座標，
負座標造成過 `Path bounds must not be negative` 崩潰。現在 `NodeOps.gesture` **一律拒絕負座標**，
呼叫端不必再各寫一次判斷。

驗證：`gen4_inventory.py` → **I1 GUARD: PASS**（2 sites）；單元測試 **758/758**；App 自我測試 **9/9**。

**Round 23：D1 第二批（服務層 17 → 0）**

| 檔案 | 之前 | 之後 |
|---|---|---|
| `WaeVisionAccessibilityService.kt` | 17 | **0** |
| `NodeOps.kt` | 2 | **3**（+`dispatch` 給拖曳／多點手勢） |
| `AndroidActuator.kt` | 8 | 8 |
| **總裝置操作點** | **27** | **11（-59%）** |

做法：把服務裡的 14 個節點動作改走 `NodeOps.perform(node, action, args)`，3 個手勢改走
`NodeOps.dispatch(this, gesture[, callback])`；文件註解裡那行會被守門掃到的字樣也一併改寫。

驗證：`I1 GUARD: PASS`、單元測試 **758/758**、App 自我測試 **9/9**。

**這批的中間失誤（都修好了，記下來）**

1. 批次取代把一句**註解**也改成程式碼樣式（`NodeOps.perform(EditText, …)`）——已改回人話。
2. `dispatch` 少了第一個參數（service），編譯才抓到；補上 `this`。
3. 有一個呼叫多帶了 `null`（原本 `dispatchGesture(gesture, callback, null)` 的第三個參數），已移除。

**還沒收斂的**：`AndroidActuator` 的 8 個點是 Gen-5 的**唯一**裝置入口，屬預期；
真正剩下的技術債是「舊路徑與 Gen-5 各有一個入口」——要合併得先決定舊 LLM 路徑的存廢。

**Round 24：I1 抵達終點（全 App 一個裝置入口）**

| 檔案 | 之前 | 之後 |
|---|---|---|
| `AndroidActuator.kt` | 8 | **0**（只決定「做什麼」，不再直接送手勢／節點動作） |
| `NodeOps.kt` | 3 | **0**（改成純轉呼叫） |
| **`DeviceIO.kt`（新）** | — | **2**（`perform` 節點動作、`dispatch` 手勢，就是全部原語） |
| **總裝置操作點** | **11** | **2（自第一輪 30 起算 -93%）** |

`DeviceIO` 只做最低層：不挑元素、不判斷成不成功、不重試。
`tap`／`swipe`／`gestureAt` 都建在那 2 個原語上，所以守門永遠只會看到 2 個點。

順手修正：`AndroidActuator` 的 KDoc 原本寫著「唯一可以呼叫 dispatchGesture／performAction」，
那早就不成立了——已改成描述它真正的職責（決定做什麼），實作在 `DeviceIO`。

驗證：`I1 GUARD: PASS`（僅 `DeviceIO` 2 sites）、單元測試 **758/758**、App 自我測試 **9/9**。

**Round 25：I3 能力基線（24 條）**

新檔 `tools/gen5_baseline20.tsv`（24 條、六類能力），設計原則是**每條都能從任何畫面跑**：

| 類別 | 例子 | 條數 |
|---|---|---|
| app | 打開設定／相機／相簿／地圖 | 4 |
| system | 回到主頁／通知列／最近任務 | 9（含歸零用） |
| volume / brightness / batch | 調高音量、調亮、調高音量3次 | 5 |
| share | 分享截圖、分享文字 | 2 |
| panel / contact / query | Wi-Fi、藍牙、傳訊息、天氣 | 4 |

**結果：24/24 PASS**，記在 `bench/gen5_baseline_history.tsv`。

**這輪抓到的兩個「不穩定」其實是測試設計問題**

1. `分享截圖給訊息` 在大清單裡失敗、單獨跑成功 → 因為上一條把畫面留在訊息 App 裡。
   修法：每次開 App 後插入「回到主頁」歸零，而不是放寬驗證。
2. `在設定裡搜尋藍牙`（多步、狀態相關）**這次真的失敗**：`input:ime` 兩次都「送出了但文字沒出現」。
   它是狀態相關的（設定頁會回到上次的子頁），已從 24 條基線移到 `gen5_bench_cases.tsv`，
   列為下一輪要修的問題（不是在基線裡放寬預期）。

> 基線的價值不是「100% 好看」，而是**下次改動時能一眼看到有沒有變差**。
> 所以：基線只放狀態無關的案例；狀態相關的另外一份，失敗要當 bug 修。

**Round 26：輸入驗證與 App 驗證的兩個假成功**

| 問題 | 修正 |
|---|---|
| 設定搜尋列 `SET_TEXT` 回 true 但**根本沒吃字** → 收據寫 SUCCESS、畫面空白 | 輸入後**讀回欄位內容**，沒進去就當失敗（並改回剪貼簿貼上，貼上也讀回） |
| `query:` 驗證只看「畫面上有沒有這個字」→ 建議清單也能讓它過關 | 保持現狀但記錄為已知弱點（下一步：要求「欄位內容」而不是「畫面出現」） |
| App 啟動的**後置條件**用家族比對，但**效果驗證**還在精確比對 pkg → 開「設定」成功後被判失敗、重試三次才棄權 | `EffectVerifier` 改用 `AppResolver.matches`（與後置條件同一套判定） |

第 3 點是這輪最有價值的發現：**同一件事有兩個判定標準，就會出現「後置說成功、驗證說失敗」的鬼打牆**。
收據裡會看到 `SUCCESS|1|` 三次然後 `ABSTAIN(replans_exhausted)`——那不是隨機不穩，是兩套標準在打架。

驗證：單元測試全綠、App 自我測試 **9/9**、24 條基線 **24/24**（`bench/gen5_baseline_history.tsv` 已記錄兩次）。

**仍然沒修好的**：`在設定裡搜尋藍牙` 會成功或失敗取決於設定頁有沒有先給建議清單——
有建議時「點搜尋列」就被驗證成成功（其實沒輸入）；沒建議時真的去輸入，而 `SET_TEXT` 被忽略、貼上也沒用。
下一步要改成「**等欄位出現再輸入**」＋「query 的驗證看欄位內容」，而不是「畫面有這個字」。已列為下一輪首要。

**Round 27：查詢驗證收緊（結果：部分改善，仍未過關）**

做了兩件事：

1. **query 驗證改成「欄位內容」優先**：畫面上有輸入框時，只認欄位裡的文字；
   沒有輸入框（結果頁、聊天視窗）才退回到「畫面上看得到」。
   → 這堵掉了「建議清單剛好有這個字就當成功」的假成功。
2. **等欄位出現**：搜尋類效果在欄位還沒出現時，最多等 3 次（每次重抓畫面，不是盲等）。

**實測（連跑三次，每次先回主頁、清反例）**：

| 次 | 結果 |
|---|---|
| 1 | `ABSTAIN(replans_exhausted)` steps=5 |
| 2 | `SUCCESS` steps=3 |
| 3 | `ABSTAIN(replans_exhausted)` steps=4 |

**結論：還是不穩（1/3）。** 這條留在 `gen5_bench_cases.tsv`，不放進 24 條基線，也不放寬預期。
下一個做法是改用**系統的搜尋意圖**（例如 `ACTION_SEARCH`）取代「點搜尋列＋打字」——
那條路不需要輸入法、不受建議清單影響，但要先確認目標 App 支援，屬下一輪的事。

驗證：App 自我測試 **9/9**（其餘能力無回歸）。

**Round 28：搜尋改走系統意圖（已建好，但還沒被選中）**

| 做了什麼 | 內容 |
|---|---|
| 資料表 | `assets/.../ui_search_actions.tsv`（`pkg<TAB>搜尋 action`），程式不認得任何 App 名稱 |
| 核心 | `Planner.queryChain` 新增第一條候選：`Means.Intent(action="wae.action.SEARCH", data=查詢字串)`，後置條件是「畫面出現這個字」 |
| App | `SearchActions` 讀表；`AndroidActuator.runSearchIntent` 用**目前前景 App** 查表、帶 `SearchManager.QUERY` 開搜尋頁 |
| 測試規格 | 查詢預設走意圖；意圖被反例擋掉時退回 `focus + input`（新增測試，全綠） |

**意圖本身是有效的**（用手動 adb 證明）：

```
am start -a com.android.settings.action.SETTINGS_SEARCH --es query 藍牙
→ EditText text="藍牙"（已填好）、畫面出現「藍牙 / 藍牙位址 / 藍牙掃描 …」
```

**但代理人流程還沒走到那條路**：收據只出現 `intent:android.intent.action.MAIN`（開 App），
查詢仍然走 `a11y:focus`／`a11y:click`；三次実測仍是 1/3。
下一步不是再猜，而是**把「選了哪條候選鏈」記錄下來**（plan notes／log），
確認是候選沒被產生、被反例擋掉、還是成本排序選錯——這是下一輪第一件事。

驗證：單元測試全綠（含新的查詢鏈規格）、App 自我測試 **9/9**。

**Round 29：搜尋意圖接上了（根因：代理人跑在自己的畫面裡）**

上一輪的疑問有了答案。把「選了哪條候選鏈」印出來之後：

```
第 1 步：開啟 App via intent:android.intent.action.MAIN
第 2 步：搜尋   via intent:wae.action.SEARCH      ← 其實有選中
```

**根因**：`runSearchIntent` 用「目前前景 App」查表，但代理人執行時前景是 **我們自己的 MainActivity**（`com.wae.agent`）→ 查不到任何搜尋 action → 回 `noEffect` → 反例 → 換 UI 路徑。

**修法**：
1. Actuator 記住「這個任務剛開啟的 App」（`lastTargetPkg`），搜尋意圖优先用它，而不是「現在的前景」。
2. 搜尋意圖的後置條件改成「畫面真的換了」（`pkgChanged`）——搜尋頁還在載入時，用「出現這個字」會誤判失敗；
   真正的正確性交給效果驗證（欄位裡有沒有這個字）。

**實測（連跑三次，每次先回主頁、清反例）：3/3 SUCCESS**（`steps=4 silent=0`，走 `intent:wae.action.SEARCH`）。

**同一輪的基線**：24 條 → **23/24**。失敗的是 `分享截圖給訊息`（在序列中失敗、單獨跑 2/2 成功），
屬「前一個指令留下的狀態」造成的抖動；基線照實記錄 23，不放寬預期。

> 這輪的教訓：**「意圖有沒有被選中」跟「意圖有沒有成功」是兩件事**。
> 之前只看收據（沒出現 intent 列）就以為沒選中，其實是選中了但執行時查錯 App。
> 把「選了什麼」印出來，五分鐘就找到根因。

**Round 30：分享截圖的序列抖動（修好，基線回到 24/24）**

上一輪基線掉到 23/24，失敗的是 `分享截圖給訊息`：單獨跑 2/2 成功、在大清單裡失敗。

**根因**：`takeScreenshot` 在連續動作（前一個指令剛換頁、動畫還沒停）時會直接失敗；
原本失敗就回 `noEffect`，沒有重試。

**修法**：截圖失敗時重試 3 次（每次間隔遞增 160/320/480ms）。
不是放寬驗證，是修「本來就該等一下」的時序問題。

**實測**：24 條基線 **24/24**（重跑兩次確認，記錄在 `bench/gen5_baseline_history.tsv`）。

**Round 31：圖示通道終於真的在跑（A4）**

| 問題 | 根因 | 修法 |
|---|---|---|
| `icons=0` 永遠是 0 | `OmniParserIconDetector.ensureLoaded()` **從來沒有人呼叫** → `isReady` 永遠 false → 圖示通道直接短路 | `IconChannel` 延遲載入（第一次要用才載模型），log 出現 `IconDetector ready` |
| 同一幀連續抓圖，第 3 次拿到 0 個候選 | Android 對**截圖有速率限制**，連續呼叫會失敗 | 加 800ms TTL 快取；被限流時 3 秒內回上一次結果 |

**證據（模擬器實測）**：

```
WAE_VISION: IconDetector ready: input=images outputs=[output0]
WAE_VISION: icon scan 320x640 boxes=10 / 13      ← 模型真的有偵測到圖示
WAE_GEN5:   dryrun ... controls=12 icons=5       ← 圖示真的進了 frame
```

現在「沒有無障礙標籤的按鈕」（相機快門、純圖工具列）終於有候選可以點；
`captureChain` 會優先挑「底部中央」的圖示。

驗證：單元測試全綠、24 條基線 **24/24**。

**Round 32：用圖示通道點快門——卡在模擬器沒有相機**

| 觀察 | 結果 |
|---|---|
| 相機畫面圖示偵測 | `icon scan 320x640 boxes=2`（快門那顆**沒被偵測到**，太小／不像 icon） |
| 代理人實際按下去 | `拍照 → ABSTAIN(capture_unverified) steps=1`（有按、但無法確認） |
| **手動**在快門座標 `(160,563)` 點一下 | **也沒有照片**（`/sdcard/DCIM` 檔案數 0） |
| 結論 | **模擬器的相機是 stub，按快門不會存檔**——這條只能在真機驗 |

**順手做的兩件事**

1. 圖示 log 現在會印出**分數與座標**（`(88,573,232,616:0.43)`），下次才知道模型看到什麼。
2. **系統權限對話框不再亂點**：新增 `Posture.PERMISSION_WALL`——畫面同時出現「允許」與「拒絕」時，
   直接 `ABSTAIN(permission_missing)`，由人決定。這是「代理人不得代按權限」的硬邊界。
   （相機第一次啟動會跳「要允許相機存取位置嗎」，之前代理人會對那個畫面亂點圖示。）

驗證：24 條基線 **24/24**（沒有把權限頁誤判成一般畫面）。

**Round 33：從相簿分享照片（D11 完成）**

| 指令 | 結果 | 證據 |
|---|---|---|
| 分享照片給訊息（已授權） | **SUCCESS** steps=1 | `WAE_PHOTOS: share photo id=1000000041 uri=content://media/external/images/media/1000000041`；前景 = messaging `MultiShareActivity` |
| 分享照片給訊息（未授權） | `ABSTAIN(permission_missing)` | 自動開到本 App 的權限頁（`SpaActivity`），不是讓它「試了才知道」 |
| 24 條基線 | **24/24** | 無回歸 |

**做法**：查 `MediaStore.Images` 取**最近一張**（`DATE_ADDED DESC`，只取一筆、查完關 cursor），
用 `content://` + `EXTRA_STREAM` 交給目標 App。權限 `READ_MEDIA_IMAGES`（Android 13+）。

**為什麼要宣告媒體權限（一個刻意的取捨）**：

- 不宣告 → 只能分享「自己拍的截圖」，分享相簿照片做不到。
- 宣告 → 使用者說「分享照片」時才查，**只查最近一張**、不建索引、不上傳。
- 所以：權限只在「明確要求」時使用，而且先檢查、沒有就帶去授權頁。

**同時修掉一個假成功**：舊的通用句型 `分享(.+?)給(.+)` 會把「分享照片給訊息」解析成
「分享文字『照片』給訊息」——收據看起來 SUCCESS，其實只傳了兩個字。現在照片／截圖的句型優先於純文字。

> 這輪的教訓：**句型順序就是語意優先序**。`分享X給Y` 太寬，會被更特定的句型搶先，否則使用者的意圖會被靜默降級。

**Round 34：新增「返回」＋把分享照片放進基線（27 條）**

| 新增 | 說明 |
|---|---|
| `返回` / `上一頁` | 全域 BACK；後置條件「畫面或 App 變了」（同 App 換頁也算成功） |
| `分享照片` | 已在第 33 輪做好，這輪放進基線變成常駐檢查 |
| 基線 | **27 條 → 27/27 PASS**（記錄在 `bench/gen5_baseline_history.tsv`） |

**一個順序上的細節**：`返回` 排在 `打開設定` 之後。
在桌面按返回什麼都不會發生，後置條件不會成立——那不是 bug，是「沒有上一頁」。
所以基線把它放在「一定離開得了」的位置，而不是讓它從桌面跑。

**Round 35：基線擴到 37 條（I3 覆蓋面）**

新增 8 個 App／情境：日曆、時鐘、聯絡人、Chrome、飛航面板、`調低音量5次`、`打開電話`（後來移除）。

**37/37 PASS**，記錄在 `bench/gen5_baseline_history.tsv`。

**擴充時抓到的兩個標籤問題**（都是「使用者怎麼叫它」vs「系統怎麼標它」）：

| 說法 | 結果 |
|---|---|
| `打開電話` | 序列中失敗、單獨跑成功 → **移除**（App 標籤解析不穩，列為待修） |
| `打開通訊錄` | 序列中失敗（標籤解析不到） |
| `打開聯絡人` | **SUCCESS** ← 改用系統實際標籤 |

> 教訓：基線會逼你面對「同一支 App 有好幾個叫法」。現在的做法是**用系統標籤當基線**，
> 其他叫法靠別名表支援（那是下一步：`ui_app_aliases.tsv`）。

**Round 36：App 別名表（叫法 ≠ 系統標籤）**

新增 `ui_app_aliases.tsv`（`別名<TAB>系統標籤`）與 `AppAliases`：
使用者說「電話」「撥號」「Phone」，系統標籤可能是 `電話` 或 `Phone`；
說「通訊錄」，系統標籤是 `聯絡人`。

解析順序：**套件名 → 精確標籤 → 包含標籤 → 別名 → 別名對到的標籤**。
只做字串對照，真正套件仍由 `PackageManager` 解析，所以換裝置／換語言也能用。

| 指令 | 結果 |
|---|---|
| 打開Phone | **SUCCESS**（別名 → 電話） |
| 打開通訊錄 | **SUCCESS**（別名 → 聯絡人） |
| 打開電話 | **SUCCESS** |
| 基線（39 條） | **39/39 PASS** |

> 這輪也修正了上一輪的誤判：我以為「打開電話」不穩而把它移除，
> 實際上是「打開通訊錄」的標籤解析失敗（兩者在亂碼輸出裡長得很像）。
> 別名表一次修好兩個，電話也放回基線了。

**Round 37：服務層瘦身第一批（Twin 點擊記錄抽出）**

`WaeVisionAccessibilityService` 從 **4,287 → 4,087 行（-200）**。

抽出的第一塊是「使用者在別的 App 點了一下 → 記成一條被動軌跡」：

| 之前 | 之後 |
|---|---|
| 服務裡 ~45 行，直接呼叫 `TwinRecorder.begin/complete/flush`、`PassiveRecordPolicy`、`Rect`、`Handler` | `core/twin/TwinClickRecorder`（一個類別，只依賴 4 個查詢 lambda） |
| 服務的 `TYPE_VIEW_CLICKED` 分支 | 3 行：`twinClickRecorder?.onClick(pkg, src)` |

為什麼挑這段當第一塊：它的邊界最清楚——**只記錄、不執行、不改畫面**，
依賴的東西都可以用 lambda 注入（被動記錄開關、是否忙碌、已知套件、當前畫面指紋），
不需要把整個服務傳進去。

驗證：單元測試全綠、39 條基線 **39/39**。

**Round 38：被動學習其實是死的（兩個根因）**

上一輪把 `TwinClickRecorder` 抽出來之後，我想驗證「它真的會記錄嗎」——結果發現**整條路從來沒通過**。

| 根因 | 說明 | 修法 |
|---|---|---|
| **無障礙服務根本沒訂閱點擊事件** | `wae_accessibility_service.xml` 的 `accessibilityEventTypes` 只有 window／content／scroll／notification，**沒有 `typeViewClicked`** → `TYPE_VIEW_CLICKED` 分支永遠收不到事件 | 加入 `typeViewClicked` |
| **白名單雞生蛋** | 被動記錄只記「已經有 Twin 歷史的 App」，但沒有歷史就不會記錄 → 永遠空 | 白名單加上「**收據裡真的成功操作過的 App**」當種子（實測 known=13） |

**修好後的證據**：

```
WAE_PASSIVE: click pkg=com.google.android.apps.nexuslauncher record=true disabled=false busy=false known=13
WAE_PASSIVE: passive step recorded pkg=com.google.android.apps.nexuslauncher
→ files/twin/steps.jsonl
   {"pkg":"...nexuslauncher","action":"TAP","target":"11月17日 週二","ok":true,"ms":812,...}
```

驗證：39 條基線 **39/39**（多訂閱一種事件沒有讓基線退步）。

> 這輪的教訓：**「程式碼寫了」不等於「功能活著」**。
> 一個沒有被訂閱的事件型別，可以讓整段邏輯安靜地永遠不執行——沒有錯誤、沒有 log，只有「功能好像沒感覺」。

**Round 39：再抓兩個「活著但沒在用」的東西**

用同一招（先問「它真的會被觸發嗎」）檢查其他儲存與索引，又抓到兩個：

| 症狀 | 真相 | 修法 |
|---|---|---|
| `files/mem/frames.tsv` **12.8 MB / 8,764 行** | 記憶體有上限（`maxEntries`），**檔案沒有**——只增不減 | 超過 4 MB 自動壓縮，只留最近 1,000 行（可注入參數、有單元測試） |
| `capabilities.tsv` **只有表頭** | `CapabilityIndex` 從來沒被寫入 → Router 永遠沒有資料 | `Agent` 成功／失敗時記一筆（效果類別 → `GoalKind`，App → pkg） |

**實測**：

```
frames.tsv:  12,876,358 bytes → 1,531,271 bytes（壓縮生效）
capabilities.tsv:
  com.google.android.settings.intelligence  OPEN_ITEM  1  1  1
  com.google.android.apps.messaging         SEND_TEXT  1  1  1
  com.google.android.calendar               OPEN_ITEM  1  1  1
```

驗證：單元測試全綠（含新的壓縮測試）、39 條基線 **39/39**。

> 這一輪沒有新增任何「使用者看得到的能力」，但它修掉的是**會慢慢惡化**的問題：
> 手機儲存被一個 12 MB 的檔默默吃光、Router 因為沒有資料而永遠學不到「哪支 App 便宜」。這種債不會自己好。

**Round 40：把「只增不減」一次清完**

上一輪修了 `frames.tsv`；這輪把同樣的問題清到其他日誌：

| 檔案 | 之前 | 之後 |
|---|---|---|
| `receipts.tsv` | 只增不減（實測 256 KB 且持續長大） | 超過 **2 MB** 壓縮到最近 **2,000 筆** |
| `transitions.tsv` | 只增不減（實測 216 KB） | 超過 **4 MB** 壓縮到最近 **5,000 筆** |

共用一個 `AppendLog`（append 前檢查大小 → 截斷保留最近 N 行），兩個 store 都改走它，
並補了 2 個單元測試（**驗「有上限」而不是「剛好幾行」**——壓縮後會再長回一批，這是設計）。

驗證：單元測試全綠、39 條基線 **39/39**。

> 測試一開始寫錯語意：我斷言「壓縮後剛好 3 行」，實際是「壓縮後又長回一批」。
> 這不是程式錯，是**測試描述錯了**。改成驗上限，才是在描述真正的契約。

**Round 41：同一件事的多種說法（15 條變體→全過）**

新增 `tools/gen5_phrasings.tsv`：15 句「同一件事的不同講法」，第一輪只過 **10/15**。

| 失敗的說法 | 原因 | 修法（加詞，不加程式） |
|---|---|---|
| 回首頁 | 只認得「回到主頁」 | 詞庫加 `回首頁／回桌面` |
| 調大聲、調小聲 | 只認得「調高音量／調低音量」 | 詞庫加 `調大聲／大聲一點／調小聲／小聲一點` |
| 亮一點、暗一點 | 只認得「調亮／調暗」 | 詞庫加 `亮一點／暗一點` |

**修完：15/15 PASS**。加上主基線 39 條 → **54/54**。

**這輪驗證了「加詞不加句」這條規則**：五個失敗全部靠詞庫解決，**沒有一行流程程式碼被改**。
如果當初為了「調大聲」去加一條 if，現在就會有兩條路徑要維護；加詞只有一個地方要改。

> 已知邊界：目前是「先比對長詞」的策略，所以「大聲一點」會整句命中；
> 但像「把聲音調大聲一點」這種長句還沒測過——那是下一批變體的事。

**Round 42：長句與禮貌詞（12 條 → 全過，總計 66 條檢查）**

新增 `tools/gen5_longphrases.tsv`：12 句真實口語（「幫我打開設定」「可以幫我回到主頁嗎」
「把聲音調大聲一點」「聲音太小幫我調大聲一點」「請問今天天氣如何」）。第一輪 **11/12**，
唯一失敗的是「幫我分享照片給訊息」——因為分享句型要求句子**從「分享」開始**。

修法：新增 `core/intent/Politeness`，在**解析前**剝掉開頭與結尾的禮貌層：

```
開頭：請幫我 / 可以幫我 / 幫我 / 請 / 麻煩 / 可以 / 能不能 / 我要 / 我想
結尾：可以嗎 / 好嗎 / 行嗎 / 嗎 / 呢 / 吧 / 謝謝 / 拜託
```

**只剝頭尾、中間一個字都不動**（避免「幫我把檔案移動到資料夾」這種句子被吃掉語意）。

**結果**：長句 12/12、變體 15/15、主基線 39/39 → **66/66**。

> 這輪的設計選擇：與其在每個句型前面加「可選前綴」，不如在**進入解析前清一次**。
> 前綴只有一處要維護，新句型自動受益——這是「加一層」而不是「每個地方都加一次」。

**Round 43（續）：100 題評測第一次跑完 → 100/101**

新增 `tools/gen5_eval.py`：把四套案例合成一次可重跑的評測，結果寫進 `bench/gen5_eval_history.tsv`。

| 套件 | 結果 |
|---|---|
| baseline（主基線） | **39/39** |
| phrasings（說法變體） | **15/15** |
| long（長句＋禮貌詞） | **12/12** |
| extra（更多 App 與情境） | **34/35** |
| **總計** | **100/101** |

唯一失敗：`在設定裡搜尋藍牙`——**已知的狀態相關案例**（設定頁會回到上次的子頁；
單獨跑是 3/3，但在大清單裡會受前一條影響）。它留在 extra 套件裡照實記 FAIL，不放寬預期。

**評測本身的兩個坑（都已修）**：

1. **Windows 主控台是 cp950**：印中文直接 `UnicodeEncodeError`，評測腳本在第一套就崩了。
   修法：`sys.stdout.reconfigure(encoding="utf-8", errors="replace")`。
2. **等待時間太短會掉條**：`--wait 11` 只跑到 35/39，`--wait 14` 才是 39/39。
   所以預設值改回 14，並在參數說明裡寫清楚（這是實測值，不是猜的）。

**同時打包了兩個 APK**：

| 版本 | 大小 | 用途 |
|---|---|---|
| full release | **518 MB** | 含 Qwen3-0.6B 離線模型 |
| lite release | **117 MB** | 不含模型，其餘功能相同 |

**Round 45：把唯一那條 FAIL 修掉 → 101/101**

上一輪唯一失敗是 `在設定裡搜尋藍牙`（在大清單裡失敗、單獨跑 3/3）。這輪先**重現**再修：

| 步驟 | 結果 |
|---|---|
| 重現（分享 → 回主頁 → 在設定裡搜尋） | 3/3 全過——重現不了 |
| 讀 code 找原因 | 搜尋意圖的後置條件是 `pkgChanged`；但搜尋 Activity **就在同一個套件裡**（`settings.intelligence`），開完設定後 pkg 根本不會變 → 後置永遠不成立 |
| 修法 | 後置改成 `frameChanged`（畫面真的換了）；正確性仍由效果驗證（欄位裡有沒有那個字）負責 |
| 驗證 | 精確序列 3/3；extra 套件 **35/35** |

**累計：101/101**（39 主基線 + 15 變體 + 12 長句 + 35 額外情境）。

> 這條 bug 的形狀值得記：**用 pkg 當「有沒有到達」的證據，在「同一個套件內換頁」時就會失效。**
> 畫面指紋（fp）才是「有沒有變」的正確依據；pkg 只適合「有沒有換 App」。

**Round 46：評測分級跑（30 分鐘 → 4 分鐘）**

新增 `tools/eval_suites.tsv`（套件清單＋標籤＋估計秒數）與 `gen5_eval.py` 的篩選參數：

```powershell
python tools/gen5_eval.py                    # 全部（約 25 分鐘）
python tools/gen5_eval.py --tag lexicon      # 只跑詞庫相關（實測 4 分鐘）
python tools/gen5_eval.py --suites baseline,extra
```

| 改什麼 | 跑什麼（`--tag`） | 檢查數 |
|---|---|---|
| 句型／詞庫／禮貌詞 | `lexicon` | 27 |
| Planner／手段／後置條件 | `planner` | 74 |
| Actuator／App 層 | `device` | 74 |
| 記憶體／儲存 | `memory` | 39 |
| 全部 | （不加參數） | 101 |

實測：`--tag lexicon` → **27/27**，4 分鐘完成（原本要等 25 分鐘才看到同樣的答案）。

> 分級的價值不是「跑得少」，而是**改完馬上知道有沒有打到**。
> 之前每次改詞庫都要等半小時，實際上只有 27 條跟它有關。

**Round 47：讓評測用實測時間，而不是我的猜測**

`gen5_eval.py` 現在會：

1. **量每套的實際耗時**，寫進 `bench/eval_timings.tsv`
2. 下次算 ETA 時**優先用實測值**，沒有才用估的
3. `--dry-run` 可以只印計畫不真的跑

實測輸出：

```
$ python tools/gen5_eval.py --dry-run
將執行：baseline, phrasings, long, extra（估計 23 分 52 秒）

  baseline    550s（估計） 標籤=device,memory,planner
  phrasings   210s（估計） 標籤=lexicon
  long        172s（實測） 標籤=lexicon      ← 這個是跑過的
  extra       500s（估計） 標籤=device,planner
```

只跑了 `long`（12/12 PASS，**172 秒**），但這一輪的重點不是那 12 條，是**評測開始記得自己做多久**。

> 同一個原則第四次出現：**讓資料取代猜測**。
> 先前的例子是「被動學習白名單靠收據」（Round 38）、「驗證用家族比對」（Round 29）、
> 「測試等待時間用實測值」（Round 44）。這次輪到評測自己的估計值。

**Round 48：服務瘦身第二塊，順手修掉「看不見的失敗」**

| 項目 | 之前 | 之後 |
|---|---|---|
| `WaeVisionAccessibilityService` | 4,277 行 | **4,085 行（-192）** |
| 抽出的第二塊 | — | `AgentDebugWatcher`（debug 期觀察 `agent_goal.txt`，~100 行） |

**先驗證「它還活著」**（上一輪的教訓）：

```
put agent_goal.txt='打開設定' → agent_result.txt
result=FAIL steps=0 llmCalls=0 msg=plan failed     ← lite 沒有模型，預期失敗
```

重點不是 FAIL，是**檔案有被讀、有被執行、有被寫回**——這條路徑搬家之後仍然活著。

**順手修的診斷問題**：`gen5_smoke.py` 加 `--out`，把結果寫成 UTF-8 TSV。

原因是這輪一度看到 `38/39` 卻**看不出哪一條掛掉**——Windows 主控台把中文顯示成亂碼，
我連「打開聯絡人」和「打開電話」都分不出來。現在失敗長這樣：

```
# command	expect	actual	pass
打開設定	SUCCESS	SUCCESS	PASS
```

重跑後是 **39/39**（那次 38/39 是啟動時序抖動，不是程式錯）。

> 這輪的教訓：**看不見的失敗比失敗更危險**。亂碼讓我把「哪一條壞了」變成猜測，
> 而猜測就會修錯東西——上一輪我才因為看錯亂碼，把「打開電話」誤判成不穩而移除。

**Round 49：連跑三次基線，抖動不存在**

上一輪看到的 38/39 讓人不安，所以這輪**連跑三次**並逐條比對：

```
run 1: PASS (39/39)
run 2: PASS (39/39)
run 3: PASS (39/39)
```

`flake_report.py` 統計：**3 次執行、117 筆結果、27 種指令，全部每次都過**。

結論：之前那次 38/39 是**單次啟動時序抖動**（相機/設定啟動慢），不是固定某一條壞掉。
連跑三次全部乾淨，所以不需要為了它改程式——**先量，再決定要不要修**。

**順手做的兩個工具與文件**：

| 產出 | 用途 |
|---|---|
| `tools/flake_report.py` | 多次執行逐條比對，找出「有時過有時不過」的指令（會保留重複，因為「回到主頁」在基線裡出現很多次） |
| `docs/WAE_How_To_Verify.md` | 操作手冊：安裝三行、自我測試、101 條評測（含 `--tag` 分級）、抖動分析、看它學到什麼、常見症狀對照 |

> 這一輪沒有改任何產品程式碼，但它把「38/39」從**傳聞**變成**數據**：
> 三次全過 → 是抖動；三次都同一條掛 → 是 bug。差別決定要不要動刀。

**Round 50：服務瘦身第三塊 ＋ 把能力清單改成「真的」**

| 項目 | 之前 | 之後 |
|---|---|---|
| `WaeVisionAccessibilityService` | 4,085 行 | **4,071 行** |
| 抽出的第三塊 | — | `perception/PageScanner`（掃畫面文字給驗證器用，純函式） |
| 9 條自我測試 | — | **9/9**（服務仍然健康） |

**更重要的一件事：能力清單原本在說謊。**

`WAE_Gen5_Capability_Map.md` 有 97 列，但很多「⬜ 還沒做」其實早就做完了：

| ID | 文件原本寫 | 實際 |
|---|---|---|
| A4 圖示按鈕偵測 | ⬜ | ✅ 模擬器驗證（10–13 候選/幀） |
| C2 前置條件推導 | ⬜ | ✅ 資料驅動＋搜尋意圖 |
| C7 路徑快取 | ⬜ | ✅ `programs.tsv` |
| D10 系統設定 | ⬜ | ✅ 面板＋亮度讀回 |
| D11 分享面板 | ⬜ | ✅ 文字／截圖／相簿照片 |
| E5 效果層驗證 | ✅ 僅 query | ✅ query／toggle／app |
| F6 回滾 | 🟡 反向未接 | ✅ 真機驗證 `rollback:3` |
| H7 反例頁 | ⬜ | ✅ App 內可看可刪 |
| I3 100 題 | ⬜ | 🟡 101 條已完成（模擬器） |

已全部改正。**一份說謊的清單比沒有清單更危險**——它會讓下一個人重做一次已經做過的事，
或以為某個能力不存在而繞路。

> 這也解釋了為什麼要有基線：清單是「我以為」，基線是「跑出來」。
> 兩者不一致時，永遠相信跑出來的那個。

**Round 51：讓「文件說謊」變成會被自動抓到的錯**

新增兩個檔案：

| 檔案 | 作用 |
|---|---|
| `tools/capability_evidence.tsv` | 48 條 ✅ 的證據（案例字串／程式碼關鍵字／測試檔／具體人工證據） |
| `tools/doc_consistency.py` | 檢查清單上每個 ✅ 都有可查的證據，查不到就 FAIL |

```powershell
python tools/doc_consistency.py
# 清單：97 列，✅ 48 條，證據檔 48 條
# 一致性檢查：PASS（每個 ✅ 都能指出證據）
```

**第一次跑就抓到 7 條證據寫錯**（路徑是 `java/` 其實是 `kotlin/`、關鍵字不在那個檔案、測試檔名不對）。
這正是這個檢查存在的理由：連我剛寫的證據表都會猜錯，更何況是幾個月後的記憶。

> 現在「✅」不是形容詞，是**可驗證的主張**：
> 要嘛指得出一條基線案例，要嘛指得出一段程式碼，要嘛寫清楚是什麼時候、用什麼證據驗的。

**Round 52：真機驗證清單（可自動跑，模擬器已 5/5 × 2）**

新增兩份：

| 檔案 | 內容 |
|---|---|
| `docs/WAE_RealDevice_Checklist.md` | R1–R8：前置三行、服務綁定、自我測試、亮度讀回、被動學習、相機快門、分享照片、搜尋 ×3、完整評測；每項有指令、預期輸出、失敗怎麼查、結果登記表 |
| `tools/device_checklist.py` | R1–R5 自動化；**判準寫死數字**（不比「看起來差不多」） |

**模擬器實跑（連兩次）：5/5**

```
PASS R1 服務綁定      dumpsys 有 wae
PASS R2 自我測試      9/9
PASS R3 亮度讀回      128 → 158
PASS R4 被動學習      3524B → 4197B
PASS R5 相機快門      略過（模擬器 stub 不存檔）
```

**三個「檢查自己也會騙人」的地方（都修了）**：

1. **R3 亮度**：原本一直按「調暗」，跑到極值就「調不動」→ 假失敗。
   改成先把亮度設到 128，再按「調亮」→ 每次都是 128→158。
2. **R4 被動學習**：在設定頁點，事件卻來自 `com.google.android.gms`（覆蓋層），
   不在白名單 → `record=false`。改在**桌面點 App 圖示**（launcher 一定在白名單）。
3. **R5 照片計數**：`find … 2>/dev/null` 被 adb 拆成參數，數到整個 `/sdcard`（22 萬筆）。
   改成單一字串 `ls /sdcard/DCIM/Camera 2>/dev/null | wc -l`。

> 三次都是同一件事：**檢查腳本本身也要被檢查**。
> 一個永遠 FAIL 的檢查會讓人忽略它，一個永遠 PASS 的檢查比沒有更糟。

**Round 53：R6／R7 也自動化，完整清單跑出 7/7**

加了兩項：

| 檢查 | 判準（寫死） |
|---|---|
| R6 分享照片 | logcat 出現 `WAE_PHOTOS: share photo id=` **且** 前景是訊息 App |
| R7 搜尋 ×3 | 三次都 SUCCESS（每次先回主頁、清反例） |

**兩次完整結果（記在 `bench/device_check_history.tsv`）**：

```
第一次：5/7（R2 自我測試 8/9、R7 搜尋 2/3）
第二次：7/7
```

**中間查了一件事**：R2 的「打開設定」失敗讓我以為又壞了，
所以單獨連跑五次 `打開設定`：**5/5 全過**。
→ 這是序列中的啟動時序抖動，不是邏輯錯；第二次完整跑就 9/9。

> 現在的判讀規則很簡單：**單獨跑穩、序列偶爾掉 → 時序；單獨跑也不穩 → bug。**
> 這條規則讓我省下「每次看到紅字就跳下去改」的時間。

**Round 54：一條命令看整體健康**

新增 `tools/health.py`，把收尾要跑的四種檢查串成一個有 exit code 的指令：

```powershell
python tools/health.py                  # 守門 + 裝置檢查（約 8 分鐘）
python tools/health.py --with-tests     # 再加單元測試
python tools/health.py --guards-only    # 只跑靜態守門（幾秒）
```

**實測輸出**：

```
健康檢查：
  PASS  I1 裝置入口守門        I1 GUARD: PASS
  PASS  能力清單一致性          一致性檢查：PASS（每個 ✅ 都能指出證據）
  PASS  裝置檢查 R1–R7       總計：7/7

總計：3/3
```

只有跑到裝置檢查時才記歷史（`bench/health_history.tsv`）——
守門-only 的分數不能當「整體健康」，那會讓歷史看起來一直很好。

> 這輪沒有新功能，是把「收尾要做什麼」從我的記憶變成程式。
> **漏跑等於沒跑**：以前靠印象決定要不要跑守門，現在一個指令、一個 exit code。

**Round 55：服務瘦身第四塊（開關事實收集）**

| 項目 | 之前 | 之後 |
|---|---|---|
| `WaeVisionAccessibilityService` | 4,071 行 | **4,027 行（-44）** |
| 抽出的第四塊 | — | `perception/ToggleFacts`（找可勾選元素＋它的標籤與狀態，純函式） |

抽法用**大括號配對**（不是行號）：先找到函式起點，再數 `{`／`}` 找結尾，把整段搬到新檔。
這比之前用行號可靠——這個檔的行號對不上內容已經誤導我好幾次。

**驗證（用上一輪做的 `health.py`，一次驗完）**：

```
PASS  I1 裝置入口守門        I1 GUARD: PASS
PASS  能力清單一致性          一致性檢查：PASS
PASS  裝置檢查 R1–R7        總計：7/7
總計：3/3
```

> 這輪的流程價值大於程式價值：**抽一塊 → 一條命令驗完**。
> 之前抽完還要記得「跑基線、跑自我測試」，現在 `health.py` 一次做完，還會自動記歷史。

**Round 56：把「判定」從服務裡拔出來**

| 項目 | 之前 | 之後 |
|---|---|---|
| `WaeVisionAccessibilityService` | 4,027 行 | **4,016 行** |
| 抽出的第五塊 | — | `core/goal/GoalOutcome`：觀察→判定的分界（純決策，不碰 Android API） |

**關鍵在切線的位置**：`verifyGoalOutcome` 原本混了兩件事——

1. **觀察**：前景 pkg、畫面指紋變化、頁面文字、可選元素、數值、開關狀態（需要服務）
2. **判定**：把「目標 ＋ 觀察到的事實」丟進 `GoalCompiler`，得到「算不算達成」（純函式）

現在 1 留在服務、2 搬到 `GoalOutcome.decide(Inputs)`。這個切法讓判定可以**獨立思考**：
它只吃字串與數值，沒有任何 Android 依賴，也不需要知道畫面是怎麼來的。

驗證（`health.py` 一次跑完）：I1 守門 PASS、清單一致性 PASS、裝置 R1–R7 **7/7**。

> 抽了五次（Twin 點擊 → 除錯監看 → 頁面文字 → 開關事實 → 目標判定），
> 共同的判準只有一條：**能不能用「輸入 → 輸出」講清楚**。
> 講得清楚的搬得動；講不清楚的（`executeSingleLocalGoalWithRecovery` 那種 677 行）
> 搬了只會把混亂換位置。

**Round 57：R7 搜尋的真相（走意圖，不走 UI）**

之前 R7 有一次 2/3，一直不確定是哪裡不穩。這輪連跑三次並**看收據**：

```
run 1: SUCCESS steps=2  SELECT:query:藍牙|intent:wae.action.SEARCH|SUCCESS
run 2: SUCCESS steps=2  同上
run 3: SUCCESS steps=2  同上
```

**3/3，而且三條都走同一條路**：開設定 → 搜尋意圖（2 步，沒有焦點、沒有點搜尋列、沒有輸入法）。

也就是說正常路徑是「**兩步**」，而先前失敗的那次是落到 UI 路徑（focus/click/input）。
所以判讀方式現在寫進清單了：

| 收據長相 | 意思 |
|---|---|
| `SELECT:query:藍牙\|intent:wae.action.SEARCH\|SUCCESS`（steps=2） | 正常 |
| `SELECT:query:藍牙\|a11y:focus\|UNSUPPORTED` | 落到 UI 路徑 → `lastTargetPkg` 沒拿到或查表失敗 |
| `SELECT:query:藍牙\|input:ime\|NO_EFFECT` | 輸入法吃不到字（設定頁已知） |

> 這輪沒有改程式。它把「R7 到底走哪條路」從猜測變成**看收據就知道**。
> 很多「不穩」其實是「走到備援路徑」——備援會過，但比較慢也比較脆。

**Round 58：手段統計（退化的第一天就會看到）**

新增 `tools/means_stats.py`：從 `receipts.tsv` 聚合「每個效果走哪條路」，並算**備援佔比**。

**實跑（模擬器收據 2,382 步）**：

| 效果 | 全部歷史 | 最近 4 小時 |
|---|---|---|
| `SELECT:query:藍牙` | intent 58／input 14／focus 12 → **備援 44%** | intent **29**／其他 0 → **備援 0%** |
| `SELECT:query:台北101` | focus 50／input 6 → 100% 備援 | —（舊資料，早於意圖路徑） |
| `SUBMIT:capture:photo` | 座標 100% | 座標 100%（設計如此） |

**這張表說明了兩件事**：

1. 搜尋意圖**真的有效**：最近 4 小時 29 次全部走意圖，0 次掉回 UI。
2. **歷史平均值會騙人**：44% 備援是「修好之前」的資料混進來的。所以工具加了 `--since-minutes`。

全域統計：2,382 步中 **380 步是靜默失敗**（回報成功但後置條件不成立，16%）——
這個數字以前只有 `silentFailureRate()` 算得出來，現在看得到**發生在哪個效果**。

> 這輪的意義：把「哪條路開始退化」變成一個**數字**，而不是等它變成失敗。
> 成功率是結果，手段分佈是**過程**；過程變了，結果只是還沒變壞而已。

**Round 59：380 步「靜默失敗」其實只有 56 步是真的**

上一輪算出 380 步靜默失敗（回報成功但後置條件不成立）。這輪把問題問得更準：

> **那一步後來補救了嗎？**

同一筆收據裡若該效果後面有 `post=1` 的成功步，那先前那次就只是**重試**，不是放掉。

| 指標 | 數字 |
|---|---|
| 靜默步（含重試） | 380 |
| **真·靜默失敗**（同任務內沒補救成功就放掉） | **56／1,558 次任務（3.6%）** |

分佈：

```
SUBMIT:capture:photo      24   ← 模擬器相機 stub，按快門不會存檔（已知）
NAVIGATE:app:相機          12   ← 同上，相機啟動後畫面沒變
NAVIGATE:app:設定           6
SELECT:query:藍牙           6
SUBMIT:share:image:訊息     4   ← 目標 App 已在前景，pkg 沒變
NAVIGATE:app:聯絡人         4
```

**結論**：真正需要修的信號只有 56 次，而且六成集中在「相機」——那是模擬器沒硬體，不是程式錯。
如果只看 380 這個數字，會得到完全錯誤的優先序。

> 這是第二次「同一個數字，換個問法就翻盤」（上一次是 100 題的 38/39）。
> 指標不是真相，**指標的回答範圍才是**：380 回答「有多少步的後置不成立」；
> 56 回答「有多少次我們真的騙了自己」。

**Round 60：追那 6 次「開設定卻放掉」——全是歷史資料**

把 56 次真·靜默失敗裡唯一「不該失敗」的 `NAVIGATE:app:設定` 拉出來看：

| 發現 | 事實 |
|---|---|
| 時間戳 | `1794935549061` → **早於 Round 16 的家族比對修正** |
| 收據 | 3 次 `post=0`，pkg 是 `settings.intelligence` |
| 結論 | 那是**修正前**的那一筆舊任務，按步數被算了 3 次（同一次任務三個失敗步） |

再限定最近 4 小時（`--since-minutes 240`）重算：

```
真·靜默失敗（647 次任務）：NAVIGATE:app:聯絡人 4
```

只剩聯絡人 4 次。單獨跑一次看：`SUCCESS steps=1 silent=2`——前兩次 post=0、第三次成功，
因為**測試 harness 每次都會重新啟動 MainActivity**，launch 後的第一幀有時還停在我們自己的畫面。
這是量測方式造成的，不是 App 的行為。

> 這輪沒有改程式，但把「要修什麼」的範圍縮到零：
> 6 次是歷史、4 次是 harness race，**真正該修的靜默失敗是 0**。
> 如果不追這一步，下一輪就會去「修」一個已經修好的東西。

**Round 61：「只在模擬器驗過」現在騙不了人**

新增真機驗證狀態追蹤：

| 機制 | 行為 |
|---|---|
| `bench/device_verification.tsv` | R1–R8 各自的「上次驗證時間／結果／備註」 |
| `device_checklist.py` | **只在真機**跑完才更新（模擬器顯示「不更新真機驗證狀態」） |
| `health.py --guards-only` | 第一行印出「已驗幾項、最舊幾天前、哪些未驗」 |

實跑：

```
真機驗證：已驗 0 項，未驗：R1, R2, R3, R4, R5, R6, R7, R8
```

（模擬器上的 5/5 或 7/7 **不會**寫進這份表。）

**為什麼要分開**：模擬器的 R5 永遠是「略過」（相機是 stub）。
如果把模擬器的 7/7 當成真機也 7/7，那份「已驗證」就是假的——
而假的已驗證比沒有更危險，因為它會讓下一次改動失去警戒。

驗證：模擬器跑完確認檔案未變；另外用假資料測過寫入邏輯（R1/R2 PASS、R3 FAIL），測完已還原。

**Round 62：盤點剩下什麼（順便把清單再修 7 列）**

新增 `docs/WAE_Remaining_Plan.md`：把剩下的 42 項分成三類——

| 類別 | 內容 | 為什麼這樣分 |
|---|---|---|
| **A 真機一接就能驗** | R1–R8、A3 OCR、B5 MiniLM、I3 評測、E6 音訊 | 全部「已寫好、只差驗」，一小時可清五項 |
| **B 需要寫程式** | B1 可靠度四項（退避／載入判定／多輪記憶）、B2 新能力五項、B3 大顆的 | 依「解鎖什麼」排序，不依「聽起來厲害」 |
| **C 刻意不做** | 影片／遊戲畫布、驗證碼辨識、約束匯出 UI、喚醒詞 | 每項都寫了理由（承諾了就是騙人／會被用來繞風控／隱私／耗電） |

**順手又修了 7 列清單**（都是「早就有、寫成 ⬜」）：

| ID | 實際狀態 |
|---|---|
| B4 別名表 | ✅ `ui_app_aliases.tsv` |
| C9 批次 | ✅ 安全重複（音量／亮度，上限 10） |
| D7 通知列／最近任務 | ✅ `global:` 效果 |
| G6 路徑快取 | ✅ `ProgramCache` |
| H5 進度顯示 | ✅ 無障礙 overlay（第 n 步＋手段） |
| A10 系統彈窗 | ✅ **依設計**只偵測不代按 |
| C6 換路回饋 | ✅ 路由讀 `CapabilityIndex` |

現在：**97 項中 ✅ 55、🟡 6、⬜ 36**，且 `doc_consistency.py` 驗證 55 條全部有證據。

> 這次盤點的收穫不是「還有很多要做」，而是**「很多已經做了但沒被算進去」**。
> 三次修清單（Round 50、62）共改正 17 列——這代表我過去一直在用一份低估自己的地圖導航。

**Round 63：指數退避（先撞牆，再修對）**

第一版把退避做成 400 → 800 → 1600 → 3200 ms，結果：

```
baseline 37/39   ← 打開聯絡人、其他啟動類掉條
```

**原因**：退避變長會把「開 App 需要等一下」的任務拖過時間預算，原本會成功的變成棄權。
退避的目的是「不要撞同一幀」，不是「等到天荒地老」——所以上限改成 **400 → 800 ms**。

**同時抓到一個更根本的 bug**：`打開聯絡人` 偶爾 `ABSTAIN(login_wall)`。

真相是：聯絡人第一次啟動會顯示「登入促銷頁」，姿態分類器判成登入牆 → 棄權。
**但目標（App 已在前景）其實已經達成了。** 所以加了「已達成」短路：

```
效果全部不是 false，且至少一個明確 true → 直接 SUCCESS
```

（驗不出來的 `null` 不算成立、也不算失敗。）

**這個短路自己也踩了一次坑**：直接呼叫 `session.done()` 從 PLANNING 跳 DONE → 非法轉移 → `UNKNOWN`。
改成照合法路徑走（PLANNING → EXECUTING → VERIFYING → DONE）。

**最後：39/39**、守門 2/2。

> 這輪示範了兩件事：
> 1. **修一個東西會撞出另一個**（退避 → 掉條；短路 → 狀態機）
> 2. **每次都要跑同一組基線**，否則不會知道自己撞到了什麼

**Round 64：等畫面「安靜」，不是等它「變了」**

慢速載入頁會「先變一半」——骨架屏、半張列表。只看「變了」就在骨架屏上找元素，
會得到「找不到」的假失敗。所以 `Settler` 現在做兩段等待：

1. 等它變（原本就有）
2. **等它安靜**（本輪新增）：變了之後再確認指紋連續相同，才算載入完成

**量測（同一台模擬器，收據時間戳）**：

| 設定 | 任務中位數 | p90 | 近期靜默失敗 |
|---|---|---|---|
| 無安靜窗（原始） | 1,316 ms | 4,110 ms | — |
| 安靜窗 500 ms | 2,346 ms | 6,139 ms | — |
| **安靜窗 300 ms（採用）** | **1,973 ms** | 5,143 ms | **0／38 步** |

代價換來什麼：最近 8 分鐘 38 步**零靜默失敗**（歷史平均約 16%）。
多花約 0.65 秒／任務，換「不要在還沒載完時動手」——這個比例我接受。

而且它是**可調的**（`perception.settle_stable_ms`），真機上慢速頁多就調大，快就調小。

> 這輪沒有「聽起來很酷」的功能，只有一個數字上的取捨：
> **+0.65 秒／任務，換掉一整類假失敗。**
> 我也把 500ms 的版本量出來放進文件，因為下次有人覺得慢，會想知道 300 是怎麼來的。

**Round 65：反問之後真的記得答案（B11）**

之前：缺資訊時會回「需要再一個資訊」，但**答案不落地**——使用者回「小明」，系統還是不知道要傳給誰。等於問了白問。

現在（實測）：

```
[傳訊息] => ABSTAIN reason=missing_slot question=要傳給誰
[小明]   => followup answer='小明' -> '傳訊息給小明'
            ABSTAIN reason=sensitive_operation      ← 補槽成功，被安全閘門攔下（正確）
```

**設計上刻意保守**（`FollowUp.looksLikeAnswer`）：

- 只把「短、且不含任何動作詞」的話當回答（≤ 8 字、不含 打開／傳／分享／調…）
- 寧可把回答當新指令（會失敗，使用者重講一次），**也不要把新指令當回答**（會做出沒要求的事）
- 缺口 5 分鐘後自動忘記，避免「上次的問題吃掉這次的指令」

**驗證**：39/39 基線、extra 套件加了 2 條多輪案例、`doc_consistency` 56 條全有證據。

> 這輪的關鍵不是「記住答案」，是**「什麼時候不該把它當答案」**。
> 對話功能最容易出的錯不是忘記，是記錯對象。

**Round 66：講錯話能收回（B10 否定與取消）**

新增取消閘門：**「取消」「不要」「算了」「停止」「別…」→ 什麼都不做**。

實測：

```
[取消]               => ABSTAIN reason=cancelled
[不要傳訊息給媽媽]    => ABSTAIN reason=cancelled   ← 沒有進安全確認流程，直接不動作
[算了]               => ABSTAIN reason=cancelled
[打開設定]           => SUCCESS（正常指令不受影響）
```

**刻意把句型做寬**：

> 這裡的誤判是把「不要傳訊息」當取消（正確）；
> 反向誤判則是「使用者說不要，系統照做」——後果嚴重得多。

所以寧可偶爾把「不要開」聽成取消（其實也是取消），也不要漏接真正的否定。

**驗證**：39/39 基線、extra 套件 +2 條取消案例、清單 ✅ 57 條且全部有證據。

（順便：`取消` 也會清掉上一輪的多輪缺口，避免「已取消卻還在等答案」。）

**Round 67：七個理由一直在說同一句廢話（H2 卡片覆蓋）**

`CardFactory` 有一個保底分支 `else -> "我停在這裡"`。很方便，但它讓沒寫說明的理由**安靜地掉進去**。

檢查 20 個 `Reason` 成員：**13 個有專屬說明、7 個掉進保底**：

| 理由 | 之前顯示 | 現在 |
|---|---|---|
| NOT_IMPLEMENTED | 我停在這裡 | 這個我還不會 |
| LOW_CONFIDENCE | 我停在這裡 | 我不夠確定 |
| TIMEOUT | 我停在這裡 | 等太久了 |
| BLOCKED_BY_CONSTRAINT | 我停在這裡 | 被已學到的禁令擋下 |
| ALL_MEANS_BANNED | 我停在這裡 | 每條路都被擋住了 |
| MEANS_NOT_SUPPORTED | 我停在這裡 | 這個做法不支援 |
| ROUTE_LAUNCH_FAILED | 我停在這裡 | 打不開那個 App |

新增 `CardCoverageTest`（3 個測試）：

1. **每個理由都要有專屬標題**（不是保底那句）
2. 每張卡都有標題、說明、至少一個下一步
3. 成功與被擋下也都要有卡

現在 **20/20**。

> 保底分支是必要的，但它會把「還沒寫」變成「看起來寫了」。
> 這個測試的價值不是抓 bug，是**逼我面對「我以為我解釋了」的地方**。

**Round 68：用同一招掃「安静吞掉錯誤」的地方**

新增 `tools/silent_catch_scan.py`：找空的 catch、或只回 `false/null/Unit` 而沒有記錄的。

| 範圍 | 安靜 catch |
|---|---|
| **core ＋ gen5 路徑** | **0** |
| 舊路徑（服務、MainActivity、其他） | **86**（其中服務 38、MainActivity 7） |

處理方式：**不是一次全改**，而是建立「只能減不能增」的基準守門
（`tools/silent_catch_baseline.tsv`），並定下慣例：

```kotlin
catch (_: Throwable) {
    // 忽略：<原因>      ← 要放行就寫這一行
}
```

守門已接進 `health.py`（現在 3 項）：

```
PASS  I1 裝置入口守門      I1 GUARD: PASS
PASS  能力清單一致性        一致性檢查：PASS
PASS  安靜 catch 守門      GUARD: PASS（沒有新增的安靜 catch）
```

> 這跟上一輪的卡片保底是同一類東西：**`else`、`catch {}`、`?: 0` 都是「看起來處理了」的地方**。
> 先量、先鎖住不讓它長大，再慢慢拆—一次全改 86 個只會換來一堆沒空看的 diff。

**Round 69：讀通知（做的到，但模擬器沒有內容可讀）**

新增兩個東西：

| 東西 | 用途 |
|---|---|
| 指令「有什麼通知」 | 把最近的通知內容列出來（`ABSTAIN reason=info`） |
| `!notify`（probe） | 自己發一則通知，驗證收件箱有沒有收到 |

**結果（誠實）**：通知事件**有送到**，但**沒有內容**。

```
WAE_NOTIF: event pkg=com.android.shell collected=0     ← 事件到了，text 是空的
```

連自己 App 發的通知也一樣（`!notify` → `!` 之後查詢 `notifications=0`）。
也就是說在 Android 15 模擬器上，`TYPE_NOTIFICATION_STATE_CHANGED` 不帶 ticker／內容，
**通知佐證（E4）與通知回覆（D12）在這裡驗不了**。

處理：
- D12 標成 🟡「已接、需真機驗」，不假裝完成
- 查詢指令保留（真機上有資料就會回內容；沒有就說「沒有收到通知」）
- 收件箱仍只留在記憶體、不落盤

**同輪還修掉一個測試假象**：剛 rebind 無障礙後的前 1–2 條指令會拿到「沒有 root」的畫面 → ABSTAIN。
`gen5_smoke.py` 現在會**先等服務真的綁上**（最多 30 秒）再開始跑，並在等不到時警告。

驗證：39/39、守門 3/3。

> 這輪的價值是「知道哪裡驗不了」。如果沒查，D12 會被寫成 ✅，
> 然後所有人（包括未來的我）都會以為通知回覆已經能用。

**Round 70：「再做一次」真的會再做一次**

原本打算做剪貼簿讀取，但想清楚後先做這個——因為它**在模擬器驗得出來**，而剪貼簿在背景讀取有版本限制。

| 實測 | 結果 |
|---|---|
| `打開日曆` | SUCCESS（並寫入 `last_command.txt`） |
| `再做一次` | `repeat last='打開日曆'` → **SUCCESS** |

做法：成功時把指令寫進 `files/last_command.txt`（只有一行指令、不含畫面內容）；
「再做一次／再來一次／重複／重做／再一次」→ 讀回來重跑。檔案不存在就誠實回「沒有上一次」。

不過刻意把句型做成**完全比對**（`^(再做一次|…)$`），避免吃掉一般句子。

**同輪發現一個測試假象**：第一條 probe 讀到的是**上一輪的結果檔**（顯示 `command=今天天氣如何`）。
原因是讀取時機早於寫入，不是功能錯；`gen5_smoke.py` 的固定等待能避開，probe 手動測試時要注意。

驗證：39/39、清單 ✅ 58 條全有證據、extra 套件 +2 條。

> 選題的判準很簡單：**能驗的先做**。
> 剪貼簿不是不做，是它需要「真機上某個 App 有焦點欄位」才有意義；
> 「再做一次」今天就能證明它活著。

**Round 71：真機接上後，一條命令把該驗的都驗完**

```powershell
python tools/device_checklist.py -s <序號> --full --with-eval
```

會依序跑 **R1–R8**（R8 是 101 條評測），跑完**自動更新** `bench/device_verification.tsv`。

| 模式 | 內容 | 時間 |
|---|---|---|
| `--guards-only`（health） | 3 項靜態守門 | 幾秒 |
| `--fast --full` | R1、R3–R7（略過自我測試） | 約 4 分鐘 |
| `--full` | R1–R7（含自我測試 9 項） | 約 6 分鐘 |
| `--full --with-eval` | **R1–R8（加 101 條評測）** | 約 25 分鐘 |

實測（模擬器）：`--fast --full` → **7/7**，且正確顯示「模擬器：不更新真機驗證狀態」。
R8 的寫入邏輯另外用假資料驗過（`R8 … PASS 101/101`），測完已還原。

> 這輪沒有新功能，只是把「真機那一次連線」的價值最大化。
> 真機難得接上一次，不該把時間花在「我忘了跑哪一項」。

**Round 72：診斷包（H9 第一版）**

本來要做「使用者糾正學習」（G7），但想清楚後**先不做**——它需要改反例 API（目前只能靠 step 物件寫入），
那是半天的工，而這輪的時間適合做能立刻驗證的東西。

新增 probe 指令 `!diag`：產生 `files/diag.txt`，內容**刻意只有結構與計數**：

```
WAE 診斷包
version=3.0.0-wae
android=15 sdk=35
device=Google sdk_gphone64_x86_64
a11yBound=true

[計數]
  receipts.tsv=2220
  refutations.tsv=1
  capabilities.tsv=15
  programs.tsv=97
  transitions.tsv=2671
  frames.tsv=2156
  twin=15
```

**不含**：指令原文、畫面內容、通知內容。要回報問題時給這個檔就夠，不會順手把使用者的東西送出去。

驗證：`!diag` → 575 bytes、內容正確；自我測試 9/9；守門 3/3。

**順手記下 G7 為什麼延後**：反例的入口是 `recordFailure(step, …)`，
「使用者說這步錯了」需要一個只吃 `(effectKey, means, frameFp)` 的入口。
在那之前硬做，只會把 step 物件假造出來——那是給自己挖坑。

> 選題的第二條原則：**改 API 的工先不動，除非它是瓶頸**。

**Round 73：把 G7 的瓶頸拆掉（禁令 API）**

上一輪說 G7 卡在反例 API；這輪就把它拆了。

新增 `RefutationLog.ban(scope, frameFp, effectKey, means, attribution)`：

| 特性 | 為什麼 |
|---|---|
| **一次就到 BAN** | 是人說的，不是統計猜的——不需要「累積兩次」 |
| 仍然只作用在（畫面 × 效果 × 手段） | 人的糾正也要有範圍，否則「這次不對」會變成「以後都不准」 |
| 再記一次失敗也不會降級 | 已是 BAN 就維持 BAN |

三個測試（`RefutationBanTest`）：

1. 一次糾正就生效
2. 只影響同一個（畫面／效果／手段）
3. 後續再記錄失敗不會把它降回 LOWER

驗證：`core:test` 全綠、app 建置成功、守門 3/3。

**還沒做的**：把「不是這個」接成指令。那需要「上一次動了哪一步」的資訊，
而目前只有 `ProgramCache` 與收據裡有——要嘛把收據解析出來，要嘛讓 Agent 回報最後一步。
這輪先把 API 做好、測好；**指令接線留給下一輪，不硬塞**。

> 一次只做一個層次：先把「可以禁」變成可靠的事實，再談「怎麼讓使用者觸發它」。

**Round 74：G7 接起來了（「不是這個」→ 一次就禁 → 可刪除恢復）**

完整驗證（真機流程在模擬器上跑）：

| 步驟 | 結果 |
|---|---|
| `打開日曆` | SUCCESS |
| `不是這個` | `ABSTAIN reason=info correction=applied`；`refutations.tsv` 出現 **`global` scope 的 BAN** |
| 再 `打開日曆` | **ABSTAIN(no_candidate)** ← 被自己的糾正擋住 |
| 刪掉那條反例 | **SUCCESS** ← 立刻恢復 |

**中途改了一個設計（重要）**：原本禁令的範圍是「畫面指紋」，
但使用者糾正時，規劃看的是**我們自己的畫面**（WAE 主頁），不是目標 App 的畫面。
用畫面指紋當範圍 → 下一輪指紋一變就失效。

改成 **global scope**，範圍並沒有變大——因為效果鍵本身已含目標（`NAVIGATE:app:日曆`）。
「全域」在這裡是「這個（效果 × 手段）組合」，不是「所有 App 的所有動作」。

驗證：39/39 基線、清單 ✅ 59 條全有證據、守門 3/3。

> 這輪的教訓：**「範圍」要對齊「規劃時看得到的東西」。**
> 用執行時的東西當鍵，換一輪就對不上——即使那個東西聽起來更精確。

**Round 75：五個對話行為串起來跑，10/10**

新增 `docs/WAE_Dialogue_Spec.md`（規格）與 `tools/dialogue_script.py`（可重跑劇本）。

五件事過去各別測過，但**沒測過它們互相影響**：

```
打開設定        → SUCCESS
再做一次        → SUCCESS（重跑）
不是這個        → correction=applied
打開設定        → ABSTAIN no_candidate（被自己的糾正擋住）
（刪除反例）     → 恢復
打開設定        → SUCCESS
傳訊息          → missing_slot（反問）
小明            → 回答接回原句 → sensitive_operation（安全閘門）
取消            → cancelled
打開設定        → SUCCESS（取消沒有污染狀態）
```

**實測 10/10。** 這比五個獨立測試有價值，因為它驗的是**狀態互相污染**：
反問的缺口會不會吃掉下一個指令？糾正的禁令會不會擋住別的事？取消之後還能不能正常用？

規格也順便把「寧可錯哪邊」寫清楚：

| 情境 | 寧可的錯 |
|---|---|
| 判斷「這句是回答嗎」 | 寧可當新指令（失敗，重講），**不要**把新指令當回答（做出沒要求的事） |
| 判斷「這句是取消嗎」 | 句型做寬（少做一件事，不要多做） |
| 缺口過期 | 5 分鐘就忘（不要讓舊問題吃掉新指令） |

> 這份文件最重要的不是流程圖，是**每個決策點「寧可錯哪邊」**。
> 那些取捨才是產品個性；流程只是外殼。

**Round 76：健康檢查變成 5 項，回答也聽得懂「給小明」**

兩件事：

1. **對話劇本接進 `health.py`**（第 4 項）。現在一個指令看完：

```
健康檢查：
  PASS  I1 裝置入口守門        I1 GUARD: PASS
  PASS  能力清單一致性          一致性檢查：PASS
  PASS  安靜 catch 守門      GUARD: PASS
  PASS  裝置檢查 R1–R7       總計：7/7
  PASS  對話劇本             總計：10/10

總計：5/5
```

2. **回答支援前綴**：使用者很自然會說「給小明」而不是「小明」。

```
[傳訊息] => ABSTAIN reason=missing_slot question=要傳給誰
[給小明] => followup answer='給小明' -> '傳訊息給小明'
            ABSTAIN reason=sensitive_operation
```

`FollowUp.normalizeAnswer` 會去掉「給／傳給／送給／發給」再填入模板，
`looksLikeAnswer` 也接受這種句型（≤ 10 字、帶前綴）。

驗證：health **5/5**、對話劇本 **10/10**、守門 3/3。

> 「使用者不會照你的語法說話」這件事，每次都會再出現一次。
> 這次是「給小明」；上次是「幫我打開設定」；下次大概是「傳給那個誰」。

**Round 77：服務瘦身第六塊，順便驗證守門會咬人**

抽出 `RuntimeSignalsBuilder`（讀節點的滑桿範圍／可展開／清單維度／輸入型別，80 行 → 新類別）：
`WaeVisionAccessibilityService` 現在 **4,120 行**。

**守門抓到我**：

```
FAIL  安靜 catch 守門   GUARD: 安靜 catch 增加了（只能減少）
```

原因很單純：這 4 個 catch 本來在服務檔案裡（已列入基準），搬到新檔案後變成「新檔案有 4 個」。
**守門是對的**——搬家不該讓總數變多。

處理不是改基準，而是**在程式裡標註意圖**：

```kotlin
} catch (_: Throwable) {
    // 忽略：節點隨時可能失效（TOCTOU），讀不到就當沒有這個訊號
}
```

標註後守門 PASS，而且下一個人會知道「這是刻意忽略，不是忘了處理」。

同一次執行的裝置檢查（同一份邏輯，標註只動註解）：
**R1–R7 7/7、對話劇本 10/10、守門 3/3**。

> 這輪證明守門的價值不在「抓壞人」，在**逼你把「刻意」寫出來**。
> `catch {}` 跟「有註解的 catch」在執行上完全一樣，但在維護上差很多。

**Round 78：服務瘦身第七塊 ＋ 把「怎麼抽」寫成協定**

| 項目 | 結果 |
|---|---|
| 抽出 | `perception/NodeContextCollector`（走訪節點樹、收集帶語境的節點，100 行） |
| 順帶 | `RawCollectedNode` 從服務的私有巢狀類別搬到 `perception` 套件 |
| 服務行數 | **4,014 行**（本輪 -106） |
| 守門 | 新檔 2 個 catch 未標註 → 被擋 → 標註「節點走訪途中可能失效（TOCTOU）」→ PASS |

新增 `docs/WAE_Service_Slim_Plan.md`：

1. **剩下 10 個大塊的風險排序**（677 行的 `executeSingleLocalGoalWithRecovery` 標「極高，不動」）
2. **抽取協定 7 步**：確認活著 → 大括號配對 → 搬到新類別 → 服務只留委派 → catch 要標註 → 跑 `health.py` → 記錄
3. **已完成 7 塊的對照表**

健康檢查：**5/5**（守門 3、裝置 R1–R7 7/7、對話劇本 10/10）。

> 這輪把「師傅手感」變成「新人照做也不會錯的步驟」。
> 之前每次抽完都在想「還要跑什麼」——現在文件裡有答案。

**Round 79：服務終於低於 4,000 行（3,953）**

抽出 `core/explore/ExplorationScheduler`（挑一個安全動作做「影子執行」，51 行）。

| 項目 | 值 |
|---|---|
| 服務行數 | **3,953 行**（本輪 -61；第一次低於 4,000） |
| 依賴 | `context`、Twin 軌跡、`charging: () -> Boolean` —— 其餘全是 core 靜態元件 |
| 邊界 | **只模擬、不碰裝置**；開關仍是 `EXPLORER_ENABLED`（預設關） |

過程中的兩個小坑（都靠文件裡那 7 步協定解決）：

1. 新檔少了 `AutomatonPlanner` 的 import → 編譯錯，補上即可。
2. 抽完先跑 **守門**（幾秒）再跑完整 health——守門先過，避免花 10 分鐘才發現格式問題。

健康檢查：**5/5**（守門 3、裝置 7/7、對話 10/10）。

> 從 4,285 走到 3,953，共抽 8 塊。
> 這個數字本身沒有意義，有意義的是**每一塊抽完都跑過同一組檢查**：
> 沒有那 8 次驗證，這 8 塊只是把程式搬來搬去。

**Round 80：第九塊（數值元件 I/O）＋一個差點寫錯的常數**

抽出 `value/ValueAdjustIo`：`dispatchStroke`（送出拖曳）與 `readValue`（先 refresh 再讀值）。

| 項目 | 值 |
|---|---|
| 服務行數 | **3,937 行**（-16） |
| 為什麼抽 | 不是行數，是**讓「送出拖曳」只有一個地方**（I1 的延伸） |

**差點寫錯的地方**：我把 stroke 的 duration 寫成 `1L`（自己加的常數）。
回頭對照舊版才發現原本是 `stroke.durationMs`——**拖曳時長是路徑算出來的，不是固定值**。
如果沒對照，滑桿拖曳會變成「瞬間跳過去」，在某些 App 上等於沒拖。

驗證：編譯、守門 **3/3**、自我測試 **9/9**。

> 這輪的教訓：**搬移程式時，「看起來像常數」的數字最危險。**
> 它編譯得過、跑得動，只是行為悄悄變了——而這種變化通常只有使用者會發現。

**Round 81：先釘住行為，再搬（值調整決策表）**

`performAdjustValue` 有 131 行，一半是**判斷要不要動手**。這些判斷以前只能靠真機踩坑：

- 0→0 的假成功
- 不知道在點什麼的盲目點擊
- 唯讀元件
- 導覽步被誤報成完成

新增 `core/component/ValueAdjustDecider`（純函式）與 **7 個測試**，把決策釘住：

| 輸入 | 決策 |
|---|---|
| 沒有 live node | `TapOnlyNoNode`（只點、不算完成） |
| 沒量程 + 沒有語意證據（sim < 0.65） | `RefuseBlindClick` |
| 沒量程但目標可信 | `OpenDeeper`（導覽步） |
| 量程寬度 ≤ 0 | `RefuseInvalidRange` |
| 唯讀元件 | `RefuseReadOnly` |
| 沒有明確的調整意圖 | `OpenDeeper` |
| 以上皆非 | `Apply(range, desired)` |

**這輪刻意沒把服務改接**：先讓測試綠，下一輪才把 `performAdjustValue` 的守衛段換成 `decide(...)`。
順序反過來的話，就是「先搬再祈禱」。

驗證：`core:test` 全綠（含 7 個新測試）、守門 3/3。

> 抽程式的順序應該是：**先把行為寫成測試 → 再搬 → 測試還是綠**。
> 直接搬只會得到「編譯過」這種安慰。

**Round 82：把決策表接上去（行為沒變，但現在有測試撐著）**

`performAdjustValue` 的守衛段換成 `ValueAdjustDecider.decide(...)`：

```
決策（core，有 7 個測試）  →  動作（服務，只負責 log 與點擊）
```

| 決策 | 服務做的事 |
|---|---|
| `TapOnlyNoNode` | 點座標，**回 false**（不算完成） |
| `RefuseBlindClick` | 只記 log，什麼都不做 |
| `OpenDeeper` | 進下一層找滑桿，**回 false**（導覽步） |
| `RefuseInvalidRange` / `RefuseReadOnly` | 只記 log |
| `Apply` | 走原本的 SET_PROGRESS → 拖曳 → 修正 |

**服務行數反而變多**（3,937 → 3,951）：決策對應的 log 比原本的守衛 verbose。
這輪的價值不是行數，是**那 131 行裡最容易錯的一段現在有測試了**。

過程中我自己踩了兩個 Kotlin 陷阱（都靠編譯器抓到）：

1. `node` 不再被 smart-cast（守衛搬走後），後面全部變成 nullable → 補 `?: return false`。
2. 用 regex 把 `node` 換成 `liveNode` 時，**把宣告那行自己也換掉了**（`val liveNode = liveNode ?: …` 自我引用）。

驗證：`core:test` 全綠、守門 3/3、自我測試 9/9。

> 這輪沒有新功能，但它讓「調數值」這件事從**靠真機踩坑**變成**靠測試說明**。

**Round 83：條件句（C8 最小版）——只剝語法，不猜狀態**

新增 `core/intent/Conditional`：把「如果…就…」的條件子句剝掉，後面的動作照常解析。

```
如果開著就打開設定      → 打開設定
如果藍牙開著就打開設定   → 打開設定     （log 有記錄）
假如還沒開就打開設定     → 打開設定
要是太小聲就調高音量     → 調高音量
```

**為什麼不真的做「條件判斷」**：判斷要靠執行期的畫面，不是靠字串猜。
而我們的管線**本來就會先看畫面狀態**（開關已在目標狀態 → 直接算完成，不會亂點）。
所以「如果開著就關掉」＝「關掉」，只是前面多一層語法。

**明確的邊界**（能力清單標 🟡）：「如果有貨就下單」這種需要**等待與觀察**的分支還沒做。

驗證：`core:test` 全綠（含 4 個新測試）、基線 **39/39**、守門 3/3、extra 套件 +2 條。

> 這輪的節制是刻意的：把「條件」拆成「語法」與「判斷」兩層，
> 先做語法（今天就能驗），判斷留給真的需要等待的那一天。

**Round 84：每日回歸變成一個指令**

```powershell
python tools/daily.py
```

| 模式 | 內容 | 時間 |
|---|---|---|
| 預設 | 自我測試 9 項 ＋ 對話劇本 10 步 | **353 秒** |
| `--no-dialogue` | 只跑自我測試 | 113 秒 |

實測：

```
DAILY PASS  selftest=9/9  dialogue=10/10  353s
```

歷史記進 `bench/daily.tsv`（只留最近 30 次），失敗時 exit code 非 0。

**為什麼是這兩個檢查**：它們加起來只花 6 分鐘，卻同時蓋到
「單一能力」（打開 App、調音量、開關、分享、搜尋）與「狀態交互」（反問、取消、重複、糾正）。
完整 101 條評測要 25 分鐘，適合每次大改後跑；`daily` 適合**每次小改後跑**。

> 這輪做的不是功能，是**把「今天有沒有退步」變成 30 秒能回答的問題**。

**Round 85：完整評測 108/109，追下去發現是「讀到舊結果」**

跑完整 101＋新增案例（共 109 條）：

```
baseline   FAIL (38/39)
phrasings  PASS (15/15)
long       PASS (12/12)
extra      PASS (43/43)
總計：108/109
```

單獨重跑那條 → SUCCESS；再跑一次 baseline → 又變 **37/39**（換兩條失敗）。
**同一份程式碼，不同次跑，失敗的項目不一樣** —— 這是時序，不是功能。

根因：`gen5_smoke.py` 是**固定睡 N 秒再讀結果檔**。負載高時它讀到的是**上一條的結果**。

修法（兩處）：

1. **等「新結果」而不是等秒數**：送出前記下 `gen5_result.txt` 的 mtime，送出後輪詢到 mtime 變了才讀。
2. **評測把每個套件的結果寫成 UTF-8 檔**（`bench/last_<套件>.tsv`），失敗時直接看檔就知道哪一條
   ——而不是從 cp950 主控台輸出裡猜那些亂碼。

修完重跑：**39/39**。

> 這輪最重要的事不是「108/109」，是**發現那個 1 可能是假的**。
> 如果不追，我會把一條正常的指令記成壞的，然後去「修」一個不存在的 bug。

**Round 86：109/109，而且快了一倍**

用「等新結果」的機制重跑完整評測：

```
baseline   PASS (39/39)   283s   （改前 564s）
phrasings  PASS (15/15)   106s   （改前 217s）
long       PASS (12/12)    76s   （改前 173s）
extra      PASS (43/43)   266s   （改前 621s）

總計：109/109          731s
```

**兩個結果一起發生**：

1. **全過**（109/109）——證明昨天那條失敗真的是時序假象。
2. **快 2.2 倍**（1575s → 731s）——因為原本的時間大半浪費在「等固定秒數」。

`tools/eval_suites.tsv` 的 ETA 也更新了（現在 `--dry-run` 會顯示「估計 12 分 10 秒」）。

> 修好「怎麼等」，順便得到「跑得快」。
> 這不是巧合：**固定等待是唯一一種「做對了也慢、做錯了更慢」的設計。**

**Round 87：把「等新結果」套到所有驗證工具，全部快 2–3 倍**

| 工具 | 改前 | 改後 | 結果 |
|---|---:|---:|---|
| 對話劇本（10 步） | ~240s | **75s** | 10/10 |
| 裝置檢查 `--fast --full` | ~250s | **106s** | 7/7 |
| `daily.py`（自我測試＋對話） | 353s | **107s** | PASS |

修法都一樣：**送出前記 mtime，送出後輪詢到 mtime 變了才讀**。

過程中抓到一個低級錯誤：`device_checklist.py` 裡的變數叫 `REMOTE` 不是 `FILES`，
所以第一次跑直接 `NameError`——**這也是為什麼每改完工具就要真的跑一次**。

> 三個工具、同一個修法、三次都有感。
> 現在「每天的回歸」是 107 秒，比泡一杯咖啡還短。

**Round 88：讓文件自己承認它過期了（`doc_facts.py`）**

新增守門：**現場算數字，跟文件裡寫的比對**。

```
現場數字：評測 109 條、能力 ✅59／🟡13／⬜25、服務 3951 行

不一致：
  - README.md: 說「101 條」，實際 109 條
  - README.md: 說「✅ 101 條」，實際 59 條
  - WAE_RealDevice_Checklist.md: 說「101 條」，實際 109 條
  - WAE_Remaining_Plan.md: 說「101 條」，實際 109 條
```

修完後 `DOC FACTS: PASS`，並接進 `health.py`（現在 **4 項守門**）。

**這支工具馬上抓到我自己的錯**：我一直以為能力清單是 `✅59／🟡9／⬜29`，
現場算是 **`🟡13／⬜25`** —— 我每次都是「用眼睛數」，而眼睛每次都少算。

> 這一輪的價值不是修那幾個 101，是**建立一個會指出「你在說舊數字」的機制**。
> 人手更新的文件一定會過期；能自動比對的文件才有機會不過期。

**Round 89：13 個 🟡 全部給出結局，順手補了「簡體輸入」**

先說新功能：**簡體打字現在也能用**（實測）

```
打開设置  → simplified '打開设置' -> '打開設定' → SUCCESS
打开日历  → simplified '打开日历' -> '打開日曆' → SUCCESS
```

做法是**共用語音那張繁簡表**（`ui_asr_trad_map.tsv`，長詞優先），不新增詞庫。
新增能力列 B14、2 條案例、1 條證據。

**13 個 🟡 的處理**（規則是：只能升 ✅ 或降 ⬜，不准停在「差不多」）：

| 結局 | 項目 |
|---|---|
| **升 ✅** | C10 等待／重試（指數退避 400→800ms＋安靜窗＋重試上限） |
| **降 ⬜** | B7 EffectGraph（模型在，但 Planner 沒用它——沒接線就是沒做） |
| 留 🟡，但寫明缺什麼 | A3（真機 OCR）、B5（真機別名）、B9（缺時間/條件）、C8（缺等待型分支）、D12（真機通知）、G9（缺 UI/簽章）、H8（缺接新管線）、H9（缺分享 UI）、H10（缺 Gen5 導引）、I3（缺真機）、I6（缺探針接線） |

現況：**✅61／🟡11／⬜26**（共 98 列）。

**同輪的誠實記錄**：跑完 daily 後接 baseline，`打開聯絡人`／`回到主頁` 兩條 ABSTAIN；
單獨重跑都 SUCCESS。判定為**環境性抖動**（連續負載下冷啟動超過預算），已寫進 `WAE_How_To_Verify.md` §5.98。

> 「差不多做了」是最危險的狀態：它會讓下一個人（包括未來的我）以為可以直接用。
> 這輪把 13 個模糊狀態變成 13 個明確答案——其中 11 個答案是「還缺 X」。

**Round 90：診斷包真的能分享了（H9 ✅）**

`!diagshare`：把 `cache/shots/diag.txt` 用 `content://` 交給系統分享選擇器。

實測：

```
result: !diagshare  DIAG_SHARE content://com.wae.agent.shots/diag.txt
focus:  com.android.intentresolver/.ChooserActivityLauncher     ← 分享面板真的開了
```

順手修了兩件小事：

1. `ShotProvider` 的 mime 原本寫死 `image/png`，現在**依副檔名**給（txt → text/plain）——
   否則對方 App 會以為收到一張壞掉的圖。
2. `doc_facts` 接進 `health.py` 時**沒有加 `--strict`**，所以「文件數字不一致」也被算成 PASS
   ——守門寫了卻不會擋，等於沒寫。現在加了。

現況：**✅62／🟡10／⬜26**，守門 4/4。

> 這輪抓到一個「守門的守門」問題：**檢查工具本身回 0，不代表它通過**。
> 如果沒去讀那一行輸出，我會以為文件數字一直被守著。

**Round 91：約束可以帶走了（G9，但仍是 🟡）**

新增 `!export` / `!import`：把反例（禁令）匯出成純文字、驗 checksum 後匯入。

完整往返（實測）：

```
打開日曆   → SUCCESS
不是這個   → correction=applied（refutations 出現 global BAN）
!export   → rows=1 checksum=0c70f6c9bdb7c59c
（刪掉 refutations.tsv）
!import   → rows=1 added=1
打開日曆   → ABSTAIN(no_candidate)     ← 禁令真的回來了
```

另外把 checksum 與解析抽到 core（`ConstraintBundle`）＋ **5 個測試**：
「改一列一定驗不過」「多一列一定驗不過」「半行會被丟掉」。
這種測試用 adb 很難做（在裝置上很難穩定製造「檔案被改一個位元」）。

**為什麼仍是 🟡**：checksum 只防**手滑與傳輸損壞**，不是簽章；
而且沒有匯出／匯入的 UI（只有指令）。清單照實寫，不升 ✅。

> 這輪學到：**能驗的部分用測試驗，不能驗的部分不要假裝驗了。**
> 簽章沒做，就不會因為「匯出功能好像完成了」而被記成完成。

**Round 92：把四份「等結果」合成一份（`tools/device_probe.py`）**

```python
from device_probe import Device
out = Device("emulator-5554").run("打開設定")   # 等「新結果」再讀
```

`dialogue_script`、`device_checklist`、`daily` 全部改成走它（`gen5_smoke` 原本就有）。

**為什麼要集中**：同一個「送出 → 等新結果 → 讀」的邏輯原本有四份，
其中一份（Round 91 我手寫的 probe）忘了改——**整串輸出因此錯位一格**。

> 四份程式碼就有四次忘記的機會；一份只有一次。

改完實測三支工具：

| 工具 | 結果 |
|---|---|
| 對話劇本 | 10/10 |
| 裝置檢查 `--fast --full` | 7/7 |
| `daily.py` | PASS（114s） |

守門 4/4。

> 這輪的收穫是「把紀律變成程式結構」：
> 以前靠我記得要輪詢 mtime，現在靠**只有一個地方能送指令**。

**Round 93：連「安裝」也只有一個地方能錯**

`gen5_smoke` 也改走 `device_probe`，並把**安裝＋重綁無障礙**收進 `Device.install()`：

```python
Device(serial).install(apk)   # install -r + 兩個 settings put
```

**改完馬上抓到一個真問題**：帶 `--install` 跑第一條案例 → `UNKNOWN`。
原因：剛安裝完的第一次啟動最慢（dexopt ＋服務重綁），第一條正式案例被誤判。

修法：正式案例前先跑一條 `!refs` **暖機**（不影響狀態）。再測 → **1/1 PASS**。

| 驗證 | 結果 |
|---|---|
| 三條案例（不安裝） | 3/3 |
| 一條案例（含安裝＋重綁） | 1/1 |
| 守門 | 4/4 |

> 「安裝後第一條會壞」這種事，只有在**真的帶 `--install` 跑一次**才會發現。
> 平常都加 `--skip-install` 是效率，但偶爾要跑一次完整路徑——那不是浪費，是驗證。

**Round 94：把「慢」跟「壞」分開（開 App 專用等待上限）**

完整評測跑出 `打開Chrome ABSTAIN`。追 logcat：

```
result=ABSTAIN reason=replans_exhausted frames=162 silent=2 steps=1 tx=0 command=打開Chrome
```

意思是：**啟動指令送出了、畫面卻還沒變**（silent=2 次），不是 Chrome 打不開。
單獨重跑 3 次都 SUCCESS。

根因：`settle_timeout_ms` 是 4 秒，但 Chrome 在模擬器負載高時冷啟動 **> 8 秒**。

修法：新增 `perception.launch_settle_timeout_ms`（**12 秒**），只用在 `app:` 效果上。
它**只等到「畫面變了」為止**，不會每次都等滿 12 秒——所以正常情況不會變慢。

同輪也修好評測的診斷：`gen5_eval` 以前只抓 stdout，
smoke 崩潰時只會印「無結果」；現在連 stderr 與 exit code 都印出來。

驗證（四個套件全部重跑）：

| 套件 | 結果 |
|---|---|
| baseline | 39/39 |
| phrasings | 15/15 |
| long | 12/12 |
| extra | 45/45 |
| **總計** | **111/111** |

> 這輪學到：**「慢」和「壞」在日誌裡長得一樣**（都是 silent failure）。
> 分開它們的方法不是猜，是量——量出「它其實會成功，只是需要 8 秒」。

**Round 95：把「12 秒」從經驗值變成有數字的決定**

新增 `tools/coldstart.py`（`am start -W` 冷啟動量測）與代理層統計：

| App | 冷啟動（模擬器） | 代理層 p90（收據） |
|---|---:|---:|
| Chrome | 5.1 s | — |
| 地圖 | **8.3 s**（另一次 11.3 s） | **11.5 s**（n=53） |
| 相機 | 4.1 s | 7.0 s（n=123） |
| 設定 | 4.6 s | 5.4 s（n=248） |
| 日曆 | — | 7.1 s（n=76） |

**結論**：12 秒覆蓋目前觀察到的 p90（最大 11.5 秒）；4 秒明顯不夠。
所以那個「12 秒」不是拍脑袋，是**上限要>實測最大值**。

同時把 `perception.launch_settle_timeout_ms` 寫進 `ui_tunables.tsv`，
真機上可以用 `Tunables.override` 直接調，不必重編。

> 這輪的價值不是量到數字，是**把「量」變成一支可重跑的工具**。
> 真機接上時，同一支工具會告訴我「手機比模擬器快還是慢」，而不是再猜一次。

**Round 96：剪貼簿通了（D14 ✅），但先踩了一個「靜默丟棄」**

新增使用者指令 **「複製結果」**（把上一次的結果放進剪貼簿）＋探針 `!copy` / `!clip`。

實測：

```
!copy 測試字     → COPIED chars=3
!clip            → CLIP chars=3 text=測試字
打開設定          → SUCCESS
複製結果          → copy=ok chars=40
!clip            → CLIP chars=40 text=SUCCESS frames=145 silent=0 steps=1 tx=1
```

**踩到的坑**：第一版在背景執行緒直接 `setPrimaryClip`，
回傳成功、`!clip` 卻是空的——**Android 10+ 只讓「有焦點」的 App 碰剪貼簿，系統靜默丟棄**。

修法：剪貼簿操作丟到**主執行緒**，並在探針開始前等 600ms 讓 Activity 取得焦點。

> 這是這個專案第 N 次遇到同一類問題：**「沒有回報失敗」不等於「成功」**。
> 剪貼簿不會告訴你它丟掉了，就像 `catch {}` 不會告訴你它吞了什麼。

現況：**✅63／🟡10／⬜25**，守門 4/4（`doc_facts` 又抓到兩處 111→113，已修）。

**Round 97：113/113，順便找到「無結果」的真兇**

上一輪評測出現 `extra 無結果（腳本輸出異常，exit=1）`。這輪把 stderr 印出來後真相大白：

```
UnicodeEncodeError: 'cp950' codec can't encode character '\u5f00'   ← 「开」
```

**不是 agent 壞了，是工具在印結果時崩潰**——因為 extra 套件加了簡體案例（`打开日历`），
而 `gen5_smoke.py` 沒有搶救 stdout（其他工具都有，就它沒有）。

修法：`sys.stdout.reconfigure(encoding="utf-8", errors="replace")`。

驗證：

| 套件 | 結果 |
|---|---|
| baseline | 39/39 |
| phrasings | 15/15 |
| long | 12/12 |
| extra | **47/47**（含剪貼簿與簡體案例） |
| **總計** | **113/113** |

對話劇本也加了「複製結果」，現在 **11/11**。

> 這輪的收穫：**「工具崩潰」和「功能壞了」在報告裡長得一模一樣**（都是「無結果」）。
> 把 stderr 印出來只花 3 行，卻省掉一次對 agent 的誤診。

**Round 98：每次評測都留證據，不必重跑**

`gen5_eval.py` 現在每跑完一個套件，固定留下兩份檔：

| 檔案 | 內容 |
|---|---|
| `bench/last_<套件>.tsv` | 每條結果（UTF-8，直接看得出哪條 FAIL） |
| `bench/last_<套件>.log` | 該次 smoke 的**完整 stdout＋stderr** |

實測：`phrasings` 15/15，產出 `last_gen5_phrasings.log`（1,028 bytes）與 `.tsv`（530 bytes）。

**為什麼要這個**：Round 97 那次「無結果」重跑一次花 12 分鐘才看到真正原因（cp950 崩潰）。
現在同樣情況 `cat bench/last_gen5_suite4.log` 就有 traceback。

> 這輪沒有改行為，只是**讓下一次的診斷不用重跑**。
> 這種投資的報酬率，取決於「以後還會不會出錯」——而它一定會。

**Round 99：daily 也會留下證據了**

```powershell
python tools/daily.py        # 每跑一次就寫 bench/daily_last.log
python tools/daily.py -v     # 順便把 11 條對話劇本印出來
```

實測：`DAILY PASS  selftest=9/9  dialogue=11/11  119s`，
`daily_last.log` 1,174 bytes（含每條的實際輸出與 `總計：11/11`）。

**為什麼**：之前 daily 紅了只印一行 `DAILY FAIL`，我得自己重跑 dialogue 才知道哪一條。
現在 `cat bench/daily_last.log` 就有全部細節——跟上一輪的 `bench/last_<suite>.log` 是同一個原則。

> 三個工具（評測／對話／daily）現在都遵守同一條規則：
> **跑過的證據要留下來，不能只留在終端機裡。**

守門 4/4。

**Round 100：選檔器（D15 ✅，半自動但誠實）**

新增效果 `picker:image`：

```
選一張照片 → SUCCESS frames=144 silent=0 steps=1 tx=1
前景：com.google.android.providers.media.photopicker.PhotoPickerGetContentActivity
```

做法（三層各加一點）：

| 層 | 改動 |
|---|---|
| 動詞表（資源） | 加「選一張照片／選照片／挑一張照片 → picker」 |
| Planner | `picker:` 前綴 → `Means.SystemApi("picker:image")`，後置條件 `frameChanged` |
| Actuator | `startActivity(ACTION_GET_CONTENT, image/*, OPENABLE)` |

**為什麼只做半自動**：相簿第一張不一定是使用者要的；**亂選比不選更糟**。
所以代理人只負責「把選檔器打開」，選哪張留給人——這在能力清單照實寫「半自動」。

過程中發現一件重要的事：`IntentCompiler` 的 `DEFAULT_RULES` 根本不是執行時用的表，
真正載入的是 `core/src/main/resources/ui/intent_verbs.tsv`。
我一開始改錯地方，測出來仍是 `unknown_verb`——**改了程式碼但沒改到資料，等於沒改**。

驗證：114 條評測（新增 1 條）、守門 4/4、`doc_facts` PASS（✅64／🟡10／⬜24）。

**目前真機待驗**（`WAE_RealDevice_Checklist.md`）：R5 相機快門、R6 分享照片、R7 搜尋、R8 完整 101 條。
模擬器上 R5 一律自動略過（相機是 stub），所以「5/7」實際是「能驗的都過了」。

**Round 12 實測**

| 項目 | 結果 |
|---|---|
| 拍照（相機未開） | 效果 `SUBMIT:capture:photo`；**自動先開相機**（前景 = `com.android.camera2/.CameraLauncher`）→ C2 生效 |
| 拍照（快門） | `ABSTAIN(replans_exhausted)`：模擬器相機沒有真實快門反應，按下去畫面不變 → 誠實失敗（不是假成功） |
| 冒煙（9 例） | **9/9 PASS**（無回歸） |

**已知邊界（新）**

- 模擬器的相機是 stub（沒有感光元件），快門按下去畫面不變 → 拍照能力只能在**真機**驗證。
- 快門用「位置先驗」是刻意取捨：純圖示、沒有無障礙標籤的相機介面，除了位置沒有別的辦法；風險標 MEDIUM 並以「畫面變了」驗證。

**Round 11 實測**

| 項目 | 結果 |
|---|---|
| 打開Wi-Fi | `ABSTAIN(panel_opened)` 且**面板沒有被回滾關掉**（這是預期結果） |
| 打開設定 ×2 | 兩次都 SUCCESS；`programs.tsv` 記到 `NAVIGATE:app:設定 → intent:MAIN`，**hits=2**（第二次走快取提示） |
| 快取失效條件 | key 含 `pkg + appVersion`，App 改版自動失效（單元測試） |
| 冒煙（9 例） | **9/9 PASS** |

**D2／D3 實測基線（2026-11-17，第二輪）**

| 指標 | 數字 | 目標 |
|---|---|---|
| Kotlin 檔 | 179 | — |
| `object` 單例 | **152** | 3（Memory／Tunables／Clock） |
| 直接寫 `filesDir` | 94 處 | 1 個 `MemoryStore` |
| 不同 JSON 儲存檔 | **19** | 4（frames／contracts／refutations／programs） |
| 裝置操作點（I1） | 30（service 17、Gen5 Actuator 8、AgentRunner 5）；**守門通過** | 1（`Executor` → `Actuator`） |
| 上帝物件行數 | service **4,285**／V26 總管 1,743／MainActivity 1,783 | service ≤ 1,800 |
| 冒煙存活率 | 2 輪 100%（8/8 ×2） | ≥ 90% |

**尚未做**：B3（圖示偵測）、C6／C7（卡面 UI）、D1–D6／D8／D10–D12、E2–E6。

---

## 實測紀錄（2026-11-17 21:36，emu-5554 / Android 15 / lite+x86）

| 指令 | 結果 | 證據 |
|---|---|---|
| 打開設定 | **SUCCESS** steps=1 | 前景變成 `com.android.settings/.Settings`；收據 `intent:…MAIN SUCCESS post=1` |
| 回到主頁 | **SUCCESS** steps=1 | `system_api:global_action:HOME` |
| 調高音量 | **SUCCESS** steps=1 | `system_api:volume:up` |
| 搜尋台北101 | **SUCCESS** steps=2 | `a11y:focus` → `input:ime`，兩步後置條件都成立 |
| 今天天氣如何 | **ABSTAIN(unknown_verb)** | 正確不猜 |

**兩個真 bug（只有實機才會出現）**：

1. **感知競態**：動作送出後第一幀還是舊畫面 → 後置條件失敗 → 被誤記成靜默失敗、甚至把手段 BAN 掉。修法：加 `Settler`，等畫面真的變了再驗證（`perception.settle_timeout_ms`）。
2. **數量詞吃掉指令**：「台北101」被當成「數量 101」→ 效果變成 `query:台北`。修法：數量詞必須有量詞才算（`101個` 才算），否則不動。另外把「聚焦」的後置條件從 `frameChanged` 改成 `controlPresent`、輸入改成 `textAppeared`（不綁元素，DOM 重建也驗得到）。
