# Third-party components

The project MIT license covers StreamBridge's own code, not the licenses of its
dependencies, tools or generated distribution. Preserve their notices and terms.
This is a source-development inventory, not a completed redistribution/legal audit.

| Component | Use and source |
| --- | --- |
| Gradle 9.1.0 wrapper | Build bootstrap; wrapper scripts retain Gradle's Apache-2.0 notices. The committed wrapper JAR is verified against Gradle's official checksum. |
| Eclipse Temurin 21.0.13+9 / jpackage | Pinned packaged runtime, GPLv2 with Classpath Exception and component-specific terms. Keep `runtime/legal` and arrange access to complete matching source/build materials before redistribution. |
| Playwright Java 1.64.0 | Apache-2.0 browser integration; resolved driver includes playwright-core 1.64.1 and Node 24.22.1 with their own notices. See the resolved artifacts and [upstream](https://github.com/microsoft/playwright-java). |
| Apache HttpClient 5.6.5 / HttpCore 5.4.4 | Bounded media transport. Preserve notices in resolved JARs; [upstream](https://hc.apache.org/). |
| Jackson 3.1.8 BOM | Worker/UI serialization; the core/databind family is aligned using the BOM, retaining its selected annotations version. Preserve Apache-2.0 and embedded third-party notices. |
| Spring Boot 4.1.2 and JUnit/Mockito | Build/test scaffolding; Spring test libraries and test runners are excluded from the pilot runtime. OpenTest4J is a Playwright runtime dependency and is included. |
| Chrome for Testing | Playwright's current `chromium` download. It is external, absent from the repository/app-image. Do not equate Chromium source licenses with permission to redistribute the complete branded Chrome binary. |
| FFmpeg / FFprobe | External media tools. License requirements depend on the exact build/configuration; do not assume all builds have identical terms. See [FFmpeg legal information](https://ffmpeg.org/legal.html). |

The HLS HTML fixtures use hls.js from a CDN for manual experiments; that library is
not vendored. Generated test media uses local synthetic sources, not captured video.
CI downloads tools for testing only; it does not publish a redistributable package.

Before distributing a self-contained alpha installer, inventory the exact included
files/builds, review notices, corresponding-source obligations where applicable,
provenance and known vulnerabilities. Keep upstream licenses with the distribution;
changing StreamBridge's license cannot replace those requirements.

Gyan's current essentials media fixture build is GPLv3, not LGPL. A distributable
installer needs matching source/build material for it and its linked dependencies,
or a separately reviewed alternative. Playwright's own FFmpeg helper is a different
binary and needs its own assessment if included. Passing tests or a clean
top-level dependency scan does not clear bundled native libraries for release.

Optional browser staging (`-PbundledChromiumDirectory=<absolute-unpacked-directory>`)
omits `d3dcompiler_47.dll` and verifies its absence from the resulting app-image.
Startup relies on and checks the Windows system copy. Do not embed the original
complete browser archive in an installer: it would still redistribute that DLL.
This policy avoids shipping that one Microsoft binary; it does not resolve the
separate `dxcompiler.dll`, Chromium/ANGLE, embedded FFmpeg, or other third-party
obligations. The optional mechanism is for private validation until the complete
native payload and corresponding materials have passed redistribution review.
The exact candidate, collected evidence and remaining release gates are recorded
in [Browser distribution candidate](browser-distribution.md). In particular,
DXC has separate open-source notices; excluding the SDK DLL does not settle
statically linked Microsoft runtime or embedded LGPL/MPL obligations.
The [Hibbiki material review](browser/hibbiki-materials.md) tracks the remaining
runtime/source requirements separately from the verified payload inventory.

An owned [FFmpeg build recipe](../native/ffmpeg/README.md) now targets FFmpeg 9.0.2
for Windows x64, shared libraries, LGPL-2.1-or-later configuration and no external
codec libraries. Its source archive/hash, frozen toolchain inputs, configuration,
licenses and generated binary inventory are retained separately from Git.
It is a compatibility candidate; the installed launcher and release payload are
not switched automatically. The build now retains a matching source archive,
headers/import libraries, recipe, notices and evidence under one checked manifest;
`native/ffmpeg/Verify-Materials.ps1` checks completeness and the expected profile.
The separate native CI builds and tests that candidate rather than substituting
the fixture generator. Its artifacts are temporary developer outputs, not releases.
Before shipping, retain the matching materials beside the binary release, expose
their source link and notices in the distribution, and complete final Windows
installer acceptance. Integrity checks do not establish patent clearance or
absence of vulnerabilities in the native code.
