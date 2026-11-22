# WAE LLM Agent — 交接與真機自測指南

> 狀態：M0–M3 程式碼完成，emulator 已驗證；**真機部分交給你自己測**。
> 最後建置：2026-11-15 23:44（unit tests 全綠、release APK 481.5MB）

## 1. 這版有什麼

- 內建 **Qwen3-0.6B**（llama.cpp JNI，離線）→ 目標→計畫
- **Agent 迴圈**：規劃 → 觀察（無障礙樹）→ 決策（MiniLM＋訓練 reranker）→ 安全 → 執行 → 驗證 → 重試
- 決策模型：MiniLM 相似度 50% ＋ **訓練後 linear reranker** 50%
- 通用動作：tap / type / scroll / back / open_app（**無任何 App 專屬流程**）
- App 名稱解析：`global_tw_cn_app_metadata.tsv`（資料驅動）＋系統設定 fallback
- 嚴格驗證：TYPE 文字必須出現、OPEN_APP 必須換 App、開關（checkable）必須變狀態
- 助理接線：`AGENT_ENABLED` 開啟後，目標類語句走 Agent（進度 overlay＋TTS）

## 2. 安裝與啟用（真機）

```powershell
$adb = "E:\WAE\android-sdk\platform-tools\adb.exe"
& $adb install -r "app\build\outputs\apk\release\app-release.apk"

# 綁定（每次安裝後都要）
& $adb shell settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
& $adb shell pm grant com.wae.agent android.permission.RECORD_AUDIO

# 啟用 Agent 驗證鉤子（release 版用外部旗標）
& $adb shell mkdir -p /sdcard/Android/data/com.wae.agent/files
& $adb shell touch /sdcard/Android/data/com.wae.agent/files/agent_enabled_flag
```

## 3. 怎麼跑一個目標（免講話，最快）

```powershell
# 寫入目標（UTF-8）
$goal = "幫我把藍牙關掉"
[System.IO.File]::WriteAllText("E:\WAE\temp\goal.txt", $goal, (New-Object System.Text.UTF8Encoding($false)))
& $adb push "E:\WAE\temp\goal.txt" /sdcard/Android/data/com.wae.agent/files/agent_goal.txt

# 看結果（約 5–20 秒）
& $adb shell cat /sdcard/Android/data/com.wae.agent/files/agent_result.txt
& $adb logcat -d | Select-String "WAE_AGENT|WAE_AGENT_PLAN|WAE_AGENT_STEP|WAE_LLAMA"
```

**結果檔格式**：`result=SUCCESS steps=N llmCalls=1 msg=...`
**log 標籤**：
- `WAE_AGENT_PLAN`：LLM 產生的計畫（每步 do/target）
- `WAE_AGENT_STEP`：目前第幾步
- `WAE_AGENT`：goal / launched pkg / result

## 4. 建議自測任務（由易到難）

| # | 目標（直接貼上） | 預期 |
|---|---|---|
| 1 | 打開設定 | 進入設定 |
| 2 | 打開相簿，看最近的照片 | 相簿開啟 |
| 3 | 用瀏覽器搜尋台北一零一 | 瀏覽器開啟＋搜尋頁 |
| 4 | 幫我把藍牙關掉 | 設定→藍牙（狀態驗證視機型而定） |
| 5 | 看看現在電池剩多少 | 設定→電池頁 |
| 6 | 幫我找附近的餐廳 | 地圖/搜尋（可能反問或失敗） |

**判讀**：`result=SUCCESS` 表示 Agent 認為完成（有嚴格驗證把關）；`FAIL` 看 `msg`；`ASKING` 表示它想問你（目前測試模式會自動回答，真機 UI 反問還沒接）。

## 5. 已知限制（不是 bug，是範圍）

- 0.6B 規劃模型：目標太模糊/太開放時計畫會不完整
- 複雜圖形 UI（客製畫布、純圖示）成功率低（第二階段才處理）
- 反問 UI 還沒接進助理（目前自動選第一個選項）
- 開啟/關閉「開關」的狀態驗證只在元素可勾選（checkable）時生效
- 效能：手機預估 3–10 秒/次規劃（emulator 無加速要 3–4 分鐘）
- 喚醒詞、語音輸入照舊可用（sherpa 離線）

## 6. 檔案位置

- 計畫書：`docs/WAE_Agent_Plan.md`
- 模型清單／SHA：`docs/WAE_Agent_Models.md`
- 評測任務集：`benchmark/agent_tasks_20.tsv`、`docs/WAE_Agent_Benchmark.md`
- 評測腳本：`tools/agent_benchmark.ps1 -Mode agent`
- reranker 訓練：`tools/reranker/collect_ui_samples.py`、`train_reranker.py`
- 回滾 APK：`E:\WAE\apk-history\WAE-2.1.8-wae.apk`

## 7. 下一步（你測完再決定）

- 真機跑 5 題，回報哪幾題 FAIL/ASKING 及各題 `WAE_AGENT_PLAN` 內容
- 依失敗類型調 prompt／決策門檻（都只需改 assets，不用改程式）
