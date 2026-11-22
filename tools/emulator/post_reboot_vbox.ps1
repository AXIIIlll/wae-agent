# 重開機後（Hyper-V 已關）用 VirtualBox 開 Android-x86 並檢查
$ErrorActionPreference = "Continue"
$vbox = "C:\Program Files\Oracle\VirtualBox\VBoxManage.exe"
$adb  = "E:\WAE\android-sdk\platform-tools\adb.exe"
$dir  = "E:\WAE\emulator"

Write-Host "=== 1. 確認 Hyper-V 已關 ==="
bcdedit /enum "{current}" 2>&1 | Select-String "hypervisorlaunchtype"
Write-Host "=== 2. 確認 VirtualBox 是否能用原生 VT-x ==="
& $vbox showvminfo AndroidX86 --machinereadable 2>$null | Select-String "NEM|HM" | Out-Null
Write-Host "=== 3. 開機（GUI 視窗）==="
& $vbox startvm AndroidX86 --type gui 2>&1 | Select-Object -Last 2
Start-Sleep -Seconds 40
& $vbox controlvm AndroidX86 screenshotpng "$dir\vbox_gui1.png" 2>&1 | Out-Null
Write-Host "畫面已存：$dir\vbox_gui1.png"
Write-Host "=== 4. 等 Android 起來，嘗試 adb ==="
for ($i=1; $i -le 12; $i++) {
  Start-Sleep -Seconds 20
  & $vbox controlvm AndroidX86 screenshotpng "$dir\vbox_gui_$i.png" 2>&1 | Out-Null
  & $adb connect 127.0.0.1:5555 2>$null | Out-Null
  $d = (& $adb devices) -join " "
  Write-Host ("[" + $i + "] adb: " + $d)
  if ($d -match "127.0.0.1:5555\s+device|emulator-5554\s+device") { Write-Host "ADB OK"; break }
}
Write-Host "=== 5. 最後狀態 ==="
Get-Process VBoxHeadless,VirtualBoxVM -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,@{n='CPU';e={[math]::Round($_.CPU)}}
