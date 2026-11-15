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
The unsigned private package and any security prompts must be recorded accurately.
Developer app-image self-check is useful before VM testing but is not clean-machine
installation/uninstallation acceptance. Public notices/source delivery and browser
redistribution gates remain separate from this private functionality test.
