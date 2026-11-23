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
    # A malformed material/candidate must fail before creating a publishable folder.
    [IO.File]::WriteAllText("$temp/OpenJDK21U-jdk-sources_21.0.13_9.tar.gz",'corrupt')
    Reject { & "$PSScriptRoot/Verify-AlphaJavaInputs.ps1" -Directory $temp } 'Java material mismatch'
    [IO.File]::WriteAllText("$temp/build-record.json",'{"release":"private"}')
    Reject { & "$PSScriptRoot/Prepare-AlphaMaterials.ps1" -CandidateDirectory $temp -FfmpegBundleDirectory "$temp/lib" -JavaMaterialsDirectory "$temp/lib" -WixSourceArchive "$temp/missing.tar.gz" -OutputDirectory "$temp/out" } 'outside input'
    Reject { & "$PSScriptRoot/Prepare-AlphaMaterials.ps1" -CandidateDirectory $temp -FfmpegBundleDirectory "$temp/lib" -JavaMaterialsDirectory "$temp/lib" -WixSourceArchive "$temp/missing.tar.gz" -OutputDirectory ($temp+'-output') } 'Not an alpha candidate'
    $profile=& "$PSScriptRoot/Get-InstallerProfile.ps1" -Profile AlphaCandidate
    [IO.File]::WriteAllText("$temp/$($profile.asset)",'original bytes')
    [IO.File]::WriteAllText("$temp/payload-manifest.json",'{}')
    $record=@{release=$profile.label;installer=$profile.asset;upgrade_code=$profile.upgradeCode;install_directory=$profile.directory;sha256=(Get-FileHash -LiteralPath "$temp/$($profile.asset)").Hash;payload_manifest_sha256=(Get-FileHash -LiteralPath "$temp/payload-manifest.json").Hash;runtime_recipe_sha256=('0'*64)}
    $record | ConvertTo-Json | Set-Content -LiteralPath "$temp/build-record.json"
    [IO.File]::WriteAllText("$temp/$($profile.asset)",'replaced bytes')
    Reject { & "$PSScriptRoot/Prepare-AlphaMaterials.ps1" -CandidateDirectory $temp -FfmpegBundleDirectory "$temp/lib" -JavaMaterialsDirectory "$temp/lib" -WixSourceArchive "$temp/missing.tar.gz" -OutputDirectory ($temp+'-output') } 'Candidate changed'
    [IO.File]::WriteAllText("$temp/$($profile.asset)",'original bytes')
    Reject { & "$PSScriptRoot/Prepare-AlphaMaterials.ps1" -CandidateDirectory $temp -FfmpegBundleDirectory "$temp/lib" -JavaMaterialsDirectory "$temp/lib" -WixSourceArchive "$temp/missing.tar.gz" -OutputDirectory ($temp+'-output') } 'Runtime recipe changed'
    if (Test-Path -LiteralPath ($temp+'-output')) { throw 'Bad candidate created output.' }
    Write-Output 'PASS: corrupt Java, replaced installer, changed recipe and non-alpha/nested-output rejection.'
} finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    $parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notlike 'streambridge-alpha-test-*') { throw 'Unsafe test cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
