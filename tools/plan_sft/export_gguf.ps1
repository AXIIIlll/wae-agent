# 把 planner LoRA 變成可以塞進 APK 的 GGUF（merge → f16 → Q4_K_M）
# 用法：
#   powershell -File tools/plan_sft/export_gguf.ps1                       # 預設 planner-lora
#   powershell -File tools/plan_sft/export_gguf.ps1 -Adapter E:\WAE\models\planner-mix-lora -Tag mix
param(
  [string]$Adapter = 'E:\WAE\models\planner-lora',
  [string]$Tag = 'planner'
)
$ErrorActionPreference = 'Stop'
$py = 'E:\WAE\venvs\waetrain\Scripts\python.exe'
$q  = 'E:\WAE\temp\llama-build\bin\llama-quantize.exe'
$repo = 'E:\NewAndroidApp'
$merged = "E:\WAE\models\qwen3-0.6b-wae-$Tag"
$f16 = "$merged.f16.gguf"
$q4  = "E:\WAE\models\qwen3-0.6b-wae-$Tag.Q4_K_M.gguf"

Write-Host "[1/3] merge LoRA ($Adapter) -> $merged"
& $py "$repo\tools\plan_sft\merge_lora.py" --adapter $Adapter --out $merged
if ($LASTEXITCODE -ne 0) { throw 'merge failed' }

Write-Host "[2/3] convert -> f16"
& $py 'E:\WAE\temp\llama.cpp\convert_hf_to_gguf.py' --outtype f16 --outfile $f16 $merged
if ($LASTEXITCODE -ne 0) { throw 'convert failed' }

Write-Host "[3/3] quantize -> Q4_K_M"
& $q $f16 $q4 Q4_K_M
if ($LASTEXITCODE -ne 0) { throw 'quantize failed' }

Get-ChildItem $f16, $q4 | Select-Object Name, @{n='MB';e={[math]::Round($_.Length/1MB,1)}}
