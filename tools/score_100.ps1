# 100 題計分（自動判定 + 人工覆核）
#
# 為什麼要分開：跑題器只看內部訊號，會有假失敗（例如亮度已改、畫面沒變 → 判 FAIL）。
# 這個腳本把「自動結果」和「人工覆核」合併，並且：
#   - SKIP 的題目不列入分母（App 停用等），但要另外列出
#   - 覆核過的題目一定標記來源，方便回頭檢查是不是自己在自欺
#
# 用法：powershell -File tools\score_100.ps1 -Results E:\WAE\bench100_run4.tsv
param(
  [string]$Results = "E:\WAE\bench100_run4.tsv",
  [string]$Overrides = "",
  [string]$ExtraResults = ""   # 可再併入另一份（例如前 15 題在同一輪之前的檔案）
)

function Read-Tsv($path) {
  $rows = @()
  if (-not (Test-Path $path)) { return $rows }
  foreach ($raw in [System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::UTF8)) {
    $line = $raw.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { continue }
    $c = $line.Split("`t")
    if ($c[0] -notmatch '^\d+$') { continue }   # 跳過表頭（id / tier / command …）
    $rows += , $c
  }
  return $rows
}

$root = Split-Path -Parent $PSScriptRoot
if (-not $Overrides) { $Overrides = Join-Path $root "benchmark\results\manual_verdicts.tsv" }

# 1) 讀自動結果（id -> final）
$auto = @{}
foreach ($src in @($Results, $ExtraResults)) {
  if (-not $src) { continue }
  foreach ($c in (Read-Tsv $src)) {
    if ($c.Count -lt 7) { continue }
    $auto[$c[0]] = [pscustomobject]@{ id = $c[0]; tier = $c[1]; cmd = $c[2]; final = $c[6]; source = "auto" }
  }
}

# 2) 套用人工覆核
$manual = @{}
foreach ($c in (Read-Tsv $Overrides)) {
  if ($c.Count -lt 2) { continue }
  $manual[$c[0]] = $c[1].Trim().ToUpper()
  if ($auto.ContainsKey($c[0])) {
    $auto[$c[0]].final = $manual[$c[0]]
    $auto[$c[0]].source = "manual"
  }
}

# 3) 統計
$all = $auto.Values | Sort-Object { [int]$_.id }
$skip = $all | Where-Object { $_.final -eq 'SKIP' }
$scored = $all | Where-Object { $_.final -ne 'SKIP' }
$pass = $scored | Where-Object { $_.final -eq 'SUCCESS' }
$manualPass = $pass | Where-Object { $_.source -eq 'manual' }

"=== 總結 ==="
"已跑題數      : $($all.Count)"
"列入計分      : $($scored.Count)（排除 SKIP $($skip.Count) 題）"
"通過          : $($pass.Count)，其中人工覆核 $($manualPass.Count) 題"
if ($scored.Count -gt 0) {
  "通過率        : {0:P1}" -f ($pass.Count / [double]$scored.Count)
  "自動判定通過率: {0:P1}" -f (($pass.Count - $manualPass.Count) / [double]$scored.Count)
}

foreach ($t in @('EASY', 'MEDIUM', 'HARD', 'EXTREME')) {
  $g = $scored | Where-Object { $_.tier -eq $t }
  if ($g.Count -eq 0) { continue }
  $p = ($g | Where-Object { $_.final -eq 'SUCCESS' }).Count
  "{0,-8} {1,2}/{2,-2} = {3:P0}" -f $t, $p, $g.Count, ($p / [double]$g.Count)
}

"=== 未通過（含覆核前後）==="
$scored | Where-Object { $_.final -ne 'SUCCESS' } | ForEach-Object {
  "{0} {1,-7} {2,-26} {3}" -f $_.id, $_.tier, $_.cmd, $_.final
}
if ($skip.Count -gt 0) {
  "=== SKIP（不計分）==="
  $skip | ForEach-Object { "{0} {1,-7} {2}" -f $_.id, $_.tier, $_.cmd }
}
