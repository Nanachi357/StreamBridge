param([string]$OutputDirectory = (Join-Path $PSScriptRoot 'build/candidate'))
$ErrorActionPreference = 'Stop'
# Resolve against PowerShell's location, which can differ from the process CWD after Set-Location.
$output = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
if (Test-Path -LiteralPath (Join-Path $output 'runtime')) { throw 'Choose a new output directory; existing runtime will not be overwritten.' }
$lock = Get-Content (Join-Path $PSScriptRoot 'sources.lock') -Raw | ConvertFrom-StringData
$archive = & (Join-Path $PSScriptRoot 'Get-Source.ps1') -DestinationDirectory (Join-Path $output 'sources')
$tag = "streambridge-ffmpeg-builder:$($lock.FFMPEG_VERSION)"
& docker build --platform linux/amd64 --tag $tag $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Toolchain build failed.' }
# Source is already verified. Compilation needs no network access.
& docker run --rm --platform linux/amd64 --network none `
    --mount "type=bind,source=$PSScriptRoot,target=/recipe,readonly" `
    --mount "type=bind,source=$output,target=/out" $tag
if ($LASTEXITCODE -ne 0) { throw "FFmpeg build failed; see $output/evidence." }
Write-Host "Built candidate: $output/runtime. Run compatibility tests before packaging."
