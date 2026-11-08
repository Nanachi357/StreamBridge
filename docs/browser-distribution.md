# Browser distribution candidate

Status as of 2026-11-07: the original ungoogled candidate is blocked for public release because
coverage of published Chromium security fixes has not been demonstrated.
License/material gates also remain open. This document records
release gates, not a blanket legal conclusion about Chromium or its upstream.

Patched replacements have now passed private Windows compatibility checks (see
below). None is yet approved for inclusion in the public installer.

The independently recordable Hibbiki payload facts are now in
[candidate provenance](browser/hibbiki-provenance.md), with a machine-readable
file manifest. The [current material review](browser/hibbiki-materials.md)
records the later Lighthouse/axe-core coverage gap, its reproduced inlining
transformation, runtime conditions and
remaining release requirements. The older source subset and aggregate-credit
counts below must not be read as complete Windows source coverage.

## Original candidate (blocked)

- ungoogled-chromium Windows x64 `156.0.8078.79-1.1`.
- [Upstream release](https://github.com/ungoogled-software/ungoogled-chromium-windows/releases/tag/156.0.8078.79-1.1),
  ZIP SHA-256 `a97c5378e16075967660f85cc34150444a9cfbc79733f048b9c6b42a851e4eb9`.
- Windows recipe commit `8492884eb30fdd9417ea7816aa92def805c97c74`;
  ungoogled core commit `28a211ab4ab6b83d919966a4f58a6df23cf4fb29`.
- [Windows x64 build](https://github.com/ungoogled-software/ungoogled-chromium-windows/actions/runs/41098495420)
  records the release tag, Visual Studio 2022 Enterprise environment and Windows
  SDK 10.0.28000.0 setup. The first stage reports VS developer prompt 17.14.43;
  this does not establish every compiler/runtime version across all stages.
- Original inventory: 75 files. StreamBridge staging excludes only
  `d3dcompiler_47.dll`, leaving 74 candidate browser files. An original ZIP
  embedded inside an installer would defeat that exclusion.

## Security disposition

Do not ship `156.0.8078.79-1.1` or use it for arbitrary untrusted browsing on the
strength of passing application tests. Google's
[2026-11-03 desktop advisory](https://chromereleases.googleblog.com/2026/11/stable-channel-update-for-desktop.html)
announces fixes in Chrome `157.0.8103.38/.39`. Published CNA records include
critical [CVE-2026-121156](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/121xxx/CVE-2026-121156.json)
in Dawn and [CVE-2026-121126](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/121xxx/CVE-2026-121126.json)
in Core, describing possible execution outside the sandbox before
`157.0.8103.38`. This candidate predates that version; corresponding backports
have not been established. Exploitation and exact feature reachability in this
specific binary have not been demonstrated. The advisory's total fix count is
not a count of confirmed exploitable vulnerabilities in this Windows package.

Select a replacement containing the applicable fixes, and check advisories again
at selection and release time; `157.0.8103.38` is not a permanent safe-version
threshold. Record its exact payload hashes, provenance, source/material versions
and distribution terms. Repeat browser-specific sandbox/network, lifecycle,
discovery, preview/download and packaged-launch checks for that replacement.
Existing source inventories remain evidence about the old candidate only.

Packaging currently supports private engineering validation. It does not enforce
license approval, validate an approved browser payload manifest, or constitute a
security release gate. Before public release, establish a separate release check
that binds the exact payload to the reviewed provenance/materials and records
the security and recipient-terms review; successful packaging alone is insufficient.

## Replacement evaluation, 2026-11-06

Fresh release APIs were checked; cached search results or a newer ungoogled core
source tag must not be mistaken for an available Windows binary.

| Candidate | Exact evaluated payload | Result |
| --- | --- | --- |
| [Hibbiki Chromium](https://github.com/Hibbiki/chromium-win64/releases/tag/v157.0.8103.39-r1717349) | `157.0.8103.39`, `chrome.7z`, SHA-256 `0eaffa4b739d61d30146ece50db97f49a64871b8cf61333572729f9d1879379d` (matches GitHub asset digest) | Recommended next engineering candidate; compatibility passed, distribution gates remain open. |
| [Chrome for Testing Stable](https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json) | `157.0.8103.38`, Windows x64 ZIP, locally measured SHA-256 `ae0deef27d449e1d7529df7705d609bab102de48464da13567ed3dd2ee1a0586` | Compatibility passed; do not choose for bundled redistribution without an applicable binary distribution grant. |
| ungoogled Windows | Latest published Windows release checked: `156.0.8078.79-1.1`; core source `157.0.8103.38-1` has no Windows payload | Remains blocked. Re-evaluate a new Windows release when available. |
| macchrome Winchrome | Latest checked release `156.0.8078.84` | Predates the fixes; no applicable backport evidence established. Not executed. |

The two evaluated binaries meet the version threshold for the cited Core/Dawn
fixes. This is version/provenance evidence, not an independent reproduction of
either binary, an exploit test, or assurance against all CVEs.

Using the unchanged application at `61ef2401fc4f0a48d427218b98d779f92968183d`,
Java Playwright 1.64.0, private browser caches and an isolated test profile:
each candidate passed 272 Windows Java tests (270 repository tests plus two
private network audit tests), with no failures or skips. The 19 UI tests passed.
Tests covered discovery/lifecycle, preview/media, cancellation, network policy
and direct WebRTC checks; all browser runs were headless on controlled fixtures.
This does not establish visible-window focus behavior, all-site compatibility
or a clean-machine installation.

A separate Hibbiki app-image built with the existing packager passed the hidden
EXE self-check using its private Java runtime and bundled Chromium. All 245
browser files matched the source payload after excluding `d3dcompiler_47.dll`;
the versioned DLL subdirectory was preserved. Media tools were supplied from
the separately verified external FFmpeg kit. This was not a self-contained
public installer and did not replace the installed application or shortcut.

### Hibbiki-specific distribution gaps

- Recipe commit `1e8c8123309052bbbdc7877cbfc090949e2d573a`; reported Chromium
  revision `9a31f0acec1854d44e49a73fbf8017598151a583` on branch 8103.
  The archive digest agrees with its GitHub release metadata, but `chrome.exe`
  is unsigned and the binary has not been independently rebuilt.
- This is not ungoogled Chromium. The recipe enables Google API integration,
  proprietary codecs and Widevine support. No `widevinecdm.dll` was found in
  this archive; support flags alone do not establish an embedded CDM. Background
  service/privacy behavior was subsequently checked under the application policy
  described below; signed-in behavior and all production sites remain untested.
- Chromium's BSD license is only part of the distribution terms. The captured
  `chrome://credits` contains 487 notice blocks, not a complete Windows SBOM.
  Its `chrome://terms` placeholder is not an affirmative distribution grant.
  Complete the new version's component/source/build/relink materials and actual
  recipient terms, including the MPL 1.1 gate below. Old 156 source inventories
  do not certify this different 157 build.
- The pinned FFmpeg fork is `5c85ff666b3434d9323b83437f910ad8431ab7c9`;
  its checked Chrome/win/x64 configuration selects LGPL 2.1-or-later with GPL,
  GPLv3 and nonfree disabled. This is build-input evidence, not a link-map proof.
  Browser FFmpeg remains distinct from StreamBridge's own external FFmpeg kit.
- Dawn `3c24c91e70eaf701ae92ab8cc414669cc4c38892` pins the same DXC source
  revision as below, but the new `dxcompiler.dll` hash is
  `69dba914c8d1e9b1d6bedb5ec05dfed865bdd2417d4477fd0b9504dd8516596f`.
  Retain its full NCSA/third-party notices; do not inherit the old binary hash.
- Upstream README names Visual Studio 2026 while committed GN arguments still
  set `visual_studio_version = "2022"`. Follow-up source inspection explains why
  these do not establish a conflict: when `visual_studio_path` is empty,
  [the pinned GN file](https://github.com/chromium/chromium/blob/157.0.8103.39/build/config/win/visual_studio_version.gni)
  overwrites that value with auto-detection; `vs_toolchain.py` prefers an installed
  VS 2026. Hibbiki sets `DEPOT_TOOLS_WIN_TOOLCHAIN=0` and does not pin the VS path.
  Exact toolset/SDK, edition and distribution authorization still need build-specific
  evidence. The [VS 2026 terms](https://visualstudio.microsoft.com/license-terms/vs2026-ga-pro-enterprise/)
  provide a distribution route with conditions, not an unconditional exemption.
- No root license was found for the recipe repository at this commit. The exact
  payload review below narrows which changes affect our runtime and prepares a
  source route without copying the original automation. A missing root license
  alone is not a blanket prohibition on Chromium binary redistribution.

Chrome for Testing's actual `chrome://terms` points to the
[Chrome executable terms](https://www.google.com/chrome/terms/) and
[Google general terms](https://policies.google.com/terms). The Apache license of
the CfT tooling repository does not by itself license the downloaded browser
binary. No sufficient onward-distribution grant was established in this review.

### Hibbiki follow-up: runtime, recipe rights and background traffic

The source's non-component desktop configuration selects static CRT. The archive
does not contain a separate VCRuntime/MSVCP redistributable DLL; absence of such a
file does not remove the obligations for code linked into the browser. Microsoft's
development terms provide a downstream distribution route as part of an application,
with requirements protecting the Distributable Code and conditional indemnity.
The upstream licensee's obligations must not be silently attributed wholesale to
every downstream user. Establish the actual downstream authorization and recipient
terms. The runtime end-user agreement and the removal of the SDK D3DCompiler DLL
do not independently resolve this. Do not bundle the Visual Studio SDK/toolchain
as though Chromium's license covered those tools.

The pinned Hibbiki repository has no root LICENSE; its original scripts and four
patch files contain no explicit license grant located in this review. Patched
Chromium files retain BSD headers and the patches have Signed-off-by lines, but
neither fact alone proves the author's grant for every original contribution.
All four patches applied in order to the matching 157 source files in a private
check; this proves applicability, not redistribution permission or a full build.
The subsequent exact-payload review below narrows this initial broad concern:
setup/updater implementations and policy templates are not distributed, while
the shared identifier change is represented independently against Chromium's
licensed source. The proposed kit omits original Hibbiki automation. Contacting
the author is not a prerequisite imposed by this plan, and no message was sent.
Do not infer a general license for those original scripts from public visibility;
see [GitHub's licensing guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository).

Background Google traffic was observed with a fresh, unauthenticated temporary
profile and no external page loaded. With StreamBridge's launch settings plus
NetLog instrumentation, `accounts.google.com/ListAccounts` returned HTTP 200;
the proxy also saw connections requested to `www.google.com`. Page request events
did not expose this browser-internal traffic. A second run navigating only the
public `example.com` control and a third run removing Playwright's
`--disable-background-networking` default also returned HTTP 200 from ListAccounts.
Each observation lasted 60 seconds after page setup. A fourth 60-second run called
the unchanged `ChromiumLaunch.launch` factory without NetLog flags and reproduced
the accounts/www.google.com proxy destinations. No personal Chrome profile or
Google sign-in was used; this does not establish leakage of user account data.

NetLog also recorded Cloudflare DoH attempts ending in error -105 (name resolution
failure); these must not be described as successful external requests. The successful
Google request used the configured proxy: this is background public traffic, not a
demonstrated private-address policy bypass. Public-address protection is not a
tracker blocker. The background-networking switch alone did not suppress this
traffic, and blocking every Google domain could break user-requested sites.
Before adopting Hibbiki, define and verify the desired background-traffic policy
and candidate-specific suppression. No product launch flags were changed here.
These short headless runs do not cover visible startup, all delayed services,
signed-in use, persisted profiles or every protocol/process on Windows.

### Initial background suppression experiment (before application integration)

Private Windows tests of Hibbiki `157.0.8103.39` with Playwright 1.64.0 found
three targeted controls, preserving sandbox, proxy and existing Playwright defaults:

- Enable `SkipListAccountsWithoutGaiaCookies`. The pinned
  [account-cookie implementation](https://github.com/chromium/chromium/blob/157.0.8103.39/components/signin/internal/identity_manager/gaia_cookie_manager_service.cc)
  skips ListAccounts when the browser's Gaia cookie list is empty. It still permits
  the request when cookies exist; this is not a universal Google account-service ban.
- Disable `PreconnectToSearch`. The pinned
  [search preconnector](https://github.com/chromium/chromium/blob/157.0.8103.39/chrome/browser/navigation_predictor/search_engine_preconnector.cc)
  stops speculative connections to the default search provider. Explicit navigation
  remains available; the intended trade-off is loss of this latency optimization.
- Disable `AutofillServerCommunication`. The pinned
  [autofill feature](https://github.com/chromium/chromium/blob/157.0.8103.39/components/autofill/core/common/autofill_debug_features.cc)
  controls server-assisted form predictions. Opening Google's sign-in page exposed
  this additional request; disabling it removed the observed autofill-server traffic.
  This may reduce autofill assistance, not provide a guarantee about every login flow.

Separate 40-second baseline/single-control runs isolated ListAccounts and search
preconnection. A 120-second blank-page run with both controls, and another with
all three, recorded no proxy destinations or socket-send events in NetLog. Normal
Google sign-in-page navigation and an example.com control returned HTTP 200 with
all three controls. Page-requested Google resources remained reachable. Nobody
signed in, supplied credentials or used a personal browser profile.

With all three controls, 273 Windows Java tests passed without skips: 270 repository
tests, two private network audit tests and one extra cookie/CORS/redirect/iframe
manifest-discovery test. The 19 UI tests passed. The two-control suite also passed.
The private launch-options unit test was adapted to expect the intentional feature
arguments, while retaining sandbox and no-fallback assertions. The first run with
its old null-arguments expectation failed; it was not a website compatibility failure.

Remaining NetLog entries included failed Cloudflare DoH attempts (-105), and UDP
connect calls to a Google IPv6 address ending in -109 with no send events. The pinned
[resolver implementation](https://github.com/chromium/chromium/blob/157.0.8103.39/net/dns/host_resolver_manager.cc)
uses that address for a local route/address reachability check; the inspected path
does not send a payload. These entries must not be reported as successful external
requests, nor do these observations substitute for a system-wide packet capture.

This is evidence for fresh-profile headless scenarios, not all-site, signed-in,
visible-window, long-running or clean-Windows acceptance. The user-supplied video
sites were not retested in this experiment. At that stage, no product runtime flags,
installed package or shortcut changed. The subsequent integration below adds an
explicit tested contract for the effective Playwright feature lists; regression
checks remain necessary on browser/Playwright upgrades. Distribution/material
gates remain open after adopting these engineering controls.

### Application integration

The shared `ChromiumLaunch` now applies the three controls above in discovery and
dependency checks, in both visible and headless configurations. This does not
replace the installed browser payload or approve Hibbiki for redistribution.

`ChromiumFeaturePolicy` appends the controls to the reviewed Playwright 1.64.0
feature defaults. The Java API does not expose default arguments; Chromium uses
the last occurrence of a feature switch. A versioned contract is therefore explicit:
the portable test reads the actual bundled driver's Chromium switch module and
compares its defaults and conditional screenshot setting. Runtime rejects a missing
or different driver version rather than silently applying a stale default list.
This deliberately requires review on Playwright upgrades. Other Playwright default
arguments are not filtered or disabled; sandbox and proxy restrictions remain.
The `PLAYWRIGHT_LEGACY_SCREENSHOT` environment override retains the driver's
nonempty-string semantics, including the value `0`.

The browser integration test checks the last effective feature switches in
`chrome://version`, not merely the presence of names in Java launch options.
A controlled two-origin test exercises credentialed CORS, an HttpOnly cookie,
HTTP redirect and iframe manifest discovery under this policy. No real account
credentials or production-site authentication are part of that fixture.

Integration verification on 2026-11-06 passed 274 Windows Java tests, 234 portable
Linux tests (34 expected skips), 19 UI tests and a private Hibbiki EXE self-check.
An additional 60-second blank-page observation through the product factory saw
no proxy destinations; subsequent deliberate Google sign-in-page/example.com
navigation succeeded without an autofill-server destination. These checks retain
the scope limits above; no visible window, real account login or clean VM was tested.

### Exact runtime payload and source preparation (2026-11-07)

The selected `chrome.7z` expands to 246 files. StreamBridge's candidate contains
245 files after excluding the SDK `d3dcompiler_47.dll`: 4 EXEs, 7 DLLs, 223 PAKs
and 11 other files. It excludes Hibbiki's separate installer and policy-template
archives. The exact input archive hash is recorded in the candidate table above.

The four published patches do not all affect the delivered runtime. Patches 0001
and 0003 concern setup; patch 0004 concerns policy-template generation. Patch 0002
also changes the shared `chromium_install_modes.h` application GUID, which is
present in `chrome_elf.dll` and `notification_helper.exe`. Thus omitting the updater
does **not** omit every effect of that patch. An independently prepared minimal
delta against Chromium's BSD-licensed header reproduces that GUID change; its
resulting Git blob is `94f17357b8eb19802f08d5b2baf3a7997e459d67`.

The proposed material kit preserves all 487 ordered `chrome://credits` blocks,
including repeated names, Chromium's license, the published GN settings as data,
the narrow GUID delta and a payload hash manifest. It does not relicense or copy
Hibbiki's original automation. The missing root license in that repository is not
by itself evidence that the entire Chromium runtime cannot be redistributed.

The exact [official Chromium source archive](https://storage.googleapis.com/chromium-browser-official/chromium-157.0.8103.39.tar.xz)
was downloaded (4,513,989,004 bytes), with SHA-256
`48cd9e743d3cbd8b66369ecf0c4c468980c7c73a1999aa8b5f7a8f304b76b782`.
Its MD5 matches official object metadata; no detached signature was verified.
The complete archive was inventoried and a subset of 56,626 regular source files
was extracted and individually hashed. Sampled Git blobs match the exact version,
DEPS, original GUID header, DXC license and Blink notice. The subset contains
FFmpeg, Blink, DXC, Mozilla components, Hunspell, Eigen, gRPC, DevTools axe-core,
hyphenation patterns and twelve Symphonia crates. It is not all source needed
to rebuild `chrome.dll`, nor a binary/source reproducibility proof.

The official archive omits Hunspell dictionary contents. The matching dependency
archive was obtained separately at `cee14e319bb7603a1157bb4d1e216be64ee82b77`;
it contains source dictionaries, affix files, deltas, generated dictionaries and
per-language notices. Liblouis's Chromium wrapper/build files and nested source
at `9700847afb92cb35969bdfcbbfbbb74b9c7b3376` were also collected. Its Chromium
metadata selects **LGPL-3.0**, distinct from browser FFmpeg's LGPL-2.1-or-later.
Its prebuilt Wasm and the dictionary resources still require Windows payload
mapping; aggregate credits alone do not establish their inclusion.

The full official archive includes build tools and sysroots; it is not approved
for blanket rehosting as a supposedly source-only kit. Supplemental archives also
contain generated resources. Final source/build-input coverage and distribution
scope remain open. A separate material download need not be installed on an end
user's computer. No Chromium compilation was performed.

### Runtime terms: downstream distribution route

[Microsoft's guidance](https://www.microsoft.com/licensing/guidance/Visual-Studio)
allows distribution of linked output containing distributable static-library
code; the `.lib` inputs themselves cannot simply be handed out. The
[VS 2026 agreement](https://visualstudio.microsoft.com/license-terms/vs2026-ga-pro-enterprise/)
allows licensees to authorize application distributors, subject to requirements
including protective recipient terms. The runtime end-user agreement alone is
not a separate redistribution grant. A declared tool version establishes neither
all applicable permissions nor their absence. No conclusion that a downstream
distributor must purchase Visual Studio or inherit every builder obligation follows.

For this candidate, the remaining work is to record the applicable permission and
recipient terms, keep them scoped to Microsoft code, and preserve LGPL/MPL rights.
A draft component notice is prepared, but is not a finished runtime license or
installer agreement. The final terms must also satisfy the MPL 1.1 review below.
This check does not mandate correspondence or a local Chromium build.

### Required corresponding-source/material kit for this candidate

Use a versioned material release alongside the binary, with a manifest binding
payload hashes, source revisions and selected license terms. The current collected
metadata and credits are review evidence, not this completed kit.

1. Preserve exact Chromium sources, nested DEPS repositories, vendored Rust crates,
   generated input data and all applicable modifications. Pin depot_tools, GN,
   Clang/LLVM, Rust, Python packages, toolchain and SDK requirements. Preserve the
   Hibbiki GN arguments, patch order and authorized build/packaging scripts.
   Its provisioning script refers to an unversioned `internal` directory and
   checkout installs unpinned pywin32; document a lawful, usable reconstruction
   without redistributing private API credentials. Do not assume those omissions
   make all source delivery impossible, or that recipients need original secrets.
2. For browser FFmpeg (LGPL 2.1-or-later) and the applicable Blink/WebKit Library
   GPL/LGPL portions, supply the exact library source and modifications plus the
   work needed to rebuild/relink the browser with modified libraries under the
   selected license route. LGPL 2.1 section 6(a) permits object code and/or source;
   a usable source route is possible. A bare repository link, notices alone or
   StreamBridge's external FFmpeg kit is insufficient. Preserve modification and
   reverse-engineering-for-debugging rights required by the applicable license.
   If using section 6(d) for downloads, offer equivalent access to the materials
   from the same place; a section 6(c) written offer instead has a three-year term.
3. For MPL-covered files, retain their corresponding sources, modifications,
   notices and change records. Rechecked 157 build paths identify NSPR, Mozilla
   Windows helpers, URL parser and conditional Windows accessibility/Hunspell
   inputs. Account separately for Eigen, gRPC's covered portions, the twelve
   Symphonia crates, DevTools axe-core and relevant dictionaries/hyphenation data.
   Preserve per-file alternative licenses; MPL 1.1 and MPL 2.0 obligations differ.
4. Generate the Windows target/dependency and packaged-resource inventory. The
   captured credits contain 34 notice blocks mentioning LGPL/Library GPL or MPL,
   including other-platform material and exceptions: this is not 34 proven linked
   Windows libraries. The pinned `tools/licenses/licenses.py` supports
   `--target-os`, `--gn-out-dir` and `--gn-target`; use the configured build graph
   and account for generated/copied resources too. Archive the actual relevant
   source paths and retain non-copyleft third-party notices as well.
5. Document and check the chosen rebuild/relink route and its prerequisites.
   Rebuilding with a harmless library change would be strong engineering evidence,
   but a distributor's own compilation and bit-identical output are not universal
   LGPL requirements. No Chromium compilation is authorized for this evaluation.
   Check source completeness and usable build instructions without claiming a
   tested rebuild. Preserve externally obtainable proprietary build-tool
   prerequisites and their terms without distributing unauthorized tools.
6. Put readable notices and exact material-download locations in the delivered
   package, retain releases durably, and complete the runtime and MPL recipient-term
   checks below. MIT remains the license of StreamBridge's own code, not a blanket
   license for the browser. The installer must not override recipients' component
   source rights or promise support/warranties on upstream contributors' behalf.

## Original candidate: component decisions and open evidence

| Component | Established evidence | Remaining release gate |
| --- | --- | --- |
| `d3dcompiler_47.dll` | Excluded; launcher checks the system component. | Confirm behavior on clean supported Windows. |
| `dxcompiler.dll` | Dawn pins DXC `9757d44faa47de7e8d1bb731c30551ec608624f9`; source/GN recipe and credits agree. NCSA and additional third-party notices collected. | Include the complete notices in the final package; retain provenance. The binary was not independently reproduced. |
| Chromium, ungoogled, ANGLE, Dawn, ICU, SwiftShader, Vulkan loader | Source metadata, upstream patches, major notices and file hashes collected. | Finish the Windows-specific transitive component/notice mapping. Top-level licenses alone are insufficient. |
| Embedded FFmpeg and Blink LGPL portions | Browser configuration/source metadata identify LGPL material; FFmpeg is embedded, not a separately replaceable DLL in this archive. | Provide matching source, modifications, build/relink materials and recipient instructions for the selected compliance route. |
| MPL components | Matching source and notices collected for the identified Mozilla, Hunspell, Eigen, axe-core and Symphonia candidates; Windows build conditions checked for a subset below. | Complete the Windows dependency/resource graph and deliver the applicable source and notices. |
| Microsoft runtime code | Non-component desktop configuration selects static CRT; upstream uses Visual Studio Enterprise. Microsoft documents distribution of linked static-library output. | Establish the applicable downstream grant and recipient terms for this upstream runtime code, not just the removed SDK DLL. |

DXC and the Windows SDK D3DCompiler are different components. The pinned
[DXC license](https://github.com/microsoft/DirectXShaderCompiler/blob/9757d44faa47de7e8d1bb731c30551ec608624f9/LICENSE.TXT)
permits redistribution with notices and contains additional third-party terms;
do not classify the entire DLL as GPL merely from an autoconf-exception entry.
Its binary SHA-256 is
`6bd8f381683820394c2962f22aa3c05ec5920fb453810afb135a6afd736e8775`.
Its embedded Microsoft product name is not evidence that the SDK DLL license
applies to it.

The [Visual Studio 2022 license](https://visualstudio.microsoft.com/license-terms/vs2022-ga-proenterprise/)
contains a route for third-party distribution and associated distribution
requirements, including recipient protections and an indemnity clause with an
exception for claims based solely on Distributable Code. The
[redistribution list](https://learn.microsoft.com/en-us/visualstudio/releases/2022/redistribution)
is subject to those terms. Their precise application to this upstream browser
and its downstream packaging remains to be documented; neither a blanket ban
nor exemption is established here. StreamBridge's MIT disclaimer does not
resolve third-party requirements by itself. Do not silently accept additional
distributor commitments during packaging.

### Microsoft runtime: what is and is not resolved

Microsoft's [licensing guidance](https://www.microsoft.com/licensing/guidance/Visual-Studio#how-certain-software-can-be-distributed-to-others-within-your-applications)
expressly distinguishes distributable `.lib` code linked into an application
from redistribution of the library file itself. Its
[deployment documentation](https://learn.microsoft.com/en-us/cpp/windows/deployment-in-visual-cpp?view=msvc-170)
also describes static linking as a deployment method. Static CRT use alone is
therefore not a reason to reject the candidate.

The separate [2015-2022 runtime end-user license](https://visualstudio.microsoft.com/license-terms/vs2022-cruntime/)
grants installation/use rights and restricts onward distribution. It is not a
substitute for the development/distribution grant. Conversely, the VS agreement
allows its licensee to authorize distributors of its applications. Document
that downstream route and the terms passed to recipients; do not infer that a
downstream packager automatically accepts every obligation of the upstream VS
licensee, or must buy Visual Studio solely to redistribute the finished browser.
The upstream license channel is not established merely by an Enterprise path
in a build log: subscription/product terms may also be relevant.

The [CRT reference](https://learn.microsoft.com/en-us/cpp/c-runtime-library/crt-library-features?view=msvc-170)
separates UCRT, VCRuntime and startup code. Even dynamic UCRT linkage retains
statically linked startup code. The system D3DCompiler policy does not remove
these components or establish their distribution terms. The current evidence
establishes the build's `/MT` policy, not an exact inventory of every linked
Microsoft object in every shipped binary.

## Source and notice delivery

Retain the official Chromium source archive, the exact Windows/core patch
archives, required dependency sources and build inputs. The collected lite
archive SHA-256 is
`d9e8b637dbdd3a1cccb4966e81a66e3f3c4c29f5ce9732a70f5a4f949cd03923`.
Upstream actually used its clone-based build path. Its `--tarball` path disables
PGO, so that path alone is not evidence of reproducing the released configuration.
This difference is not by itself proof that a source-based LGPL compliance route
is unavailable. No independent full browser rebuild has been performed.

Chromium's embedded FFmpeg revision is
`9db86ce5b5b454dfc96c435f9f0723012980c37f`. The separately built StreamBridge
FFmpeg 9.0.2 materials do not cover this browser component.

The archive was read through again and its SHA-256 rechecked. It contains
11,114 regular files under `third_party/ffmpeg`, 20,986 under `third_party/blink`,
and 98 under `third_party/hunspell`. These counts include supporting files;
they establish archive contents, not completeness of a working rebuild kit.
Both pinned recipe archives are retained and hashed. A private evidence catalog
maps 36 cross-platform shipped-license candidates to source roots and notice
files, including the actual vendored source for 12 Symphonia Rust crates.

### LGPL delivery route

For embedded FFmpeg, LGPL 2.1 section 6(a) permits the work that uses the library
in object and/or source form, with the required library source, modifications
and material sufficient to rebuild/relink with a modified library. Section 6(d)
allows equivalent access to the specified materials at the same download place.
Plan a versioned source/material download alongside the browser binary, with
local instructions and license notices. Do not substitute an unfulfilled written
source offer or a short-lived CI artifact.

This route need not inherently include proprietary compiler libraries, a separate
archive of every original `.obj`, or yield a byte-identical original executable.
It does require usable and complete materials for the chosen route; applicable
compiler/system-component exceptions and rights to any bundled build inputs
still need to be checked. Byte-identical reproduction is a separate supply-chain
check, not the sole license-compliance criterion.

Keep the matching Chromium FFmpeg configuration/patches, Windows/core patches,
build scripts and required generated inputs. Validate that a recipient can
modify the LGPL component and build/use the resulting browser. Retain the
LGPL-required permission to modify for personal use and reverse-engineer to
debug those modifications in any applicable binary terms. The shared-library
replacement route does not describe this embedded browser FFmpeg.

Blink's aggregate notice includes the older GNU Library GPL version 2 text;
FFmpeg uses LGPL 2.1-or-later. Preserve the actual per-file licenses and applicable
version choices rather than relabeling every Blink file LGPL 2.1.

### Windows applicability checked so far

| Component | Checked source/build evidence | Status |
| --- | --- | --- |
| NSPR time code, MPL 2.0 | `base/BUILD.gn` includes `prtime.cc`/`.h`; matching six-file source root and notice present. | Retain in source/notice materials. |
| Mozilla Windows certificate helpers, MPL 2.0 | `net/BUILD.gn` includes `mozilla_win` files under `is_win`; seven-file root and notice present. | Retain. |
| Mozilla URL parser, BSD/MPL 1.1 metadata | `url/BUILD.gn` includes `url_parse.cc`/`.h`; four-file root and mixed notice present. | Retain the actual notices; do not discard MPL solely from a BSD label. |
| Hunspell, MPL 1.1 selection | Renderer spellcheck includes Hunspell on non-Android platforms; Windows enables renderer spellcheck. Source, `google.patch`, `COPYING.MPL` and Chromium modification notes are present. | Retain with modifications. |
| ISimpleDOM, MPL 1.1 | Windows native-accessibility branch depends on it; its build file asserts Windows and lists three IDL inputs. Eight-file root and notice present. | Retain under the applicable accessibility configuration. |
| Mozilla NSS certificate helpers, MPL 1.1 | `net/BUILD.gn` gates them on `use_nss_certs`; `crypto/features.gni` defaults this to Linux/ChromeOS. | Not a default Windows dependency through this path. |
| libusb, LGPL 2.1 | Its build file asserts macOS; `services/device/usb` uses WinUSB on Windows and libusb only on macOS. | Not a Windows dependency through this path. |
| Eigen, axe-core, Symphonia, gRPC, hyphenation data and other candidates | Source roots and notices located. DevTools has an axe-core resource-copy target. | Final Windows target/resource inclusion still needs closure. |

These are checks of specific build paths, not a generated SBOM or a complete
link map. Do not remove notices or required source based on an unproven exclusion.
Android package metadata can reference files absent from the lite archive;
that is not itself a missing Windows material. ScreenAI has attribution metadata
for a separately downloaded component; it must not be treated as an embedded
library merely from its `Shipped: yes` marker.

### MPL versions and recipient rights

MPL source delivery covers the relevant covered files and modifications; it
does not require changing StreamBridge's separate Java code from MIT. Keep
license texts, copyright notices, change records and an accessible description
of where recipients obtain the corresponding source.

For [MPL 1.1](https://www.mozilla.org/en-US/MPL/1.1/), sections 3.2, 3.3 and 3.6
add source-availability, modification-record and executable-notice requirements.
In particular, section 3.2 specifies a 12-month/6-month availability rule for
modifications made available electronically; account for it when designing
retention, rather than treating all MPL material as MPL 2.0. Preserve the upstream
modification records as well. For [MPL 2.0](https://www.mozilla.org/en-US/MPL/2.0/),
sections 3.1-3.4 govern source availability and notices. Keep both versions where
the files require them.

MPL 1.1 section 3.6 also addresses executable distribution under a different
license. Terms that differ from MPL must be clearly offered by the distributor
alone, not the Initial Developer or Contributors, and must not limit or alter
recipients' MPL source-code rights. The section includes indemnification of the
Initial Developer and Contributors for liability incurred as a result of those
different terms. This is a conditional obligation separate from Microsoft's
runtime terms, not a conclusion that StreamBridge has already incurred liability.
Section 3.5 separately addresses warranty, support, indemnity or liability
obligations voluntarily offered to recipients.

Before release, review the actual browser terms and any installer EULA, notices
or other recipient terms against the selected licenses for the covered components.
Record whether different executable terms or additional promises are offered,
who offers them, whether source-code rights remain intact, and the applicability
of sections 3.5 and 3.6, including any resulting indemnity. Do not treat the MIT
license of the separate Java code as automatically licensing the browser under
MIT or resolving these conditions. Retain the reviewed text and rationale; any
unresolved applicability or distributor commitment keeps this release gate open.

Publish durable, versioned material downloads beside the binary release and
provide local notices pointing to them. Do not rely solely on expiring CI
artifacts. The upstream build-artifact listing was empty when checked on
2026-11-06; this does not mean upstream source is unavailable.

The browser's aggregate credits and cross-platform `Shipped: yes` metadata are
review inputs, not a Windows SBOM. Preserve applicable alternative-license
choices and exceptions. A regex hit on GPL or LGPL is not proof that a Windows
binary links that component. For MPL source-delivery guidance see the
[Mozilla FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/).

## Before enabling a public installer

1. Replace the blocked browser candidate with a security-reviewed exact payload,
   or establish the applicable backports; repeat candidate-specific checks and
   prevent unreviewed payload substitution in the public release path.
2. Complete the component/source/notice matrix for the exact Windows payload.
3. Resolve the runtime redistribution terms and corresponding-source/relink route.
4. Complete the MPL 1.1 browser/installer recipient-terms review described above:
   document license choices, different executable terms, additional promises,
   preservation of source rights and any conditional indemnity under sections
   3.5 and 3.6. Resolve applicability and distributor commitments before release.
5. Assemble and validate a versioned browser-material archive, retaining matching
   inputs under the project's control and keeping agent notes out of it.
6. Package notices and source links, verify the excluded DLL stays absent, and
   test the finished installer on clean supported Windows.

Hash checks establish consistency, not license sufficiency or absence of native
vulnerabilities. This documentation follow-up verified the cited advisories and
reviewed supplied test evidence; it did not rerun tests or reproduce CVE exploits.
