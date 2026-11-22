# 真機驗證清單（arm64 手機）

> 這份只列**必須在真機上驗**的項目。模擬器已驗過的（27+ 條基線、圖示通道載入、回滾、能力索引）
> 不重複列，除非真機行為可能不同。
>
> 用法：手機接上後照順序跑；每項都有「預期輸出」與「失敗怎麼查」。
> 可自動化的部分：`python tools/device_checklist.py`（會把 R1–R5 跑一遍）。

## 一鍵跑完（真機接上後）

```powershell
python tools/device_checklist.py -s <手機序號> --full --with-eval
```

會依序跑 R1–R8（R8 是 114 條評測，約 15 分鐘），跑完**自動更新** `bench/device_verification.tsv`。
之後 `python tools/health.py --guards-only` 的第一行就會顯示「已驗幾項」。

只想快速確認（約 2 分鐘）：`python tools/device_checklist.py -s <序號> --full --fast`（略過自我測試）。

## 0. 前置（每次安裝後都要做）

```powershell
$adb="E:\WAE\android-sdk\platform-tools\adb.exe"
& $adb devices -l                                  # 確認手機序號出現（不是 emulator-5554）
& $adb install -r "E:\NewAndroidApp\app\build\outputs\apk\full\release\app-full-release.apk"
& $adb shell settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
& $adb shell appops set com.wae.agent WRITE_SETTINGS allow
& $adb shell pm grant com.wae.agent android.permission.READ_MEDIA_IMAGES
& $adb shell am start -n com.wae.agent/.MainActivity
```

**判準**：App 打開後狀態列顯示無障礙服務已啟用（或「系統檢查」全綠）。

> 注意：真機是 arm64，**不要**加 `-PwaeIncludeX86=true`；模擬器才需要。
> 安裝會解除無障礙綁定，**每次安裝後都要重跑上面兩行 settings**。

## R1 服務綁定

```powershell
& $adb shell dumpsys accessibility | findstr wae
```

**預期**：`Enabled services:{{com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService}}`

## R2 App 內自我測試（9 項）

```powershell
# 走 probe（不用手點）
[System.IO.File]::WriteAllText("$env:TEMP\st.txt", "!selftest", (New-Object System.Text.UTF8Encoding($false)))
& $adb push "$env:TEMP\st.txt" /sdcard/Android/data/com.wae.agent/files/gen5_command.txt
& $adb shell am start -n com.wae.agent/.MainActivity
Start-Sleep -Seconds 110
& $adb shell cat /sdcard/Android/data/com.wae.agent/files/gen5_result.txt
```

**預期**：`自我測試：9/9 通過`

## R3 亮度真的會變（讀回確認）

```powershell
& $adb shell settings get system screen_brightness      # 記下 before
# 執行「調亮」後
& $adb shell settings get system screen_brightness      # 應該增加（約 +30）
```

**判準**：數值真的變了。**沒變就是 FAIL**（不是「大概有變」）。

## R4 被動學習：自己操作也會被記錄

```powershell
& $adb shell run-as com.wae.agent ls -l files/twin/steps.jsonl     # 記下大小
& $adb shell input keyevent KEYCODE_HOME
& $adb shell am start -a android.settings.SETTINGS
Start-Sleep 3
& $adb shell input tap 540 900                                     # 點一個設定列（座標依機型）
Start-Sleep 3
& $adb shell run-as com.wae.agent ls -l files/twin/steps.jsonl     # 應該變大
```

**預期**：`steps.jsonl` 變大，`logcat` 出現 `WAE_PASSIVE: passive step recorded`。

**失敗怎麼查**：`WAE_PASSIVE` 有沒有 `click pkg=… record=false`（白名單沒收錄 → 先跑一次該 App 的任務讓收據寫入）。

## R5 相機快門（**只有真機能驗**）

```powershell
& $adb shell "find /sdcard/DCIM -type f | wc -l"     # 記下 before
# 執行「拍照」
& $adb shell "find /sdcard/DCIM -type f | wc -l"     # 應該 +1
& $adb logcat -d -s WAE_VISION:I | findstr "icon scan"
```

**預期**：照片數 +1；log 出現 `icon scan … boxes=N`（N ≥ 1）。

**已知**：模擬器的相機是 stub，按快門不會存檔（`DCIM` 永遠 0）——這項在模擬器上**必然失敗**，不是 bug。

## R6 分享相簿照片

```powershell
# 先確認相簿裡有照片
& $adb shell "content query --uri content://media/external/images/media --projection _id | head -3"
# 執行「分享照片給訊息」
& $adb logcat -d -s WAE_PHOTOS:I | findstr "share photo"
```

**預期**：`share photo id=… uri=content://media/…`，前景變成訊息 App 的分享畫面。

## R7 搜尋（連跑三次）

```powershell
# 執行「在設定裡搜尋藍牙」三次（每次先回主頁）
```

**預期**：3/3 SUCCESS。若失敗，先看收據走哪條路：

```
SELECT:query:藍牙|intent:wae.action.SEARCH|SUCCESS   ← 走系統搜尋意圖（正常，steps=2）
SELECT:query:藍牙|a11y:focus|UNSUPPORTED             ← 落到 UI 路徑（這才是問題）
SELECT:query:藍牙|input:ime|NO_EFFECT                ← 輸入法吃不到字（設定頁已知）
```

走意圖是正常路徑；一落到 UI 路徑就代表 `lastTargetPkg` 沒拿到或查表失敗。

## R8 完整評測（可選，約 25 分鐘）

```powershell
python tools/gen5_eval.py --install
```

**預期**：≥ 109/114。失敗的逐條記在 `bench/last_baseline.tsv`。

## 結果登記

> 在真機上跑 `python tools/device_checklist.py -s <序號>` 會**自動更新**這些狀態
> （`bench/device_verification.tsv`）；模擬器跑完不會更新。
> `python tools/health.py --guards-only` 的第一行就會顯示「已驗幾項、最舊幾天前、哪些未驗」。


| 項目 | 日期 | 結果 | 備註 |
|---|---|---|---|
| R1 服務綁定 | | | |
| R2 自我測試 | | | |
| R3 亮度 | | | |
| R4 被動學習 | | | |
| R5 相機快門 | | | |
| R6 分享照片 | | | |
| R7 搜尋 ×3 | | | |
| R8 完整評測 | | | |

> 驗完請把結果填進上表並更新 `docs/WAE_Master_Backlog.md` 的「真機測試記錄」。
