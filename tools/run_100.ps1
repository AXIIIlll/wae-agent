# 自動跑 100 題（工具，不是 App 功能）
#
# 每題流程（照 AGENTS 第 3 節協議）：
#   1. 關掉被測 App（force-stop 目標套件，不是 com.wae.agent）
#   2. 回桌面
#   3. 注入指令（filesDir/debug_command）+ 開 WAE 主畫面 + 按「執行」
#   4. 等結果，讀內部訊號（outcome= / result= / taskResult=）
#   5. 失敗再跑一次；第二次仍失敗就記「跳過」，繼續下一題
#
# 用法：powershell -File tools\run_100.ps1 -From 1 -To 10
# 結果：E:\WAE\bench100_result.tsv（可續跑）
param(
  [string]$Adb = "E:\WAE\android-sdk\platform-tools\adb.exe",
  [int]$From = 1,
  [int]$To = 100,
  [int]$WaitSeconds = 22,
  [string]$Out = "E:\WAE\bench100_result.tsv"
)

$ErrorActionPreference = 'Continue'
$adbPath = $Adb
$root = Split-Path -Parent $PSScriptRoot
$tasksFile = Join-Path $root "benchmark\tasks_100.tsv"
if (-not (Test-Path $tasksFile)) { throw "找不到題庫：$tasksFile" }

function Invoke-Adb {
  param([string[]]$A)
  return (& $adbPath @A 2>&1 | ForEach-Object { "$_" })
}

function Get-TaskRows {
  $rows = @()
  foreach ($raw in [System.IO.File]::ReadAllLines($tasksFile, [System.Text.Encoding]::UTF8)) {
    $line = $raw.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { continue }
    $c = $line.Split("`t")
    if ($c.Count -lt 4) { continue }
    $rows += [pscustomobject]@{ id = $c[0]; tier = $c[1]; command = $c[2]; pkg = $c[3] }
  }
  return $rows
}

function Classify([string]$log) {
  $lines = ($log -split "`n") | Where-Object { $_ -match 'WAE_' }
  if (-not $lines) { return @{ v = "NONE"; d = "" } }
  $text = $lines -join "`n"
  foreach ($k in @("outcome=SUCCESS", "result=SUCCESS", "taskResult=SUCCESS")) {
    if ($text.Contains($k)) { return @{ v = "SUCCESS"; d = $lines[-1].Trim() } }
  }
  $m = [regex]::Match($text, '(?:outcome|result|taskResult)=([A-Z_]+)')
  if ($m.Success) { return @{ v = $m.Groups[1].Value; d = $lines[-1].Trim() } }
  return @{ v = "UNKNOWN"; d = $lines[-1].Trim() }
}

function Invoke-OneRun {
  param([string]$command, [string]$pkg)
  if ($pkg -ne "") { Invoke-Adb @('shell', 'am', 'force-stop', $pkg) | Out-Null }
  Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
  Start-Sleep -Milliseconds 700
  Invoke-Adb @('logcat', '-c') | Out-Null

  $tmp = Join-Path $env:TEMP "wae_bench_cmd.txt"
  [System.IO.File]::WriteAllText($tmp, $command, (New-Object System.Text.UTF8Encoding($false)))
  Invoke-Adb @('push', $tmp, '/data/local/tmp/wae_bench_cmd.txt') | Out-Null
  Invoke-Adb @('shell', 'run-as', 'com.wae.agent', 'cp', '/data/local/tmp/wae_bench_cmd.txt', 'files/debug_command') | Out-Null

  Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
  Start-Sleep -Milliseconds 400
  Invoke-Adb @('shell', 'am', 'start', '-n', 'com.wae.agent/.MainActivity') | Out-Null
  Start-Sleep -Seconds 3

  Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/wae_bench.xml') | Out-Null
  $xml = ((Invoke-Adb @('shell', 'cat', '/sdcard/wae_bench.xml')) -join "`n")
  $m = [regex]::Match($xml, 'text="執行"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $m.Success) { return @{ v = "NO_BUTTON"; d = "找不到執行按鈕" } }
  $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
  $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
  Invoke-Adb @('shell', 'input', 'tap', "$([int]$x)", "$([int]$y)") | Out-Null

  Start-Sleep -Seconds $WaitSeconds
  $log = (Invoke-Adb @('logcat', '-d')) -join "`n"
  return Classify $log
}

$rows = Get-TaskRows | Where-Object { [int]$_.id -ge $From -and [int]$_.id -le $To }
Write-Output ("要跑 {0} 題（{1} → {2}）" -f $rows.Count, $From, $To)

if (-not (Test-Path $Out)) {
  "id`ttier`tcommand`ttarget`tattempt1`tattempt2`tfinal`tdetail" |
    Out-File -FilePath $Out -Encoding utf8 -Append
}

foreach ($t in $rows) {
  Write-Output ("-- {0} [{1}] {2}" -f $t.id, $t.tier, $t.command)
  $r1 = Invoke-OneRun $t.command $t.pkg
  Write-Output ("   1st: {0}" -f $r1.v)
  $final = $r1.v
  $r2v = ""
  if ($r1.v -ne "SUCCESS") {
    $r2 = Invoke-OneRun $t.command $t.pkg
    $r2v = $r2.v
    $final = $r2.v
    Write-Output ("   2nd: {0}" -f $r2.v)
  }
  $line = @($t.id, $t.tier, $t.command, $t.pkg, $r1.v, $r2v, $final, $r1.d) -join "`t"
  $line | Out-File -FilePath $Out -Encoding utf8 -Append
}
Write-Output ("完成，結果：{0}" -f $Out)
