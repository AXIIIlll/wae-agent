# WAE Agent 評測腳本（20 題）
# 模式：
#   legacy  — 舊快速路徑（輸入指令 → 既有拆解管線）
#   agent   — LLM Agent（外部 agent_goal.txt → WAE_AGENT 結果）
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\agent_benchmark.ps1
#   powershell -File tools\agent_benchmark.ps1 -Mode agent -Serial emulator-5556
param(
  [string]$Adb = "E:\WAE\android-sdk\platform-tools\adb.exe",
  [string]$Serial = "",
  [string]$Tasks = "E:\NewAndroidApp\benchmark\agent_tasks_20.tsv",
  [ValidateSet("legacy","agent")][string]$Mode = "agent",
  [int]$WaitSeconds = 240,
  [int]$Repeat = 1
)

$ErrorActionPreference = "Continue"

function A([string[]]$cmdArgs) { & $Adb @cmdArgs 2>$null }

$s = $Serial
if (-not $s) {
  A @("start-server") | Out-Null
  $lines = A @("devices") | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice\s*$" }
  $serials = @($lines | ForEach-Object { ($_ -split "\s+")[0] })
  $physical = $serials | Where-Object { $_ -notmatch "^emulator-" } | Select-Object -First 1
  if ($physical) { $s = $physical } else { $s = $serials | Select-Object -First 1 }
}
if (-not $s) { throw "沒有連接任何裝置" }
Write-Host "目標裝置: $s 模式: $Mode" -ForegroundColor Cyan

$reportDir = "E:\WAE\reports"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$csv = Join-Path $reportDir "agent_benchmark-$Mode-$stamp.csv"
"id,app,utterance,result,ms" | Set-Content $csv -Encoding UTF8

A @("shell","mkdir -p /sdcard/Android/data/com.wae.agent/files") | Out-Null

$rows = @(Get-Content $Tasks -Encoding UTF8 | Select-Object -Skip 1 | Where-Object { $_.Trim() })
$ok = 0; $total = 0
foreach ($r in $rows) {
  $p = $r -split "`t"
  if ($p.Count -lt 4) { continue }
  $id = $p[0]; $app = $p[1]; $utter = $p[2]
  for ($i = 1; $i -le $Repeat; $i++) {
    $total++
    $start = Get-Date
    $result = "NONE"

    if ($Mode -eq "agent") {
      A @("shell","rm -f /sdcard/Android/data/com.wae.agent/files/agent_result.txt") | Out-Null
      $tmpf = "E:\WAE\temp\bench_goal.txt"
      [System.IO.File]::WriteAllText($tmpf, $utter, (New-Object System.Text.UTF8Encoding($false)))
      A @("push",$tmpf,"/sdcard/Android/data/com.wae.agent/files/agent_goal.txt") | Out-Null
      for ($w = 0; $w -lt $WaitSeconds; $w += 3) {
        Start-Sleep -Seconds 3
        $x = A @("shell","cat /sdcard/Android/data/com.wae.agent/files/agent_result.txt")
        $txt = ($x -join " ").Trim()
        if ($txt) {
          if ($txt -match "result=([A-Z]+)") { $result = $matches[1] }
          break
        }
      }
    } else {
      A @("logcat","-c") | Out-Null
      A @("shell","input keyevent KEYCODE_HOME") | Out-Null
      Start-Sleep -Milliseconds 700
      A @("shell","am start -n com.wae.agent/.voice.AssistActivity") | Out-Null
      Start-Sleep -Seconds 3
      A @("shell","input text $($utter -replace ' ','%s')") | Out-Null
      Start-Sleep -Milliseconds 500
      A @("shell","input keyevent KEYCODE_ENTER") | Out-Null
      for ($w = 0; $w -lt $WaitSeconds; $w += 3) {
        Start-Sleep -Seconds 3
        $logs = A @("logcat","-d","-t","600")
        $hit = $logs | Select-String "taskResult=" | Select-Object -Last 1
        if ($hit) { $result = (($hit.Line -split "taskResult=")[1] -split " ")[0]; break }
        $ag = $logs | Select-String "WAE_AGENT: result=" | Select-Object -Last 1
        if ($ag) { $result = (($ag.Line -split "result=")[1] -split " ")[0]; break }
      }
    }

    $ms = [int]((Get-Date) - $start).TotalMilliseconds
    if ($result -eq "SUCCESS") { $ok++ }
    "$id,$app,$utter,$result,$ms" | Add-Content $csv -Encoding UTF8
    Write-Host ("[$id] $app | $utter -> $result ($ms ms)")
  }
}
Write-Host ""
Write-Host ("完成：$ok/$total 成功；CSV: $csv") -ForegroundColor Green
exit 0
