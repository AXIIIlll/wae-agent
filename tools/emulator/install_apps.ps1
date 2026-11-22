# 用 adb 連上 Android-x86 模擬器，安裝 F-Droid 與開源 App
$ErrorActionPreference = "Stop"
$adb = "E:\WAE\android-sdk\platform-tools\adb.exe"
$dl = "E:\WAE\emulator\downloads"
Write-Host "連線 adb 127.0.0.1:5555 …"
& $adb connect 127.0.0.1:5555
& $adb wait-for-device
Start-Sleep -Seconds 2
Write-Host "=== 裝置 ==="
& $adb devices
$fdroid = Join-Path $dl "fdroid.apk"
if (Test-Path $fdroid) {
  Write-Host "安裝 F-Droid…"
  & $adb install -r $fdroid
}
Write-Host "已完成。之後可用 F-Droid 安裝開源 App，或用 clone.py 採集畫面。"
