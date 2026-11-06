param([Parameter(Mandatory)][string]$BundleDirectory)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $BundleDirectory).Path
& (Join-Path $PSScriptRoot 'Verify-Materials.ps1') -BundleDirectory $source
$root = Join-Path ([IO.Path]::GetTempPath()) ('streambridge-material-test-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $root | Out-Null
try {
    $lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'sources.lock') -Raw | ConvertFrom-StringData
    $cases = @('changed-license', 'missing-source', 'extra-runtime', 'traversal', 'duplicate', 'rehashed-gpl', 'rehashed-source',
        'rehashed-config-override', 'rehashed-config-duplicate', 'rehashed-shared-override', 'rehashed-license-override',
        'rehashed-missing-header', 'rehashed-changed-header', 'rehashed-changed-import', 'rehashed-import-evidence',
        'rehashed-truncated-pe', 'rehashed-pe-offset', 'rehashed-delay-import',
        'rehashed-config-bare-cr', 'rehashed-config-control',
        'rehashed-export-forwarder', 'rehashed-export-ordinal-forwarder', 'rehashed-export-end-forwarder',
        'rehashed-export-count', 'rehashed-export-range', 'rehashed-export-eat-range',
        'rehashed-virtual-overlap', 'rehashed-virtual-overflow', 'rehashed-section-alignment',
        'rehashed-headers-overlap', 'rehashed-import-range')
    foreach ($case in $cases) {
        $copy = Join-Path $root $case
        Copy-Item -LiteralPath $source -Destination $copy -Recurse
        switch ($case) {
            'changed-license' { Add-Content -LiteralPath (Join-Path $copy 'licenses/ffmpeg/COPYING.LGPLv2.1') -Value 'tampered' }
            'missing-source' { Remove-Item -LiteralPath (Join-Path $copy "sources/ffmpeg-$($lock.FFMPEG_VERSION).tar.xz") }
            'extra-runtime' { Set-Content -LiteralPath (Join-Path $copy 'runtime/unreviewed.dll') -Value 'fixture' }
            'traversal' { Add-Content -LiteralPath (Join-Path $copy 'SHA256SUMS') -Value (('0' * 64) + '  ../outside') }
            'duplicate' { $line = Get-Content -LiteralPath (Join-Path $copy 'SHA256SUMS') -TotalCount 1; Add-Content -LiteralPath (Join-Path $copy 'SHA256SUMS') -Value $line }
            'rehashed-gpl' {
                $file = Join-Path $copy 'evidence/config.h'
                $text = (Get-Content -LiteralPath $file -Raw).Replace('#define CONFIG_GPL 0', '#define CONFIG_GPL 1')
                [IO.File]::WriteAllText($file, $text)
            }
            'rehashed-source' { Add-Content -LiteralPath (Join-Path $copy "sources/ffmpeg-$($lock.FFMPEG_VERSION).tar.xz") -Value 'tampered' }
            { $_ -in @('rehashed-config-override','rehashed-config-duplicate','rehashed-shared-override','rehashed-license-override') } {
                $file = Join-Path $copy 'evidence/config.h'
                $extra = switch ($case) {
                    'rehashed-config-override' { "#undef CONFIG_NETWORK`n#define CONFIG_NETWORK 1" }
                    'rehashed-config-duplicate' { '#define CONFIG_NETWORK 0' }
                    'rehashed-shared-override' { '#define CONFIG_SHARED 0' }
                    'rehashed-license-override' { '#define FFMPEG_LICENSE "GPL version 3 or later"' }
                }
                $text = (Get-Content -LiteralPath $file -Raw).Replace('#endif /* FFMPEG_CONFIG_H */', "$extra`n#endif /* FFMPEG_CONFIG_H */")
                [IO.File]::WriteAllText($file, $text)
            }
            { $_ -in @('rehashed-config-bare-cr','rehashed-config-control') } {
                $file = Join-Path $copy 'evidence/config.h'
                $extra = if ($case -eq 'rehashed-config-bare-cr') { "#define AUDIT 0`r#undef CONFIG_NETWORK`r#define CONFIG_NETWORK 1" }
                    else { "#define AUDIT 0$([char]11)#undef CONFIG_NETWORK" }
                $text = (Get-Content -LiteralPath $file -Raw).Replace('#endif /* FFMPEG_CONFIG_H */', "$extra`n#endif /* FFMPEG_CONFIG_H */")
                [IO.File]::WriteAllText($file, $text)
            }
            { $_ -like 'rehashed-export-*' -or $_ -in @('rehashed-virtual-overlap','rehashed-virtual-overflow','rehashed-section-alignment','rehashed-headers-overlap','rehashed-import-range') } {
                # Mutate real PE structures with unchanged import evidence. Never load the DLL.
                $file = Join-Path $copy 'runtime/avutil-61.dll'
                $bytes = [IO.File]::ReadAllBytes($file)
                $pe = [BitConverter]::ToUInt32($bytes,0x3c)
                $optional = $pe + 24
                $table = $optional + [BitConverter]::ToUInt16($bytes,$pe + 20)
                function Read-U32([int]$offset) { [BitConverter]::ToUInt32($bytes,$offset) }
                function Write-U32([int]$offset,[uint32]$value) { [Array]::Copy([BitConverter]::GetBytes($value),0,$bytes,$offset,4) }
                function Map-FixtureRva([uint32]$rva) {
                    for ($i=0; $i -lt [BitConverter]::ToUInt16($bytes,$pe + 6); $i++) {
                        $s = $table + 40 * $i
                        $va = Read-U32 ($s + 12); $size = Read-U32 ($s + 16)
                        if ($rva -ge $va -and [uint64]$rva -lt [uint64]$va + $size) { return [int]((Read-U32 ($s + 20)) + $rva - $va) }
                    }
                    throw 'Missing PE mutation control.'
                }
                if ($case -like 'rehashed-export-*') {
                    $exportRva = Read-U32 ($optional + 112); $exportSize = Read-U32 ($optional + 116)
                    $directory = Map-FixtureRva $exportRva
                    $eat = Map-FixtureRva (Read-U32 ($directory + 28))
                    switch ($case) {
                        'rehashed-export-count' { Write-U32 ($directory + 20) ([uint32]::MaxValue) }
                        'rehashed-export-range' { Write-U32 ($optional + 116) ([uint32]::MaxValue) }
                        'rehashed-export-eat-range' { Write-U32 ($directory + 28) ([uint32]::MaxValue) }
                        default {
                            $forwardRva = Read-U32 ($directory + 12)
                            $target = [Text.Encoding]::ASCII.GetBytes("evilxx.x`0")
                            if ($forwardRva -lt $exportRva -or [uint64]$forwardRva + $target.Length -gt [uint64]$exportRva + $exportSize) { throw 'Missing forwarder mutation control.' }
                            [Array]::Copy($target,0,$bytes,(Map-FixtureRva $forwardRva),$target.Length)
                            if ($case -eq 'rehashed-export-end-forwarder') { $forwardRva = $exportRva + $exportSize - 1 }
                            Write-U32 $eat $forwardRva
                            # Remove names: ordinal-only EAT entries must still be checked.
                            if ($case -eq 'rehashed-export-ordinal-forwarder') { Write-U32 ($directory + 24) 0 }
                        }
                    }
                } else {
                    switch ($case) {
                        'rehashed-virtual-overlap' { Write-U32 ($table + 8) ((Read-U32 ($table + 52)) - (Read-U32 ($table + 12)) + 4096) }
                        'rehashed-virtual-overflow' { Write-U32 ($table + 8) ([uint32]::MaxValue) }
                        'rehashed-section-alignment' { Write-U32 ($optional + 32) 0 }
                        'rehashed-headers-overlap' { Write-U32 ($table + 12) 0 }
                        'rehashed-import-range' { Write-U32 ($optional + 124) ([uint32]::MaxValue) }
                    }
                }
                [IO.File]::WriteAllBytes($file,$bytes)
            }
            'rehashed-missing-header' { Remove-Item -LiteralPath (Join-Path $copy 'development/include/libavutil/version.h') }
            'rehashed-changed-header' { Add-Content -LiteralPath (Join-Path $copy 'development/include/libavutil/version.h') -Value 'tampered' }
            'rehashed-changed-import' {
                # Change the real PE import, not its textual evidence. Never execute it.
                $file = Join-Path $copy 'runtime/avutil-61.dll'
                $encoding = [Text.Encoding]::GetEncoding(28591)
                $text = $encoding.GetString([IO.File]::ReadAllBytes($file))
                if (!$text.Contains("bcrypt.dll`0")) { throw 'Missing import mutation control.' }
                [IO.File]::WriteAllBytes($file, $encoding.GetBytes($text.Replace("bcrypt.dll`0", "evilxx.dll`0")))
            }
            'rehashed-import-evidence' {
                $file = Join-Path $copy 'evidence/avutil-61.dll.pe.txt'
                $text = (Get-Content -LiteralPath $file -Raw).Replace('bcrypt.dll','kernel32.dll')
                [IO.File]::WriteAllText($file, $text)
            }
            'rehashed-truncated-pe' { [IO.File]::WriteAllBytes((Join-Path $copy 'runtime/avutil-61.dll'), [byte[]]@(77,90)) }
            { $_ -in @('rehashed-pe-offset','rehashed-delay-import') } {
                $file = Join-Path $copy 'runtime/avutil-61.dll'
                $bytes = [IO.File]::ReadAllBytes($file)
                if ($case -eq 'rehashed-pe-offset') { [Array]::Copy([BitConverter]::GetBytes([uint32]::MaxValue),0,$bytes,0x3c,4) }
                else {
                    $optional = [BitConverter]::ToUInt32($bytes,0x3c) + 24
                    [Array]::Copy([BitConverter]::GetBytes([uint32]1),0,$bytes,($optional + 112 + 13 * 8),4)
                }
                [IO.File]::WriteAllBytes($file,$bytes)
            }
        }
        if ($case.StartsWith('rehashed-')) {
            # A self-consistent checksum list must not bypass the source/config policy.
            $lines = foreach ($file in (Get-ChildItem -LiteralPath $copy -Recurse -File | Where-Object Name -NE 'SHA256SUMS')) {
                (Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant() + '  ' + $file.FullName.Substring($copy.Length + 1).Replace('\', '/')
            }
            [IO.File]::WriteAllLines((Join-Path $copy 'SHA256SUMS'), [string[]]$lines)
        }
        $rejected = $false
        try { & (Join-Path $PSScriptRoot 'Verify-Materials.ps1') -BundleDirectory $copy }
        catch {
            $expected = switch ($case) {
                'changed-license' { 'Material missing or changed:*' }
                'missing-source' { 'Material missing or changed:*' }
                'extra-runtime' { 'Unlisted files*' }
                'traversal' { 'Unsafe or duplicate*' }
                'duplicate' { 'Unsafe or duplicate*' }
                'rehashed-gpl' { 'Forbidden build feature: GPL' }
                'rehashed-source' { 'Unexpected FFmpeg source archive.' }
                { $_ -in @('rehashed-config-override','rehashed-config-duplicate','rehashed-shared-override','rehashed-license-override') } { 'Ambiguous build configuration.' }
                { $_ -in @('rehashed-missing-header','rehashed-changed-header') } { 'Development material missing or changed:*' }
                { $_ -in @('rehashed-config-bare-cr','rehashed-config-control') } { 'Ambiguous build configuration.' }
                { $_ -in @('rehashed-export-forwarder','rehashed-export-ordinal-forwarder','rehashed-export-end-forwarder') } { '*Invalid PE: forwarded exports are unsupported*' }
                'rehashed-export-count' { '*Invalid PE: export address table*' }
                { $_ -in @('rehashed-export-range','rehashed-export-eat-range','rehashed-import-range') } { '*Invalid PE: unmapped RVA*' }
                'rehashed-virtual-overlap' { '*Invalid PE: overlapping virtual sections*' }
                { $_ -in @('rehashed-virtual-overflow','rehashed-headers-overlap') } { '*Invalid PE: section outside image or misaligned*' }
                'rehashed-section-alignment' { '*Invalid PE: image alignment or headers*' }
                'rehashed-changed-import' { 'Unreviewed runtime import: evilxx.dll' }
                'rehashed-import-evidence' { 'Import evidence does not match binary:*' }
                { $_ -in @('rehashed-truncated-pe','rehashed-pe-offset','rehashed-delay-import') } { '*Invalid PE:*' }
            }
            if ($_.Exception.Message -notlike $expected) { throw }
            $rejected = $true
        }
        if (!$rejected) { throw "Invalid material bundle accepted: $case" }
        Write-Output "PASS: rejected $case"
    }
} finally {
    $resolved = (Resolve-Path -LiteralPath $root).Path
    if ($resolved -ne [IO.Path]::GetFullPath($root) -or !(Split-Path -Leaf $resolved).StartsWith('streambridge-material-test-')) { throw 'Unexpected cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
