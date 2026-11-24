# WAE 專案工具箱

把 `E:\WAE\NewAndroidApp` 這個 Android 專案搬進 DSH 的側邊欄：一張隨程式碼更新的地圖、建置與裝置狀態、資料表目錄、`ui_tunables.tsv` 編輯器與技術債看板；另外提供一個以 AGENTS.md 為準的「WAE 開發模式」。

## 入口

左側欄新增一列 **WAE 地圖**（點陣圖示：中心節點加三條分支）。點下去會在中央欄開啟全寬面板，和「插件」頁同等級；不會佔用對話，也不會擠在右側 360px 的欄位裡。

## 六個分頁

### 地圖

左邊是可折疊的樹：`app 主程式 / app 測試 / core 主程式 / core 測試 → 功能區 → 套件 → 檔案`，外加 **資料表（65 個 TSV）** 與 **文件（54 份 Markdown）** 兩棵獨立的樹。每個節點顯示行數或數量，測試檔以琥珀色點標記；搜尋框可以找檔名、套件名或 top-level 宣告名。

右邊是以目前套件為中心的心智圖：藍線是它依賴的套件、綠線是依賴它的套件，線寬代表引用檔案數；點任何一個節點就換中心。滾輪縮放、拖曳平移，右下角有放大／縮小／重設。

最右邊的細節欄顯示選中檔案的：行數、套件、top-level 宣告（class／object／interface／fun／val 與行號）、依賴套件、同套件的測試檔，以及「複製路徑」和「預覽程式碼」（讀取檔案內容，唯讀）。

解析方式要先講清楚：**套件與 import 是可靠的**；class／fun 這類宣告是用縮排與正則抽出來的近似值，不是編譯器。所以把它當地圖，不要當語意索引。

### 架構總覽

內嵌 `wae-mindmap-app/assets/WAE-flow-mindmap.svg` 這張既有的端到端流程概念圖，可縮放、拖曳。它是**靜態快照**，會落後於程式碼；真正的現況請看「地圖」分頁。

### 建置

一張卡片看 full debug APK 的大小與時間、`testFullDebugUnitTest` 的測試數／失敗數／略過數與最後執行時間、裝置序號與無障礙服務是否綁定、總開關是否開啟。

按鈕：**跑 full 單元測試**、**建置 full APK**、**安裝並重綁**（`adb install -r` 後重設 `enabled_accessibility_services` 與 `accessibility_enabled`）、**只重綁無障礙**。Gradle 與 adb 都在 Host 的背景工作執行，日誌會即時回到面板；同種類的工作同時只跑一個。

### 資料表

65 個 `assets/open_source_ai_databases/*.tsv` 的列數、欄數、大小、是否帶 BOM，以及有多少 Kotlin 檔直接提到這個檔名（點一列看引用清單）。BOM 那一欄是給「TSV 不可有 BOM」這條規則用的。

### 調參

`ui_tunables.tsv` 的 key／value／說明表格，改 value 後按「儲存」會：先備份成 `ui_tunables.tsv.bak-<時間戳>`、只改那一行、以 LF 寫回、不寫 BOM。改完重啟 App 生效；也可以在程式裡用 `Tunables.override` 臨時覆蓋。

### 技術債

`runCatching`、TODO／FIXME、emoji、最大檔行數四個數字，加上最大的 12 個檔案。這些數字照專案規則**只准下降**。

## WAE 開發模式

安裝這個 bundle 會多一個 Agent 預設「WAE 開發模式」（內部 id `wae-dev`）。它以標準模式為底：

- 換上 WAE 的人設：繁體中文、先看 AGENTS.md、改完跑 full 測試與建置、判定只看 `WAE_EXEC taskResult`、不要 `force-stop`、數值與清單放 TSV、UI 不用 emoji、產物放 E 槽。
- `AGENTS.md` 仍由 `agent-instructions` 自動注入（上限 64 KB），模式裡不重貼全文，避免兩份規則不同步。
- 多了 `wae_map` 工具：可以問「gen5 依賴誰」、「哪個檔最大」、「有哪些 tunable」、「哪些 TSV 有 BOM」，不必每次 grep。
- 只影響**新開的任務**；既有對話不會換模式。

## 設定

patch 的 `wae-kit` 列有四個值，改 profile 的 `cordis.patch.yml` 即可：

| 設定 | 預設 |
| --- | --- |
| `projectRoot` | `E:\WAE\NewAndroidApp` |
| `javaHome` | `C:\Users\User\.jdks\jdk-21.0.13.1+2` |
| `gradleUserHome` | `E:\WAE\gradle-home` |
| `adbPath` | `E:\WAE\android-sdk\platform-tools\adb.exe` |

## 已知邊界

- 掃描 516 個 Kotlin 檔約 0.2 秒；45 秒內重複要求會用快取，按「重新掃描」才強制重讀。
- 心智圖的套件層依賴只算 `com.wae.*` 的 import；Android／Kotlin 標準庫不算邊。
- Linux／macOS 上沒有 Windows 路徑與 adb 預設值，要在 patch 裡改設定。
- 「安裝並重綁」會讓無障礙服務重新綁定；這是專案 AGENTS.md 要求的流程，不是外掛自作主張。
- 修改 `client.js` 後要重新載入頁面；Host 端改動由 cordis 重載。打包版沒有 `dev:web` 的 HMR。
