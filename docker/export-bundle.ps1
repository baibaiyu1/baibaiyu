param(
    [string]$Output,
    [string]$LightRagDir = (Join-Path $PSScriptRoot '../services/lightrag'),
    [string]$MineruModelsDir = (Join-Path $PSScriptRoot '../models/mineru'),
    [string]$LightRagContainer,
    [string]$ImageMap,
    [switch]$NoArchive,
    [switch]$Gzip
)
$ErrorActionPreference = 'Stop'
$taskArgs = @((Join-Path $PSScriptRoot '../scripts/deployment_bundle.py'),
    '--lightrag-dir', $LightRagDir, '--mineru-models-dir', $MineruModelsDir)
if ($Output) { $taskArgs += @('--output', $Output) }
if ($LightRagContainer) { $taskArgs += @('--lightrag-container', $LightRagContainer) }
if ($ImageMap) { $taskArgs += @('--image-map', $ImageMap) }
if ($NoArchive) { $taskArgs += '--no-archive' }
if ($Gzip) { $taskArgs += '--gzip' }
& python @taskArgs
if ($LASTEXITCODE -ne 0) { throw 'Deployment bundle export failed. See the export directory and source-state.json.' }
