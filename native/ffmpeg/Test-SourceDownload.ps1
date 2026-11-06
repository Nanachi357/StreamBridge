$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('streambridge-source-test-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $root | Out-Null
try {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Get-Source.ps1') -Destination $root
    $fixture = Join-Path $root 'fixture'
    [IO.File]::WriteAllText($fixture, 'Owned source download fixture.')
    $hash = (Get-FileHash -LiteralPath $fixture).Hash
    "FFMPEG_VERSION=fixture`nFFMPEG_SHA256=$hash`nFFMPEG_URL=https://primary.invalid/source`nFFMPEG_MIRROR_URL=https://mirror.invalid/source" |
        Set-Content -LiteralPath (Join-Path $root 'sources.lock')
    $calls = [Collections.Generic.List[string]]::new()
    function Start-Sleep { param($Seconds) if ($Seconds -ne 2) { throw 'Unexpected delay' } }
    function Invoke-FakeCurl {
        $arguments = @($args)
        if ($arguments[0] -ne '--disable' -or
            $arguments[$arguments.IndexOf('--max-time') + 1] -ne '60' -or
            $arguments[$arguments.IndexOf('--connect-timeout') + 1] -ne '20' -or
            $arguments[$arguments.IndexOf('--proto') + 1] -ne '=https' -or
            $arguments[$arguments.IndexOf('--proto-redir') + 1] -ne '=https') { throw 'Unsafe curl options' }
        $target = $arguments[$arguments.IndexOf('--output') + 1]
        if (Test-Path -LiteralPath $target) { throw 'Partial bytes survived a retry' }
        $calls.Add($arguments[-1])
        [IO.File]::WriteAllText($target, 'Truncated transfer')
        if ($mode -eq 'exception') { throw 'Simulated curl invocation failure' }
        $success = $mode -in @('success','bad-hash','race') -or
            ($mode -eq 'retry' -and $calls.Count -eq 2) -or
            ($mode -eq 'fallback' -and $calls.Count -eq 4)
        if ($success) {
            if ($mode -ne 'bad-hash') { Copy-Item -LiteralPath $fixture -Destination $target -Force }
            if ($mode -eq 'race') { [IO.File]::WriteAllText($archive, 'Concurrent writer') }
            $global:LASTEXITCODE = 0
        } else { $global:LASTEXITCODE = 35 }
    }
    function curl { Invoke-FakeCurl @args }
    function curl.exe { Invoke-FakeCurl @args }
    foreach ($mode in @('success','retry','fallback','offline','bad-hash','valid-cache','bad-cache','exception','race')) {
        $directory = Join-Path $root "output [$mode]"
        New-Item -ItemType Directory -Path $directory | Out-Null
        $archive = Join-Path $directory 'ffmpeg-fixture.tar.xz'
        if ($mode -eq 'valid-cache') { Copy-Item -LiteralPath $fixture -Destination $archive }
        if ($mode -eq 'bad-cache') { [IO.File]::WriteAllText($archive, 'Invalid existing source') }
        $calls.Clear()
        $errorMessage = $null
        try { $result = & "$root/Get-Source.ps1" -DestinationDirectory $directory }
        catch { $errorMessage = $_.Exception.Message }
        $expectedCalls = switch ($mode) { 'retry' {2} 'fallback' {4} 'offline' {6} 'valid-cache' {0} 'bad-cache' {0} default {1} }
        if ($calls.Count -ne $expectedCalls) { throw "Unexpected attempt count: $mode ($($calls.Count))" }
        if ($mode -eq 'fallback' -and ($calls[0] -ne 'https://primary.invalid/source' -or $calls[3] -ne 'https://mirror.invalid/source')) { throw 'Wrong fallback order' }
        if ($mode -in @('success','retry','fallback','valid-cache')) {
            if ($errorMessage -or $result -ne $archive -or (Get-FileHash -LiteralPath $archive).Hash -ne $hash) { throw "Expected verified source: $mode ($errorMessage)" }
        } else {
            $expected = switch ($mode) {
                'offline' {'Source download failed from both configured servers; no build attempted.'}
                'exception' {'Simulated curl invocation failure'}
                'race' {'*'}
                default {'Source checksum mismatch.'}
            }
            if (!$errorMessage -or $errorMessage -notlike $expected) { throw "Unexpected failure: $mode ($errorMessage)" }
            if ($mode -eq 'race') {
                if ([IO.File]::ReadAllText($archive) -ne 'Concurrent writer') { throw 'Concurrent destination overwritten' }
            } elseif ($mode -ne 'bad-cache' -and (Test-Path -LiteralPath $archive)) { throw 'Unverified source published' }
        }
        if (@(Get-ChildItem -LiteralPath $directory -Filter '*.partial').Count -ne 0) { throw 'Partial files leaked' }
        Write-Output "PASS: $mode"
    }
} finally {
    $resolved = (Resolve-Path -LiteralPath $root).Path
    if ($resolved -ne [IO.Path]::GetFullPath($root) -or !(Split-Path -Leaf $resolved).StartsWith('streambridge-source-test-')) { throw 'Unexpected cleanup target' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
