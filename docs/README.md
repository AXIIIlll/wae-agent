# 文件索引（2026-11-17 整理）

> 目的：**每一份文件都代表一件現在仍然成立的事**。歷史計劃已移到 `tools/archive/docs/`，
> 不要在這裡新增「已放棄方向」的文件，避免下一個人（或未來的你）誤會。

## 架構基準（唯一真相，重做照這份）

| 文件 | 內容 |
|---|---|
| **`WAE_Master_Backlog.md`** | **主線總表（唯一工作清單）**：已完成基線 13 項 ＋ Phase A–E（A 12、B 18、C 12、D 12、E 6）＝ **73 項**，含關鍵路徑與三個 Kill Gate。不估時長。 |
| **`WAE_Gen5_Capability_Map.md`** | **新管線能力清單（92 項）**：感知／意圖／規劃／手段／驗證／安全／記憶／產品／評測九層，逐項標「已驗證／有碼未驗／缺」與「補上後解鎖什麼」，附「只能做 12 件事」的優先序。 |
| **`WAE_Gen5_Architecture.md`** | **Gen-5 架構（v5.0，定稿）**：七條不變量、七層單向管線、六個資料契約、四個記憶庫、`S1–S9` 重做順序、舊→新對照表、前四代取捨一覽。**本文件生效後，其他架構文件一律降為對照。** |
| **`WAE_Effect_Routing.md`** | **旗艦創新提案：效果路由**（使用者說結果，系統在多個 App 之間挑路並解釋）。含三個候選比較、評分公式、驗收指標、風險止損。 |
| **`WAE_MWM_Adoption.md`** | **MWM 文件吸收清單**：12 項採用／5 項丟棄、編譯器角色分工（模型只當離線編譯器）、三個立刻可做的（靜默失敗率、三通道驗證、版本戳）。 |

## 實作進度（2026-11-18 現況）

| 模組 | 狀態 | 怎麼驗 |
|---|---|---|
| `core/`（純 JVM） | ✅ 完成：契約、管線、反例、契約推導、手段求解、安全、記憶體、世界模型、路由 | `.\gradlew.bat :core:test`（86 測試綠） |
| `app/` Gen-5 接線 | ✅ 完成：`DeviceIO`（唯一裝置入口）、`AndroidActuator`、`A11yFrameSource`、`Gen5Runtime`、`Gen5Probe` | App 內「🧪 自我測試」9/9 |
| 舊路徑收斂 | ✅ `AgentRunner`／服務層的裝置操作已全數收斂到 `DeviceIO`（I1：全 App 2 個原語） | `python tools/gen4_inventory.py` → `I1 GUARD: PASS` |
| 能力清單 | ✅ 64 條（🟡 11、⬜ 23） |
| 能力評測 | ✅ **114 條**（主基線 39 ＋ 說法變體 15 ＋ 長句 12 ＋ 追加 43 ＋ 額外情境 35） | `python tools/gen5_eval.py --dry-run` 看要跑什麼 |
| 真機驗證 | ⬜ 快門／圖示通道需 arm64 真機（模擬器相機是 stub） | 見 `WAE_How_To_Verify.md` |

## 操作手冊

| 文件 | 內容 |
|---|---|
| **`WAE_How_To_Verify.md`** | **怎麼驗**：安裝三行、App 內自我測試、114 條評測（含 `--tag` 分級）、抖動分析、看它學到什麼、常見症狀對照。 |
| **`WAE_RealDevice_Checklist.md`** | **真機驗證清單（R1–R8）**：手機接回來照著跑；R1–R5 可用 `python tools/device_checklist.py` 自動跑（判準寫死數字，R5 在模擬器自動略過）。 |
| **`WAE_Remaining_Plan.md`** | **剩下什麼（盤點與排序）**：真機一接就能驗的五項、需要寫程式的分批、刻意不做的四項與理由、建議順序。 |
| **`WAE_Dialogue_Spec.md`** | **對話行為規格**：反問／記住答案／取消／再做一次／糾正五件事的觸發條件、狀態、有效期與交互作用；`python tools/dialogue_script.py` 一次跑完（10/10）。 |
| **`WAE_How_To_Verify.md`** §5.5 | **每日回歸**：`python tools/daily.py` 一次跑自我測試＋對話劇本（約 6 分鐘，一行結果）。 |
| **`WAE_Service_Slim_Plan.md`** | **服務瘦身計畫**：剩下 10 個大塊的風險排序、抽取協定（含「catch 要標註」與「抽完跑 health」）、已完成 7 塊的對照。 |
| **`WAE_TwoModel_Plan.md`** | **三層兩憶計畫**：模型 A（App 選擇）／大模型（任務拆解）／小模型（單步決策）／點擊器（永不呼叫 LLM），加上任務記憶與單步記憶的切分、失效階梯、指標與 S0–S6 階段。 |
| **`WAE_Purpose_Taxonomy.md`** | **目的分類法 v1**：10 大類／42 中類／**165 個葉節點目的**（每個都要有可驗證的 post）；含風險三級、「禁止自動」清單、與規則表的接法。 |
| **`WAE_Rule_System.md`** | **規則系統 v1**：五張表（目的／操作／句型／HTN 方法／具體性）＋匹配器＋判斷器；`!rules on/off`、`!judge`；離線 gate G1–G3（匹配 95%、一致性 100%）。 |
| **`WAE_Lexicon_Architecture.md`** | **詞庫架構**：統一詞庫（15 通用角色）＋依 Play 分類的 20 個家族詞庫；三層分類解析、資料來源與授權、`!lexicon` 探針。 |


## 現在仍然有效（Gen-5 相容，不是架構）

| 文件 | 內容 |
|---|---|
| `GREEN_ZONE_POLICY.md` | 綠區紅線（不可違反的法律契約） |
| `WAE_NoLLM_Plan.md` | 為什麼拿掉 LLM、APK 尺寸組成 |
| `WAE_Decision_OnDeviceOnly.md` | 全手機端（無雲端、無 PC 大腦）的決策 |
| `WAE_MultiDevice.md` | OEM 設定分類差異（資料驅動） |
| `WAE_VoiceAssistant.md` / `WAE_WakeWord_Spec.md` | 語音與喚醒詞（Gen-5 仍沿用，只做前端） |
| `WAE_Agent_Models.md` | 模型清單與角色 |
| `WAE_Benchmark.md` | 評測方法（Gen-5 §10 沿用並加離線重放） |
| `WAE_S31_RootAndSafetyFix.md` | 回根頁規則與安全誤攔（避免復發） |

## Gen-4 架構與路線（僅供對照；重建後失效）

> 這些文件描述的是**現在的程式**，不是 Gen-5。讀的時候記得兩者不是同一件事。

`WAE_Atlas_Architecture.md`、`WAE_Extreme_Architecture.md`、`WAE_Stability_Coverage_Plan.md`、
`WAE_TaskOS.md`、`WAE_Service_Decomposition.md`、`WAE_OpenEnded_Intent.md`、`WAE_Planner_SFT.md`、
`WAE_DecisionHead_Eval.md`、`Grounding_Report.md`、`WAE_EndToEnd_Flow.md`、
`WAE_v3_Architecture_Plan.md`（已標示「已被 Gen-5 取代」）。

## 已歸檔（歷史，不再維護）

`tools/archive/docs/`：舊計劃、舊稽核、舊提案（Agent Plan、Hardening Plan、Extreme Status、
Learned World Model、Learning Report、Legacy Inventory、Morning Check、Plan Critique、
GAP Audit、Generic App Selection、Grounding2 Plan、AppMemory Plan、Verified Execution、
Agent Handoff、Agent Benchmark、EndToEnd Flow、COMPAT_MATRIX、NORTH_STAR）。

## 已移除的程式子系統

| 子系統 | 移除日期 | 歸檔位置 |
|---|---|---|
| WAE-Ω（shadow 模式，只寫 log） | 2026-11-17 | `tools/archive/omega_removed/`、`tools/archive/omega_removed_tests/` |
| 極限架構殘留（TimeTravel、AotCompiler、SpeculativeExecutor、TaskGenerator、WlMetaGraph、NegativeSpaceIndex、ParamCalibrator、AutomatonDigest） | 2026-11-16 | 直接刪除（無歸檔） |
| Qwen3-0.6B GGUF（full flavor 仍保留） | — | lite 不含；full 內建 |
| Gen-4 四條執行路徑（重建後） | 待執行 | 由 `WAE_Gen5_Architecture.md` §11 S9 刪除 |

## 技術債指標

整理期間每次改動都跑 	ools\debt_report.ps1（runCatching／TODO／emoji／最大檔行數，只能下降）。
計畫：docs/superpowers/plans/2026-11-24-codebase-cleanup.md。
