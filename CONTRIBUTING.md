# 貢獻指南

這份文件是 `AGENTS.md` 的對外版本：把「這個專案怎麼改才不會壞」講清楚。

> 內部開發者請直接讀 `AGENTS.md`（更完整）；這份是給第一次接觸的人。

## 一句話認識專案

WAE 是一個**完全離線**的 Android 無障礙代理人：使用者說一句話，它透過無障礙 API 在任意 App 裡
完成操作，全程不上傳資料、不連網、不使用生成式 LLM。

## 開始之前

```powershell
$env:JAVA_HOME       = 'C:\Users\User\.jdks\jdk-21.0.13.1+2'
$env:GRADLE_USER_HOME = 'E:\WAE\gradle-home'   # 依你的機器調整

.\gradlew.bat :core:test :app:testFullDebugUnitTest
.\gradlew.bat :app:assembleFullDebug
```

需求：JDK 21、Android SDK（`local.properties` 的 `sdk.dir`）、約 10 GB 磁碟。

## 五條硬規則（CI 會擋）

| # | 規則 | 為什麼 | 檢查方式 |
|---|---|---|---|
| 1 | **不出貨 LLM** | 使用者明確要求；產品必須離線 | `ClickPathNoLlmTest` |
| 2 | **資料驅動，不硬編清單** | 新增 App／說法應該是加一列 TSV，不是改程式 | `NoAppSpecificCodeTest`、`DataSourcePolicyTest` |
| 3 | **數值可調** | 門檻／權重／逾時放 `ui_tunables.tsv` | 人工審查（新程式碼不得出現魔術數字） |
| 4 | **UI 不得有 emoji** | 使用者要求；需要圖示用 VectorDrawable | CI `policy` job |
| 5 | **TSV 不得有 BOM** | 有 BOM 時第一行 `#` 註解會被當成資料列 | CI `policy` job、`tools/debt_report.ps1` |

## 錯誤處理

可降級的失敗一律用 `com.wae.core.util.Degrade`：

```kotlin
// 好
val pkg = Degrade.orElse("where:why", "") { node.packageName?.toString() ?: "" }

// 不好：安靜吞錯
val pkg = runCatching { node.packageName?.toString() }.getOrNull().orEmpty()
```

`SilentFailureRatchetTest` 會記住「吞錯總數」，只准下降不准上升。
目前總數：`runCatching` 457（baseline 471）。

## 提交前自檢

```powershell
powershell -File tools\debt_report.ps1     # 五項指標都要 ok
.\gradlew.bat :core:test :app:testFullDebugUnitTest
```

CI（`.github/workflows/android.yml`）會再跑一次，三個 job 必須全綠：

- `test`：單元測試與守門測試
- `policy`：BOM／emoji
- `build`：建 debug APK 並上傳 artifact

## Commit 訊息

用 Conventional Commits 的語氣（中文字沒問題）：

```
fix(task19): 修正指紋把時鐘秒數算進去，導致「假成功」
refactor(ui): onCreate 抽出設定抽屜，817 → 100 行
chore(ci): 升級 actions/checkout 至 v5
```

## 真機驗證比單元測試重要

這個專案的難點是**行為**，不是編譯：

- `uiautomator` 在會動的畫面（時鐘秒數）會 dump 失敗
- 無障礙節點常常缺 label，要靠 OCR／圖示通道
- 同一個指令在不同 App 可能走不同路徑

所以改動「決策」或「點擊」相關程式碼時，請在真機上跑對應題目，並附上
`adb logcat -s WAE_TASK WAE_PICK WAE_OPTION WAE_MENU` 的證據。

## 文件

- 索引：`docs/README.md`
- 架構：`docs/WAE_Atlas_Architecture.md`
- 穩定性與覆蓋率路線：`docs/WAE_Stability_Coverage_Plan.md`
- 已知邊界：`docs/WAE_How_To_Verify.md`
