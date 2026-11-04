$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
# A parent PowerShell 7 may export incompatible modules. Use only this OS host's
# built-in modules; this changes the diagnostic child environment, not Windows.
$env:PSModulePath = Join-Path $PSHOME 'Modules'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
try {
    # Supplied as data by Java, never interpolated as PowerShell source.
    $dll = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('@@DLL_PATH_BASE64@@'))
    if (![Environment]::Is64BitProcess) { throw 'The system DLL check requires an x64 process' }
    if (!(Test-Path -LiteralPath $dll -PathType Leaf)) {
        [Console]::WriteLine('SYSTEM_D3DCOMPILER_MISSING: ' + $dll); exit 25
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $dll
    if ($signature.Status -ne 'Valid') {
        [Console]::WriteLine('SYSTEM_D3DCOMPILER_INVALID_SIGNATURE: ' + $signature.Status); exit 26
    }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class StreamBridgeD3DCheck {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr LoadLibraryExW(string path, IntPtr file, uint flags);
    [DllImport("kernel32.dll", CharSet=CharSet.Ansi, ExactSpelling=true)]
    static extern IntPtr GetProcAddress(IntPtr module, string name);
    [DllImport("kernel32.dll")] static extern bool FreeLibrary(IntPtr module);
    [UnmanagedFunctionPointer(CallingConvention.StdCall, CharSet=CharSet.Ansi)]
    delegate int Compile([MarshalAs(UnmanagedType.LPStr)] string source, UIntPtr length,
        string name, IntPtr defines, IntPtr include, string entry, string target,
        uint flags1, uint flags2, out IntPtr code, out IntPtr errors);
    public static void Check(string path) {
        // Absolute target; dependencies may resolve only from Windows System32.
        IntPtr module = LoadLibraryExW(path, IntPtr.Zero, 0x00000800);
        if (module == IntPtr.Zero) throw new Exception("LoadLibraryEx failed: " + Marshal.GetLastWin32Error());
        try {
            IntPtr entry = GetProcAddress(module, "D3DCompile");
            if (entry == IntPtr.Zero) throw new Exception("D3DCompile export missing");
            Compile compile = (Compile)Marshal.GetDelegateForFunctionPointer(entry, typeof(Compile));
            const string source = "float4 main() : SV_Target { return float4(1,0,0,1); }";
            IntPtr code = IntPtr.Zero, errors = IntPtr.Zero;
            try {
                int hr = compile(source, new UIntPtr((uint)source.Length), "streambridge-check",
                    IntPtr.Zero, IntPtr.Zero, "main", "ps_4_0", 0, 0, out code, out errors);
                if (hr < 0 || code == IntPtr.Zero) throw new Exception("D3DCompile failed: " + hr);
            } finally {
                if (errors != IntPtr.Zero) Marshal.Release(errors);
                if (code != IntPtr.Zero) Marshal.Release(code);
            }
        } finally { FreeLibrary(module); }
    }
}
'@
    try { [StreamBridgeD3DCheck]::Check($dll) }
    catch { [Console]::WriteLine('SYSTEM_D3DCOMPILER_UNUSABLE: ' + $_.Exception.Message); exit 26 }
    [Console]::WriteLine('SYSTEM_D3DCOMPILER_OK: ' + $dll)
    exit 0
} catch {
    # Policy restrictions or a failed diagnostic are not proof of DLL corruption.
    [Console]::WriteLine('SYSTEM_D3DCOMPILER_CHECK_FAILED: ' + $_.Exception.Message)
    exit 27
}
