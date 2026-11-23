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
    $run = if ($c.Count -ge 6) { $c[5].Trim().ToLower() } else { 'ok' }
    $rows += [pscustomobject]@{ id = $c[0]; tier = $c[1]; command = $c[2]; pkg = $c[3]; run = $run }
  }
  return $rows
}

function Classify([string]$log) {
  # 只看 WAE_ 行，且排除背景探索（WAE_EXPLORE 是 shadow，跟這題結果無關）
  $lines = ($log -split "`n") | Where-Object { $_ -match 'WAE_' -and $_ -notmatch 'WAE_EXPLORE' }
  if (-not $lines) { return @{ v = "NONE"; d = "" } }
  $text = $lines -join "`n"
  # 存「有用的那幾行」：任務層/決策層/接地層的最後 3 行，才看得出失敗原因
  $useful = $lines | Where-Object { $_ -match 'WAE_TASK:|WAE_GEN5:|WAE_GROUNDING:|WAE_EXEC:' }
  $detail = if ($useful) { (($useful | Select-Object -Last 3) -join ' || ') } else { $lines[-1].Trim() }
  # 優先序：taskResult（基準訊號）→ outcome → result。
  # 一定要取「最後一次」：先前用 Match（第一次）會把中途的 result=ABSTAIN
  # 當成最終結果，實際最後是 taskResult=FAIL。
  # 任務層的 status 也是正式訊號（TaskExecutor 成功時不一定會再印 taskResult）
  #   WAE_TASK: status=DONE / ASK / FAILED
  $all = [regex]::Matches($text, '(?:taskResult|outcome|result)=([A-Z_]+)|status=(DONE|ASK|FAILED)')
  if ($all.Count -eq 0) { return @{ v = "UNKNOWN"; d = $detail } }
  $last = $all[$all.Count - 1]
  $v = if ($last.Groups[1].Success) { $last.Groups[1].Value } else { $last.Groups[2].Value }
  # 語意對映：DONE＝任務層完成；CLEAR_WIN＝決策層選出明確贏家並已執行
  #（沒有 taskResult 時最接近成功的訊號）；REJECT＝決策被駁回，等於沒做成。
  switch ($v) {
    'DONE' { $v = 'SUCCESS' }
    'CLEAR_WIN' { $v = 'SUCCESS' }
    'REJECT' { $v = 'FAIL' }
  }
  return @{ v = $v; d = $detail }
  return @{ v = "UNKNOWN"; d = $detail }
}

function Get-UiXml {
  Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/wae_bench.xml') | Out-Null
  return ((Invoke-Adb @('shell', 'cat', '/sdcard/wae_bench.xml')) -join "`n")
}

function Find-Bounds([string]$xml, [string]$text) {
  $m = [regex]::Match($xml, ('text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'))
  if (-not $m.Success) {
    $m = [regex]::Match($xml, ('bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="' + [regex]::Escape($text) + '"'))
  }
  if (-not $m.Success) { return $null }
  return @{
    x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
  }
}

# 回到「可以按執行」的輸入頁。App 跑完會停在「任務已完成」卡片（要按「知道了」），
# 執行中按鈕會變「停止」；這兩種都不能直接找「執行」。
function Get-InputScreen {
  for ($i = 0; $i -lt 4; $i++) {
    $xml = Get-UiXml
    if ($xml -match 'text="執行"') { return $xml }
    $known = Find-Bounds $xml "知道了"
    if ($known) {
      Invoke-Adb @('shell', 'input', 'tap', "$([int]$known.x)", "$([int]$known.y)") | Out-Null
      Start-Sleep -Seconds 1
      continue
    }
    if ($xml -match 'text="停止"') {
      Start-Sleep -Seconds 3
      continue
    }
    Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
    Start-Sleep -Milliseconds 800
  }
  return $null
}

function Invoke-OneRun {
  param([string]$command, [string]$pkg)
  Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
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

  $xml = Get-InputScreen
  if ($null -eq $xml) { return @{ v = "NO_BUTTON"; d = "回不到輸入頁" } }
  $b = Find-Bounds $xml "執行"
  if ($null -eq $b) { return @{ v = "NO_BUTTON"; d = "找不到執行按鈕" } }
  Invoke-Adb @('shell', 'input', 'tap', "$([int]$b.x)", "$([int]$b.y)") | Out-Null

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
  if ($t.run -eq 'skip') {
    # 這台裝置上該 App 被停用（實測 YouTube Music 是 disabled），跑不了 → 記 SKIP 不計分
    Write-Output "   SKIP（目標 App 已停用）"
    (@($t.id, $t.tier, $t.command, $t.pkg, "SKIP", "", "SKIP", "目標 App 已停用") -join "`t") |
      Out-File -FilePath $Out -Encoding utf8 -Append
    continue
  }
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
