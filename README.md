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
- Discovery opens a separate, isolated Chromium context. Start the intended video
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

- Windows x64 is the application target. A clean-Windows acceptance run remains
  outstanding; developer-machine tests are not a compatibility guarantee.
- One operation at a time; no persistence or resume of jobs after restart.
- Passive discovery sees requests the player actually makes, not every possible
  source. Switching a player, quality or episode may reveal additional sources.
- HLS support is a restricted subset: no encrypted segments, fMP4 initialization
  maps, byte ranges or general discontinuity handling. The media parser caps a
  playlist at 10,000 segments. DASH, arbitrary direct MP4 and YouTube downloads
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

1. Install the browser revision selected by Playwright Java **1.64.0**:

   ```powershell
   $env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $env:LOCALAPPDATA 'StreamBridge-dev/playwright'
   .\backend\gradlew.bat -p backend installSpikeChromium
   ```

   Playwright's `chromium` download is **Chrome for Testing**. It remains an
   external development dependency; its binary redistribution terms must be
   resolved before bundling it in an installer. After upgrading Playwright,
   repeat this install command; the previous browser revision is insufficient.

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
   isolated Chromium used for discovery. Use **Finish StreamBridge** in the launcher
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

Keep the Chromium and FFmpeg variables from setup. Fixtures use owned local
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
Chromium and FFmpeg/FFprobe are still external. For persistent tool paths, copy
`backend/packaging/tools.example.properties` to
`%LOCALAPPDATA%/StreamBridge/pilot-launcher/tools.properties` and edit it.
Explicit environment variables take precedence. This is an unsigned developer
app-image, **not the planned self-contained alpha installer**. The numeric Windows
version is currently `0.1.0`; Gradle's development version is `0.1.0-SNAPSHOT`.

For private packaging tests, an explicitly supplied **unpacked** browser directory
can be included (absolute path containing `chrome.exe`):

```powershell
.\backend\gradlew.bat -p backend packageWindowsApp '-PbundledChromiumDirectory=C:/Tools/reviewed-chromium'
```

This stages the browser under `app/native/chromium`; that browser takes precedence
over the external Playwright cache. A broken included browser does not silently
fall back to another installation. `d3dcompiler_47.dll` is excluded at every depth,
case-insensitively, and the final image is checked for its absence. Other files
are preserved. Nested archives are rejected because they could reintroduce it.
This mechanism does **not** clear a browser build for public redistribution;
matching sources, notices and remaining component licenses still require review.
FFmpeg/FFprobe remain external at this stage.

On Windows, startup verifies the system `System32/d3dcompiler_47.dll` in a bounded,
hidden Windows PowerShell process: Authenticode signature, native loading and a
small shader compilation. Missing/unusable DLLs stop startup with instructions to
update or repair Windows using official Microsoft tools. A blocked or timed-out
diagnostic is reported separately from corruption. No DLL is downloaded, copied
into Windows or repaired automatically; the browser sandbox remains enabled.
Clean-Windows and visible-browser acceptance are still release gates.

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
