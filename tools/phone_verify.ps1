# WAE 真機驗證腳本（一鍵跑完，約 3–5 分鐘）
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\phone_verify.ps1
#   powershell -File tools\phone_verify.ps1 -ApkPath "app\build\outputs\apk\debug\app-debug.apk" -Serial emulator-5556
param(
  [string]$ApkPath = "",
  [string]$Serial = "",
  [string]$WavDir = "E:\WAE\temp\voice_base",
  [string]$Adb = "E:\WAE\android-sdk\platform-tools\adb.exe",
  [int]$PerCommandSeconds = 9
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
if (-not $ApkPath) { $ApkPath = Join-Path $root "app\build\outputs\apk\release\app-release.apk" }
if (-not (Test-Path $ApkPath)) { throw "找不到 APK: $ApkPath" }

function A([string[]]$cmdArgs) {
  & $Adb @cmdArgs 2>$null
}

function Find-Serial {
  param([string]$preferred)
  A @("start-server") | Out-Null
  $lines = A @("devices") | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice\s*$" }
  $serials = @($lines | ForEach-Object { ($_ -split "\s+")[0] })
  if ($preferred -and ($serials -contains $preferred)) { return $preferred }
  $physical = $serials | Where-Object { $_ -notmatch "^emulator-" } | Select-Object -First 1
  if ($physical) { return $physical }
  return $serials | Select-Object -First 1
}

$s = Find-Serial $Serial
if (-not $s) { throw "沒有連接任何裝置（adb devices 為空）" }
Write-Host "目標裝置: $s" -ForegroundColor Cyan
$isEmu = $s -match "^emulator-"

function D([string[]]$args2) { & $Adb -s $s @args2 2>$null }

$reportDir = "E:\WAE\reports"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$report = Join-Path $reportDir "phone_verify-$stamp.md"
$results = New-Object System.Collections.ArrayList

function Check([string]$name, [bool]$ok, [string]$detail) {
  $mark = "FAIL"
  if ($ok) { $mark = "PASS" }
  [void]$results.Add("| $name | $mark | $detail |")
  Write-Host ("[" + $mark + "] " + $name + " " + $detail)
}

# 1) 安裝
D @("install","-r",$ApkPath) | Out-Null
$pkg = (D @("shell","dumpsys package com.wae.agent") | Select-String "versionName=" | Select-Object -First 1)
Check "安裝 APK" ($pkg -ne $null) ("" + $pkg).Trim()

# 2) 綁定
D @("shell","settings put secure enabled_accessibility_services com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService") | Out-Null
D @("shell","settings put secure accessibility_enabled 1") | Out-Null
D @("shell","pm grant com.wae.agent android.permission.RECORD_AUDIO") | Out-Null
D @("shell","cmd role add-role-holder android.app.role.ASSISTANT com.wae.agent") | Out-Null
$a11yOk = $false
for ($i = 0; $i -lt 10; $i++) {
  Start-Sleep -Seconds 2
  $a = (D @("shell","dumpsys accessibility") | Select-String "WaeVisionAccessibilityService" | Select-Object -First 1)
  if ($a -ne $null) { $a11yOk = $true; break }
}
Check "無障礙服務" $a11yOk ""

# 3) 啟動助理 + 引擎（等 SHERPA_READY，最多 60 秒）
D @("shell","input keyevent KEYCODE_HOME") | Out-Null
Start-Sleep -Milliseconds 700
D @("logcat","-c") | Out-Null
D @("shell","am start -n com.wae.agent/.voice.AssistActivity -f 0x10008000") | Out-Null
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
  Start-Sleep -Seconds 2
  $logs = D @("logcat","-d","-t","400")
  if ($logs | Select-String "SHERPA_READY" | Select-Object -First 1) { $ready = $true; break }
}
Check "離線語音引擎" $ready "SHERPA_READY"

# 4) 語音基線（WAV；套用誤認修正後比對）
$corrPath = Join-Path $root "app\src\main\assets\open_source_ai_databases\ui_asr_corrections.tsv"
$corr = @()
if (Test-Path $corrPath) {
  $corr = Get-Content $corrPath -Encoding UTF8 | Where-Object { $_ -match "\t" -and -not $_.StartsWith("#") } | ForEach-Object {
    $p = $_ -split "`t"; [pscustomobject]@{ from = $p[0]; to = $p[1] }
  }
}
function Correct([string]$t) {
  foreach ($c in $corr) { if ($t -and $t.Contains($c.from)) { $t = $t.Replace($c.from, $c.to) } }
  return $t
}
$manifest = Join-Path $WavDir "manifest.tsv"
$rows = @(Get-Content $manifest -Encoding UTF8 | Select-Object -Skip 1 | Where-Object { $_.Trim() })
$csv = Join-Path $reportDir "voice_baseline-$stamp.csv"
"id,expected,raw,corrected,ms,ok" | Set-Content $csv -Encoding UTF8
$okCount = 0
D @("shell","mkdir -p /sdcard/Android/data/com.wae.agent/files") | Out-Null
foreach ($r in $rows) {
  $p = $r -split "`t"
  $id = $p[0]; $expected = $p[1]; $wav = $p[2]
  D @("push",$wav,"/sdcard/Android/data/com.wae.agent/files/sherpa_test.wav") | Out-Null
  D @("shell","rm -f /sdcard/Android/data/com.wae.agent/files/sherpa_test_out.txt") | Out-Null
  D @("shell","input keyevent KEYCODE_HOME") | Out-Null
  Start-Sleep -Milliseconds 800
  D @("shell","am start -n com.wae.agent/.voice.AssistActivity -f 0x10008000") | Out-Null
  $text = ""; $ms = ""
  for ($try = 0; $try -lt 4; $try++) {
    Start-Sleep -Seconds $PerCommandSeconds
    $raw = D @("shell","cat /sdcard/Android/data/com.wae.agent/files/sherpa_test_out.txt")
    foreach ($line in $raw) {
      if ($line -match "^ms=(.+)$") { $ms = $matches[1] }
      if ($line -match "^text=(.+)$") { $text = $matches[1] }
    }
    if ($text) { break }
  }
  $fixed = Correct $text
  $ok = ($fixed.Replace(" ", "") -eq $expected.Replace(" ", ""))
  if ($ok) { $okCount++ }
  $mark = "MISS"; if ($ok) { $mark = "OK" }
  "$id,$expected,$text,$fixed,$ms,$mark" | Add-Content $csv -Encoding UTF8
  Write-Host ("[$id] $expected -> $text ($ms ms) $mark")
}
Check "語音基線（WAV）" ($okCount -ge 9) "$okCount/$($rows.Count)"

# 5) 端到端（測試鉤子：自動執行 01）
D @("shell","rm -f /sdcard/Android/data/com.wae.agent/files/sherpa_test_exec") | Out-Null
D @("push",(Join-Path $WavDir "01.wav"),"/sdcard/Android/data/com.wae.agent/files/sherpa_test.wav") | Out-Null
D @("shell","touch /sdcard/Android/data/com.wae.agent/files/sherpa_test_exec") | Out-Null
D @("logcat","-c") | Out-Null
D @("shell","input keyevent KEYCODE_HOME") | Out-Null
Start-Sleep -Milliseconds 700
D @("shell","am start -n com.wae.agent/.voice.AssistActivity -f 0x10008000") | Out-Null
$success = $false
for ($i = 0; $i -lt 20; $i++) {
  Start-Sleep -Seconds 3
  $l = D @("logcat","-d","-t","800")
  if ($l | Select-String "taskResult=SUCCESS" | Select-Object -First 1) { $success = $true; break }
}
Check "端到端（打開設定）" $success "taskResult=SUCCESS"
D @("shell","rm -f /sdcard/Android/data/com.wae.agent/files/sherpa_test_exec") | Out-Null

# 6) 截圖
D @("shell","input keyevent KEYCODE_HOME") | Out-Null
Start-Sleep -Milliseconds 600
D @("shell","am start -n com.wae.agent/.MainActivity") | Out-Null
Start-Sleep -Seconds 6
D @("shell","screencap -p /sdcard/wae_verify_main.png") | Out-Null
D @("pull","/sdcard/wae_verify_main.png",(Join-Path $reportDir "main-$stamp.png")) | Out-Null

# 7) 報告
$lines = @(
  "# WAE 真機驗證報告（$stamp）",
  "",
  "- 裝置：$s",
  "- APK：$ApkPath",
  "- 語音基線 CSV：voice_baseline-$stamp.csv",
  "",
  "| 檢查 | 結果 | 備註 |",
  "|---|---|---|"
) + @($results) + @("", "## 已知限制", "- emulator 只能驗 WAV/流程，聲學麥克風與誤喚醒需實體手機。")
$lines | Set-Content $report -Encoding UTF8
Write-Host ""
Write-Host "報告：$report" -ForegroundColor Green
if ($okCount -lt 9) { exit 1 }
exit 0
