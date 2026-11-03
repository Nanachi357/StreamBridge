# Architecture

## Implemented runtime

StreamBridge is one local Windows product with a launcher/UI host and a child JVM
per operation. It is not a network service or a deployed set of microservices.

```text
Windows launcher (LocalUiLauncher)
  -> local HTTP server (LocalUiSpike) <-> ordinary browser UI: HTTP commands + SSE
  -> operation owner (LocalUiSession)
       -> child JVM (RealDiscoverySpike), bounded line-based JSON events on stdout
            -> isolated Playwright Chromium -> public-address SOCKS5 gateway
            -> observed HLS catalog -> user selection -> transfer plan
            -> DNS-pinned HTTP acquisition -> owned local segment files
            -> FFmpeg / FFprobe, local inputs only
       -> cancellable output copy -> final rename in chosen destination
```

Arrows show communication/dependency direction, not Java inheritance. The backend
owns operation state; UI state is a projection. SSE reconnects obtain a current
snapshot. A session key, Host/Origin checks and a restrictive CSP protect the
loopback UI; do not expose it through a remote bind or public tunnel.

## Discovery and selection

Discovery observes requests made by the selected page and its frames. It has no
default overall timeout or cumulative playlist-count cutoff. Pending requests
expire, caches are bounded, and the selected source is pinned against ordinary
eviction. Page closure, disconnection, navigation and extra tabs have explicit
lifecycle handling; a dynamic page cannot be proven to have no undiscovered streams.

`HlsCandidateCatalog` associates master variants and audio groups with their
observed frame and revisions. Selection uses a source revision so unrelated ads
or sources do not invalidate it. Declared metadata is not proof of actual encoded
content. Additional labels are exposed only when attributable.

`BrowserManifestBody` rejects compressed/unknown encodings before Playwright
materializes the decoded body. Known completed unencoded manifests are limited to
256 KiB. This intentionally reduces compatibility; Chromium memory is outside that
guard. See [SECURITY.md](../SECURITY.md).

## Acquisition and lifecycle

The pilot supports one operation with separate status and phase. Discovery leads
to selection/preflight, optional preview and explicit confirmation, acquisition,
remux/probe and saving. Separate discovery/download IDs exist, but durable job
queues and a general domain job API are not implemented. Jobs do not resume after
restart.

`PublicWebPolicy` and `BoundedResourceFetcher` validate and pin destinations and
bound resource reads. FFmpeg receives local files instead of arbitrary remote
URLs. The browser has a separate SOCKS5 gateway and policy; neither is an OS
firewall. Cookies and authorization are not generally forwarded to acquisition.

`HlsPreview` shares request/byte/deadline budgets across tracks and redirects.
`OutputFiles` copies into an owned partial file in the destination, then publishes
without replacing existing filenames. `LocalUiSession` serializes cancellation
with the final rename: whichever occurs first determines cancellation versus
success. Cleanup failure stays explicit. Hard crashes may leave owned files.

## Source layout and build

The pilot remains in `backend/src/test/java/dev/streambridge/spike`. Classes ending
in `Test` are JUnit tests; other classes are experiments and the current runtime.
The Gradle `pilot` source set compiles shared runtime sources while excluding
tests and the test-only worker fixture. It packages only UI resources from
`src/test/resources/spike/ui` and its own runtime dependencies. JUnit/Mockito and
Spring test libraries are not part of the pilot application.

`build` verifies the pilot JAR as well as testing Java. `bootJar` and the empty
main-source JAR are disabled because there is no Spring Boot entry point.
`packageWindowsApp` produces a fresh jpackage app-image with Java 21; external
tools remain prerequisites. Node runs UI tests, not the application UI.

This is an experimental layout, not a finished domain/application/infrastructure
architecture. The old in-memory repository/controller skeleton has been removed.

## Next boundaries

- Move proven behavior to production source packages incrementally, retaining
  regression tests; do not rewrite solely for a layer diagram.
- Keep one product/deployment lifecycle, as intended in ADR 0002. Worker processes
  isolate failures without becoming independently deployed services.
- Extract reusable modules only when a stable boundary and a real second consumer
  or independent lifecycle justify them.
- Before alpha.1: self-contained tools, dependency/license audit, consistent release
  identity, clean-Windows acceptance and final artifact review.

CI and local fixtures cover selected scenarios, not every hostile page, filesystem
race or operating-system configuration.
