# Private Windows installer acceptance

This is a local engineering workflow, not a public alpha release. Do not upload
the installer or bundled browser to GitHub releases or public CI artifacts while
the distribution/material decisions remain open. See [browser materials](browser/hibbiki-materials.md).

## Build

On Windows x64, provide the pinned Temurin 21.0.13+9 JDK, unpacked WiX 3.14.1 build
tools, the original Hibbiki 157.0.8103.39 directory matching the checked-in manifest,
and the complete owned FFmpeg bundle matching the current recipe:

```powershell
./scripts/Build-PrivateInstaller.ps1 `
  -JdkDirectory C:/build-tools/jdk-21.0.13+9 `
  -WixDirectory C:/build-tools/wix314 `
  -ChromiumDirectory C:/inputs/Chrome-bin `
  -FfmpegBundleDirectory C:/inputs/streambridge-ffmpeg
```

Paths are examples, not personal settings. The build is offline and requires a
previously populated Gradle dependency cache. WiX is a build prerequisite only;
it is not installed on the recipient's computer. No Chromium or FFmpeg compilation
is performed. The script never installs the output or starts a visible UI.

The script validates native inputs, creates an app-image with private Java,
Chromium and FFmpeg, retains notices, excludes the Windows system DLL, and builds
a per-user EXE installer. The installer has a private upgrade identity and installs
under `StreamBridge Private Alpha`, separate from the developer image. Its Windows
numeric version is `0.1.0`; it is not a public `alpha.1` qualification claim.

Find the output directory in `backend/build/private-installer/latest-path.txt`.
Keep `StreamBridge-0.1.0.exe`, `payload-manifest.json` and `build-record.json` together
for private testing. The record identifies the source commit and whether it had
uncommitted edits. Preserve the source diff/snapshot with local test evidence when
building an uncommitted candidate. Do not publish private build paths in that record.

The builder extracts the MSI embedded in the resulting EXE as data, then checks
every installed file against the app-image. Only jpackage's known replacement of
`app/.jpackage.xml` with the exact `app/.package` marker is allowed. The resulting
`payload-manifest.json` describes installed files, while `image-manifest.json`
describes the pre-install image. No MSI actions execute during this inspection.

The installed app uses adjacent bundled tools ahead of personal `tools.properties`
or environment overrides. Missing bundled executables/DLLs stop startup. Existing
source/developer launches without bundled tools keep their external configuration.
Compatible FFmpeg library replacement remains possible; startup does not enforce
original hashes on user-modified installations. Inventory verification is an
acceptance step for the original artifact, not a restriction on modification.

## Acceptance on a clean VM

Use a supported Windows 11 x64 image with a standard user, no development tools,
and a checkpoint before installing. Do not share the checkout, user profiles,
developer caches or personal configuration with the guest.

1. Copy only the installer and verification materials. Check the installer hash
   against the build record. Install as the standard user; check that elevation
   is not requested and that Start Menu/desktop shortcuts and uninstall entry exist.
2. Compare installed files against `payload-manifest.json` using
   `scripts/Verify-FileInventory.ps1`. Run the installed EXE with
   `--self-check=C:\Users\TestUser\Desktop\self-check.json` and inspect its result.
3. With networking disabled, verify installation and startup need no downloads.
   Enable networking for the media checks; normal network restrictions stay enabled.
4. Discover an owned supported stream, preview, select quality/audio, save to
   Downloads and another directory. Verify playback, cancellation, partial cleanup,
   browser closure and loss of connection. Then check an authorized real site.
5. Restart the app and Windows; check saved output-directory preferences. Uninstall
   and reinstall, checking that completed videos and unrelated files are preserved.
   Profile settings/diagnostics are deliberately retained by uninstall.

Do not disable Defender or other OS protections to make the installer pass.
Measure the **first** installed EXE launch before warming up FFmpeg or Chromium
manually. A later successful retry is not a first-launch acceptance pass. Startup
allows 60 seconds per media tool and 60 seconds for Chromium launch, inside a
four-minute dependency-check limit (plus bounded termination). These are maximum
waits, not mandatory delays. Logs record native process-creation time and driver,
browser and page stages. Timeouts are reported separately from missing/broken tools;
they do not by themselves establish corruption or an antivirus problem. Preserve
the original failure logs when repeating tests after reinstall or checkpoint restore.
The unsigned private package and any security prompts must be recorded accurately.
Developer app-image self-check is useful before VM testing but is not clean-machine
installation/uninstallation acceptance. Public notices/source delivery and browser
redistribution gates remain separate from this private functionality test.

## Verified candidate: 2026-11-13

One private `0.1.0` candidate was exercised on Windows 11 Enterprise Evaluation
26H2, build 26300.9974, in Hyper-V with four virtual CPUs and dynamic 4-8 GiB RAM.
The standard test account had no developer tools on PATH or personal tool-path
settings. The VM had SSH and test accounts configured; it was not an untouched
OS image. The candidate used bundled Java, Chromium and FFmpeg. OS protections
and application network restrictions were not disabled.

| Scenario | Result and evidence scope |
| --- | --- |
| Clean-checkpoint install and first EXE self-check | Passed without manually warming up native tools; first self-check took about 100 seconds. |
| Offline install and first EXE self-check | Passed, about 72 and 73 seconds respectively; VM internet blocked while local administration remained available. |
| Windows reboot | First interactive-user self-check after reboot passed in about 21 seconds, still offline; saved preferences and an owned Downloads sentinel were unchanged. |
| Uninstall and reinstall | Passed; executable and shortcuts removed/recreated; user data and settings retained. |
| Installed payload | All 534 files matched the candidate's payload manifest. |
| Media API flow | Real browser discovery found five qualities in the public hls.js/Mux Big Buck Bunny sample. Selected 320x184; preview 5.016 seconds, full video 634.633 seconds, H.264/AAC stereo. Both files decoded completely without errors. |
| Preview quality check | Actual resolution matched the selection; first 20 compressed packets of each video/audio stream matched the full file. This was a bounded comparison, not an exhaustive original-frame comparison. |
| Cancellation | Discovery and active segment transfer cancelled, workers stopped, partial media removed, earlier completed output unchanged. |
| Close discovery window | Normal close produced `CANCELLED` with `DISCOVERY_PAGE_CLOSED`; another search succeeded in the same backend. |
| Interrupt internet during transfer | Automatic `SEGMENTS_SELECTED_VARIANT_TIMEOUT` about six seconds after VM traffic was blocked; no cancellation command needed. Partials removed, internet restored, a fresh download succeeded in the same backend. |
| Manual UI follow-up | The tester reported all supplied shortcut/UI, preview, folder selection, download/playback and application restart steps working, including folder persistence. This is user-reported evidence, separate from the automated API checks. |

Console media tests used the installed JAR and bundled runtime through the
existing `LocalUiSpike` entry point and the normal authenticated HTTP API. They
did not substitute a fixture worker or automate desktop input. EXE startup was
tested separately. Raw reports, hashes and logs are retained privately; they are
not release attachments and may contain local paths or expired session keys.

Two findings affect interpretation:

- Direct SSH execution returned `NotSigned` for the Windows system DLL, while
  an interactive standard-user task returned `Valid` with a catalog signature
  for the identical file. The SSH result repeated after login. Desktop acceptance
  therefore used an interactive user token, with SSH only as command transport;
  signature validation was not bypassed. The underlying Windows API cause was
  not traced. The failed SSH attempt preceded the successful reboot check, so
  that result is not an entirely untouched cold-process measurement.
- The sample's 1080p stream failed with `SEGMENTS_SELECTED_VARIANT_TOO_LARGE`
  before network fault injection. Full acquisition currently caps each segment
  at 8 MiB. This does not cap total video size or reject every 1080p stream;
  discovery metadata alone does not prove a quality is downloadable. The network
  fault was exercised on 720p, and recovery downloaded 320x184 successfully.

The graphical installer wizard and downloaded-file SmartScreen behavior were
not verified by silent installation or the subsequent installed-app UI check.
Other Windows versions, arbitrary sites/codecs, all failure races and a full
rendered-UI/SSE matrix are outside this record. Public source/material delivery,
redistribution decisions, current browser security review and acceptance of the
exact final release artifact remain separate requirements. These limits do not
prevent review and commit of the private installer workflow and startup fix.
