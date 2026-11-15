$ErrorActionPreference = 'Stop'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('streambridge-inventory-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$temp/payload" -Force | Out-Null
$manifest = "$temp/inventory.json"
$target = "$temp/payload/owned.txt"
try {
    [IO.File]::WriteAllText($target, 'owned')
    $entry = @{path='owned.txt'; bytes=5; sha256=(Get-FileHash -LiteralPath $target).Hash; included=$true}
    @{files=@($entry)} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifest
    & "$PSScriptRoot/Verify-FileInventory.ps1" -Root "$temp/payload" -Manifest $manifest
    $rejected = 0
    foreach ($scenario in @('modified','missing','extra','traversal','duplicate','excluded')) {
        $entries = @($entry.Clone())
        [IO.File]::WriteAllText($target, 'owned')
        if ($scenario -eq 'modified') { [IO.File]::WriteAllText($target, 'other') }
        if ($scenario -eq 'missing') { Remove-Item -LiteralPath $target }
        if ($scenario -eq 'extra') { [IO.File]::WriteAllText("$temp/payload/extra.txt", 'extra') }
        if ($scenario -eq 'traversal') { $entries[0].path='../owned.txt' }
        if ($scenario -eq 'duplicate') { $entries += $entry.Clone() }
        if ($scenario -eq 'excluded') { $entries[0].included=$false }
        @{files=$entries} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifest
        $failed = $false
        try { & "$PSScriptRoot/Verify-FileInventory.ps1" -Root "$temp/payload" -Manifest $manifest -IncludedOnly } catch { $failed=$true }
        if (!$failed) { throw "Accepted bad inventory: $scenario" }
        $rejected++
        if (Test-Path -LiteralPath "$temp/payload/extra.txt") { Remove-Item -LiteralPath "$temp/payload/extra.txt" }
    }
    Write-Output "PASS: valid inventory and $rejected negative cases."
} finally {
    # Only remove this invocation's literal, randomly named child of the temp directory.
    $resolved = [IO.Path]::GetFullPath($temp)
    $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($parent, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notlike 'streambridge-inventory-*') { throw 'Unsafe cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
