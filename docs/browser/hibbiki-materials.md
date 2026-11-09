# Hibbiki source materials and distribution review

Status: incomplete, updated 2026-11-09. The [candidate provenance](hibbiki-provenance.md)
and file inventory are independently recordable facts. This document identifies
what remains before that candidate may enter a public alpha.1 installer.
No final recipient license is offered here.

## Microsoft runtime

The non-component desktop build configuration selects static CRT. The inspected
payload has no separate VCRuntime/MSVCP redistributable DLL. Removing the SDK
D3DCompiler DLL does not remove runtime code linked into other binaries.

[Microsoft's licensing guidance](https://www.microsoft.com/licensing/guidance/Visual-Studio#how-certain-software-can-be-distributed-to-others-within-your-applications)
allows distribution of output containing distributable static-library code,
while distinguishing that from distribution of the `.lib` inputs themselves.
Static linking is therefore not, by itself, a distribution prohibition.

The [VS 2026 agreement](https://visualstudio.microsoft.com/license-terms/vs2026-ga-pro-enterprise/)
provides a route for the licensee to authorize distributors of its applications.
Its distribution conditions cover added functionality, protective recipient
terms and an indemnity obligation for the licensee, with an exception for claims
based solely on Distributable Code. It restricts distribution involving
unreleased components and Insiders Channel software. Do not silently transfer
every licensee obligation to a downstream packager, or claim that buying Visual
Studio independently resolves rights in someone else's finished binary.

The [EA/EAS subscription terms](https://www.microsoft.com/licensing/terms/productoffering/VisualStudioSubscriptions/EAEAS)
refer use of the tools to their accompanying terms; they do not identify
Hibbiki's actual license channel. The [redistribution list](https://learn.microsoft.com/en-us/visualstudio/releases/2026/redistribution)
is likewise conditional, not a standalone unconditional grant for this payload.

**Still open:** a documented basis for the upstream-to-downstream authorization
for the included runtime and the actual protective terms delivered to recipients.
The upstream version statement alone does not settle those questions or establish
Insiders usage. This is an unresolved release-review requirement, not a finding
that redistribution is unlawful. No agreement was accepted on the user's behalf.
No correspondence or Chromium compilation is required by this document.

## Resource and source coverage

| Component | New evidence | Remaining action |
| --- | --- | --- |
| Lighthouse / axe-core | A decoded browser resource exactly matches the pinned DevTools worker. It contains axe-core 4.13.0, separately from the old 2017 DevTools copy. The axe source build reproduces the npm files; Lighthouse's second minification reproduces the embedded string exactly (details below). | Preserve the applicable sources, dependency patch, build materials and notices, and add this copy to the recipient index. Source delivery and the wider browser audit remain open. |
| Liblouis | The inspected resource path is ChromeOS/ChromeVox, guarded by `assert(is_chromeos)` in the accessibility resource build. A marker occurs in aggregate credits, not proof of executable Windows linkage. | Do not label it a confirmed Windows dependency or remove its notice solely from this observation; finish target/resource coverage. |
| Hunspell dictionaries | No loose `.bdic`, `.dic` or `.aff` files occur in the exact payload. The pinned dictionary implementation supports later downloads from `redirector.gvt1.com`. | Distinguish initial payload from future browser downloads; no assertion that dictionary downloading is disabled or that all dictionary resources are absent. |
| Hyphenation data | No `hyphen-data` directory in the payload; the pinned source has a component-updater installation path. | Keep later acquisition separate from installed-payload source obligations; resource coverage still needs completion. |

PAK inspection covered all 223 packs, including 916 Brotli and 3,335 gzip
resources. Marker searches are supporting evidence, not an exhaustive SBOM or
proof of absence. Browser code was not executed during this inspection.

The source supplement contains axe-core revision
`1cc54b900413660610180d631feb73c9e74f4dc9` (MPL-2.0), Lighthouse revision
`cb853a38a6410617518b363590c967b3fe211949` (Apache-2.0 plus dependency terms),
and the npm axe-core 4.13.0 package. The npm archive matches the Lighthouse
lockfile's SHA-512 and registry SHA-1. The fetched npm provenance statement names
the same axe commit; its signature/transparency chain was not independently
validated. The independent reconstruction below establishes file correspondence;
it does not validate that signature/transparency chain.

### Reproduced axe-core correspondence (2026-11-08)

The byte difference was caused by a documented second minification, not an
unexplained source delta. Lighthouse's
[`core/lib/axe.js`](https://github.com/GoogleChrome/lighthouse/blob/cb853a38a6410617518b363590c967b3fe211949/core/lib/axe.js)
reads npm `axe-core/axe.min.js`. Its
[`inline-fs` plugin](https://github.com/GoogleChrome/lighthouse/blob/cb853a38a6410617518b363590c967b3fe211949/build/plugins/inline-fs.js)
minifies JavaScript before embedding it as a string. The root `yarn.lock` pins
Terser 5.48.0. Applying that version with the upstream options reproduces every
embedded byte:

```js
const result = await terser.minify({'axe.min.js': npmAxeSource}, {ecma: 2019});
// Compare UTF-8 bytes of result.code with the decoded embedded string.
```

The earlier link in the chain was also rebuilt from the complete axe source
archive at `1cc54b900413660610180d631feb73c9e74f4dc9`, using Node 24.22.1,
pnpm 11.17.0 and its frozen lockfile in a separate Windows working directory.
The upstream `colorjs.io@0.4.3` patch was retained. Dependency lifecycle scripts
were disabled; the matching Windows esbuild 0.10.2 binary was supplied explicitly
through `ESBUILD_BINARY_PATH`. The upstream `node build/run-build.mjs --log`
command generated both npm files without changes to the implementation or build
recipe. The copyright year input was 2026. The build uses UglifyJS 3.19.3 before
Lighthouse applies Terser.

| Output | Bytes | SHA-256 | Comparison |
| --- | ---: | --- | --- |
| Rebuilt `axe.js` | 1,305,279 | `138a93a4ce7b7c6c08ed84144e45dcd8cc36d2d4ff8ed673619faf0406906d88` | Exact npm match |
| Rebuilt `axe.min.js` | 580,491 | `c24f097bd2f451d4f933e8bc7d8d539f8672a2ebcb5cc9f9f3eec8ca9470a0c1` | Exact npm match |
| Terser output | 578,672 | `ce756af95f971248c36e483eb1c0f9258071a90c530d790e18dfc3b319391cf0` | Exact embedded match |

An independent JavaScript parser decoded the literal without executing the
browser resource. Four negative controls rejected the untransformed npm file,
an altered version string, disabled name mangling and a changed target byte.
The MPL copyright/license header survives the transformation.

This closes the specific axe source-to-embedded-code question for this payload.
It is not a reconstruction of the complete Lighthouse worker or Chromium, an
exhaustive vulnerability test, or proof that recipients already receive the
required sources. Preserve the full axe archive, patch, lockfile, build recipe,
Lighthouse transform and applicable dependency notices in the material plan;
the generated minified file alone is not the preferred form for modification.

The older 56,626-file covered-source subset and 34-entry candidate index are
therefore **not complete coverage**, including for MPL resources. Under
[MPL 2.0](https://www.mozilla.org/en-US/MPL/2.0/), source form means the preferred
form for modification; collecting a minified bundle and its license alone does
not establish that requirement. Retain complete applicable sources and tell
recipients where to obtain them.

## Reconstruction inputs

### Remaining component checks (2026-11-08)

The retained source subset was checked again against its individual-file
inventory: all **56,626 regular files** match. This verifies the collected
subset's integrity, not the completeness of the browser build inputs. The
following results use Chromium revision
`9a31f0acec1854d44e49a73fbf8017598151a583` and the published candidate settings;
they are static build-path checks, not a generated GN graph or binary link map.

| Component | Verified materials or build path | Delivery decision / remaining work |
| --- | --- | --- |
| Browser FFmpeg | DEPS pins `5c85ff666b3434d9323b83437f910ad8431ab7c9`. The `Chrome/win/x64/config.h` license is LGPL 2.1-or-later; GPL, GPLv3, version3 and nonfree flags are zero. The subset contains 11,351 files, the LGPL text, Windows configuration and generated GN source list. | Preserve these exact sources and configurations, not the standalone StreamBridge FFmpeg kit. Full-browser rebuild/relink inputs remain incomplete. |
| Blink | 20,975 retained files; checked `string_impl.cc` carries GNU Library GPL version 2-or-later terms. | Preserve file-level terms, aggregate notices and applicable modifications. Do not describe the entire Blink tree as BSD or replace its license texts with FFmpeg's. |
| Symphonia | All twelve source crates are version 0.6.1, MPL-2.0, with LICENSE and original Cargo manifests present. All 102 distinct vendor paths referenced by the seven inspected decoder dependency targets exist in the subset. | Retain all twelve crates and their notices. The default decoder path reaches seven; do not claim twelve independently linked production libraries. Build wrappers and external dependencies also belong in the reconstruction plan. |
| Mozilla helpers / Hunspell / ISimpleDOM | Previously identified source roots remain intact; MPL texts and Hunspell's `google.patch` are present. Metadata records Hunspell modifications and no local ISimpleDOM modifications. | Keep per-file notices and upstream change records. The URL parser has BSD/MPL-1.1 metadata; do not silently erase the MPL notice. |
| Eigen | `eigen_public_includes` defines `EIGEN_MPL2_ONLY`; the source tree and `COPYING.MPL2` are present. | Keep sources and notices. This config does not prove every consumer used it, or license every file in the retained source archive solely under MPL. |
| gRPC | All 2,942 retained files match; the combined notice includes Apache-2.0, BSD and MPL-2.0. Follow-up identifies upstream `etc/roots.pem` as explicitly MPL-2.0; Chromium's inspected import omits that file (details below). | Retain the Chromium-patched source root and combined notice. Do not classify all gRPC as MPL or infer a complete Windows link graph from this finding. |
| Libbrlapi / Liblouis | `chrome/common/features.gni` sets `use_brlapi = is_chromeos`; the inspected braille consumer adds libbrlapi only under that flag. The previously checked Liblouis resource route is ChromeOS-specific. | These inspected paths do not require a new Windows runtime addition. Preserve aggregate notices and keep any whole-payload exclusion claim narrower than an exhaustive graph result. |
| Other-platform headers, downloaded components and dictionaries | Aggregate credits also contain Android NDK, libsecret, speech-dispatcher, v4l-utils, NSS helpers and ScreenAI entries. A license/name match alone does not establish bundled Windows code. | Their complete target/resource mapping is not closed by this pass. Keep the existing notices and distinguish later downloads from this installer's payload. |

The Symphonia result follows
[`media_options.gni`](https://github.com/chromium/chromium/blob/9a31f0acec1854d44e49a73fbf8017598151a583/media/media_options.gni),
[`media/filters/BUILD.gn`](https://github.com/chromium/chromium/blob/9a31f0acec1854d44e49a73fbf8017598151a583/media/filters/BUILD.gn)
and the seven referenced crate targets. In the inspected defaults,
`media_use_symphonia_decoder` follows enabled `media_use_symphonia`, while
`media_use_symphonia_demuxer` is false. The published candidate settings do not
override these flags. Runtime feature activation and linker dead-code removal
are separate questions. Decoder roots are FLAC, MP3, PCM, Vorbis and core;
their Symphonia dependencies add common and metadata. The top-level facade and
four format crates remain collected without a claim that this path links them.

**FFmpeg change-record caveat:** `README.chromium` points to
`chromium/patches/README`, but that file is absent from both the full source
archive's inventory and the
[pinned repository directory](https://chromium.googlesource.com/chromium/third_party/ffmpeg/+/5c85ff666b3434d9323b83437f910ad8431ab7c9/chromium/patches/).
Only `config_flag_changes.txt` is present there. Do not make that nonexistent
file a recipient prerequisite or claim a complete change log was collected.
The fork sources remain available; provide the actual modification records and
usable pinned history/differences as appropriate. A retained first page of Git
history is not a complete replacement for that work. This discrepancy does not
establish missing implementation source or a license violation.

### Runtime terms and recipient delivery follow-up (2026-11-08)

The current VS 2026 Professional/Enterprise agreement was downloaded again and
matches the previously reviewed SHA-256
`dd2ab92a7c2bb90dd509e16572707a34b13bc0bc09b9199d115f25594ac39053`.
The separate [V14 runtime agreement](https://visualstudio.microsoft.com/license-terms/vs2026-ga-visualcpp-v14-redist-runtime/)
was also checked: it restricts redistribution and is not the missing independent
redistribution grant. Attaching that agreement to the package does not by itself
resolve the Visual Studio distribution route.

The pinned Hibbiki repository root and release metadata do not supply an explicit
runtime-specific downstream authorization or recipient agreement. This is the
scope of the public evidence checked, not proof that the builder lacks rights,
nor a requirement that every distributor obtain a private letter. No such letter
was requested. The remaining question is the defensible basis for applying the
licensee's third-party-distributor permission to this binary and the protective
terms actually passed to recipients.

For the distribution route described by the VS agreement, a passive notice is
not automatically equivalent to the requirement that distributors and external
end users agree to protective terms. The actual wording and acceptance mechanism
need review before release. Keep Microsoft-only restrictions scoped to Microsoft
code and preserve LGPL modification/debugging and MPL source rights. Do not apply
a blanket reverse-engineering ban or MIT-only license to the browser. The builder's
indemnity provision must not silently become an obligation accepted for the
StreamBridge author. Likewise, do not promise that an installer disclaimer removes
all obligations under upstream licenses.

The practical material-delivery plan remains: offline component notices and
license texts, a payload-to-source index, a durable versioned source/material
download alongside the binary, and documented reconstruction prerequisites.
The source download is separate from the ordinary installation; end users need
not install build tools merely to run StreamBridge. The existing private subset,
archives and notices are preparation inputs, not a published fulfillment of this
plan. Until the unresolved terms, coverage and relink requirements are satisfied,
this candidate remains a private test input rather than an approved public bundle.

### Other reconstruction prerequisites

The [relink material plan](hibbiki-relink-plan.md) now records prepared inputs,
tool pins, the FFmpeg tree diff and the exact boundaries of the unvalidated
source route. The [Microsoft runtime decision](hibbiki-runtime-decision.md)
separates the concrete authorization, recipient-term and delivery evidence
needed for release. Neither document approves the candidate for distribution.

The subsequent root-DEPS inventory check identified three empty Windows source
dependencies in the full archive. Separate MIT-licensed DirectX-Headers, MMR and
WebAuthn overlays now match all 104 files in their pinned Git trees. GN, Ninja
and Siso Windows packages were also downloaded and hash-verified without being
executed. See the [prepared input index](hibbiki-relink-inputs.json); neither the
presence audit nor these overlays establish complete recursive build closure.

The next pass also inspects six nested dependency lists (706 declarations).
It corrects the root condition count to 192 active entries and 36 empty paths
before overlays: string-valued conditions must be evaluated recursively, rather
than treated as truthy text. Restricted ANGLE traces are inactive. Agility SDK
is declared for checkout, but its use is disabled in the inspected Dawn default
and is not enabled by the inspected Chromium overrides/candidate arguments.
The [relink plan](hibbiki-relink-plan.md#nested-dependency-and-bootstrap-follow-up)
records these distinctions, remaining bootstrap inputs and the limits of static
presence checks. This follow-up does not change the separate runtime decision.

Community and Build Tools terms were additionally reviewed: the general runtime
distribution route is broader than paid editions, but artifact-specific
authorization and actual recipient conditions remain unresolved.

The pinned `google_apis/BUILD.gn` has an explicit public build path with
`use_official_google_api_keys=false`; the private-key branch is not required by
that configuration. Do not copy keys from upstream scripts or binaries. This
source inspection does not prove functional parity for Google services or that
every other reconstruction input is covered.

The official Windows build defaults enable PGO. The exact file named by
`chrome/build/win64.pgo.txt` was subsequently retrieved from the public
`chromium-optimization-profiles/pgo_profiles` bucket. Its 111,311,073 stored bytes
use gzip encoding and match the object's MD5; the decoded profile is 514,785,664
bytes. Separate local SHA-256 pins for both representations are recorded in the
[input index](hibbiki-relink-inputs.json). This is an integrity/acquisition check,
not an upstream SHA-256 signature or permission to rehost the profile. It was not
used in a build. Do not silently disable PGO and claim identical configuration.

The existing narrow GUID delta, published GN settings and source subset remain
preparation inputs. A usable, complete LGPL source/relink route for embedded
FFmpeg and applicable Blink code is still unverified. This does not require
StreamBridge to compile Chromium merely to perform this review, nor does it
justify claiming reconstruction works without checking its inputs.

### Source attribution follow-up (2026-11-09)

The exact upstream gRPC archive at
`ff8d12a129eedc58f9602c9fa9d1c01a2a270d62` was collected separately for comparison
(14,648,741 bytes; SHA-256
`9eed0912d4b74cef21d3e54acb045c12a796344c4f4c77bf8b2d38db9f2d1efe`).
Its [`etc/roots.pem`](https://github.com/grpc/grpc/blob/ff8d12a129eedc58f9602c9fa9d1c01a2a270d62/etc/roots.pem)
contains an explicit MPL-2.0 header. A bounded text-marker scan of regular files
up to 3 MB found MPL references in this file and the combined LICENSE; this is
not exhaustive proof that no other file has MPL obligations.

Chromium's pinned
[`update.sh`](https://github.com/chromium/chromium/blob/9a31f0acec1854d44e49a73fbf8017598151a583/third_party/grpc/update.sh)
imports only `src`, `include` and `third_party`, filters source/header extensions,
copies the combined LICENSE and applies its local patches. It does not import
`etc/roots.pem`. The retained Chromium source subset contains no such file;
its gRPC LICENSE is byte-identical to upstream. The import script was read, not
executed. Keep the local patches and notices; the upstream archive alone is not
the corresponding source for Chromium's modified copy. This narrows an ambiguous
notice attribution without claiming whole-binary absence or removing notices.

For Speech Dispatcher, the pinned
[`content/browser/BUILD.gn`](https://github.com/chromium/chromium/blob/9a31f0acec1854d44e49a73fbf8017598151a583/content/browser/BUILD.gn)
adds `speech/tts_linux.cc` and `//third_party/speech-dispatcher` only under
`is_linux`. This inspected consumer does not add a Windows dependency. It is a
scoped source-path finding, not an exhaustive generated target graph. The other
unresolved aggregate-credit entries remain conservative candidates.

## Release requirements still open

1. Resolve runtime authorization and recipient terms without restricting LGPL/MPL
   source rights; review conditional MPL 1.1 executable-terms obligations.
2. Complete the source/resource matrix and remaining conditional dependencies.
   Include the now-reproduced Lighthouse axe copy, applicable notices and changes.
3. Complete the source/relink instructions and durable, versioned material archive.
4. Install readable notices and real source links; enforce the reviewed payload and
   material hashes in the release path. Current private packaging is not that gate.
5. Refresh security-version review and verify the final installer on clean Windows.

Documentation of confirmed facts can be reviewed and committed independently of
these unresolved requirements. It does not enable or approve a binary release.
