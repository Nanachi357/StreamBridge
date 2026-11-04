param([string]$OutputDirectory = (Join-Path $PSScriptRoot 'build/candidate'))
$ErrorActionPreference = 'Stop'
# Resolve against PowerShell's location, which can differ from the process CWD after Set-Location.
$output = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
if (Test-Path -LiteralPath (Join-Path $output 'runtime')) { throw 'Choose a new output directory; existing runtime will not be overwritten.' }
$lock = Get-Content (Join-Path $PSScriptRoot 'sources.lock') -Raw | ConvertFrom-StringData
New-Item -ItemType Directory -Force (Join-Path $output 'sources') | Out-Null
$archive = Join-Path $output "sources/ffmpeg-$($lock.FFMPEG_VERSION).tar.xz"
if (!(Test-Path -LiteralPath $archive)) {
    & curl.exe --fail --location --retry 3 --connect-timeout 20 --max-time 600 --output "$archive.partial" $lock.FFMPEG_URL
    if ($LASTEXITCODE -ne 0) { throw 'Source download failed; no build attempted.' }
    if ((Get-FileHash -LiteralPath "$archive.partial" -Algorithm SHA256).Hash -ne $lock.FFMPEG_SHA256) { throw 'Source checksum mismatch.' }
    Move-Item -LiteralPath "$archive.partial" -Destination $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $lock.FFMPEG_SHA256) { throw 'Source checksum mismatch.' }
$tag = "streambridge-ffmpeg-builder:$($lock.FFMPEG_VERSION)"
& docker build --platform linux/amd64 --tag $tag $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Toolchain build failed.' }
# Source is already verified. Compilation needs no network access.
& docker run --rm --platform linux/amd64 --network none `
    --mount "type=bind,source=$PSScriptRoot,target=/recipe,readonly" `
    --mount "type=bind,source=$output,target=/out" $tag
if ($LASTEXITCODE -ne 0) { throw "FFmpeg build failed; see $output/evidence." }
Write-Host "Built candidate: $output/runtime. Run compatibility tests before packaging."
