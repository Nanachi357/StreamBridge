# Microsoft runtime release decision for Hibbiki

Status: unresolved, 2026-11-09. Scope: Hibbiki Chromium 157.0.8103.39,
Windows x64, as identified in the [candidate manifest](hibbiki-157.0.8103.39-manifest.json).
This is a record of evidence and release criteria, not recipient license terms
or a legal determination that the binary cannot lawfully be redistributed.

## What the evidence establishes

The inspected build configuration selects static CRT; no separate VCRuntime or
MSVCP runtime DLL appears in the payload. Excluding `d3dcompiler_47.dll` does not
remove code already linked into the other binaries. Without original link maps,
the complete inventory of contributed Microsoft object code is not established.

[Microsoft's guidance](https://www.microsoft.com/licensing/guidance/Visual-Studio#how-certain-software-can-be-distributed-to-others-within-your-applications)
allows distributing application output containing distributable `.lib` code,
while distinguishing it from the `.lib` inputs. Static linking alone is not
a prohibition. The actual agreement and distributable list determine the
conditions; the guidance is not an unconditional license for this artifact.

The [VS 2026 Professional/Enterprise agreement](https://visualstudio.microsoft.com/license-terms/vs2026-ga-pro-enterprise/)
contains third-party-distributor permission and requirements for protective
recipient terms. Its licensee indemnity has an exception for claims based
solely on Distributable Code. The builder's stated tool version does not prove
which license channel applies, permission to us, or use of Insiders software.
Do not silently impose all builder obligations on StreamBridge's author.

The separate [V14 runtime agreement](https://visualstudio.microsoft.com/license-terms/vs2026-ga-visualcpp-v14-redist-runtime/)
does not supply an independent redistribution grant. The
[2026 redistribution list](https://learn.microsoft.com/en-us/visualstudio/releases/2026/redistribution)
identifies permitted components subject to applicable terms; copying either
document into the installer does not establish the missing authorization.

## Evidence needed to close this decision

| Decision | Concrete acceptance evidence | Current state |
| --- | --- | --- |
| Applicable route for this finished binary | Identify applicable Microsoft terms and why this build's runtime is covered by them. Use public release/build provenance and applicable terms; record any material inference and uncertainty. A version string alone is insufficient. | General VS route identified; actual builder channel and artifact-specific basis not established. |
| Upstream-to-StreamBridge distribution permission | Public applicable grant/terms covering downstream distribution, or a documented legal assessment explaining why existing publication and agreements provide sufficient authorization for this particular route. Identify the obligations borne by us. | Inspected public repository/release evidence does not explicitly settle the route. No conclusion that the builder lacks rights. |
| Recipient conditions | Actual proposed wording plus its presentation/agreement mechanism, reviewed against the applicable distribution conditions. Preserve required notices and apply Microsoft-specific restrictions only within their proper scope. | No final recipient agreement or acceptance mechanism prepared/approved. |
| Component and channel restrictions | Check the exact payload against the applicable distributable list and release provenance; resolve any evidence of excluded preview/Insiders inputs. Confirm omitted SDK DLL stays absent. | File inventory and DLL exclusion recorded; no complete object-level provenance or established channel conclusion. |
| LGPL/MPL interaction | Review the concrete terms and reconstruction route together. Preserve library modification/debugging permissions and MPL source rights; assess conditional MPL 1.1 executable-term obligations. | Engineering plan exists; final combined review remains open. |
| Delivered result | Installer presents the reviewed terms/notices and includes the exact approved binary/material set. Record document versions/hashes, not just a checkbox labelled “licenses checked”. | Not implemented as a release approval gate. |

These are acceptance criteria for this project, not a claim that Microsoft
requires every redistributor to collect a private letter or the builder's
purchase receipts. A reasoned assessment of already public evidence may settle
the permission route without correspondence. It cannot be replaced by an
unsupported assertion that every public download is redistributable.

The two central unresolved decisions are **authorization for this finished
binary** and **the actual recipient conditions**. More file hashes alone will
not settle them. A focused review of the existing public release, the applicable
Microsoft distribution clause and our proposed installer terms is the next
decision-making step. This document does not authorize correspondence, accept
terms for the user, or assert that buying a new Visual Studio license grants
rights in someone else's already compiled browser.

## Public terms follow-up

The additional public review covers the
[Community 2026 agreement](https://visualstudio.microsoft.com/license-terms/vs2026-ga-community/),
the [Diagnostic and Build Tools 2026 agreement](https://visualstudio.microsoft.com/license-terms/vs2026-ga-diagnostic-buildtools/),
the redistribution list, the existing Professional/Enterprise agreement and
the [EA/EAS subscription terms](https://www.microsoft.com/licensing/terms/productoffering/VisualStudioSubscriptions/EAEAS).

**Closed at the general-rule level:** redistribution is not limited to paid
Professional/Enterprise editions. Community also contains the Distributable
Code and third-party-distribution route, with the same substantive requirements
examined here: primary functionality, protective recipient terms, licensee
indemnity and component/channel restrictions. Therefore, inability to name the
precise edition is not by itself proof that this binary lacks a possible route.
No private purchase receipt or private letter is established as a universal
prerequisite by these documents. Subscription terms refer use to the applicable
accompanying product terms; they do not supply a separate unconditional grant.

**Not closed for this artifact:** the pinned Hibbiki README identifies tool
versions and build settings, and the release body supplies checksums. Neither
states a Microsoft-specific downstream grant or recipient conditions. Additional
public issue searches for license, redistribution and Microsoft yielded no
matching issues; this is a bounded search, not proof no such statement exists.
The public availability of the binary and its Chromium notices alone do not let
this review affirm that the upstream permission and protective-term conditions
have been satisfied. This is an evidence limit, not a finding of unlawful use.

**New reconstruction constraint:** the Build Tools agreement permits certain
unlicensed users to build OSI-licensed dependencies, but restricts development
and testing under that exception to specified minor changes. It is not a sound
basis for promising arbitrary LGPL library modification. A recipient should use
an independently valid Visual Studio entitlement suited to the work; Community
has individual and qualifying open-source organizational routes. Eligibility is
the recipient's situation, not something the installer may universally assert.
The Build Tools document also does not independently establish permission for
us to redistribute Hibbiki's finished runtime.

The newly collected DirectX-Headers, multimedia-redirection and WebAuthn source
overlays carry MIT licenses. Their permission can be established from those
licenses, but must not be extended to the proprietary CRT merely because all
four originate from Microsoft.

Conclusion of this public-only pass: the general permitted routes and a usable
licensing choice for eligible rebuilders are clearer; **the finished-binary
authorization and recipient-term decision remains unresolved**. Do not mark it
approved by inference alone. If no additional public grant is located, closing
it requires a defensible assessment of the existing publication and applicable
agreements, plus the concrete recipient terms. No correspondence, software
purchase, accepted agreement or compilation was performed to reach this result.

## Interaction with source and relink delivery

Do not place Microsoft's compiler, SDK, static-library inputs or private
credentials into the public source kit under an assumed open-source license.
The plan is to describe recipients' independent acquisition of legitimate
build prerequisites. That approach still needs a completeness assessment.

LGPL 2.1 section 6 allows the work using the library in object and/or source
form and contains an exception for normal compiler/operating-system components,
with a qualification when a component accompanies the executable. Do not
automatically treat every statically incorporated runtime as covered by that
exception, or conclude that Microsoft code must be relicensed under LGPL.
Resolve the actual dependency and licensing boundary in the
[relink plan](hibbiki-relink-plan.md).

For MPL components, preserve source access and applicable notices; our executable
terms must not remove source rights. MPL 1.1 section 3.6 has conditional
responsibility/indemnity terms when different executable terms are offered; it
does not automatically establish an obligation simply because this audit exists.
See [MPL 1.1](https://www.mozilla.org/en-US/MPL/1.1/) and
[Mozilla's MPL 2.0 distribution FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/).

Until the decisions above are supported and the delivered package checked,
continue using this candidate only in the current private test packaging.
Confirmed provenance and source findings can still be committed independently;
they do not approve a public bundled-browser installer.
