param(
    [Parameter(Mandatory)][string]$LibraryDirectory,
    [Parameter(Mandatory)][string]$Destination,
    [string]$LibraryManifest = "$PSScriptRoot/../backend/packaging/alpha-libraries.json"
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path -LiteralPath $Destination) { throw 'Use a fresh notice directory.' }
& "$PSScriptRoot/Verify-FileInventory.ps1" -Root $LibraryDirectory -Manifest $LibraryManifest
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$inventory = @()
foreach ($jar in Get-ChildItem -LiteralPath $LibraryDirectory -Filter '*.jar' -File | Sort-Object Name) {
    $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $notices = @()
        foreach ($entry in $zip.Entries | Sort-Object FullName) {
            if (!$entry.Name -or $entry.Name -notmatch '(?i)(license|notice|copying|copyright)') { continue }
            if ($entry.Length -gt 32MB) { throw "Oversized notice in $($jar.Name)" }
            # Do not interpret entry paths as filesystem paths. Numbered text files
            # retain the bytes; the index maps them back to their original location.
            $relative = $jar.BaseName + '/' + $notices.Count.ToString('D3') + '.txt'
            $target = Join-Path $Destination $relative
            New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target)
            $notices += [ordered]@{ entry=$entry.FullName; file=$relative; sha256=(Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() }
        }
        $inventory += [ordered]@{ jar=$jar.Name; sha256=(Get-FileHash -LiteralPath $jar.FullName).Hash.ToLowerInvariant(); notices=$notices }
    } finally { $zip.Dispose() }
}
if (!$inventory.Count) { throw 'No runtime JARs found.' }
[ordered]@{ description='Original embedded notices; absence of a notice here is not absence of license obligations. See THIRD-PARTY.txt.'; libraries=$inventory } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$Destination/index.json" -Encoding utf8
Write-Output "Exported original notices for $($inventory.Count) runtime JARs."
