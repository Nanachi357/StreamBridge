param([Parameter(Mandatory)][string]$Directory)
$ErrorActionPreference = 'Stop'
# Exact existing Temurin release, not a dependency upgrade or download-on-install.
# Source/metadata digests come from the upstream release API; build-script digest
# records the archive at the commit identified by that metadata (not a signature).
$expected = [ordered]@{
    'OpenJDK21U-jdk-sources_21.0.13_9.tar.gz'='b5232c9980d8a0f0459676c084c41c6e2c771ed9e785890b03c6a438406ad1ca'
    'temurin-build-1cf43d7.tar.gz'='f524e42fdb6480934dae49e59b07bd979763dfb413046f73b8cbceefe5b08591'
    'temurin-build.json'='a7f64d6e7a6ffd90838f4fcdc7b52b8f46cf04225f660fffa83377bd820d874f'
}
foreach ($name in $expected.Keys) {
    if ((Get-FileHash -LiteralPath (Join-Path $Directory $name) -Algorithm SHA256).Hash -ne $expected[$name]) { throw "Java material mismatch: $name" }
}
$metadata = Get-Content -LiteralPath (Join-Path $Directory 'temurin-build.json') -Raw | ConvertFrom-Json
if ($metadata.sha256 -ne 'a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9' -or
    $metadata.buildRef -ne 'https://github.com/adoptium/temurin-build/commit/1cf43d7a3e77886bfea7d550a902e151a87d724d') { throw 'Java binary/build correspondence mismatch.' }
Write-Output 'PASS: pinned Temurin full source, build scripts and binary build metadata.'
