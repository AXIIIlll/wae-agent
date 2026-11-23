# AndroidWorld 首次對照（2026-11-23）

環境：AndroidWorld 官方 repo（116 題）、AndroidWorldAvd（Pixel 6 / API 33 / x86_64 模擬器）、
我們的 APK 以 -PwaeIncludeX86=true 建置後安裝，無障礙服務已綁定。
代理：android_world/agents/wae_adb.py（把整句指令丟給 App，done=True，成敗由 AndroidWorld 判定）。

## 抽樣 10 題結果

| # | 任務 | 指令（英文） | 我們的結果 | AndroidWorld 判定 |
|---|---|---|---|---|
| 1 | ClockStopWatchRunning | Run the stopwatch. | DONE | 通過 |
| 2 | ContactsAddContact | Create a new contact for Hugo Pereira... | NONE | 失敗 |
| 3 | SimpleCalendarAddOneEvent | In Simple Calendar Pro, create a calendar event... | NONE | 失敗 |
| 4 | MarkorDeleteNewestNote | Delete the newest note in Markor. | ASK | 失敗 |
| 5 | SimpleCalendarNextEvent | What is my next upcoming event... | NO_ACTIVITY | 失敗 |
| 6 | SimpleSmsSend | Send a text message using Simple SMS Messenger... | NONE | 失敗 |
| 7 | SystemBluetoothTurnOnVerify | Turn bluetooth on. | NO_ACTIVITY | 通過 |
| 8 | SystemWifiTurnOff | Turn wifi off. | NO_ACTIVITY | 失敗 |
| 9 | TasksDueOnDate | What tasks do I have due October 24 2023... | NO_ACTIVITY | 失敗 |
| 10 | VlcCreatePlaylist | Create a playlist titled "Ultimate Fails Series"... | NO_ACTIVITY | 失敗 |

**2 / 10 = 20%**

## 通過的兩題為什麼會過

兩題的共同點是「英文詞剛好命中既有資料」：
- Turn bluetooth on. → SystemTargets 的別名表本來就有 \luetooth\
- Run the stopwatch. → 名詞解析命中時鐘類 App

## 失敗的主因（不是執行力，是語言層）

1. **指令是英文，我們的詞彙與分類器全是中文**：多數題目連動作都沒開始
   （NO_ACTIVITY＝30 秒內沒有任何 WAE 日誌）。
2. 有些題目走到反問（ASK），因為槽位抽不到（英文人名、時間格式、檔案路徑）。
3. AndroidWorld 的 App（Markor／Simple Calendar／Expenses／VLC…）我們沒有專屬知識，
   只能靠畫面通用規則。

## 結論

- 外部基準第一跑：**20%**（10 題抽樣）。不能當成完整 116 題的分數。
- 若要比這個基準，先補「英文指令 → 中文意圖」的轉譯層（離線 MT 或雙語詞表），
  否則量到的是語言落差，不是操作能力。
- 我們的強項仍在中文指令（自建 100 題：修正前 49%，修正中）。