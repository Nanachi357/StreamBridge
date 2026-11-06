param([Parameter(Mandatory)][string]$BundleDirectory)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $BundleDirectory).Path
$entries = @(Get-ChildItem -LiteralPath $root -Recurse -Force)
if ($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Links are not allowed in the material bundle.' }
$manifest = Join-Path $root 'SHA256SUMS'
$listed = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($line in (Get-Content -LiteralPath $manifest)) {
    if ($line -notmatch '^([a-f0-9]{64})  ([A-Za-z0-9_.+/-]+)$') { throw 'Invalid material manifest entry.' }
    $hash = $Matches[1]; $relative = $Matches[2]
    if ($relative.StartsWith('/') -or $relative.Split('/') -contains '..' -or $relative.Split('/') -contains '.' -or !$listed.Add($relative)) { throw 'Unsafe or duplicate material path.' }
    $file = Join-Path $root $relative
    if (!(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $hash) { throw "Material missing or changed: $relative" }
}
$actual = @($entries | Where-Object { !$_.PSIsContainer -and $_.FullName -ne $manifest })
if ($actual.Count -ne $listed.Count) { throw 'Unlisted files in material bundle.' }
foreach ($file in $actual) {
    $relative = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
    if (!$listed.Contains($relative)) { throw "Unlisted material: $relative" }
}
$runtime = @('ffmpeg.exe', 'ffprobe.exe', 'avcodec-63.dll', 'avfilter-12.dll', 'avformat-63.dll', 'avutil-61.dll', 'swresample-7.dll', 'swscale-10.dll')
$runtimeRoot = Join-Path $root 'runtime'
$found = @(Get-ChildItem -LiteralPath $runtimeRoot -Recurse -File | ForEach-Object { $_.FullName.Substring($runtimeRoot.Length + 1).Replace('\', '/') })
if (Compare-Object ($runtime | Sort-Object) ($found | Sort-Object)) { throw 'Unexpected runtime inventory.' }
$required = @('NOTICE.txt', 'licenses/ffmpeg/COPYING.LGPLv2.1', 'licenses/ffmpeg/LICENSE.md',
    'licenses/ffmpeg/NOTICE.txt', 'licenses/ffmpeg/jfdctfst.c', 'licenses/ffmpeg/jfdctint_template.c', 'licenses/ffmpeg/jrevdct.c',
    'licenses/toolchain/mingw-w64-common.copyright', 'licenses/toolchain/gcc-mingw-w64-x86-64-win32-runtime.copyright',
    'licenses/toolchain/common-licenses/GPL-3', 'evidence/config.h', 'evidence/config_components.h',
    'evidence/config.mak', 'evidence/configure-command.txt', 'evidence/debian-packages.txt', 'evidence/compiler.txt', 'evidence/toolchain-downloads.txt', 'evidence/development-smoke.log')
foreach ($name in @('avcodec','avfilter','avformat','avutil','swresample','swscale')) {
    $required += "development/include/lib$name/$name.h", "development/lib/lib$name.dll.a"
}
foreach ($file in $required) { if (!$listed.Contains($file)) { throw "Required material absent: $file" } }
foreach ($file in @('build.sh','Build.ps1','Dockerfile','install-toolchain.sh','sources.lock','README.md','Verify-Materials.ps1','Read-PeImports.ps1','development.sha256','Test-Materials.ps1','Test-OutputDirectory.ps1','BUNDLE-NOTICE.txt')) {
    $relative = "recipe/$file"
    if (!$listed.Contains($relative) -or (Get-FileHash -LiteralPath (Join-Path $root $relative)).Hash -ne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash) { throw "Recipe does not match this checkout: $file" }
}
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'sources.lock') -Raw | ConvertFrom-StringData
$source = "sources/ffmpeg-$($lock.FFMPEG_VERSION).tar.xz"
if (!$listed.Contains($source) -or (Get-FileHash -LiteralPath (Join-Path $root $source)).Hash -ne $lock.FFMPEG_SHA256) { throw 'Unexpected FFmpeg source archive.' }
$development = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($line in (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'development.sha256'))) {
    if ($line.StartsWith('#')) { continue }
    if ($line -cnotmatch '^([a-f0-9]{64})  (development/[A-Za-z0-9_.+/-]+)$') { throw 'Invalid trusted development inventory.' }
    $hash = $Matches[1]; $relative = $Matches[2]
    if (!$development.Add($relative) -or !$listed.Contains($relative) -or
        (Get-FileHash -LiteralPath (Join-Path $root $relative)).Hash -ne $hash) { throw "Development material missing or changed: $relative" }
}
$developmentFiles = @($actual | Where-Object { $_.FullName.StartsWith((Join-Path $root 'development') + [IO.Path]::DirectorySeparatorChar) })
if ($developmentFiles.Count -ne $development.Count) { throw 'Unexpected development inventory.' }
# Accept only configure's flat, guarded #define format. Do not interpret arbitrary C.
$configText = Get-Content -LiteralPath (Join-Path $root 'evidence/config.h') -Raw
# C treats bare CR as a line ending too. Accept only LF/CRLF and tabs, not
# control characters hidden inside a replacement value (including bare CR).
if ($configText -match '\r(?!\n)|[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]') { throw 'Ambiguous build configuration.' }
$config = $configText.TrimEnd([char[]]"`r`n") -split '\r?\n'
if ($config.Count -lt 4 -or $config[0] -cne '/* Automatically generated by configure - do not modify! */' -or
    $config[1] -cne '#ifndef FFMPEG_CONFIG_H' -or $config[-1] -cne '#endif /* FFMPEG_CONFIG_H */') { throw 'Ambiguous build configuration.' }
$defines = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
$protected = @('FFMPEG_CONFIG_H','FFMPEG_LICENSE','CONFIG_GPL','CONFIG_GPLV3','CONFIG_NONFREE','CONFIG_VERSION3','CONFIG_NETWORK','CONFIG_STATIC','CONFIG_SHARED')
foreach ($line in $config[2..($config.Count - 2)]) {
    if ($line -cnotmatch '^#define ([A-Za-z_][A-Za-z0-9_]*)(?:[ \t]+(.*))?$' -or
        $line.Contains('\') -or $line.Contains('/*') -or $line.Contains('//')) { throw 'Ambiguous build configuration.' }
    $name = $Matches[1]; $value = [string]$Matches[2]
    if ($defines.ContainsKey($name)) {
        # Upstream emits identical CONFIG_LIBSMBCLIENT definitions twice. Only
        # identical non-policy duplicates are permitted; policy flags occur once.
        if ($protected -ccontains $name -or $defines[$name] -cne $value) { throw 'Ambiguous build configuration.' }
        continue
    }
    $defines.Add($name, $value)
}
if (!$defines.ContainsKey('FFMPEG_CONFIG_H') -or $defines['FFMPEG_CONFIG_H'] -ne '') { throw 'Ambiguous build configuration.' }
foreach ($flag in @('GPL','GPLV3','NONFREE','VERSION3','NETWORK','STATIC')) {
    if (!$defines.ContainsKey("CONFIG_$flag") -or $defines["CONFIG_$flag"] -cne '0') { throw "Forbidden build feature: $flag" }
}
if ($defines['CONFIG_SHARED'] -cne '1' -or $defines['FFMPEG_LICENSE'] -cne '"LGPL version 2.1 or later"') { throw 'Unexpected linking or license configuration.' }
. (Join-Path $PSScriptRoot 'Read-PeImports.ps1')
$allowedImports = @($runtime | Where-Object { $_.EndsWith('.dll') }) + @('bcrypt.dll','kernel32.dll','msvcrt.dll','shell32.dll')
foreach ($file in $runtime) {
    $report = "evidence/$file.pe.txt"
    if (!$listed.Contains($report)) { throw "Missing import evidence: $file" }
    $recorded = @(Select-String -LiteralPath (Join-Path $root $report) -Pattern 'DLL Name:\s*(\S+)' | ForEach-Object { $_.Matches[0].Groups[1].Value.ToLowerInvariant() })
    $imports = @([StreamBridge.Materials.PeImports]::FromFile((Join-Path $runtimeRoot $file)) | ForEach-Object { $_.ToLowerInvariant() })
    foreach ($import in $imports) { if ($allowedImports -notcontains $import) { throw "Unreviewed runtime import: $import" } }
    if ($recorded.Count -ne $imports.Count -or (Compare-Object ($imports | Sort-Object) ($recorded | Sort-Object))) { throw "Import evidence does not match binary: $file" }
}
Write-Output "PASS: $($listed.Count) material files verified; eight runtime files, matching source/recipe, development files, notices and import inventory."
