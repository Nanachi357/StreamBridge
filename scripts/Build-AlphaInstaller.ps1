param(
    [Parameter(Mandatory)][string]$JdkDirectory,
    [Parameter(Mandatory)][string]$WixDirectory,
    [Parameter(Mandatory)][string]$FfmpegBundleDirectory,
    [switch]$AllowUncommitted
)
$ErrorActionPreference = 'Stop'
# Local candidate only. This script does not install, sign, tag or upload anything.
& "$PSScriptRoot/Build-PrivateInstaller.ps1" @PSBoundParameters -Profile AlphaCandidate
