# 拿掉 LLM、把 APK 壓到最小（2026-11-17）

## 為什麼要拿掉

完整版 535.6MB，其中 **378.3MB 是 Qwen3-0.6B GGUF**。而實測顯示：
EASY 36 題裡絕大多數（看劇／聽歌／導航／訂餐／查詢／設定）**根本不需要 LLM**——
意圖＋模板＋規則就做完了（`llmCalls=0`）。LLM 只在長尾（跨 App、多條件）才上場。

所以策略是：**讓不需要 LLM 的路徑變成預設，LLM 變成可選的「長尾包」**。

## 實測組成（535.6MB）

| 項目 | 大小 | 判定 |
|---|---|---|
| Qwen3-0.6B GGUF | 378.3 MB | 移除（full 保留、lite 不裝） |
| sherpa ASR（encoder 20.6 + decoder 7.2 + joiner 6.8） | 30.9 MB | 保留（離線語音是本體） |
| bge-small 嵌入 | 22.8 MB | **只服務 LLM 選 App → 移到 full** |
| MiniLM 嵌入 | 22.0 MB | 保留（元素打分核心） |
| ONNX Runtime（app 17.4 + sherpa 15.2） | 32.6 MB | 重複，待合併 |
| OCR det 4.5 + rec 10.4 | 14.9 MB | 保留（沒有 a11y 時的眼睛） |
| OmniParser 圖示偵測 | 5.8 MB | 待評估（圖示型 UI 才用） |
| llama.so | 4.0 MB | 只有 full 需要 |

## 已完成

| 動作 | 效果 | 驗證 |
|---|---|---|
| bge 移到 `src/full/assets` | lite −22.8MB | lite 156.7 → **134.5MB** |
| `ElementPlanner`：無 LLM 的逐元素決策迴圈 | lite 的長尾指令不再直接反問 | 6 個單元測試 |
| lite 無模型時改走 ElementPlanner | `llmCalls=0`、明確回報成功/需反問/失敗 | 838 tests / 0 |

### ElementPlanner 是什麼

```
觀察畫面 → 對每個元素打分（語意/角色/決策頭）→ 選最高分動作
        → 執行 → 看畫面有沒有出現指令關鍵詞 → 重複（最多 8 步）
```

它**不生成任何文字**，所以不需要 tokenizer、不需要 378MB 的模型。
終止條件：畫面出現目標關鍵詞（成功）／決策器要求反問／連續兩次沒變化（失敗）。

## 接下來能再砍多少（估計）

| 動作 | 預估 | 風險 |
|---|---|---|
| sherpa decoder/joiner 轉 int8 | −9~10MB | 需真機驗證辨識率 |
| sherpa 改用 app 的 ORT（刪 `libsherpa_ort1.so`） | −15.2MB | 需要重編 sherpa，ABI 相容性 |
| OCR 換 PP-OCRv4 mobile 精簡版 | −5~8MB | 辨識率略降 |
| 移除 OmniParser 圖示偵測 | −5.8MB | 圖示型 UI 只能靠 a11y/OCR |
| MiniLM 換更小嵌入（或純字元 n-gram） | −18~22MB | 元素匹配品質下降 |
| R8 + shrinkResources + 只留 zh/en 資源 | −3~6MB | 反射/JNI 需 keep 規則 |

**目標：lite release 90–100MB**（目前 debug 134.5MB）。

## 品質驗證方式

1. 用 lite 跑 120 題（`tools/open_ended_benchmark.py`），記錄成功率。
2. 與 full 對照：EASY/MEDIUM 不應退步（那些本來就不靠 LLM）。
3. 長尾（HARD）本來就依賴 LLM；若 lite 明顯較差，就把 full 定位成「長尾包」單獨發佈。

## 風險

- ElementPlanner 是**貪婪式**的：沒有計畫，只有當下最高分。多步任務（購物結帳）可能走到死路。
  需要「進度檢查 + 回到上一層」的補強。
- 元素打分依賴 MiniLM；若之後砍掉嵌入模型，匹配品質要重新量。
- 拿掉 LLM 後，`查高鐵時刻然後加行事曆` 這類跨 App 任務在 lite 不可能完成——這是取捨，不是 bug。
