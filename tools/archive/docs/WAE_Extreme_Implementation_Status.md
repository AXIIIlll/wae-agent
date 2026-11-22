# WAE 極限架構：實作對照表（Final Status）

> 對應設計書：`docs/WAE_Extreme_Architecture.md`
> 本檔案回答一個問題：**架構書裡的每一項，到底做了沒有、怎麼量、邊界在哪。**
> 最後更新：2026-11（第八輪）

## 總覽

| 指標 | 數值 |
|---|---|
| 主程式 | 16,676 行 / 71 檔 / 20 個子套件 |
| 測試 | **463 項 / 0 失敗**（53 個測試類別） |
| 資料庫 | 31 個 TSV（全資料驅動，無硬編碼清單） |
| APK | 80.9 MB（含 MiniLM / YOLO icon / OCR 三個模型） |
| 綠區 | 只用 Android 公開 SDK／無障礙 API；CI 三道守門測試 |

## 子系統對照

| # | 子系統 | 狀態 | 實作位置 | 關鍵量測 | 邊界／限制 |
|---|---|---|---|---|---|
| S1 | App 自動機編譯器 | ✅ | `core/automaton/` | 2,000 步 → 256 節點 / 1,008 邊；全量 13–20ms、增量 8–20ms | 只記錄走過的路 |
| S2 | 負向知識 | ✅ | `core/action/NegativeEdgeStore.kt` | 5 種原因、TTL、成功衰減、狀態維度 | 手動觸發，不主動探測 |
| S3 | 目標編譯器（後置條件） | ✅ | `core/goal/` | 可驗證率 **96.7%**、誤報成功 **0/8**、編譯 **0.0004ms** | `ALWAYS` 條件不可作為成功依據 |
| S4 | 規劃器（A*） | ✅ | `core/automaton/AutomatonPlanner.kt` | 最壞情況（無解、全展開）**0.70–1.07ms** | 一次只規劃到「可操作狀態」 |
| S5 | 主動探索 | ✅（影子模式） | `core/explore/` | Bandit + 三層護欄 + 影子執行；16 項測試 | 預設關閉；開啟後需真機 A/B |
| S6 | 跨 App 遷移 | ✅（預設關閉） | `core/transfer/TransferEngine.kt` | 借邊信賴度 0.6、失敗 3 次移除 | 只比較 edge_sig，不做完整圖同構 |
| S7 | 觀測壓縮 | ✅ | `MinHash`（16 通道） | 比對 O(1)；解析度 1/16 | SimHash 未實作（MinHash 已足夠） |
| S8 | 安全型別系統 | ✅ | `core/safety/SafetyTypeSystem.kt` | 編譯期拒絕敏感套件／破壞性目標 | 仍保留執行期閘門（雙保險） |
| S9 | 推測執行 | ✅ | `core/extreme/SpeculativeExecutor.kt` | 預測後繼 + 預取；命中率可統計 | 只預測不執行，預設不啟用 |
| S10 | 負空間儲存 | ✅ | `core/automaton/NegativeSpaceIndex.kt` | 只存 blocked，查詢 O(1) | 尚未取代現有 JSON 儲存格式 |
| S11 | 時間旅行 | ✅ | `core/extreme/TimeTravel.kt` | 8 快照上限、5 次分支 | 只對可逆步驟建快照 |
| S12 | 前向／反向模型 | ✅ | `core/automaton/ModelS12.kt` | 前向機率表 + 反向 BFS（深度 ≤ 6） | 反向模型是查表 + BFS，非決策樹 |
| S13 | 能力蒸餾 | ✅（記錄中） | `core/decision/DecisionMemo.kt` | 需 3 次一致且成功率 ≥ 90% 才查表 | 查表跳過打分預設關閉 |
| S14 | 自我對抗任務生成 | ✅ | `core/extreme/TaskGenerator.kt` | 模板 × 目標元素；只生成可驗證任務 | 尚未接上自動跑分迴圈 |
| S15 | 聯邦圖 | ❌ **不做** | — | — | 需後端、匿名化與隱私基礎設施；離線單機 App 無法獨力完成 |
| S16 | 路徑編譯成位元碼 | ✅（預設不執行） | `core/program/` | 決策次數 = 0；乾跑不碰裝置 | 執行需開啟開關 |
| S17 | 零感知模式 | ✅ | `core/extreme/PerceptionFreeAndHotPath.kt` | 每步 30ms → 2ms；30 步 < 100ms | 每個動作後必須有 VERIFY |
| S18 | 失敗根因標註 | ✅ | `core/observe/ObservationModels.kt` | 四類根因 + 主因統計 | 歸因是啟發式，非因果推斷 |
| S19 | 參數自我校準 | ✅ | `core/automaton/ParamCalibrator.kt` | 召回 ≥ 0.95 下最大壓縮；< 20ms | 候選網格固定，不線上學習 |
| S20 | WL 分層狀態（meta-node） | ✅ | `core/automaton/WlMetaGraph.kt` | 同構 4 節點 → 2 meta-node | 只用於分析，規劃尚未走 meta 層 |
| S21 | 熱路徑常駐 | ✅ | `core/extreme/PerceptionFreeAndHotPath.kt` | LRU 32 條、≥3 次即熱 | 尚未預熱選擇器快取 |
| S22 | 圖健康度 | ✅ | `core/observe/ObservationModels.kt` | `health < 0.40` → 需重建 | 只提示，不自動重建 |
| S23 | 雙層 ID O(1) 查表 | ✅ | `core/automaton/EdgeIndex.kt` | 微基準最佳 **31.3ns**（5 輪取最佳） | 需暖機；字串 key 版本較慢 |
| S24 | 可重現性與差分回歸 | ✅ | `core/automaton/AutomatonDigest.kt` | 同語料 sha256 完全相同 | CI 尚未接上 golden hash |
| S25 | 事件驅動狀態維持 | ✅ | `core/perception/IncrementalPerception.kt` | 髒區剪枝；策略 12 項測試 | **真機路徑尚未被觸發過** |
| S26 | AOT 全路徑編譯 | ✅ | `core/extreme/AotCompiler.kt` | 深度 ≤ 5、路徑 ≤ 32；候選門檻 0.80/3 次 | 產出候選程式，尚未自動執行 |

## 另外完成（架構書外的工程強化）

| 項目 | 內容 |
|---|---|
| 多步導覽 | 規劃 1–4 步 + 逐跳抵達驗證（MinHash ≥ 0.50） |
| 編譯去抖 | 新步數 ≥ 12 或距上次 ≥ 5 分鐘 |
| 感知幀快取 | 同頁無事件時重用（省 ~290ms/幀） |
| 子套件驗證 | `com.android.settings` ↔ `.intelligence` 視為同一 App |
| 指令拆解根因修法 | App 片語 span 解析 + 動作×受詞查表 |
| 內容項證據 | 排除導覽列（資料夾/垃圾桶），改用結構證據排序 |
| 測試套件 | `PromptSuiteRunner` + 8 項固定提示詞，判定一律看後端日誌 |

## 相容性掃描工具（2026-11 新增）

目的：用**實測**回答「哪些 App 會擋我們」，取代猜測。

- 入口：debug 卡片「🔍 相容性」按鈕（僅開發版）
- 行為：**只啟動 App + 讀畫面文字**，不點擊、不執行任務
- 判定：畫面文字比對阻擋訊號（`dev/compat_block_signals.tsv`）
  - 命中 BLOCK 訊號 → `BLOCKED`（例如「請關閉無障礙」「遠端控制」）
  - 只命中 WARN 訊號 → `WARN`
  - 無訊號 → `OK`
- 目標：`dev/compat_scan_targets.tsv`（台灣常用 App，40 個上限）
- 輸出：`files/compat_matrix.tsv`（pkg / label / category / verdict / evidence / ms）
- 日誌：`WAE_COMPAT SCAN_START / APP / SCAN_END`
- 測試：`CompatibilityScannerTest`（7 項，含資料檔完整性）

## 誠實的邊界（哪些做不到或還沒驗證）

1. **S15 聯邦圖**：需要後端、匿名化管線與隱私審查；離線單機 App 不可能獨力完成。這是架構書裡唯一「設計上正確、但在本專案範圍內不做」的項目。
2. **S5／S6／S9／S13／S16／S17 預設關閉**：都是「有效但風險高」的能力；模組與測試完成，需人工開啟並以真機 A/B 驗證後才建議啟用。
3. **S25 增量感知**：策略與合併有測試固定，但真機上尚未走到該分支（短任務幀數太少）。
4. **完整 100 題評測從未跑過**：`benchmark/results/` 仍為空；EASY/MEDIUM 歷史數字是舊版，不可引用。
5. **S20 meta 層規劃、S21 選擇器預熱、S24 CI golden hash**：模組完成但只接了一半（見上表「邊界」欄）。

## 驗證指令

```powershell
$env:JAVA_HOME='C:\Users\Wade\.jdks\jbr-21.0.11'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain --no-daemon
```

磁碟上的狀態檔（真機）：
```
adb shell run-as com.wae.agent ls files/
  automaton/              每個 App 的狀態圖 JSON
  negative_edges.json     負向知識
  action_telemetry.json   邊的統計
  decision_memo.json      S13 查表
  failure_attribution.json S18 根因統計
  coverage_bandit.json    S5 探索統計
  prompt_suite_last.tsv   提示詞套件原始結果
```
