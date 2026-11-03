# Development launcher; deliberately does not bypass PowerShell execution policy.
$ErrorActionPreference = 'Stop'
try {
    $projectDirectory = Split-Path -Parent $PSScriptRoot
    $configPath = Join-Path $PSScriptRoot 'launcher.local.json'
    if (Test-Path -LiteralPath $configPath) {
        $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        if ($config.javaHome) { $env:JAVA_HOME = $config.javaHome }
        if ($config.chromiumDirectory) { $env:PLAYWRIGHT_BROWSERS_PATH = $config.chromiumDirectory }
        if ($config.mediaToolsDirectory) { $env:STREAMBRIDGE_FFMPEG_DIR = $config.mediaToolsDirectory }
    }
    $javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw 'Java not found. Configure javaHome in scripts/launcher.local.json.' }
    $versionInfo = New-Object System.Diagnostics.ProcessStartInfo
    $versionInfo.FileName = $javaExecutable
    $versionInfo.Arguments = '-version'
    $versionInfo.UseShellExecute = $false
    $versionInfo.CreateNoWindow = $true
    $versionInfo.RedirectStandardError = $true
    $versionProcess = [System.Diagnostics.Process]::Start($versionInfo)
    try {
        $versionRead = $versionProcess.StandardError.ReadToEndAsync()
        if (-not $versionProcess.WaitForExit(10000)) { $versionProcess.Kill(); throw 'Java version check timed out.' }
        $javaVersion = $versionRead.GetAwaiter().GetResult()
        if ($versionProcess.ExitCode -ne 0 -or $javaVersion -notmatch 'version "21\.') { throw 'This development launcher requires JDK 21. Configure javaHome in scripts/launcher.local.json.' }
    } finally { $versionProcess.Dispose() }
    # Use the same JDK for Gradle and its Java 21 toolchain.
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExecutable)
    if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { throw 'A full JDK 21 is required for this source-based launcher, not only a JRE.' }
    $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
    Write-Host 'Preparing StreamBridge. The first build may need Internet access for Gradle dependencies.'
    Write-Host 'Keep this console open. Use the launcher window to stop StreamBridge safely.'
    & (Join-Path $projectDirectory 'backend\gradlew.bat') -p (Join-Path $projectDirectory 'backend') localUiLauncher --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build or startup failed (exit $LASTEXITCODE)." }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
