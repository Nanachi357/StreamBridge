# StreamBridge FFmpeg candidate

An application-specific Windows x64 build of upstream FFmpeg and FFprobe, not a
fork of the media implementation. The installed StreamBridge launcher is not
changed by this build. This recipe is an alpha candidate, not redistribution
clearance or a promise of support for every codec.

## Build

On Windows with Docker Desktop (Linux containers) and `curl.exe`:

```powershell
./native/ffmpeg/Build.ps1
# Use a fresh directory for another build; existing runtimes are not overwritten.
./native/ffmpeg/Build.ps1 -OutputDirectory ./native/ffmpeg/build/repeat
```

Relative output paths are resolved from the current PowerShell location, including
after `Set-Location`. The default output remains relative to the recipe directory.
Run `./native/ffmpeg/Test-OutputDirectory.ps1` to check relative, absolute and default
paths and existing-runtime protection without downloads, Docker or compilation.
The Windows CI job runs this check automatically.

The Docker base digest and dated, signature-checked Debian index fix the
toolchain inputs. Package downloads from the Debian mirror must match SHA-256
from that frozen index; otherwise the snapshot is tried. `sources.lock` fixes
the upstream archive and SHA-256. Compilation runs without network access.
This fixes the recipe inputs; byte-for-byte reproducibility must be measured,
not assumed. The first build requires downloads and a C compiler; users of the
eventual installer do not need Docker, a compiler, or a separate FFmpeg install.

Outputs under the ignored `build/` directory:

- `runtime/`: FFmpeg, FFprobe and shared FFmpeg DLLs; keep the set together.
- `sources/`: exact upstream source archive.
- `recipe/`, `licenses/`, `evidence/`: recipe, notices, configuration, compiler and
  installed package inventory, build logs and PE import inspection.
- `SHA256SUMS`: hashes of the generated distribution materials.
- `development/`: matching headers and import libraries for rebuilding/relinking.
- `NOTICE.txt`: attribution, modification status and source-material instructions.

Do not commit generated binaries, downloaded archives, build logs or Docker
images. Retain the matching source/recipe/notices with any binary release.

## Scope

Java retrieves HLS manifests and segments. The runtime operates on local files:

- MPEG-TS, MOV/MP4 and raw AAC demuxers; MP4 and null/check-only muxers;
- built-in decoders, parsers, bitstream filters and non-GPL filters;
- file and pipe protocols; no HTTP, HTTPS, HLS demuxer or other network protocols;
- only wrapped-frame and PCM S16LE encoders for decoding verification;
- no external codec libraries, GPL/nonfree/version3 options, capture devices,
  FFplay or hardware-accelerated decoders.

MP4 remux and previews use stream copy, preserving compressed video/audio.
Output verification still decodes media, so removing all decoders would break
the application. This is a minimal external-dependency profile, not an exhaustive
size-optimized codec whitelist. Input/container compatibility remains bounded by
StreamBridge's supported HLS acquisition path. Legacy direct-HTTP FFmpeg spikes
are intentionally not supported by this runtime.

The MP4 muxer also enables its MOV implementation dependency. AV1 software
decoding is **not supported** by this profile: upstream's built-in AV1 path
requires hardware support; external decoders such as dav1d are not included.
An AV1 MP4 negative control fails decoding on the tested Windows host. Stream
copy alone is not evidence of compatibility because the application verifies
decoding before publishing a completed download. Do not present this profile
as a drop-in replacement for every codec in a full FFmpeg distribution.

## Compatibility tests

Use a full development-only FFmpeg build for fixture generation and independent
frame/audio comparison. It is not part of the user package:

```powershell
$env:STREAMBRIDGE_FFMPEG_DIR = (Resolve-Path ./native/ffmpeg/build/candidate/runtime).Path
$env:STREAMBRIDGE_FIXTURE_FFMPEG_DIR = 'C:/development-tools/full-ffmpeg/bin'
$env:STREAMBRIDGE_MINIMAL_FFMPEG_CHECK = '1'
cd backend
./gradlew.bat test --tests '*HlsPreviewTest' --tests '*RealHlsSampleProbeTest' --tests '*MinimalFfmpegTest' --tests '*FfmpegTransferTest' --rerun-tasks
```

`STREAMBRIDGE_FIXTURE_FFMPEG_DIR` defaults to `STREAMBRIDGE_FFMPEG_DIR` for existing
full-tool development setups. The opt-in minimal-profile acceptance tests require
both paths explicitly and refuse to test the generator as the candidate. They
exercise actual native process progress/cancellation/timeouts in addition to the
existing simulated process tests. Run the full Java/UI suites before acceptance.

## Material checks and CI

`Get-Source.ps1` is shared by the local builder and native CI. It tries the
official release server, then the pinned MacPorts mirror, with three attempts
per server. Each attempt has a 20-second connection and 60-second total timeout;
retries wait two seconds. TLS verification stays enabled and redirects must use
HTTPS. Personal curl configuration is ignored. Failed partial files are deleted.
Only bytes matching the SHA-256 in `sources.lock` are published to the source
cache; a hash mismatch stops immediately, including for an existing cached file.
The mirror is a transport fallback, not an alternative version or trust anchor.
`Test-SourceDownload.ps1` exercises retries, fallback, exhaustion, corruption,
cache reuse, cleanup and concurrent destination protection without network access.

Run `./native/ffmpeg/Verify-Materials.ps1 -BundleDirectory <output>` before using
or packaging a bundle. It verifies the complete manifest, required notices and
development files, source hash, exact recipe, LGPL/shared build configuration,
eight-file runtime inventory and actual Windows PE imports, compared with the
recorded reports. The bounded static PE reader never loads or executes the tools;
this AMD64 profile rejects bound/delayed imports, all export forwarders (including
ordinal-only exports), malformed directory ranges and overlapping virtual or file
sections. Section alignment, image/header bounds and file-backed RVA ranges are
checked separately; unsupported layouts fail closed.
The full development file inventory and hashes are pinned in `development.sha256`
in the checkout, independently of the bundle's own manifest. Update that inventory
only after reviewing a new source/toolchain/profile build. The build also compiles
and links an example against all six installed libraries without executing it.
Configuration must use configure's flat, guarded definition format; conflicting
definitions, undefinitions, conditional overrides, bare CR line endings and hidden
control characters are rejected. LF and CRLF configurations are supported. Recipe files use
LF endings on both platforms so their bytes survive checkout and artifact transfer.
`Test-Materials.ps1` additionally rejects tampering, missing source, extra runtime
files and unsafe/duplicate manifest paths. A manifest is an integrity check,
not authentication of an untrusted bundle or a substitute for a license review.

The separate **Native FFmpeg build and compatibility** workflow builds the tools
on Linux with the same Docker recipe, with networking disabled for compilation.
It verifies and transfers the complete bundle to Windows, then runs all six
candidate acceptance cases plus preview, acquisition and process-control suites.
The full FFmpeg used to generate fixtures stays separate from the candidate.
The ordinary application workflow remains a separate check.

CI artifacts expire after 14 days and are developer candidates, not installers.
Do not use them as the only source delivery for a published release. Preserve the
matching runtime and material archive in the release, with a direct source link.

## Redistribution review

The selected configuration targets LGPL-2.1-or-later FFmpeg. Verify the built
`-L`, configuration and imported DLLs on every change. Shared FFmpeg DLLs allow
replacement; preserve the corresponding source and build materials. Also review
MinGW-w64 runtime/header notices and GCC runtime exception terms; a minimal
build does not eliminate third-party obligations. This software is based in
part on the work of the Independent JPEG Group. Upstream license notices and
the relevant JPEG source notices are retained with the output.

Before the alpha installer: complete the component/source/material review,
test on clean supported Windows, and verify the final installer contents.
No automatic dependency updates; review version/hash changes and rerun tests.
