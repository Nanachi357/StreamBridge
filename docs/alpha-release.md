# Alpha.1 candidate packaging

This procedure prepares **local, unpublished** `v0.1.0-alpha.1` candidates for
Windows 11 x64. It does not create a tag, release, upload or installation. Chrome
Stable or Edge Stable is required on the recipient's machine; Java and the owned minimal FFmpeg
runtime are included. Browser redistribution is outside this package.

## Installer

Use the same pinned JDK, WiX and complete FFmpeg inputs as the
[private installer procedure](private-installer.md). From a clean checkout:

```powershell
./scripts/Build-AlphaInstaller.ps1 -JdkDirectory C:/build-tools/jdk-21.0.13+9 -WixDirectory C:/build-tools/wix314 -FfmpegBundleDirectory C:/inputs/streambridge-ffmpeg
```

For local pre-commit review only, `-AllowUncommitted` permits a dirty checkout and
records that fact. Such a candidate is not the final release artifact. Output is
under `backend/build/alpha-candidate/`; `latest-path.txt` identifies the folder.
No existing installation or developer shortcut is changed.

Public installation uses `%LOCALAPPDATA%/StreamBridge App`, Start Menu group
`StreamBridge`, and the stable UpgradeCode in `Get-InstallerProfile.ps1`. Private
test installers retain their previous, distinct identity. The application folder
is separate from `%LOCALAPPDATA%/StreamBridge` user data. Preferences still live
in the existing user-profile data directory. Close/uninstall a private test
installation before public acceptance to avoid two launchers using the same data.
Uninstall retains completed videos and user-profile settings/diagnostics.

The display/asset/JAR version is `0.1.0-alpha.1`; Windows Installer's numeric
version is `0.1.0`. Increase the numeric version for every later distributed
installer, including another alpha; do not change the public UpgradeCode or reuse
the same numeric version for a different upgrade. Windows version and SemVer label
are separate fields. The candidate is unsigned; signing is not claimed.

The builder verifies all embedded MSI files, current-user installation scope,
installation directory, vendor, numeric version and UpgradeCode without executing
the installer. Original third-party notices are retained; JAR notices are also
extracted and indexed by artifact/entry/hash under `app/licenses/jars`.

## Companion materials

Use the already selected component versions, not a new dependency audit. Obtain
these exact Java inputs from the official [Temurin release](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9):

- `OpenJDK21U-jdk-sources_21.0.13_9.tar.gz` (full upstream sources).
- `OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip.json`, saved as `temurin-build.json`.
- [Temurin build scripts at the recorded revision](https://codeload.github.com/adoptium/temurin-build/tar.gz/1cf43d7a3e77886bfea7d550a902e151a87d724d), saved as `temurin-build-1cf43d7.tar.gz`.

`Verify-AlphaJavaInputs.ps1` pins their SHA-256 and binary/build metadata. The
source and metadata hashes are from the upstream release API; the script archive
hash is a recorded digest at the metadata's build revision, not a signature.
Also obtain [WiX sources at wix3141rtm](https://codeload.github.com/wixtoolset/wix3/tar.gz/refs/tags/wix3141rtm),
commit `b40e9a32c24033e11b77baf2c91a704382f898ed`, saved as
`wix3141-source.tar.gz`; its recorded SHA-256 is checked during preparation.
Do not put downloaded/generated assets in Git.

```powershell
./scripts/Prepare-AlphaMaterials.ps1 -CandidateDirectory C:/candidates/alpha1 -FfmpegBundleDirectory C:/inputs/streambridge-ffmpeg -JavaMaterialsDirectory C:/inputs/java-materials -WixSourceArchive C:/inputs/wix3141-source.tar.gz -OutputDirectory C:/candidates/alpha1-materials
```

Use a fresh output outside input directories. The script verifies the candidate
EXE/payload hashes, current runtime recipe, Java materials and exact FFmpeg bundle.
It matches all eight installed FFmpeg files to that bundle. It then prepares
`release-files/` with an EXE, Java and FFmpeg material archives, WiX sources, an
installed-file manifest, a path-free release record and `SHA256SUMS.txt`.
Checksums detect mismatches; they are not publisher signatures or legal approval.
The Java archive retains upstream build metadata (including upstream build paths),
the full native/Java source, build scripts and StreamBridge's jlink recipe. We do
not claim a local Temurin rebuild or bit-for-bit reproducibility.

The FFmpeg archive preserves the complete verified bundle, including source,
configuration, recipe, notices and development files; it also retains the matching
runtime. These material downloads are optional for ordinary application use.
Publish and retain them alongside their matching binary, not only as expiring CI
artifacts. The installed README and THIRD-PARTY.txt point at the same release page.

## Exact-artifact acceptance before publication

1. Review/commit the packaging and materials changes, with passing CI. Build the
   final candidate from that clean commit; prepare its companion files.
2. Verify the final checksums, notices/material contents and absence of personal
   data. Upload only the explicitly reviewed `release-files` contents, never the
   whole build directory, private build record, test logs or VM evidence.
3. Restore the clean Windows checkpoint with a supported browser installed. Test standard-user
   offline installation and **first** startup. Then test online discovery,
   preview/download/cancellation, saved folder, reboot, uninstall/reinstall and
   preservation of existing user data. Verify discovery in both Chrome and Edge.
   Record the exact installer hash and browser versions.
4. Check the graphical installer and shortcut/UI flow. Keep Defender/OS protections
   enabled; document actual prompts for the unsigned EXE. Use the
   [existing acceptance steps](private-installer.md#acceptance-on-a-clean-vm).
5. Finalize the [release note draft](releases/v0.1.0-alpha.1.md) and actual asset
   links. Obtain a separate publication decision. Create the prerelease/tag and
   upload only after that authorization. Neither script performs this step.

Earlier private/Edge VM runs and developer EXE checks are useful evidence but do
not replace acceptance of the final public-identity installer.
