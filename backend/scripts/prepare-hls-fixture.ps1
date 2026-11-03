# Development fixture only. No website media is downloaded.
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$FfmpegPath, [switch]$WithAudio)

$ErrorActionPreference = 'Stop'
$ffmpeg = (Get-Command $FfmpegPath -CommandType Application -ErrorAction Stop).Source
$fixtureName = if ($WithAudio) { 'build\hls-av-fixture' } else { 'build\hls-fixture' }
$fixtureDir = Join-Path (Split-Path -Parent $PSScriptRoot) $fixtureName
if (Test-Path -LiteralPath $fixtureDir) {
    throw "Fixture directory already exists: $fixtureDir. Reuse it, or run Gradle clean before regenerating."
}
New-Item -ItemType Directory -Path $fixtureDir | Out-Null

# Pin both the player version and bytes. Keep the upstream license next to it.
$playerFiles = @(
    @{ Remote = 'dist/hls.min.js'; Local = 'hls.min.js'; Sha256 = 'A12E7EE1CD64A69DCDB314157E45DAFCBA705BFB0B1440B7935CB265D374423E' },
    @{ Remote = 'LICENSE'; Local = 'hls.LICENSE'; Sha256 = 'CA8773CF798C7ED997D4DD7C8E23C348699F8D5B7462636694CC14DE6CDA12DB' }
)
foreach ($file in $playerFiles) {
    $destination = Join-Path $fixtureDir $file.Local
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 60 -Uri ('https://cdn.jsdelivr.net/npm/hls.js@1.7.3/' + $file.Remote) -OutFile $destination
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $file.Sha256) {
        throw "Checksum mismatch for $($file.Local). Do not run this fixture."
    }
}

# Eight seconds, four 2-second segments. The AV variant flashes and beeps together
# for 120 ms at 0, 2, 4 and 6 seconds; this gives us measurable synchronization markers.
# -n refuses overwrites. These are generated build outputs, not repository assets.
$sourceArgs = @('-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=25', '-t', '8', '-an')
if ($WithAudio) {
    $sourceArgs = @(
        '-f', 'lavfi', '-i', "color=c=black:size=320x180:rate=25,drawbox=color=white:t=fill:enable='lt(mod(t,2),0.12)'",
        '-f', 'lavfi', '-i', 'aevalsrc=if(lt(mod(t\,2)\,0.12)\,0.2*sin(2*PI*1000*t)\,0):s=48000',
        '-t', '8', '-map', '0:v:0', '-map', '1:a:0', '-c:a', 'aac', '-b:a', '128k'
    )
}
& $ffmpeg -hide_banner -loglevel warning -nostdin -n `
    @sourceArgs `
    -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 50 -keyint_min 50 -sc_threshold 0 `
    -f hls -hls_time 2 -hls_playlist_type vod -hls_flags independent_segments `
    -hls_segment_filename (Join-Path $fixtureDir 'segment-%03d.ts') `
    (Join-Path $fixtureDir 'stream.m3u8')
if ($LASTEXITCODE -ne 0) { throw "FFmpeg failed with exit code $LASTEXITCODE. Fixture is incomplete." }

Write-Host "Fixture ready: $fixtureDir"
Write-Host 'Player: hls.js 1.7.3 (Apache-2.0). FFmpeg is only used to generate this fixture.'
