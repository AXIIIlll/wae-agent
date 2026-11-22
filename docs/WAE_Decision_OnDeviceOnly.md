# 架構決策記錄：全手機端（2026-11-14）

## 決策

**WAE 只走「完全在手機上執行」這條路線。** 以下路線已正式廢除，不再投入：

| 廢除的路線 | 原因 |
|---|---|
| PC 當大腦（adb 閉環 + UI-TARS/Qwen-VL） | 使用者決定廢除；無法出貨、增加維護面 |
| 開源 Android 模擬器（QEMU / VirtualBox / Android-x86） | 本機 WHPX 損壞；VirtualBox NEM 下 SurfaceFlinger 崩潰；雲端不穩 |
| 雲端 GCP Android 模擬器 | 不穩、成本、與產品無關 |
| 自行訓練 GUI 模型（C1/C2 好奇心/目標訓練） | 模擬環境內容不足以支撐長時間訓練；產品不需要 |

## 保留並作為唯一主線

```
感知   OmniParser icon_detect (fp16) + PP-OCRv4 + 無障礙樹（AOSP 公開 API）
理解   MiniLM-L6-v2 INT8（384-D）
決策   automaton planner（A* + bisimulation）+ GoalCompiler（PostCondition DSL）
執行   無障礙服務 ACTION_CLICK / 手勢 / 捲動 / 返回
驗證   verifyGoalOutcome / ToggleState / PostCondition
資料   全部 data-driven：assets/open_source_ai_databases/*.tsv
限制   無 INTERNET 權限；綠區（只用 Android 公開 SDK / 無障礙 API）
```

## 已完成（不再重做）

- 482 個單元測試全綠（`:app:testDebugUnitTest`）
- APK 80.9 MB（debug），已可安裝
- 16 條能力線（S9–S26）全部接上且可個別開關
- 真機基準：Samsung SM-A5560、1080×2340、450dpi → 384×832dp、19.5:9

## 收尾清單（依序）

1. **真機核心任務驗證**：設定 / Wi-Fi / 藍牙 / 飛航模式 / 亮度 / 開啟 App / 返回 / 多步驟鏈
2. **修 S27 toggle 問題**：三星「連接」頁的「飛航模式」列（text 可點但 clickable=false，switch 無標籤）未進入候選
3. **跑 100 題 benchmark**（benchmark/benchmark_tasks.tsv）→ 成功率、失敗分類
4. **相容性掃描**（🔍 相容性按鈕）→ 高風險 App 清單
5. 文件收尾（AGENTS.md / docs 狀態表）

## 不在範圍內

- 任何模型訓練（符號模擬器 C1/C2、雲端訓練）
- PC 端推論（UI-TARS / Qwen-VL / 任何 VLM）
- 任何網際網路功能
