# Security policy

Please do not report vulnerabilities through a public issue. Until a dedicated
security contact is configured, repository owners should enable GitHub private
vulnerability reporting before publishing the project.

Never include credentials, browser cookies, authorization headers, signed media
URLs, personal data, or copyrighted media in a report. Use synthetic examples.

## Security-sensitive areas

The experimental browser and download components treat page URLs, redirects,
manifests, filenames, subprocess arguments, and downloaded content as untrusted
input. Passing functional tests is not a comprehensive security assessment.

Chromium discovery and startup checks use the shared `ChromiumLaunch` policy:
the OS sandbox is explicitly requested, startup is bounded to 20 seconds, and a
failed launch is never retried with the sandbox disabled. An isolated browser
context separates session data; it is not a substitute for OS sandboxing.
Do not add `--no-sandbox` as a workaround for startup failures.

Windows integration checks inspect launch arguments and Chromium's own
`chrome://sandbox` renderer policy report (Lockdown token and Untrusted integrity).
These checks do not prove that every auxiliary process is sandboxed, prevent all
browser exploits, or replace clean-Windows acceptance of the final package.

Discovery and startup Chromium use an application-owned, loopback-only SOCKS5
CONNECT gateway. For each new connection it resolves and checks all returned IP
addresses, rejects non-public destinations and ports other than 80/443, and connects
to a checked IP without a second lookup. There is no cached host-allow decision or
direct-connection fallback. Chromium's implicit loopback proxy bypass is removed.
TLS is tunneled unchanged: certificate validation remains enabled; the gateway
does not decrypt traffic or retain URLs, headers, bodies or credentials.

QUIC is disabled and WebRTC is configured to disallow non-proxied UDP. Integration
checks use owned HTTP/TLS/UDP/TCP fixtures to check redirects, frames, numeric
loopback URLs, DNS changes, untrusted certificates, gateway shutdown and WebRTC
bypass. Startup also checks direct WebRTC against owned local receivers and refuses
to continue on failure. Re-run these behavioral checks for every Chromium update:
accepting a launch flag alone is not evidence that it works.

The gateway bounds concurrent sessions to 64 (not discovered playlists), handshake
time to 10 seconds, individual connects to 5 seconds and directional read inactivity
to 60 seconds. Shutdown closes active tunnels; delayed DNS cannot initiate a new
connection after its session has expired. A blocked OS DNS lookup can still occupy
one bounded worker slot until it returns. System DNS configuration is unchanged.

This is not an OS firewall or a guarantee against browser exploits, hostile local
processes, arbitrary browser extensions or every auxiliary networking feature.
SOCKS5 has no authentication in Chromium; the listener is local only and applies
the same destination restrictions to all clients. Sites requiring WebRTC, custom
ports, private addresses or blocked WebSockets may not work. The media acquisition
HTTP client retains its separate DNS-pinning policy. Clean-Windows acceptance and
security review of the final distributed image remain release gates.

Preview acquisition has one shared budget across video/audio tracks and redirects:
128 HTTP requests, 128 MiB of accepted media bodies, and a two-minute work deadline
including remux/probe. Per-resource limits still apply. Untrusted playlist durations
and a requested five-second clip do not authorize unlimited segment acquisition.
Timeouts are clamped to the remaining deadline; process shutdown and filesystem
cleanup can take additional time. These are application budgets, not OS quotas.

Browser manifest extraction rejects any Content-Encoding other than identity before
calling Playwright response.body(), which materializes the decoded response. Only
completed unencoded bodies with a known actual transfer size of at most 256 KiB
are read. Compressed manifests are currently unsupported and reported in the UI;
this can reduce available sources. This guard does not bound the page's own memory
use inside Chromium. Bounded support for compressed manifests remains future work.

Cancellation remains effective while copying a completed video to its destination.
The final rename and cancellation decision share a lock: cancellation before the
rename prevents publication; after publication, success wins and the completed
file is retained. Failed partial-file cleanup is reported as uncertain rather
than silently declared successful. Existing destination files are not overwritten.
