# WAE-Ω：離線可驗證的 App 作業系統（S34）

> 建立：2026-11-14　旗標：`ExtremeRuntimeHooks.WAE_OMEGA_ENABLED`（預設 **false**）  
> 狀態：13 個模組 + 11 支測試已進 App；`745 tests / 0 failures`；**尚未真機驗證**

## 為什麼要換架構

現有 L0–L5 是「反應式直譯器」：看畫面 → 選元素 → 動作 → 看結果。
它能做到常見任務，但天花板是：**沒有 App 行為模型、沒有編譯期檢查、沒有可驗證證據、
沒有跨 App 資料流、沒有能力複利**。WAE-Ω 把它升級成「編譯器＋執行時」架構。

## 八層架構（全部離線、純 Kotlin、JVM 可測）

| 層 | 模組 | 職責 |
|---|---|---|
| 1 | `UiOntology` + `ui_ui_ontology.tsv` | 通用 UI 本體論（TEXT_FIELD / LIST_ROW / SWITCH_ROW / DIALOG…）；跨 App 同構、可讀/可寫/可動作分類 |
| 2 | `AppModel`、`AppModelCompiler`、`ModelStore` | 本體論型別的 App 行為模型（狀態、轉移含成功率、欄位 schema、風險標註）；SHA-256 內容定址、可離線分享、壞檔驗證拒用 |
| 3 | `TaskIr`、`IntentCompiler` + `ui_ir_templates.tsv` | 自然語言 → 型別化 Task IR（NAVIGATE/READ/WRITE/COMPUTE/COMMIT）；編譯期檢查型別、欄位、資料流；缺變數→反問 |
| 4 | `PolicyVerifier` + `ui_policy_rules.tsv` | 策略自動機 + 有界模型檢查：資料流完備、型別存在、風險（金流/刪除/公開發布/權限/私密）→ SAFE/NEED_CONFIRM/UNSAFE/UNDECIDABLE + 證明書雜湊 |
| 5 | `SimSandbox` | 先在 App Model 裡模擬：預期成功率、不確定處、建議探測（probe）；不確定就不直接執行 |
| 6 | `VerifiedExecutor`、`ComputeEval` | 每一步契約＋證據＋反例；COMPUTE 只允許安全子集（四則運算/字串串接），不可能連網 |
| 7 | `OperatorSynthesizer`、`OperatorRegistry` | 對成功軌跡做**反統一（partial-token LGG）**，自動合成可重用運算子（`TAP:電池` / `TAP:螢幕` → `TAP:$p2`），存倉庫可分享 |
| 8 | `OmegaEngine` | 門面：plan（編譯→驗證→模擬）／execute（驗證式）／learnOperators／mergeObservation |

## 在 App 的整合

`WaeVisionAccessibilityService` 收到指令後（Task OS 之後）：

```
WAE_OMEGA_ENABLED = true 時
  → OmegaEngine.ensureLoaded(this)
  → OmegaEngine.plan(rawCommand, latestPackageName)
  → Log.i("WAE_OMEGA", summary)   ← shadow 模式：只記錄，不改變執行
```

- v1 是 **shadow 模式**：編譯/證明/模擬結果只寫 log，**不改變實際執行路徑**
- 關閉旗標時零行為差異（預設關閉）
- 真正的執行入口 `OmegaEngine.execute(plan, bridge)`、模型學習 `mergeObservation`、
  能力學習 `learnOperators` 都已可用，等真機驗證後再接到服務

## 驗證

| 項目 | 結果 |
|---|---|
| 單元測試 | **745 / 0 failures**（Omega 新增 76 個測試） |
| 主程式編譯 | BUILD SUCCESSFUL |
| APK | **68.27 MiB**（Omega 13 模組約 +0.19 MiB） |
| APK 副本 | `WAE.apk`、`E:\apk\WAE.apk` 已同步 |
| 真機 A/B | ⏳ 手機未連線 |

測試涵蓋：本體論分類（class/id/container/結構先驗）、模型雜湊/序列化逃脫/可達性/風險查詢、
內容定址儲存（壞檔拒用/匯入匯出）、意圖編譯（三態 + 型別/欄位/資料流檢查）、策略證明
（SAFE/NEED_CONFIRM/UNSAFE/UNDECIDABLE + 證明書）、模擬沙盒（可達/不確定/探測/確認）、
驗證式執行（READ→WRITE 資料流、缺變數反例、確認閘門、COMPUTE）、安全計算器、反統一合成、
運算子倉庫、App Model 編譯/合併、門面整合（plan/execute/learn/merge）。

## 已知邊界（誠實版）

1. **沒有主動探索器**：`AppModelCompiler` 已有「觀察 → 模型」的 API（`mergeObservation`），
   但還沒有「主動探索並餵觀察」的執行器；v1 需要真實執行或錄製後才能長模型。
2. **shadow 模式**：服務端只記錄，不執行 Omega 計畫；真正執行要接 `OmegaEngine.execute`
   （bridge 需把 IR 步驟映射到既有動作層）。
3. **沒有限時/資源預算**：有界模型檢查是靜態步數上限（40），不是時間/記憶體預算。
4. **模型來自執行**：App 改版後模型會過時；目前靠內容雜湊驗證完整性，還沒有
   「版本差異偵測 + 自動重編譯」。
5. **運算子合成是單層反統一**：只做位置層級的部分 token LGG，沒有遞迴結構合成。
6. **沒有真機驗證**：所有數字都是單元測試/編譯結果，尚未在手機上跑 A/B。

## 下一步

1. 手機接上 → 開旗標 → 觀察 `WAE_OMEGA` log 的編譯/證明/模擬結果是否合理
2. 實作「主動探索器」：對 Settings 做系統化探索 → 餵 `mergeObservation` → 產出第一個真模型
3. 把 `OmegaEngine.execute` 接到服務（先單一任務，A/B 對照）
4. 模型版本差異偵測 + 自動重編譯（抗改版）
