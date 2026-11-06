param([Parameter(Mandatory)][string]$DestinationDirectory)
$ErrorActionPreference = 'Stop'
# Native nonzero exits are handled below, including in GitHub's pwsh wrapper.
$PSNativeCommandUseErrorActionPreference = $false
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'sources.lock') -Raw | ConvertFrom-StringData
$directory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($DestinationDirectory)
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$archive = Join-Path $directory "ffmpeg-$($lock.FFMPEG_VERSION).tar.xz"
if (Test-Path -LiteralPath $archive) {
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $lock.FFMPEG_SHA256) { throw 'Source checksum mismatch.' }
    return $archive
}
$curl = if ($env:OS -eq 'Windows_NT') { 'curl.exe' } else { 'curl' }
$partial = "$archive.$([guid]::NewGuid().ToString('N')).partial"
try {
    foreach ($url in @($lock.FFMPEG_URL, $lock.FFMPEG_MIRROR_URL)) {
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            Write-Host "Downloading FFmpeg source: $url (attempt $attempt/3)"
            # Disable personal curl config; redirects must also retain HTTPS.
            & $curl --disable --fail --silent --show-error --location --proto '=https' --proto-redir '=https' `
                --connect-timeout 20 --max-time 60 --output $partial $url
            $code = $LASTEXITCODE
            if ($code -eq 0) {
                if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $lock.FFMPEG_SHA256) { throw 'Source checksum mismatch.' }
                # Same-directory rename publishes only complete, verified bytes; never overwrite.
                [IO.File]::Move($partial, $archive)
                return $archive
            }
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
            Write-Warning "Source transfer failed (curl $code)."
            if ($attempt -lt 3) { Start-Sleep -Seconds 2 }
        }
    }
    throw 'Source download failed from both configured servers; no build attempted.'
} finally {
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
}
