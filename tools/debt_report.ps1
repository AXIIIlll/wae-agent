# 技術債指標（整理期間每次改動都跑一次）
#
# 為什麼要有這支：沒有數字就分不出「整理」與「感覺整理」。
# 四項指標都只允許下降；任何一項退步就回退這一批改動。
#
# 用法：
#   powershell -File tools\debt_report.ps1                  # 全部量測
#   powershell -File tools\debt_report.ps1 -List emoji      # 列出含 emoji 的行
#   powershell -File tools\debt_report.ps1 -List runCatching
#
# 退出碼：0＝全部未退步；1＝有指標退步。
param(
    [ValidateSet('', 'emoji', 'runCatching', 'todo', 'bigfiles')]
    [string]$List = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$srcDirs = @(
    (Join-Path $root 'app\src\main\java'),
    (Join-Path $root 'core\src\main\kotlin')
)
$files = Get-ChildItem $srcDirs -Recurse -Filter *.kt | ForEach-Object { $_.FullName }

# 基準值＝2026-11-24 整理開始時的量測結果。每完成一批就把基準往下調。
$baseline = @{
    runCatching = 549
    todo        = 0
    emoji       = 32
    maxFile     = 3784
}

function Read-AllLines([string]$path) {
    [System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::UTF8)
}

function Measure-EmojiLines {
    $hits = @()
    foreach ($f in $files) {
        $lines = Read-AllLines $f
        for ($i = 0; $i -lt $lines.Count; $i++) {
            # emoji 都在 BMP 之外（surrogate pair）；CJK 擴充在 BMP 內，不會誤判
            if ($lines[$i] -match '[\uD800-\uDBFF]') {
                $hits += [pscustomobject]@{ File = $f; Line = $i + 1; Text = $lines[$i].Trim() }
            }
        }
    }
    $hits
}

function Measure-RunCatching {
    $hits = @()
    foreach ($f in ($files | Sort-Object)) {
        $lines = Read-AllLines $f
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match 'runCatching') {
                $hits += [pscustomobject]@{ File = $f; Line = $i + 1; Text = $lines[$i].Trim() }
            }
        }
    }
    $hits
}

function Measure-Todo {
    $hits = @()
    foreach ($f in $files) {
        $lines = Read-AllLines $f
        for ($i = 0; $i -lt $lines.Count; $i++) {
            # 用字界比對：-match 'TODO' 會把 .toDouble() 也算進去（實測 46 筆裡 40 筆是誤判）
            if ($lines[$i] -cmatch '\bTODO\b|\bFIXME\b') {
                $hits += [pscustomobject]@{ File = $f; Line = $i + 1; Text = $lines[$i].Trim() }
            }
        }
    }
    $hits
}

function Measure-BigFiles {
    $files | ForEach-Object {
        [pscustomobject]@{ File = $_; Lines = (Read-AllLines $_).Count }
    } | Sort-Object Lines -Descending
}

switch ($List) {
    'emoji' { Measure-EmojiLines | ForEach-Object { '{0}:{1}: {2}' -f $_.File.Replace($root + '\', ''), $_.Line, $_.Text }; exit 0 }
    'runCatching' { Measure-RunCatching | ForEach-Object { '{0}:{1}: {2}' -f $_.File.Replace($root + '\', ''), $_.Line, $_.Text }; exit 0 }
    'todo' { Measure-Todo | ForEach-Object { '{0}:{1}: {2}' -f $_.File.Replace($root + '\', ''), $_.Line, $_.Text }; exit 0 }
    'bigfiles' { Measure-BigFiles | Select-Object -First 20 | ForEach-Object { '{0,6}  {1}' -f $_.Lines, $_.File.Replace($root + '\', '') }; exit 0 }
}

$emoji = (Measure-EmojiLines).Count
$runCatching = (Measure-RunCatching).Count
$todo = (Measure-Todo).Count
$maxFile = (Measure-BigFiles | Select-Object -First 1).Lines

$rows = @(
    [pscustomobject]@{ metric = 'runCatching'; value = $runCatching; baseline = $baseline.runCatching },
    [pscustomobject]@{ metric = 'todo';        value = $todo;        baseline = $baseline.todo },
    [pscustomobject]@{ metric = 'emoji';       value = $emoji;       baseline = $baseline.emoji },
    [pscustomobject]@{ metric = 'maxFile';     value = $maxFile;     baseline = $baseline.maxFile }
)

$failed = $false
foreach ($r in $rows) {
    $status = if ($r.value -le $r.baseline) { 'ok' } else { 'REGRESSED' }
    if ($status -ne 'ok') { $failed = $true }
    '{0,-12} value={1,5}  baseline={2,5}  status={3}' -f $r.metric, $r.value, $r.baseline, $status
}

if ($failed) { exit 1 } else { exit 0 }
