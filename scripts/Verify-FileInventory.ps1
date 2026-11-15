param(
    [Parameter(Mandatory)][string]$Root,
    [Parameter(Mandatory)][string]$Manifest,
    [switch]$IncludedOnly
)
$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path.TrimEnd('\', '/')
$document = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
$expected = @($document.files | Where-Object { !$IncludedOnly -or $_.included })
if ($expected.Count -eq 0) { throw 'Empty file inventory.' }
$items = @(Get-Item -LiteralPath $rootPath) + @(Get-ChildItem -LiteralPath $rootPath -Recurse -Force)
if ($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Linked payload entries are not allowed.' }
$actual = @($items | Where-Object { !$_.PSIsContainer })
if ($actual.Count -ne $expected.Count) { throw 'Payload file count differs from inventory.' }
$names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in $expected) {
    $name = [string]$entry.path
    if ($name -notmatch '^[A-Za-z0-9_.+ /-]+$' -or $name.StartsWith('/') -or
        @($name.Split('/') | Where-Object { $_ -in @('', '.', '..') -or $_.EndsWith('.') -or $_.EndsWith(' ') }).Count -gt 0 -or
        !$names.Add($name) -or [string]$entry.sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Unsafe or duplicate inventory entry.' }
    $file = Join-Path $rootPath $name
    if (!(Test-Path -LiteralPath $file -PathType Leaf) -or
        (Get-Item -LiteralPath $file).Length -ne [long]$entry.bytes -or
        (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $entry.sha256) { throw "File differs from inventory: $name" }
}
Write-Output "PASS: $($expected.Count) payload files match inventory."
