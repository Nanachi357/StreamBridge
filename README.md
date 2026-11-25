# StreamBridge

An experimental local Windows application for discovering media requested by a
browser and saving supported HLS video as MP4. Created and maintained by
[Vladyslav Ponomarenko (Nanachi357)](https://github.com/Nanachi357).

**Development preview, before `v0.1.0-alpha.1`.** This repository contains working
experiments and a local UI pilot, not a universal downloader or a finished
installer. Use only content you own or are authorized to save. DRM, authentication,
paywalls, CAPTCHA and access-control bypasses are outside the project scope.

## What works

- A Windows launcher opens a local web UI in your chosen/default browser.
- Discovery opens the same browser as the UI (Google Chrome or Microsoft Edge on Windows),
  in a separate window with an isolated temporary profile. Start the intended video
  or switch players/episodes there to generate media requests.
- Source cards show requesting frames, declared qualities, associated audio and
  observed duration. Sources are not automatically classified as content or ads.
- Search continues until selection, cancellation or a browser lifecycle/error
  event ends it. There is no three-minute observation limit or cumulative
  24-playlist stop. Bounded caches and individual request timeouts still apply;
  the selected source is pinned against ordinary cache eviction.
- Supported finite, unencrypted HLS/MPEG-TS streams can be downloaded, including
  separate audio announced by the master playlist, and remuxed locally with FFmpeg.
  Video without audio is supported when no audio rendition is declared/selected;
  an explicitly selected audio rendition must be present in the acquired media.
- Optional preview defaults to five seconds. Expand its settings to change the
  duration, up to the shortest selected track. Stream copy preserves encoded
  quality; packet boundaries can slightly extend the actual duration.
- Progress, cancellation, remembered output directory and collision-safe names
  (`video.mp4`, `video (1).mp4`, ...) are available. Windows Downloads is the default.

## Current limits

- Windows 11 x64 with installed **Google Chrome Stable or Microsoft Edge Stable** is the application target.
  Open the UI in the browser you want to use for discovery. The authenticated start request's
  User-Agent selects an allowlisted Playwright channel (`chrome` or `msedge`); it never supplies
  an executable path. Unsupported browsers are rejected, and launch failure does not switch browsers.
  StreamBridge neither bundles nor downloads a browser. See the
  [installer acceptance procedure](docs/private-installer.md).
- One operation at a time; no persistence or resume of jobs after restart.
- Passive discovery sees requests the player actually makes, not every possible
  source. Switching a player, quality or episode may reveal additional sources.
- HLS support is a restricted subset: no encrypted segments, fMP4 initialization
  maps, byte ranges or general discontinuity handling. The media parser caps a
  playlist at 10,000 segments. Full download currently limits each media segment
  to **8 MiB**, independently of total video size. A larger segment fails with
  `SEGMENTS_SELECTED_VARIANT_TOO_LARGE`; an advertised quality can therefore be
  discoverable but unsupported for download. Preview has a separate per-resource
  budget and may fail independently. DASH, arbitrary direct MP4 and YouTube downloads
  are not implemented by the current acquisition path.
- **Compressed browser manifests are skipped before reading decoded bodies into
  Java.** The UI reports this; some players may yield fewer or no sources. This
  reduces application-side memory risk but does not bound Chromium's own memory.
- Each preview shares a budget of 128 requests, 128 MiB of media bodies and two
  minutes of work across tracks, redirects, remux and probe. Cleanup may take extra
  time. Large previews can be rejected; full download needs separate confirmation.
  Repeated previews have separate budgets.
- Private/local destinations and nonstandard ports are blocked during discovery
  and acquisition. Sites requiring unsupported browser networking may not work.
- Discovery suppresses empty-profile Google account lookup, speculative search
  connections and server-assisted autofill. This is not a Google-domain blocker:
  websites can still request Google resources, and account cookies can permit
  account-service calls. See the [browser policy and verification limits](docs/browser-network-policy.md).
- Hard crashes can leave temporary files and logs. Do not delete working files
  while an operation is active. There is no automatic updater.

See [SECURITY.md](SECURITY.md) for protection boundaries and reporting guidance.

## Build and test from source

Install a full **Eclipse Temurin JDK 21** and set `JAVA_HOME` to it. CI uses
**21.0.13+9**; EXE packaging requires that exact build. Gradle 9.1.0 is supplied through
the wrapper; the first build needs Internet access. Node.js **24.22.1** is used to run
UI tests. Commands below use PowerShell from the repository root.

```powershell
.\backend\gradlew.bat -p backend clean build
node --test backend/src/test/js/ui-connection.test.cjs
```

Without the optional tools below, browser/media integration tests explicitly skip.
On Unix, use `./backend/gradlew -p backend ...`; portable tests do not imply Linux
desktop support. `build` also assembles and verifies the pilot JAR; there is no
Spring Boot application entry point to run with `bootRun`.

Gradle configuration caching is disabled in `backend/gradle.properties` because
the current pilot packaging tasks do not support it. No extra command-line flag
is needed; dependency downloads and ordinary up-to-date task checks still work.

## Set up the Windows pilot

1. Install or update **Google Chrome Stable** from [Google](https://www.google.com/chrome/)
   or **Microsoft Edge Stable** from [Microsoft](https://www.microsoft.com/edge/download).
   Playwright Java **1.64.0** uses the matching `chrome` or `msedge` channel; no browser-cache
   download or custom executable path is needed. Browsers update independently of StreamBridge.
   Startup checks the explicitly selected browser, or an available browser for the default-browser option.
   Discovery then uses the browser of the UI tab that starts that operation.

2. Obtain FFmpeg and FFprobe from a trusted distribution linked by the
   [FFmpeg project](https://ffmpeg.org/download.html). Review its license and verify
   its download checksum. Point to the directory containing both executables:

   ```powershell
   $env:STREAMBRIDGE_FFMPEG_DIR = 'C:/Tools/ffmpeg/bin' # replace with your directory
   ```

3. Start the launcher from this same terminal:

   ```powershell
   .\backend\gradlew.bat -p backend localUiLauncher
   ```

   This opens visible windows. The browser chosen for the UI is separate from the
   isolated Edge used for discovery. Use **Finish StreamBridge** in the launcher
   to stop the application; closing the UI tab does not stop a download.

For persistent source-launch settings, copy `scripts/launcher.example.json` to the
ignored `scripts/launcher.local.json`, edit the paths, and use `Start-StreamBridge.cmd`.
This respects PowerShell execution policy; do not bypass system policy to run it.

To run all Windows integration tests without visible windows:

```powershell
$env:STREAMBRIDGE_UI_BROWSER_CHECK = '1'
$env:STREAMBRIDGE_VISIBLE_BROWSER_CHECK = '0'
.\backend\gradlew.bat -p backend test --rerun-tasks
```

Keep the FFmpeg variable from setup; Edge must be installed. Fixtures use owned local
servers and generated media. No real video site is needed for these tests.

## Build an EXE application folder

Use Eclipse Temurin **21.0.13+9** in `JAVA_HOME`; packaging rejects a different
runtime build so an older local JDK cannot silently enter the package. Obtain it
from the [official release](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9).
The Windows x64 HotSpot ZIP SHA-256 is
`a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9`.

```powershell
.\backend\gradlew.bat -p backend packageWindowsApp
```

The new folder is under `backend/build/windows-app/<build-id>/StreamBridge`;
`backend/build/windows-app/latest-path.txt` gives its full path. Run
`StreamBridge.exe` and keep the entire folder together. Java is included;
Google Chrome or Microsoft Edge, and FFmpeg/FFprobe are external prerequisites. For persistent tool paths, copy
`backend/packaging/tools.example.properties` to
`%LOCALAPPDATA%/StreamBridge/pilot-launcher/tools.properties` and edit it.
Explicit environment variables take precedence. This is an unsigned developer
app-image, **not the planned self-contained alpha installer**. The numeric Windows
version is currently `0.1.0`; Gradle's development version is `0.1.0-SNAPSHOT`.

A separate [private installer workflow](docs/private-installer.md) bundles Java
and the owned FFmpeg runtime in a per-user EXE installer. **Installed Chrome or Edge Stable
is the only browser prerequisite.** Discovery uses a temporary profile, never
the user's normal browser profile. A missing selected browser or a failed protected launch produces
an error; there is no fallback download or launch without the sandbox.

The installer verifies its embedded payload. Clean-Windows acceptance must be
repeated for the new Edge-based artifact before publication. A successful test of
an older artifact does not qualify this one.

The [alpha candidate packaging procedure](docs/alpha-release.md) prepares the
public installation identity and companion source/material files locally. It does
not publish a release; final artifact acceptance and publication remain separate.

Hidden package diagnostic (use an absolute path for the report):

```powershell
& '<application-folder>/StreamBridge.exe' '--self-check=C:/Temp/streambridge-check.json'
```

The report contains local paths; inspect it before sharing. Settings live under
`%LOCALAPPDATA%/StreamBridge`, working files under its `jobs` folder, and startup
logs under `pilot-launcher/startup-logs`. Source builds do not automatically
replace an existing installed application.

## Troubleshooting

- **Disconnected UI / 403:** open it from the launcher or copy the entire launch
  URL including its fragment. It contains a session key; do not share that URL.
- **No sources:** start playback in the discovery window, try the intended player,
  and check the activity message. Unsupported/compressed manifests may be skipped.
- **Tool startup failure:** inspect the diagnostic directory shown by the launcher.
  Check configured paths and reinstall the required tool version if needed.
  Do not disable the browser sandbox or certificate checks.
- **Failure report:** prefer the UI's technical report and remove any personal
  information before sharing. Raw startup logs are not a safe public attachment.

## Code and checks

Java 21, Playwright, Apache HttpClient 5, FFmpeg/FFprobe and plain HTML/CSS/JavaScript.
The runtime uses the JDK HTTP server, not Spring MVC. Spring Boot currently
provides build/dependency/test scaffolding. See [architecture](docs/architecture.md)
for the actual runtime, experimental source layout and future boundaries.

CI defines portable Java/UI checks, Windows hidden-browser/media integration,
Windows packaging/self-check, and a redacted secret scan. These workflows do not
publish an application or release. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Authorship and license

Created and maintained by **Vladyslav Ponomarenko**, GitHub
[Nanachi357](https://github.com/Nanachi357), with AI-assisted development and
independent review of selected security scenarios. These reviews are not a full
security certification.

Project code is under the [MIT license](LICENSE). Third-party components retain
their own licenses and attribution; see [third-party notes](docs/third-party.md).
