param(
    [Parameter(Mandatory)][string]$JdkDirectory,
    [Parameter(Mandatory)][string]$WixDirectory,
    [Parameter(Mandatory)][string]$FfmpegBundleDirectory,
    [ValidateSet('Private','AlphaCandidate')][string]$Profile = 'Private',
    [switch]$AllowUncommitted
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$settings = & "$PSScriptRoot/Get-InstallerProfile.ps1" -Profile $Profile
$sourceCommit = & git -C $repo rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify source commit.' }
$sourceStatus = & git -C $repo status --porcelain --untracked-files=all
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify source changes.' }
$dirty = [bool]$sourceStatus
if ($Profile -eq 'AlphaCandidate' -and $dirty -and !$AllowUncommitted) {
    throw 'Alpha candidate requires a clean checkout. Use -AllowUncommitted only for local preparation; rebuild from the final commit before release.'
}
foreach ($path in @($JdkDirectory, $WixDirectory, $FfmpegBundleDirectory)) {
    if (![IO.Path]::IsPathRooted($path) -or !(Test-Path -LiteralPath $path -PathType Container)) { throw 'Supply absolute existing input directories.' }
}
$jdk = (Resolve-Path -LiteralPath $JdkDirectory).Path
$wix = (Resolve-Path -LiteralPath $WixDirectory).Path
$media = (Resolve-Path -LiteralPath $FfmpegBundleDirectory).Path
foreach ($file in @("$jdk/bin/jpackage.exe", "$wix/candle.exe", "$wix/light.exe", "$wix/dark.exe", "$wix/LICENSE.TXT")) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing build tool: $file" }
}
# Validate native inputs before executing or copying them. This is identity checking,
# not public redistribution approval or a general-purpose signature trust service.
& "$repo/native/ffmpeg/Verify-Materials.ps1" -BundleDirectory $media
$destination = Join-Path $repo ('backend/build/' + $settings.output + '/' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $destination | Out-Null
$oldJava = $env:JAVA_HOME; $oldPath = $env:PATH
try {
    $env:JAVA_HOME = $jdk
    $env:PATH = "$jdk/bin;$wix;$oldPath"
    $releaseArguments = @()
    if ($Profile -eq 'AlphaCandidate') { $releaseArguments = @('-PreleaseVersion=0.1.0-alpha.1') }
    & "$repo/backend/gradlew.bat" -p "$repo/backend" packageWindowsApp --offline --no-daemon "-PbundledMediaDirectory=$media/runtime" @releaseArguments
    if ($LASTEXITCODE -ne 0) { throw 'Application image build failed.' }
    $image = (Get-Content -LiteralPath "$repo/backend/build/windows-app/latest-path.txt" -Raw).Trim()
    $mediaFiles = @(Get-ChildItem -LiteralPath "$media/runtime" -File | ForEach-Object {
        [ordered]@{path=$_.Name; bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
    })
    @{files=$mediaFiles} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$destination/media-manifest.json" -Encoding utf8
    & "$PSScriptRoot/Verify-FileInventory.ps1" -Root "$image/app/native/ffmpeg" -Manifest "$destination/media-manifest.json"
    if (Test-Path -LiteralPath "$image/app/native/chromium") { throw 'Browser payload is not supported.' }
    # Retain runtime notices without bundling compiler/source archives into the app.
    New-Item -ItemType Directory -Path "$image/app/licenses/ffmpeg" -Force | Out-Null
    Copy-Item -LiteralPath "$media/NOTICE.txt" -Destination "$image/app/licenses/ffmpeg/NOTICE.txt"
    Copy-Item -LiteralPath "$media/licenses" -Destination "$image/app/licenses/ffmpeg/materials" -Recurse
    Copy-Item -LiteralPath "$repo/backend/packaging/$($settings.readme)" -Destination "$image/app/$($settings.readme)"
    Copy-Item -LiteralPath "$repo/backend/packaging/$($settings.readme)" -Destination "$image/app/README.txt"
    if ($Profile -eq 'AlphaCandidate') {
        Copy-Item -LiteralPath "$repo/backend/packaging/THIRD-PARTY.txt" -Destination "$image/app/THIRD-PARTY.txt"
        & "$PSScriptRoot/Export-JarNotices.ps1" -LibraryDirectory "$image/app/lib" -Destination "$image/app/licenses/jars"
    }
    New-Item -ItemType Directory -Path "$image/app/licenses/wix" -Force | Out-Null
    Copy-Item -LiteralPath "$wix/LICENSE.TXT" -Destination "$image/app/licenses/wix/LICENSE.TXT"
    $entries = @(Get-ChildItem -LiteralPath $image -Recurse -Force)
    if ($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Linked app image entry.' }
    $files = @($entries | Where-Object { !$_.PSIsContainer } | Sort-Object FullName | ForEach-Object {
        [ordered]@{ path=$_.FullName.Substring($image.Length+1).Replace('\','/'); bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    foreach ($file in $files) {
        if ($file.path -match '(?i)(^|/)(tools\.properties|d3dcompiler_47\.dll|Cookies|Login Data|History|\.env)$|\.(mp4|mkv|webm|part|log|har|pml|zip|7z|tar|gz)$') { throw "Unexpected private or excluded file: $($file.path)" }
    }
    [ordered]@{ schema_version=1; purpose=$settings.purpose; files=$files } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$destination/image-manifest.json" -Encoding utf8
    & "$PSScriptRoot/Verify-FileInventory.ps1" -Root $image -Manifest "$destination/image-manifest.json"
    # Per-user installation; stable public identity is separate from private tests.
    & "$jdk/bin/jpackage.exe" --type exe --name StreamBridge --app-version $settings.version --app-image $image --dest $destination --vendor 'Vladyslav Ponomarenko' --description $settings.description --win-per-user-install --install-dir $settings.directory --win-menu --win-menu-group $settings.menu --win-shortcut --win-upgrade-uuid $settings.upgradeCode --temp "$destination/jpackage-work"
    if ($LASTEXITCODE -ne 0) { throw 'EXE installer build failed.' }
    $installer = Join-Path $destination 'StreamBridge-0.1.0.exe'
    if (!(Test-Path -LiteralPath $installer -PathType Leaf)) { throw 'Installer not produced.' }
    # Recheck after packaging, retaining the exact same inventory for installed-file comparison.
    & "$PSScriptRoot/Verify-FileInventory.ps1" -Root $image -Manifest "$destination/image-manifest.json"
    & "$PSScriptRoot/Verify-InstallerPayload.ps1" -Installer $installer -ImageManifest "$destination/image-manifest.json" -WixDirectory $wix -WorkDirectory "$destination/payload-check" -InstallDirectory $settings.directory -ExpectedUpgradeCode $settings.upgradeCode
    Copy-Item -LiteralPath "$destination/payload-check/payload-manifest.json" -Destination "$destination/payload-manifest.json"
    if ($settings.asset -ne (Split-Path -Leaf $installer)) {
        Rename-Item -LiteralPath $installer -NewName $settings.asset
        $installer = Join-Path $destination $settings.asset
    }
    [ordered]@{
        browser_requirement='Installed Microsoft Edge Stable; browser not included'
        purpose=$settings.purpose; release=$settings.label; installer=(Split-Path -Leaf $installer)
        sha256=(Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
        payload_manifest_sha256=(Get-FileHash -LiteralPath "$destination/payload-manifest.json").Hash.ToLowerInvariant()
        source_commit=$sourceCommit; uncommitted_changes=$dirty
        install_directory=$settings.directory; upgrade_code=$settings.upgradeCode
        ffmpeg_manifest_sha256=(Get-FileHash -LiteralPath "$media/SHA256SUMS").Hash.ToLowerInvariant()
        runtime_recipe_sha256=(Get-FileHash -LiteralPath "$repo/backend/build.gradle.kts").Hash.ToLowerInvariant()
        image=$image
    } | ConvertTo-Json | Set-Content -LiteralPath "$destination/build-record.json" -Encoding utf8
    Set-Content -LiteralPath "$repo/backend/build/$($settings.output)/latest-path.txt" -Value $destination -Encoding utf8
    Write-Output "Candidate installer (not published): $installer"
} finally { $env:JAVA_HOME=$oldJava; $env:PATH=$oldPath }
