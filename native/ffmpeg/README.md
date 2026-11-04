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
