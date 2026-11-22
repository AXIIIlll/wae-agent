# WAE — 離線可驗證的個人手機代理人（Android）

> 目標（北極星）：讓一支完全離線、不上傳任何資料的 Android 手機，成為第一個「可驗證」的
> 個人 AI 代理人作業系統：說一句話，它在任意 App 裡把事做完，每一步都能被檢驗。

## 特色

- **物理離線**：Manifest 以 `tools:node="remove"` 移除 INTERNET 權限；語音/視覺/決策全在本機。
- **全離線語音**：內建 sherpa-onnx 中文 zipformer int8 模型（APK 自帶，零下載、零網路）。
- **可驗證安全**：指令閘門、風險分級（BLOCK/CONFIRM）、敏感操作確認卡、前 20 次安全模式。
- **通用操作**：無障礙 API + 資料驅動（TSV）意圖/模板/策略；涵蓋任意 App，不寫死單一 App。
- **極簡 UI**：白底 + 波浪漸層、懸浮窗（HTML）、語音助理（Google 助理角色 / 快捷磚 / 電源鍵）。

## 建置

```powershell
$env:JAVA_HOME='C:\Users\Wade\.jdks\jbr-21.0.11'
$env:GRADLE_USER_HOME='E:\WAE\gradle-home'
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain --no-daemon
```

- release 簽章金鑰**不在 repo**：放在 `local.properties`（gitignored）或環境變數
  `WAE_KEYSTORE_PATH` / `WAE_STORE_PASSWORD` / `WAE_KEY_ALIAS` / `WAE_KEY_PASSWORD`。
- 未設定金鑰時會 fallback debug 簽章並印出警告。

## 安裝

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.wae.agent android.permission.RECORD_AUDIO
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.wae.agent
```

## 文件

- 北極星：[docs/WAE_NORTH_STAR.md](docs/WAE_NORTH_STAR.md)
- 缺口稽核：[docs/WAE_GAP_AUDIT.md](docs/WAE_GAP_AUDIT.md)
- 語音助理與安全：[docs/WAE_VoiceAssistant.md](docs/WAE_VoiceAssistant.md)
- 加固計畫：[docs/WAE_Hardening_Plan.md](docs/WAE_Hardening_Plan.md)
- 可驗證執行：[docs/WAE_Verified_Execution.md](docs/WAE_Verified_Execution.md)
- 評測：[docs/WAE_Benchmark.md](docs/WAE_Benchmark.md)
- 喚醒詞規格：[docs/WAE_WakeWord_Spec.md](docs/WAE_WakeWord_Spec.md)

## 已知限制

- 數字/專有名詞辨識仍弱；真人麥克風尚未建立成功率基線
- 未支援喚醒詞（目前用電源鍵/助理鍵/快捷磚）
- 多機型（非 Samsung）尚未驗證
- 自動化第三方 App 有平台與政策風險；敏感操作一律需人工確認
