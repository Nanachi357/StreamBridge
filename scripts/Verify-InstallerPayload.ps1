param(
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][string]$ImageManifest,
    [Parameter(Mandatory)][string]$WixDirectory,
    [Parameter(Mandatory)][string]$WorkDirectory,
    [ValidateSet('StreamBridge Private Alpha','StreamBridge App')][string]$InstallDirectory = 'StreamBridge Private Alpha',
    [string]$ExpectedUpgradeCode = ''
)
$ErrorActionPreference = 'Stop'
# For our freshly built installer, not an arbitrary hostile MSI. Data-file loading
# reads PE resources only: it does not run the EXE, DllMain or installer actions.
if (Test-Path -LiteralPath $WorkDirectory) { throw 'Use a fresh verification directory.' }
New-Item -ItemType Directory -Path $WorkDirectory | Out-Null
$work = (Resolve-Path -LiteralPath $WorkDirectory).Path
if (!('StreamBridgeInstallerResource' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
public static class StreamBridgeInstallerResource {
    [DllImport("kernel32", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr LoadLibraryExW(string name, IntPtr file, uint flags);
    [DllImport("kernel32", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr FindResourceW(IntPtr module, string name, IntPtr type);
    [DllImport("kernel32", SetLastError=true)] static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32")] static extern IntPtr LockResource(IntPtr resource);
    [DllImport("kernel32")] static extern uint SizeofResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32")] static extern bool FreeLibrary(IntPtr module);
    public static void Extract(string exe, string output) {
        IntPtr module=LoadLibraryExW(exe,IntPtr.Zero,2);
        if(module==IntPtr.Zero) throw new IOException("Cannot open installer resources");
        try {
            IntPtr resource=FindResourceW(module,"MSI",new IntPtr(10));
            if(resource==IntPtr.Zero) throw new IOException("Missing embedded MSI");
            uint length=SizeofResource(module,resource);
            IntPtr data=LockResource(LoadResource(module,resource));
            if(length==0 || length>Int32.MaxValue || data==IntPtr.Zero) throw new IOException("Invalid MSI resource");
            byte[] buffer=new byte[1024*1024];
            using(var file=new FileStream(output,FileMode.CreateNew,FileAccess.Write)) {
                for(int offset=0;offset<(int)length;) {
                    int count=Math.Min(buffer.Length,(int)length-offset);
                    Marshal.Copy(IntPtr.Add(data,offset),buffer,0,count);
                    file.Write(buffer,0,count); offset+=count;
                }
            }
        } finally {FreeLibrary(module);}
    }
}
'@
}
[StreamBridgeInstallerResource]::Extract((Resolve-Path -LiteralPath $Installer).Path, "$work/embedded.msi")
& "$WixDirectory/dark.exe" -nologo -x "$work/extracted" "$work/embedded.msi" "$work/installer.wxs" *> "$work/dark.log"
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect embedded installer.' }
[xml]$xml = Get-Content -LiteralPath "$work/installer.wxs" -Raw
$ns = [Xml.XmlNamespaceManager]::new($xml.NameTable)
$ns.AddNamespace('w','http://schemas.microsoft.com/wix/2006/wi')
$package = $xml.SelectSingleNode('/w:Wix/w:Product/w:Package',$ns)
if ($package.InstallPrivileges -ne 'limited') { throw 'Installer requires elevated privileges.' }
$install = $xml.SelectSingleNode('//w:Directory[@Id="INSTALLDIR"]',$ns)
if ($null -eq $install -or $install.ParentNode.Id -ne 'LocalAppDataFolder' -or $install.Name -ne $InstallDirectory) { throw 'Unexpected installation directory.' }
$product = $xml.SelectSingleNode('/w:Wix/w:Product',$ns)
if ($ExpectedUpgradeCode -and [guid]$product.UpgradeCode -ne [guid]$ExpectedUpgradeCode) { throw 'Unexpected upgrade identity.' }
if ($product.Version -ne '0.1.0' -or $product.Manufacturer -ne 'Vladyslav Ponomarenko') { throw 'Unexpected product identity.' }
$expected = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in (Get-Content -LiteralPath $ImageManifest -Raw | ConvertFrom-Json).files) { $expected.Add($entry.path,$entry) }
if (!$expected.Remove('app/.jpackage.xml')) { throw 'Missing jpackage image metadata.' }
# jpackage replaces its build-state XML with this exact installed-app marker.
$marker = [Text.Encoding]::UTF8.GetBytes('StreamBridge')
$sha = [Security.Cryptography.SHA256]::Create()
try { $markerHash=([BitConverter]::ToString($sha.ComputeHash($marker))).Replace('-','').ToLowerInvariant() } finally { $sha.Dispose() }
$expected.Add('app/.package', [pscustomobject]@{path='app/.package';bytes=$marker.Length;sha256=$markerHash})
$found = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($file in $install.SelectNodes('.//w:File',$ns)) {
    $parts = [Collections.Generic.List[string]]::new()
    $parts.Add([string]$file.Name)
    $parent = $file.ParentNode
    while ($parent -ne $install) {
        if ($parent.LocalName -eq 'Directory') { $parts.Insert(0,[string]$parent.Name) }
        $parent=$parent.ParentNode
    }
    $relative = $parts -join '/'
    if (!$expected.ContainsKey($relative) -or !$found.Add($relative)) { throw "Unexpected installed file: $relative" }
    $entry = $expected[$relative]
    $source = [string]$file.Source
    if ((Get-Item -LiteralPath $source).Length -ne [long]$entry.bytes -or (Get-FileHash -LiteralPath $source).Hash -ne $entry.sha256) { throw "Installed file differs: $relative" }
    $target = Join-Path "$work/installed-image" $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $target
}
if ($found.Count -ne $expected.Count -or $xml.SelectNodes('//w:File',$ns).Count -ne $expected.Count) { throw 'Installer file inventory differs.' }
@{schema_version=1; purpose='Installed payload verification; not publication approval'; files=@($expected.Values | Sort-Object path)} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$work/payload-manifest.json" -Encoding utf8
& "$PSScriptRoot/Verify-FileInventory.ps1" -Root "$work/installed-image" -Manifest "$work/payload-manifest.json"
Write-Output 'PASS: embedded MSI, per-user scope and exact installed payload verified without installation.'
