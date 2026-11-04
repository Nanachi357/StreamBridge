StreamBridge Windows pilot

Run StreamBridge.exe. Keep this entire folder together: app/ and runtime/ are required.
Java is included. Neither a system Java installation nor Gradle is needed to run it.
This is an unsigned app-image, not an installer. Do not bypass Windows security warnings.

Chrome for Testing (the version required by Playwright Java 1.64.0) and FFmpeg/FFprobe
are external. This image does not redistribute those executables. Reinstall the
Playwright browser after a dependency upgrade. The private Java runtime is built
with Eclipse Temurin 21.0.13+9.
Configure them in:
  %LOCALAPPDATA%\StreamBridge\pilot-launcher\tools.properties
Copy tools.example.properties there and edit the paths. Use forward slashes.
Alternatively set PLAYWRIGHT_BROWSERS_PATH and STREAMBRIDGE_FFMPEG_DIR; explicit
environment values take precedence. No system settings are changed automatically.

The launcher opens the local web UI in your saved/default browser. Use its Finish
button to stop the server; closing the UI tab does not stop it. If an older source
launcher is running, finish it first, otherwise this launcher reuses that server.

Finished videos default to the Windows Downloads known folder, including relocation.
Choose folder in the UI saves the destination across launches in
%LOCALAPPDATA%/StreamBridge/output.properties, outside this application folder.
If the saved destination is unavailable, the UI warns and uses Downloads without
forgetting the saved choice. A settings-write failure is shown explicitly.
Existing files
are preserved using video (1).mp4, video (2).mp4, etc. Working files/logs live in
%LOCALAPPDATA%\StreamBridge\jobs, separately from finished videos.
Optional preview downloads initial segments and defaults to five seconds. Its
duration is adjustable, bounded by the shortest selected track. Original encoded
quality is preserved; packet boundaries can slightly extend the clip duration.
One preview is limited to 128 requests, 128 MiB of media bodies and a two-minute
work deadline across tracks, redirects and remux/probe. A larger preview can be
rejected; try a shorter one. It does not authorize a full download. Confirm separately; the
two-minute decision window restarts after preview preparation. Preview files are
removed when the operation finishes normally; hard-crash cleanup is not automatic.
Cancellation also stops final copying to the destination. Once the completed file
has been published, a late cancellation does not delete it or undo success.

Compressed browser manifests are currently skipped before reading their decoded
bodies into the application; the discovery UI reports this limitation. Some
players may therefore yield fewer or no sources. This protection does not bound
the page's memory use inside Chromium.

No-window diagnostic (run with an absolute destination for the private JSON report):
  StreamBridge.exe --self-check=C:\Temp\streambridge-check.json
This checks bundled Java child execution, tools, a static headless Chromium page,
the browser's direct WebRTC isolation against owned local UDP/TCP receivers,
packaged web resources, authenticated local HTTP readiness, acquisition HTTP
transport and Downloads lookup. No video site is
visited. The report contains local paths; do not share it without reviewing it.

Discovery uses a local public-address-only SOCKS5 gateway, with no direct fallback.
Private destinations and ports other than 80/443 are blocked; QUIC is disabled and
WebRTC must not send directly outside the gateway. A failed WebRTC startup check
stops launch instead of disabling protection. This is not an OS firewall or a
guarantee against browser vulnerabilities. Normal startup checks may take a few
additional seconds. Your usual browser profile and system proxy are not modified.

Every dependency check (normal startup too) records diagnostics under:
  %LOCALAPPDATA%\StreamBridge\pilot-launcher\startup-logs\check-*
The failure dialog includes the exact directory. launcher.log records tool path
sources and runtime details; probe.log records missing files, OS launch errors,
timeouts, exit codes and up to 8 KiB per stdout/stderr stream from media tools.
child-stderr.log captures unexpected probe JVM errors. Review logs before sharing:
they contain local paths and tool output. Old logs are not automatically deleted.

For updates: stop the application and use a newly built whole image. Never copy
only the EXE, and do not mix libraries/runtime from different builds. Downloads
and per-user settings are outside this image. This pilot has no auto-updater.

License: see LICENSE. Third-party notices remain in the library JAR files and the
runtime/legal directory. Public distribution still requires dependency/license
review, signing decisions and acceptance testing on a clean supported Windows PC.
