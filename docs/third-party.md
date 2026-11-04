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
