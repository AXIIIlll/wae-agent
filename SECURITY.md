# 安全政策

WAE 是一個**完全離線**的 Android 代理人：Manifest 以 `tools:node="remove"` 移除 INTERNET 權限，
程式碼中沒有任何 HTTP 呼叫。因此「資料外洩」的風險主要來自**開發流程**，不是執行期。

## 回報安全問題

請**不要**開公開 Issue。改用以下任一方式：

1. GitHub 的 [Private vulnerability reporting](../../security/advisories/new)（本 repo 已啟用）
2. 直接寄信給維護者（見 GitHub 個人檔案）

我們會在 7 天內回覆，並在修復後於 CHANGELOG 記錄（不公開細節直到修復釋出）。

## 絕對不可以進版控的東西

| 檔案 | 內容 | 現況 |
|---|---|---|
| `local.properties` | keystore 路徑與**明文密碼** | 已被 `.gitignore` 排除 |
| `keystore/`、`*.jks`、`*.keystore` | 簽章金鑰 | 已被排除 |
| `*.apk`、`*.aab` | 建置產物（數百 MB） | 已被排除 |
| `.dsh/`、`*.log` | 本機工具與日誌 | 已被排除 |

CI 的 `policy` job 會檢查資料檔（TSV 不得有 BOM、UI 不得有 emoji），但**不會**掃描密鑰；
若你新增任何憑證，請一併確認 `.gitignore` 有對應規則。

## 簽章金鑰的建議做法

目前 `local.properties` 以明文保存 keystore 密碼（本機開發方便）。要改成環境變數：

```powershell
$env:WAE_KEYSTORE_PATH   = 'E:/WAE/keystore-wae/release-upload-key.jks'
$env:WAE_STORE_PASSWORD  = '...'
$env:WAE_KEY_ALIAS       = 'upload_key'
$env:WAE_KEY_PASSWORD    = '...'
```

`app/build.gradle.kts` 已優先讀取環境變數，找不到才退回 `local.properties`。

## 回報「代理人做錯事」

如果 WAE 在真機上產生了不該做的動作（誤點、誤送出），那不是傳統資安問題，屬於**行為正確性**，
請用「任務失敗回報」Issue 範本，附上：

- 指令原文
- 題號（若來自 benchmark）
- `adb logcat -s WAE_TASK WAE_PICK WAE_OPTION` 的相關片段
- 當下截圖
