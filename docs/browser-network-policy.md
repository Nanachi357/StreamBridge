# Discovery browser background services

StreamBridge uses an isolated browser profile for media discovery. Its shared
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
headless integration tests inspect the effective switches in `chrome://version`
and exercise a local two-origin fixture with credentialed CORS, an HttpOnly
cookie, an HTTP redirect and iframe HLS discovery. Use the existing browser-test
instructions in the [README](../README.md).

Private observations with Hibbiki Chromium 157.0.8103.39 found no proxy
destinations during a 60-second empty-page observation with these controls.
Deliberate Google sign-in-page and example.com navigation still succeeded.
This observation is bounded in time and does not prove that the browser never
contacts Google. No real account login or exhaustive production-site check was
performed. Browser updates require renewed compatibility and traffic checks.

This application policy does not approve a particular third-party browser for
redistribution. Browser licensing, source materials and installer readiness are
separate release requirements.
