# WAE 真機自動驗證（不需要人工打字）
#
# 為什麼要這支：adb 無法輸入中文，App 的 debug_command 只會「填入輸入框」，
# 還需要按一次「執行」。這支腳本把整條流程串起來：安裝 → 重綁無障礙 →
# 依序注入指令 → 按執行 → 用內部訊號（taskResult=）判定。
#
# 用法：
#   powershell -File tools\verify_on_device.ps1
#   powershell -File tools\verify_on_device.ps1 -Commands "打開設定","打開藍牙" -SkipInstall
#
param(
  [string]$Adb = "E:\WAE\android-sdk\platform-tools\adb.exe",
  [string[]]$Commands = @("打開設定", "打開藍牙", "打開飛航模式"),
  # 與 -Commands 一一對應的「被測 App」套件；空字串代表這題不需要先冷啟。
  # 為什麼要有：AGENTS 的測試協議是「被測 App 先 force-stop 回冷啟狀態」，
  # 否則上一題留下的頁面會讓結果不可比較（也測不出「從首頁找路」）。
  [string[]]$TargetPackages = @("com.android.settings", "com.android.settings", "com.android.settings"),
  [int]$WaitSeconds = 25,
  [switch]$SkipInstall
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $root "app\build\outputs\apk\full\debug\app-full-debug.apk"

function Invoke-Adb {
  param([string[]]$AdbArgs)
  # adb 會把正常訊息寫到 stderr；轉成字串避免被 PowerShell 當成終止錯誤
  $out = & $Adb @AdbArgs 2>&1 | ForEach-Object { "$_" }
  return $out
}

function Assert-Device {
  $list = Invoke-Adb @('devices')
  if (-not ($list -match 'device$')) {
    throw "找不到已授權的裝置。請在手機上確認 USB 偵錯並按「允許」。`n$($list -join "`n")"
  }
}

function Install-And-Bind {
  Write-Output "[1/4] 安裝 $apk"
  $r = Invoke-Adb @('install', '-r', $apk)
  if (-not ($r -match 'Success')) { throw "安裝失敗：$($r -join ' | ')" }
  Write-Output "[1/4] 重新綁定無障礙服務"
  Invoke-Adb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services',
    'com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService') | Out-Null
  Invoke-Adb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
  Start-Sleep -Seconds 3
  $bound = Invoke-Adb @('shell', 'dumpsys', 'accessibility')
  if (-not ($bound -match 'Bound services.*wae')) {
    Write-Warning "無障礙服務尚未顯示 Bound，稍後指令可能失敗（可在手機上確認 App 的無障礙開關）"
  }
}

function Push-Command {
  param([string]$Text)
  $tmp = Join-Path $env:TEMP "wae_cmd.txt"
  # 一定要 UTF-8 無 BOM：App 用 Charsets.UTF_8 讀
  [System.IO.File]::WriteAllText($tmp, $Text, (New-Object System.Text.UTF8Encoding($false)))
  Invoke-Adb @('push', $tmp, '/data/local/tmp/wae_cmd.txt') | Out-Null
  $r = Invoke-Adb @('shell', 'run-as', 'com.wae.agent', 'cp', '/data/local/tmp/wae_cmd.txt', 'files/debug_command')
  if ($LASTEXITCODE -ne 0) { throw "run-as 失敗（App 必須是 debug 版）：$($r -join ' ')" }
}

function Get-UiXml {
  Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/wae_ui.xml') | Out-Null
  return ((Invoke-Adb @('shell', 'cat', '/sdcard/wae_ui.xml')) -join "`n")
}

function Ensure-InputScreen {
  # 上一次執行完會停在「任務已完成」的結果頁，那時候畫面上沒有「執行」鈕。
  # 用 BACK 退回輸入頁（不要 force-stop，會殺掉無障礙服務）。
  for ($i = 0; $i -lt 3; $i++) {
    $xml = Get-UiXml
    if ($xml -match 'text="執行"') { return $xml }
    Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
    Start-Sleep -Milliseconds 1500
  }
  throw "回不到輸入頁（找不到「執行」按鈕）"
}

function Tap-ExecuteButton {
  param([string]$Xml)
  $m = [regex]::Match($Xml, 'text="執行"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $m.Success) {
    # 有些版本 bounds 在 text 前面
    $m = [regex]::Match($Xml, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="執行"')
  }
  if (-not $m.Success) { throw "找不到「執行」按鈕" }
  $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
  $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
  Invoke-Adb @('shell', 'input', 'tap', "$([int]$x)", "$([int]$y)") | Out-Null
}

function Get-TaskResult {
  $log = (Invoke-Adb @('logcat', '-d')) -join "`n"
  # 這個 App 有兩條路徑會記結果：
  #   Gen5：「WAE_GEN5: result=SUCCESS ...」
  #   舊路徑／Task OS：「WAE_EXEC taskResult=SUCCESS」
  # 只認 WAE_ 的日誌行，避免吃到系統的 result=false。
  $lines = $log -split "`n" | Where-Object { $_ -match 'WAE_' -and $_ -match '(taskResult|result)=' }
  if ($lines.Count -eq 0) { return "NONE" }
  $m = [regex]::Matches(($lines -join "`n"), '(?:taskResult|result)=([A-Z_]+)')
  if ($m.Count -eq 0) { return "NONE" }
  return $m[$m.Count - 1].Groups[1].Value
}

Assert-Device
if (-not $SkipInstall) { Install-And-Bind }

$rows = @()
$i = 0
foreach ($cmd in $Commands) {
  $i++
  Write-Output "[2/4] ($i/$($Commands.Count)) 注入：$cmd"
  Invoke-Adb @('logcat', '-c') | Out-Null
  # 協議第一步：把「被測 App」冷啟（force-stop 的是目標 App，不是 com.wae.agent）
  $target = if ($i -le $TargetPackages.Count) { $TargetPackages[$i - 1] } else { "" }
  if ($target -ne "") {
    Invoke-Adb @('shell', 'am', 'force-stop', $target) | Out-Null
    Write-Output "      冷啟：$target"
  }
  Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
  Start-Sleep -Milliseconds 800

  Push-Command $cmd
  Invoke-Adb @('shell', 'am', 'start', '-n', 'com.wae.agent/.MainActivity') | Out-Null
  Start-Sleep -Seconds 3
  $ui = Ensure-InputScreen
  Tap-ExecuteButton $ui
  Start-Sleep -Seconds $WaitSeconds

  $result = Get-TaskResult
  $rows += [pscustomobject]@{ command = $cmd; taskResult = $result }
  Write-Output ("      → taskResult=$result")
}

Write-Output "[4/4] 解析軌跡（若本次有走述詞路徑）"
$trace = Invoke-Adb @('shell', 'run-as', 'com.wae.agent', 'cat', 'files/eval/resolution_trace.tsv')
if ($trace -and ($trace -notmatch 'No such file')) {
  $trace | Select-Object -Last 6
} else {
  Write-Output "      （沒有軌跡檔：本次可能沒走到 PredicateRuntime，或 trace 被關掉）"
}

Write-Output ""
Write-Output "=== 結果 ==="
$rows | Format-Table -AutoSize | Out-String | Write-Output
