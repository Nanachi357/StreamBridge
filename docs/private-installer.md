# Private Windows installer acceptance

This workflow creates a private test installer. Publication is a separate step
after acceptance of the exact artifact and delivery of included components' notices
and matching source materials. **Microsoft Edge Stable is an external prerequisite**;
StreamBridge does not include a browser or a browser installer.

`Build-AlphaInstaller.ps1` uses the same payload checks with a distinct public
installation identity, explicitly marked as an unpublished candidate. Its default
requires a clean checkout; `-AllowUncommitted` is only for local preparation. The
private profile, existing upgrade identity and output location remain unchanged.

## Build

On Windows x64, provide the pinned Temurin 21.0.13+9 JDK, unpacked WiX 3.14.1 build
tools and the complete owned FFmpeg bundle matching the current recipe:

```powershell
./scripts/Build-PrivateInstaller.ps1 `
  -JdkDirectory C:/build-tools/jdk-21.0.13+9 `
  -WixDirectory C:/build-tools/wix314 `
  -FfmpegBundleDirectory C:/inputs/streambridge-ffmpeg
```

Paths are examples, not personal settings. The build is offline and requires a
previously populated Gradle dependency cache. WiX is a build prerequisite only;
it is not installed on the recipient's computer. No browser or FFmpeg compilation
is performed. The script never installs the output or starts a visible UI.

The script validates native inputs, creates an app-image with private Java
and FFmpeg, retains notices, rejects a browser payload, and builds
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

Use a supported Windows 11 x64 image with installed Edge Stable, a standard user, no development tools,
and a checkpoint before installing. Do not share the checkout, user profiles,
developer caches or personal configuration with the guest.

1. Copy only the installer and verification materials. Check the installer hash
   against the build record. Install as the standard user; check that elevation
   is not requested and that Start Menu/desktop shortcuts and uninstall entry exist.
2. Compare installed files against `payload-manifest.json` using
   `scripts/Verify-FileInventory.ps1`. Run the installed EXE with
   `--self-check=C:\Users\TestUser\Desktop\self-check.json` and inspect its result.
3. With Edge already installed and networking disabled, verify installation and startup need no downloads.
   Enable networking for the media checks; normal network restrictions stay enabled.
4. Discover an owned supported stream, preview, select quality/audio, save to
   Downloads and another directory. Verify playback, cancellation, partial cleanup,
   browser closure and loss of connection. Then check an authorized real site.
5. Restart the app and Windows; check saved output-directory preferences. Uninstall
   and reinstall, checking that completed videos and unrelated files are preserved.
   Profile settings/diagnostics are deliberately retained by uninstall.

Do not disable Defender or other OS protections to make the installer pass.
Measure the **first** installed EXE launch before warming up FFmpeg or Edge
manually. A later successful retry is not a first-launch acceptance pass. Startup
allows 60 seconds per media tool and 60 seconds for Edge launch, inside a
four-minute dependency-check limit (plus bounded termination). These are maximum
waits, not mandatory delays. Logs record native process-creation time and driver,
browser and page stages. Timeouts are reported separately from missing/broken tools;
they do not by themselves establish corruption or an antivirus problem. Preserve
the original failure logs when repeating tests after reinstall or checkpoint restore.
The unsigned private package and any security prompts must be recorded accurately.
Developer app-image self-check is useful before VM testing but is not clean-machine
installation/uninstallation acceptance. Public notices/source delivery remains separate from this private functionality test.

## Edge migration acceptance

The previous private candidate used a bundled browser. Its VM results are retained
in private historical evidence and do not qualify a different artifact.

The Edge-based candidate was checked on 2026-11-17 in the existing Windows 11
Enterprise Evaluation 26H2 VM (build 26300.9974), with installed Edge Stable
157.0.4341.60 and standard user SBTester. Installer SHA-256:
`20da37f60360ff29698911d4ad4c4ea65ff2f319a53eab443ccf9f8dbfe6012c`.

| Check | Result |
| --- | --- |
| Remove previous app and install Edge candidate | Passed; existing output preferences and downloaded files retained. No external Java, Gradle, FFmpeg or FFprobe on PATH. |
| First and repeated EXE self-check | Passed in about 28 and 10 seconds; absent Playwright browser cache and intentionally invalid external media path did not affect bundled tools. |
| Installed payload and shortcuts | All 288 files matched the manifest; bundled browser absent; desktop and Start Menu shortcuts present. |
| Discovery, preview and download | Public hls.js/Mux Big Buck Bunny sample exposed five qualities. Selected 320x184; preview 5.016 seconds, full video 634.633 seconds, H.264/AAC stereo. Both files decoded completely; first 20 compressed packets of each track matched between preview and full file. |
| Cancellation | Discovery and active download cancelled; workers stopped, no partial media remained, completed output unchanged. |
| Uninstall/reinstall Edge candidate | Passed; all existing tested settings and Downloads files retained. |
| Guest reboot and EXE self-check | Passed in about 12 seconds through SSH without a new desktop login; payload and saved data unchanged. |

This was a replacement/reinstallation in an existing test VM, not a restored
pristine checkpoint. Installation ran as an interactive standard-user scheduled
task dispatched through SSH. A direct SSH-token MSI attempt returned 1601 with
access denied before uninstalling anything; the interactive-user attempt passed.
No host desktop input or screen capture was used. Media checks exercised the
installed JAR/runtime and normal authenticated API, not a rendered-UI acceptance
test. Temporary execution-policy override was limited to private test-script
processes; machine execution policies were not changed.

Still repeat installation/startup without Internet from the clean checkpoint with
Edge installed, and check the graphical installer and normal shortcut/UI flow for
this candidate. Acceptance of the exact final release artifact remains required.

Also verify missing Edge produces an actionable failure, with no browser download,
personal-profile use or weaker fallback. Edge policies may prevent automation;
report the error rather than changing system policies. Verify the installed Edge
version and record it with acceptance evidence because it updates independently.
