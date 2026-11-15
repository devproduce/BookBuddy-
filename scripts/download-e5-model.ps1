# Downloads multilingual-e5-small ONNX + tokenizer into backend/models/
# Source: Xenova/multilingual-e5-small (Hugging Face)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "backend\models"
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$base = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main"
$files = @(
    @{ Url = "$base/onnx/model.onnx"; Out = (Join-Path $dest "model.onnx") },
    @{ Url = "$base/tokenizer.json"; Out = (Join-Path $dest "tokenizer.json") },
    @{ Url = "$base/tokenizer_config.json"; Out = (Join-Path $dest "tokenizer_config.json") }
)

foreach ($f in $files) {
    if (Test-Path $f.Out) {
        Write-Host "Already exists: $($f.Out)"
        continue
    }
    Write-Host "Downloading $($f.Url) ..."
    curl.exe -L --fail --retry 3 -o $f.Out $f.Url
    Write-Host "Saved $($f.Out) ($([math]::Round((Get-Item $f.Out).Length / 1MB, 1)) MB)"
}

Write-Host ""
Write-Host "Done. Set EMBEDDING_ENABLED=true and restart the backend."
Write-Host "Then re-upload / re-process books so chunks get E5 embeddings."
