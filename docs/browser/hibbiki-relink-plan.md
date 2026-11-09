# Hibbiki source and relink material plan

Status: preparation only, 2026-11-09. Applies to the exact
[157.0.8103.39 candidate](hibbiki-provenance.md), not future releases.
No Chromium compilation or successful relink is claimed. This is not an
instruction to compile Chromium as part of installing or running StreamBridge.

## Proposed delivery route

For the browser's statically incorporated LGPL code, prepare the corresponding
library sources, modifications, and the source form of the work using them,
with the inputs and instructions needed to produce a modified executable.
For LGPL 2.1, this follows the source option of section 6(a), with equivalent
download access under 6(d). Preserve the applicable GNU Library GPL 2-or-later
notices in Blink and assess those files under their actual terms too.
Do not replace all file-level licenses with a single LGPL-2.1 label.

The candidate does not establish the replaceable shared-library mechanism of
6(b). A separate archive of every original object file is not automatically
required if a complete, usable source route is supplied. Conversely, library
sources alone and a link to Chromium's home page are insufficient for that route.
Retain the license-required modification and debugging permissions in recipient
terms. See the [LGPL 2.1 text](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)
and the [runtime decision](hibbiki-runtime-decision.md).

The intended release layout is readable notices with the application plus a
versioned source/material download beside the binary. Ordinary users do not
need to download build inputs. The current private preparation supplement and
source archives have not been delivered to recipients or approved as complete.

## Prepared inputs and exact limits

| Input | Prepared evidence | Remaining work |
| --- | --- | --- |
| Candidate | Existing manifest identifies the original 246 files and intended 245-file payload after excluding `d3dcompiler_47.dll`. | Make release packaging verify this manifest and its associated materials. |
| Library sources | Retained 56,626 regular-file subset, including browser FFmpeg, Blink, Mozilla helpers, Hunspell, Eigen, gRPC, DXC and twelve Symphonia crates. The separate axe/Lighthouse supplement covers the reproduced embedded axe copy. | Complete work-using-library sources and dependency/build closure; the subset is not sufficient. |
| Full source reference | Official Chromium 157.0.8103.39 archive, 4,513,989,004 bytes, SHA-256 `48cd9e743d3cbd8b66369ecf0c4c468980c7c73a1999aa8b5f7a8f304b76b782`, retained and verified earlier. | Curate redistribution contents, including tools/sysroots and non-regular entries. Do not automatically publish the entire archive as an approved material kit. |
| Candidate changes | Independently prepared minimal install-mode GUID patch and 26 published GN arguments. | Confirm complete reconstruction scope without distributing Hibbiki automation under an assumed license. |
| Public configuration | Prepared GN argument file changes only `use_official_google_api_keys` to `false`. | Verify functional reconstruction without private credentials; do not claim byte identity or Google-service parity. |
| Browser FFmpeg changes | Full Gitiles tree diff from upstream merge `98e92563a3b60dbf6d370fd3491d7f896398e4c1` to Chromium fork `5c85ff666b3434d9323b83437f910ad8431ab7c9`: 6,684,432 bytes, 587 paths, SHA-256 `153f1c488c8780e1c85f801046f05f24d4ea5781da2a04f82fde5e0a5e68651b`. | Diff retained, not applied or rebuilt. Keep actual fork sources and modification notices; a tree diff is not full commit history. |
| Notices | Chromium aggregate notices and applicable LGPL/MPL/Blink/DXC texts, source index and gRPC upstream notice retained in preparation supplement. | Recipient presentation, final source links and complete component mapping. |

The FFmpeg diff is an additional change record for the stale reference to
`chromium/patches/README`, which is absent at the pinned revision. It does not
replace the collected fork source with an untested patch recipe. This is the
browser's FFmpeg, not the separately built StreamBridge media tools.

## Pinned reconstruction tools

These values come from the pinned Chromium
[DEPS](https://github.com/chromium/chromium/blob/9a31f0acec1854d44e49a73fbf8017598151a583/DEPS),
[Clang update script](https://github.com/chromium/chromium/blob/9a31f0acec1854d44e49a73fbf8017598151a583/tools/clang/scripts/update.py)
and [Rust update script](https://github.com/chromium/chromium/blob/9a31f0acec1854d44e49a73fbf8017598151a583/tools/rust/update_rust.py).
They identify source-defined dependencies, not a recovered complete record of
the machine that built Hibbiki's binaries.

| Tool | Pin | Check performed |
| --- | --- | --- |
| Clang | `llvmorg-24-init-7747-g62397f8b-67` | Complete Windows archive downloaded; SHA-256 and size match pinned DEPS. Not executed. |
| Rust | `1edd55dcfcd573872c727fa3e086369a71661ee0-1125-llvmorg-24-init-7747-g62397f8b` | Windows Rust and rust-libclang archives downloaded; both complete hashes and sizes match DEPS. Not executed. |
| GN | `0035961837fbcfe875d855744b3eda4fd2eefb43` | Windows CIPD instance resolved, archive downloaded and SHA-256 verified in the follow-up below. Not executed. |
| Ninja | `version:3@1.12.1.chromium.4` | Windows CIPD instance resolved, archive downloaded and SHA-256 verified. Not executed. |
| Siso | `b521b230cb7c56c821235e498285cf8e548a73ee` | Windows CIPD instance resolved, archive downloaded and SHA-256 verified. Not executed. |
| depot_tools | `96b9c092c0cdaa9f1ec87b0af777ec85977dee91` | Chromium DEPS dependency, not proof of Hibbiki's original bootstrap checkout. |
| Microsoft tools | Builder reports VS 2026 18.11.3 and SDK 10.0.28000.2705. | Preserve as reported provenance; no tools installed and no right to redistribute compiler/SDK inputs inferred. |

The earlier five successful storage HEAD checks include optional clang-tidy and a
Windows runtime-library package for cross-compiling from a non-Windows host.
They are not five mandatory Windows-host prerequisites. The subsequent byte-level
check collected the three Windows-host compiler archives listed above, not the
two optional/cross-host packages. Recipient acquisition of tools is separate from
our right to redistribute their contents.

### Archive completeness and collected Windows inputs

A follow-up compared all 430 entries in the root `DEPS` with the retained full
source-archive inventory, using explicit Windows-x64 checkout defaults. It
now evaluates 192 entries as active, with no unresolved conditions. Of the active
entries, 36 have no regular files beneath their declared paths before overlays.
This corrects the earlier 193/37 count: condition variables containing strings
must be evaluated recursively; a nonempty `"False"` is not true. The remote
execution client is inactive. The two previously unresolved bare `mac`
comparisons are false on this host. CPU checkout built-ins are explicit too.
This remains a root-level presence check, not a generated target graph or proof
of each existing dependency's revision.
Some missing paths are test fixtures or updater inputs, not runtime blockers.

Three actual Windows source dependencies were empty in that archive. Their
complete pinned Git trees were obtained separately and compared file by file:

| Destination beneath `src` | Revision | Regular files | Terms |
| --- | --- | ---: | --- |
| `third_party/microsoft_dxheaders/src` | `07b94cba474be6cdfd934febdbd0b21ca0524187` | 81 | MIT, retain copyright and permission notice |
| `third_party/microsoft_multimedia_redirection/src` | `8b1d8893d126547309f1bab8d8232418aa7c7109` | 15 | MIT, retain copyright and permission notice |
| `third_party/microsoft_webauthn/src` | `ef82c157125a0490e05f6ea82a7adb1b8e1bad08` | 8 | MIT, retain copyright and permission notice |

All 104 Git blob IDs match the pinned upstream trees; the trees were not
truncated and contain no Git submodules. Chromium's corresponding BUILD files
reference `dxguids.cpp`, MMR headers/definitions and `webauthn.h` in these paths.
These are separately licensed open-source Microsoft components, **not** a grant
covering Microsoft's proprietary CRT. Their original archives and notices are
retained as source overlays; the large Chromium archive itself was not changed.

The source overlay URLs, archive hashes, sizes and resolved tool instances are
recorded in [the prepared input index](hibbiki-relink-inputs.json). GN, Ninja and
Siso archives were downloaded and their complete bytes checked against the
SHA-256 instances returned by the public CIPD repository. Their ZIP integrity
also passed. The binaries were not executed, installed or approved for inclusion
in the end-user installer. Clang/Rust, the pinned 89,935,872-byte Windows Node
binary and the 10,214,076-byte Windows TypeScript ZIP were subsequently retained
and checked against their complete DEPS/CIPD hashes. The input index records
these separate checks; metadata availability is not substituted for byte identity.

The inventory contains 7,039 symbolic links and 26 hard links. A lexical check
that resolves intermediate link components found no missing final destinations,
cycles or paths outside the archive root. This is not a Windows extraction test:
the source assembly must preserve or deliberately materialize these entries.
Dropping them because the earlier copyleft subset contains only regular files
does not produce a complete reconstruction tree.

Concrete remaining input work includes a complete Windows Perl distribution,
bootstrap tools, Python wheels and any Go modules required by selected targets.
The pinned Perl archive download was incomplete and is excluded from verified
materials; no substitute Perl version was silently chosen. Other
missing DEPS paths must be classified against the selected targets; do not
install every test/updater package simply because it is declared in root DEPS.
The three collected source overlays close identified omissions, not all source
or build prerequisites.

### Nested dependency and bootstrap follow-up

The pinned `DEPS` files of ANGLE, Dawn, Open Screen, DevTools and Web Page Replay,
plus the active Open Screen buildtools recursion, were inspected without running
their hooks. Parent variable precedence and recursive string conditions were
checked against the pinned
[`gclient.py`](https://chromium.googlesource.com/chromium/tools/depot_tools.git/+/96b9c092c0cdaa9f1ec87b0af777ec85977dee91/gclient.py)
and [`gclient_eval.py`](https://chromium.googlesource.com/chromium/tools/depot_tools.git/+/96b9c092c0cdaa9f1ec87b0af777ec85977dee91/gclient_eval.py).
Fifteen focused checks cover condition chains, false strings, parent overrides,
cycles, unsupported expressions and safe path substitution. These checks belong
to the private audit tooling; no application behavior changed.

| Nested list | Entries | Active under the inspected Windows configuration |
| --- | ---: | ---: |
| ANGLE | 523 | 3 |
| Dawn | 107 | 10 |
| Open Screen | 32 | 5 |
| DevTools | 30 | 1 |
| Web Page Replay | 10 | 3 |
| Open Screen buildtools | 4 | 1 |

All 706 inspected nested conditions resolve; 23 entries are active. Seven active
paths lack regular files in the original source inventory. These are Agility SDK,
two Dawn Go platforms, nested GN, two clang-format locations and Web Page Replay's
Windows Go. This is a dependency declaration/presence inventory, not seven proven
requirements of `//chrome:chrome`. In particular:

- ANGLE's restricted trace dependencies are inactive because their condition
  chains lead to disabled internal checkouts. Do not download them wholesale.
- Dawn's [`dawn_use_agility_sdk`](https://dawn.googlesource.com/dawn.git/+/3c24c91e70eaf701ae92ab8cc414669cc4c38892/scripts/dawn_features.gni)
  defaults to false; the inspected Chromium overrides and candidate arguments
  do not enable it. Its native D3D12 dependency is conditional on that flag.
  The downloaded-DEPS entry alone does not require adding its DLLs to the payload.
- Open Screen requests the same Windows GN instance already retained for the root
  tree. It needs a separate destination, not a newly chosen GN version.
- Dawn's `tools/run.py` selects Go by host platform and can invoke `go run`.
  A Go executable alone does not establish offline availability of its module
  dependencies. Determine whether the chosen runtime targets invoke these tools.
- Formatter and test-tool checkouts are not evidence of runtime linkage. Keep
  their classification explicit until the selected build path is established.

The bootstrap manifest, its resolved infrastructure-tool pins, CIPD client
version/digests and `vpython` lock file are retained at depot_tools revision
`96b9c092c0cdaa9f1ec87b0af777ec85977dee91`. Its Windows bootstrap requests
`infra/3pp/tools/cpython3/windows-amd64` at `version:2@3.11.8.chromium.35`.
The CIPD client has its own pinned digest, distinct from a package ZIP digest.
These are acquisition records: they do not prove that every bootstrap binary,
Python wheel or Go module is collected, nor authorize redistribution of tools.
No bootstrap script, compiler or downloaded executable was run.

Archive inspection retained 89 LICENSE/COPYRIGHT/NOTICE-named files from Rust
and two from TypeScript. No such separately named files were found in the Clang
or rust-libclang archives; this filename check is not a full license audit.
Node was acquired as a single executable, not a distribution with accompanying
notices. Keep these tools in the private verification cache. The delivery draft
should pin independent recipient acquisition of tools instead of automatically
adding their binaries to either the installer or the source archive. If tools
are later bundled, review their own notices and redistribution requirements;
retaining their hashes does not settle that separate packaging decision.

## Reconstruction procedure to finish validating

1. Assemble exact Chromium, nested repositories, generated sources, build
   wrappers, licenses and required dependency data from the source manifest.
   Compare complete inputs, not only the copyleft subset. Account for symlinks
   and other non-regular entries instead of silently dropping them.
2. Apply the independently prepared shared GUID change to the correct source
   revision. Keep its BSD notice and the component modification records.
3. Acquire pinned public tools and independently licensed Windows prerequisites.
   Document the supported local-toolchain selection, actual SDK/tool paths and
   any bootstrap dependency. Do not require a private Google toolchain download.
4. Use the published GN arguments with the documented public API-key change.
   Use the exact `chrome/build/win64.pgo.txt` profile. Its stored gzip bytes and
   decoded profile have now been retained with separate hashes in the input index;
   permission for rehosting and durable recipient delivery remain to be reviewed.
   Do not silently disable PGO and describe the result as the same configuration.
5. Establish the runtime targets and their dependency closure. The published
   `chrome_official_builder_no_unittests` group also builds installer, updater,
   policy and other outputs absent from StreamBridge's runtime payload. Do not
   copy that broad command into a supposedly minimal relink recipe without
   resolving those extra inputs and patch scope.
6. Document how a recipient can modify the applicable library, generate the
   build graph, link the runtime and run the modified result. A successful
   controlled demonstration, or sufficiently specific independent upstream
   evidence for that route, would substantiate usability; none is claimed here.
   Any local Chromium build requires a separate user decision.
7. Deliver the complete reviewed materials with a stable versioned index and
   verify the installer/source links against the same candidate manifest.

Steps 1 and 3-7 remain incomplete. The prepared files make the gaps explicit;
they are not a release approval, a validated build script or an instruction to
install compilers now. An object-based alternative would require the actual
usable objects and link inputs, not fabricated substitutes. The public source
route remains the preparation preference.

### Specific instruction corrections from the pinned source

- The pinned [Windows instructions](https://github.com/chromium/chromium/blob/9a31f0acec1854d44e49a73fbf8017598151a583/docs/windows_build_instructions.md)
  explicitly describe `DEPOT_TOOLS_WIN_TOOLCHAIN=0` for a locally installed
  toolchain and optional `vs2026_install` for its path. A public recipe must
  include this choice rather than falling into the Google-internal default.
  These variables were not set and no toolchain installed during this audit.
- The prepared source configuration should explicitly retain local execution.
  `use_remoteexec=false` is the pinned default. Siso selection additionally
  depends on a generated `.sisoenv` file; do not assume that copying `args.gn`
  reproduces bootstrap state. The pinned instructions say public external
  Chromium remote execution does not support Windows builds.
- `//chrome:chrome` is the runtime target to investigate first, rather than
  the broad official-builder group. Its source declares the main DLL/EXE,
  packed resources, Windows import-reordering and helper dependencies. This
  narrows the plan; it is not proof that all 245 packaged files are reproduced.
- The public PGO update hook names the `win64` target and the
  `chromium-optimization-profiles/pgo_profiles` bucket. Its update path can
  remove old profiles. Preserve the candidate profile in release materials or
  document durable acquisition; do not call that mutable cache a source archive.
  No update hook was run in this review.
- Recipients need an appropriate Visual Studio license for the intended
  modification work. The standalone Build Tools open-source-dependency exception
  is limited and must not be advertised as permission for arbitrary library
  modifications. Community can be an option for eligible users; see the
  [runtime terms follow-up](hibbiki-runtime-decision.md#public-terms-follow-up).

The retained PGO object is generation `1793083922288618`: 111,311,073 gzip-encoded
bytes, SHA-256 `c20dae171845878ad73f17711140e9dd0de07318c5855db8cd9988eb767a1a16`.
Its bytes match the storage metadata's MD5. Gzip decoding verifies its CRC and
produces a 514,785,664-byte profile with SHA-256
`42685ab2206d12b2890b09b9ba1f1b20e94a460d5ec47cc8072030e4b85f25da`.
These locally calculated hashes identify retained material; they are not an
upstream SHA-256 attestation or a completed redistribution review.
