# Third-party components

The project MIT license covers StreamBridge's own code, not the licenses of its
dependencies, tools or generated distribution. Preserve their notices and terms.
This is a source-development inventory, not a completed redistribution/legal audit.

| Component | Use and source |
| --- | --- |
| Gradle 9.1.0 wrapper | Build bootstrap; wrapper scripts retain Gradle's Apache-2.0 notices. The committed wrapper JAR is verified against Gradle's official checksum. |
| JDK 21 / jpackage | Compiler and packaged runtime. The runtime's `legal` directory must remain intact. Exact redistribution terms depend on the JDK distribution. |
| Playwright Java 1.63.0 | Browser integration; includes driver/Node components with their own notices. See the resolved artifacts and [upstream](https://github.com/microsoft/playwright-java). |
| Apache HttpClient 5.6.5 / HttpCore 5.4.4 | Bounded media transport. Preserve notices in resolved JARs; [upstream](https://hc.apache.org/). |
| Jackson | Worker/UI serialization, version selected through the build's dependency management. Inspect the resolved dependency report and artifact notices. |
| Spring Boot 4.1.1 and JUnit/Mockito | Build/test scaffolding; Spring test libraries and test runners are excluded from the pilot runtime. |
| Chromium | External browser installed through Playwright. Not included in the source repository or current app-image. |
| FFmpeg / FFprobe | External media tools. License requirements depend on the exact build/configuration; do not assume all builds have identical terms. See [FFmpeg legal information](https://ffmpeg.org/legal.html). |

The HLS HTML fixtures use hls.js from a CDN for manual experiments; that library is
not vendored. Generated test media uses local synthetic sources, not captured video.
CI downloads tools for testing only; it does not publish a redistributable package.

Before distributing a self-contained alpha installer, inventory the exact included
files/builds, review notices, corresponding-source obligations where applicable,
provenance and known vulnerabilities. Keep upstream licenses with the distribution;
changing StreamBridge's license cannot replace those requirements.
