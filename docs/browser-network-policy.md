# Discovery browser background services

StreamBridge uses installed Microsoft Edge Stable with an isolated temporary browser profile for media discovery. Its shared
launch factory suppresses three services that are unnecessary for that task:

| Setting | Intended effect | Limit |
|---|---|---|
| Disable `PreconnectToSearch` | Avoid speculative connections to the search provider. | Does not block navigation or resources requested by a website. |
| Disable `AutofillServerCommunication` | Disable server-assisted autofill predictions. | May reduce form-filling assistance. It is not a network firewall. |
| Enable `SkipListAccountsWithoutGaiaCookies` | Skip Google account-list requests when Gaia cookies are absent. | Account cookies can permit these requests; this is not a blanket account-service ban. |

These controls apply to both visible and headless launches. They do not replace
the network proxy, address restrictions, browser sandbox, or profile isolation.
Normal Playwright launch arguments remain enabled. A website can still request
Google resources through the existing network policy.

## Preserving Playwright defaults

Chromium uses the last occurrence of an enabled/disabled feature switch.
Appending only StreamBridge's feature names could therefore replace Playwright's
defaults. `ChromiumFeaturePolicy` combines the reviewed Playwright 1.64.0 defaults
with StreamBridge's additions instead.

The Java driver version is checked at runtime. A missing or changed version
fails explicitly so an upgrade cannot silently use an obsolete feature list.
The portable contract test reads the bundled driver's switch module and compares
the actual defaults, including the `PLAYWRIGHT_LEGACY_SCREENSHOT` environment
override. Like the driver's JavaScript, any nonempty value, even `0`, enables that
override. Review the contract when upgrading Playwright.

## Verification and limits

Unit tests check version mismatch, defaults and environment semantics. Opt-in
headless integration tests inspect the effective switches in `edge://version`
and exercise a local two-origin fixture with credentialed CORS, an HttpOnly
cookie, an HTTP redirect and iframe HLS discovery. Use the existing browser-test
instructions in the [README](../README.md).

The installed browser updates independently. These Chromium feature switches are
best-effort background-service controls, not a promise that Edge has no Microsoft
or Google background traffic. Compatibility tests verify effective arguments,
sandboxing, profile isolation and protected networking. Renew those checks after
browser/Playwright updates; website functionality is not guaranteed for every site.

StreamBridge does not distribute or compile a browser. Edge is installed and
updated separately through Microsoft.
