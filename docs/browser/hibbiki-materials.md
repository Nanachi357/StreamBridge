# Hibbiki source materials and distribution review

Status: incomplete, updated 2026-11-08. The [candidate provenance](hibbiki-provenance.md)
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

The pinned `google_apis/BUILD.gn` has an explicit public build path with
`use_official_google_api_keys=false`; the private-key branch is not required by
that configuration. Do not copy keys from upstream scripts or binaries. This
source inspection does not prove functional parity for Google services or that
every other reconstruction input is covered.

The official Windows build defaults enable PGO. The exact file named by
`chrome/build/win64.pgo.txt` was reachable by HTTP HEAD from the public
`chromium-optimization-profiles/pgo_profiles` bucket on 2026-11-07. This checks
availability only: the profile was not downloaded, hashed, licensed for rehosting
or used in a build. Include its acquisition/version plan in the reconstruction
instructions; do not silently disable PGO and claim identical configuration.

The existing narrow GUID delta, published GN settings and source subset remain
preparation inputs. A usable, complete LGPL source/relink route for embedded
FFmpeg and applicable Blink code is still unverified. This does not require
StreamBridge to compile Chromium merely to perform this review, nor does it
justify claiming reconstruction works without checking its inputs.

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
