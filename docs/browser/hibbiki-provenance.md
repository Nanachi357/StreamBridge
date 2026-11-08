# Hibbiki Chromium candidate provenance

Recorded on 2026-11-07. This is an inventory of an engineering candidate, not
permission to redistribute it or approval for a public StreamBridge installer.
The file hashes are in [the candidate manifest](hibbiki-157.0.8103.39-manifest.json).
No browser binary, credentials, personal paths or upstream build automation is
stored in this directory. The current packager does not enforce this manifest.

## Exact input and intended payload

| Item | Identity |
| --- | --- |
| Release | [Hibbiki 157.0.8103.39-r1717349](https://github.com/Hibbiki/chromium-win64/releases/tag/v157.0.8103.39-r1717349) |
| Archive | `chrome.7z`, SHA-256 `0eaffa4b739d61d30146ece50db97f49a64871b8cf61333572729f9d1879379d` |
| Published recipe | [`1e8c8123309052bbbdc7877cbfc090949e2d573a`](https://github.com/Hibbiki/chromium-win64/tree/1e8c8123309052bbbdc7877cbfc090949e2d573a) |
| Chromium source | `9a31f0acec1854d44e49a73fbf8017598151a583`, version `157.0.8103.39` |
| DevTools source | `12ada949de6a27116dea47c600da5322f44c64f4`, pinned by Chromium DEPS |
| Original payload | 246 files |
| Intended payload | 245 files, 448,389,375 bytes |
| Exclusion | `157.0.8103.39/d3dcompiler_47.dll`; StreamBridge uses the checked Windows system copy |

The remaining payload consists of 4 EXEs, 7 DLLs, 223 PAKs and 11 other files.
Hibbiki's separate installer and policy-template archives are not inputs to this
candidate. Preserve the versioned subdirectory. Every file in the retained
original payload was rehashed against the recorded manifest on 2026-11-07.

The [upstream README at the recipe revision](https://github.com/Hibbiki/chromium-win64/blob/1e8c8123309052bbbdc7877cbfc090949e2d573a/README.md)
reports Visual Studio 2026 18.11.3 and Windows SDK 10.0.28000.2705. This is an
upstream statement, not an independently captured compiler log or proof of the
builder's license channel. The primary executable is unsigned. No independent
Chromium compilation or bit-for-bit reproduction was performed.

## Source archive identity

The [official Chromium source archive](https://storage.googleapis.com/chromium-browser-official/chromium-157.0.8103.39.tar.xz)
is 4,513,989,004 bytes; its measured SHA-256 is
`48cd9e743d3cbd8b66369ecf0c4c468980c7c73a1999aa8b5f7a8f304b76b782`.
The downloaded MD5 also matched the official object's metadata. These hashes
establish consistency with the acquired object, not a signed source-to-binary
attestation. The archive includes tools and other platforms' inputs; it is not
approved wholesale for rehosting or installation as runtime.

## Verified resource identity

Decoding the PAK format and its gzip/Brotli resource compression found the
Lighthouse worker at resource ID `53945` in `157.0.8103.39/resources.pak`.
The decoded 2,261,055 bytes have SHA-256
`5492b85170de5e7035b844b00967c997d0c2f9f3edfdb9742958a057436eeb90`.
They match [the pinned DevTools worker file](https://github.com/ChromeDevTools/devtools-frontend/blob/12ada949de6a27116dea47c600da5322f44c64f4/front_end/third_party/lighthouse/lighthouse-dt-bundle.js)
byte for byte. Resource IDs are evidence for this payload only.

Its embedded axe banner and version identify axe-core 4.13.0. The matching
[Lighthouse metadata](https://github.com/ChromeDevTools/devtools-frontend/blob/12ada949de6a27116dea47c600da5322f44c64f4/front_end/third_party/lighthouse/README.chromium)
points to Lighthouse 13.5.0, revision `cb853a38a6410617518b363590c967b3fe211949`.
That revision's lockfile pins axe-core 4.13.0. This is a different copy from
DevTools' old `front_end/third_party/axe-core` metadata (2017). Neither a shared
component name nor the browser's aggregate credits establishes version coverage.

The decoded embedded axe string differs from the npm package's `axe.min.js`
because Lighthouse minifies it a second time before inlining it. On 2026-11-08,
the npm file was transformed with the lockfile-pinned Terser 5.48.0 and the
upstream `{ecma: 2019}` options. The result matches all 578,672 embedded bytes,
SHA-256 `ce756af95f971248c36e483eb1c0f9258071a90c530d790e18dfc3b319391cf0`.
The npm input is 580,491 bytes, SHA-256
`c24f097bd2f451d4f933e8bc7d8d539f8672a2ebcb5cc9f9f3eec8ca9470a0c1`.
The pinned [Lighthouse inlining transform](https://github.com/GoogleChrome/lighthouse/blob/cb853a38a6410617518b363590c967b3fe211949/build/plugins/inline-fs.js)
documents this extra minification step.
This establishes the npm-to-embedded transformation, not a Chromium rebuild.

## Limits

This manifest is not a Windows dependency graph or a complete SBOM. It does not
close Microsoft runtime terms, LGPL relinking, MPL source delivery, security
advisories, or clean-Windows installer acceptance. Engineering tests and hash
matches alone do not settle those separate release requirements. No public
installer or source-material release is authorized by this inventory.
