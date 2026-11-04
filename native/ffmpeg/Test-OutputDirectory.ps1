$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('streambridge-output-path-' + [guid]::NewGuid())
$originalProcessDirectory = [Environment]::CurrentDirectory
New-Item -ItemType Directory -Path $testRoot | Out-Null
Push-Location $testRoot
try {
    $recipe = New-Item -ItemType Directory -Path (Join-Path $testRoot 'recipe')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Build.ps1') -Destination $recipe.FullName
    $caller = New-Item -ItemType Directory -Path (Join-Path $testRoot 'caller')
    $processDirectory = New-Item -ItemType Directory -Path (Join-Path $testRoot 'process')
    [Environment]::CurrentDirectory = $processDirectory.FullName
    Set-Location -LiteralPath $caller.FullName

    # Exercise the actual entry point with owned source bytes and no external tools.
    function curl.exe { throw 'Unexpected download: output resolved to the wrong directory.' }
    $dockerCalls = [System.Collections.Generic.List[object]]::new()
    function docker {
        $dockerCalls.Add(@($args))
        $global:LASTEXITCODE = 0
    }
    $fixture = Join-Path $testRoot 'fixture.tar.xz'
    [IO.File]::WriteAllText($fixture, 'Owned path-resolution fixture; not a real archive.')
    $hash = (Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash
    "FFMPEG_VERSION=fixture`nFFMPEG_SHA256=$hash`nFFMPEG_URL=https://example.invalid/unused" |
        Set-Content -LiteralPath (Join-Path $recipe.FullName 'sources.lock')

    $cases = @(
        @{ Name = 'relative after Set-Location'; Argument = './output with spaces'; Expected = (Join-Path $caller.FullName 'output with spaces') },
        @{ Name = 'absolute'; Argument = (Join-Path $testRoot 'absolute output'); Expected = (Join-Path $testRoot 'absolute output') },
        @{ Name = 'relative square brackets'; Argument = './output [ab]'; Expected = (Join-Path $caller.FullName 'output [ab]') },
        @{ Name = 'absolute square brackets with matching neighbor'; Argument = (Join-Path $testRoot 'output [ab]'); Expected = (Join-Path $testRoot 'output [ab]'); Neighbor = (Join-Path $testRoot 'output a/runtime') },
        @{ Name = 'default'; Argument = $null; Expected = (Join-Path $recipe.FullName 'build/candidate') }
    )
    foreach ($case in $cases) {
        # A wildcard match in another directory must not reject this fresh output.
        if ($case.ContainsKey('Neighbor')) {
            New-Item -ItemType Directory -Path $case.Neighbor -Force | Out-Null
        }
        $sources = New-Item -ItemType Directory -Force -Path (Join-Path $case.Expected 'sources')
        Copy-Item -LiteralPath $fixture -Destination (Join-Path $sources.FullName 'ffmpeg-fixture.tar.xz')
        $dockerCalls.Clear()
        $parameters = @{}
        if ($null -ne $case.Argument) { $parameters.OutputDirectory = $case.Argument }
        & (Join-Path $recipe.FullName 'Build.ps1') @parameters
        $expectedMount = "type=bind,source=$($case.Expected),target=/out"
        if ($dockerCalls.Count -ne 2 -or $dockerCalls[1] -notcontains $expectedMount) {
            throw "Incorrect Docker output mount: $($case.Name)"
        }
        # The same path must also protect an already existing runtime.
        New-Item -ItemType Directory -Path (Join-Path $case.Expected 'runtime') | Out-Null
        $dockerCalls.Clear()
        $rejected = $false
        try { & (Join-Path $recipe.FullName 'Build.ps1') @parameters }
        catch {
            if ($_.Exception.Message -ne 'Choose a new output directory; existing runtime will not be overwritten.') { throw }
            $rejected = $true
        }
        if (!$rejected -or $dockerCalls.Count -ne 0) { throw 'Existing runtime was not protected.' }
        Write-Output "PASS: $($case.Name); correct mount and existing-runtime rejection"
    }
    if (@(Get-ChildItem -LiteralPath $processDirectory.FullName -Force).Count -ne 0) {
        throw 'Files were created relative to the process directory.'
    }
} finally {
    [Environment]::CurrentDirectory = $originalProcessDirectory
    Pop-Location
    $resolvedRoot = (Resolve-Path -LiteralPath $testRoot).Path
    if ($resolvedRoot -ne [IO.Path]::GetFullPath($testRoot) -or
        !(Split-Path -Leaf $resolvedRoot).StartsWith('streambridge-output-path-')) {
        throw 'Unexpected cleanup target.'
    }
    Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
}
