$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$temp = Join-Path ([IO.Path]::GetTempPath()) ('streambridge-alpha-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$temp/lib" -Force | Out-Null
function Reject([scriptblock]$Action, [string]$Message) {
    $failed=$false
    try { & $Action } catch { if ($_.Exception.Message -notlike "*$Message*") { throw }; $failed=$true }
    if (!$failed) { throw "Expected rejection: $Message" }
}
try {
    $private = & "$PSScriptRoot/Get-InstallerProfile.ps1"
    $alpha = & "$PSScriptRoot/Get-InstallerProfile.ps1" -Profile AlphaCandidate
    if ($private.upgradeCode -ne 'e1ec4f1f-4d19-4e5a-9f5a-095ab053ba09' -or $private.directory -ne 'StreamBridge Private Alpha') { throw 'Private upgrade compatibility changed.' }
    if ($alpha.upgradeCode -eq $private.upgradeCode -or $alpha.directory -eq $private.directory -or $alpha.asset -notlike '*alpha.1*') { throw 'Public/private collision.' }
    if ($alpha.directory -eq 'StreamBridge' -or $alpha.menu -ne 'StreamBridge') { throw 'Installation must be separate from user data, with a stable menu name.' }
    # A synthetic JAR with hostile archive paths must preserve notice bytes without
    # allowing its entry paths to become disk paths or extracting executable code.
    $zip = [IO.Compression.ZipFile]::Open("$temp/lib/example.jar",[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in @('../../LICENSE','native/NOTICE','native/evil.dll')) {
            $entry=$zip.CreateEntry($name)
            $writer=[IO.StreamWriter]::new($entry.Open())
            try { $writer.Write('original notice bytes') } finally { $writer.Dispose() }
        }
    } finally { $zip.Dispose() }
    @{files=@(@{path='example.jar'; bytes=(Get-Item -LiteralPath "$temp/lib/example.jar").Length; sha256=(Get-FileHash -LiteralPath "$temp/lib/example.jar").Hash})} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$temp/libraries.json"
    & "$PSScriptRoot/Export-JarNotices.ps1" -LibraryDirectory "$temp/lib" -Destination "$temp/notices" -LibraryManifest "$temp/libraries.json"
    $index=Get-Content -LiteralPath "$temp/notices/index.json" -Raw | ConvertFrom-Json
    if ($index.libraries.Count -ne 1 -or $index.libraries[0].notices.Count -ne 2) { throw 'Notice selection mismatch.' }
    foreach ($notice in $index.libraries[0].notices) {
        if ([IO.File]::ReadAllText((Join-Path "$temp/notices" $notice.file)) -ne 'original notice bytes') { throw 'Notice bytes altered.' }
    }
    if (Test-Path -LiteralPath "$temp/LICENSE") { throw 'Notice path escaped.' }
    Reject { & "$PSScriptRoot/Export-JarNotices.ps1" -LibraryDirectory "$temp/lib" -Destination "$temp/notices" -LibraryManifest "$temp/libraries.json" } 'fresh notice'
    [IO.File]::WriteAllText("$temp/lib/example.jar",'changed artifact')
    Reject { & "$PSScriptRoot/Export-JarNotices.ps1" -LibraryDirectory "$temp/lib" -Destination "$temp/changed-notices" -LibraryManifest "$temp/libraries.json" } 'File differs'
    Write-Output 'PASS: installer identities, notice bytes, archive-path isolation and overwrite protection.'
} finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    $parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notlike 'streambridge-alpha-test-*') { throw 'Unsafe test cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
