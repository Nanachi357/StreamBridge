param(
    [Parameter(Mandatory)][string]$CandidateDirectory,
    [Parameter(Mandatory)][string]$FfmpegBundleDirectory,
    [Parameter(Mandatory)][string]$JavaMaterialsDirectory,
    [Parameter(Mandatory)][string]$WixSourceArchive,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$repo = Split-Path -Parent $PSScriptRoot
$candidate = (Resolve-Path -LiteralPath $CandidateDirectory).Path
$media = (Resolve-Path -LiteralPath $FfmpegBundleDirectory).Path
$java = (Resolve-Path -LiteralPath $JavaMaterialsDirectory).Path
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a fresh material output directory.' }
$fullOutput = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
foreach ($inputPath in @($candidate,$media,$java)) {
    $prefix = $inputPath.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if ($fullOutput.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be outside input directories.' }
}
$record = Get-Content -LiteralPath "$candidate/build-record.json" -Raw | ConvertFrom-Json
$profile = & "$PSScriptRoot/Get-InstallerProfile.ps1" -Profile AlphaCandidate
if ($record.release -ne $profile.label -or $record.installer -ne $profile.asset -or
    $record.upgrade_code -ne $profile.upgradeCode -or $record.install_directory -ne $profile.directory) { throw 'Not an alpha candidate.' }
if ((Get-FileHash -LiteralPath "$candidate/$($profile.asset)").Hash -ne $record.sha256 -or
    (Get-FileHash -LiteralPath "$candidate/payload-manifest.json").Hash -ne $record.payload_manifest_sha256) { throw 'Candidate changed after verification.' }
if ((Get-FileHash -LiteralPath "$repo/backend/build.gradle.kts").Hash -ne $record.runtime_recipe_sha256) { throw 'Runtime recipe changed since candidate build.' }
& "$repo/native/ffmpeg/Verify-Materials.ps1" -BundleDirectory $media
if ((Get-FileHash -LiteralPath "$media/SHA256SUMS").Hash -ne $record.ffmpeg_manifest_sha256) { throw 'FFmpeg materials differ from installer inputs.' }
& "$PSScriptRoot/Verify-AlphaJavaInputs.ps1" -Directory $java
if ((Get-FileHash -LiteralPath $WixSourceArchive).Hash -ne '374ec1923954c0df5874964d845c09d887012fbab5aa13d000201d0b9f789d96') { throw 'WiX source mismatch.' }
foreach ($directory in @($media,$java)) {
    if ((Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked material directory.' }
    if (Get-ChildItem -LiteralPath $directory -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Linked material input.' }
}
# One fresh output; only release-files is intended for eventual upload. No private
# build record (absolute paths), logs or arbitrary input files enter that folder.
New-Item -ItemType Directory -Path "$OutputDirectory/release-files","$OutputDirectory/java" -Force | Out-Null
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path
$assets = "$output/release-files"
Copy-Item -LiteralPath "$candidate/$($profile.asset)","$candidate/payload-manifest.json" -Destination $assets
Copy-Item -LiteralPath $WixSourceArchive -Destination "$assets/wix3141-source.tar.gz"
$javaNames = @('OpenJDK21U-jdk-sources_21.0.13_9.tar.gz','temurin-build-1cf43d7.tar.gz','temurin-build.json')
foreach ($name in $javaNames) { Copy-Item -LiteralPath "$java/$name" -Destination "$output/java/$name" }
Copy-Item -LiteralPath "$repo/backend/packaging/JAVA-MATERIALS.txt" -Destination "$output/java/README.txt"
Copy-Item -LiteralPath "$repo/backend/build.gradle.kts" -Destination "$output/java/streambridge-build.gradle.kts"
[IO.Compression.ZipFile]::CreateFromDirectory("$output/java", "$assets/StreamBridge-0.1.0-alpha.1-java-materials.zip", [IO.Compression.CompressionLevel]::Optimal, $false)
[IO.Compression.ZipFile]::CreateFromDirectory($media, "$assets/StreamBridge-0.1.0-alpha.1-ffmpeg-materials.zip", [IO.Compression.CompressionLevel]::Optimal, $false)
# Explicitly map the installer bytes to the runtime bytes/materials, not just a
# version string. Embedded payload verification occurred during candidate build.
$payload = Get-Content -LiteralPath "$candidate/payload-manifest.json" -Raw | ConvertFrom-Json
$mediaEntries = @($payload.files | Where-Object { $_.path -like 'app/native/ffmpeg/*' })
if ($mediaEntries.Count -ne 8) { throw 'Unexpected installed media inventory.' }
foreach ($entry in $mediaEntries) {
    if ((Get-FileHash -LiteralPath (Join-Path "$media/runtime" (Split-Path -Leaf $entry.path))).Hash -ne $entry.sha256) { throw 'Installed media/material mismatch.' }
}
$files = @(Get-ChildItem -LiteralPath $assets -File | Sort-Object Name | ForEach-Object {
    [ordered]@{file=$_.Name; bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
})
[ordered]@{
    release=$profile.label; source_commit=$record.source_commit; uncommitted_changes=$record.uncommitted_changes
    windows_version=$profile.version; upgrade_code=$profile.upgradeCode
    status='CANDIDATE - requires exact-artifact VM acceptance and explicit publication decision'
    browser='Installed Microsoft Edge Stable (not bundled)'; files=$files
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$assets/release-manifest.json" -Encoding utf8
Get-ChildItem -LiteralPath $assets -File | Sort-Object Name | ForEach-Object {
    '{0}  {1}' -f (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant(), $_.Name
} | Set-Content -LiteralPath "$output/SHA256SUMS.txt" -Encoding ascii
Move-Item -LiteralPath "$output/SHA256SUMS.txt" -Destination "$assets/SHA256SUMS.txt"
Write-Output "Prepared local candidate materials: $assets. Nothing published."
