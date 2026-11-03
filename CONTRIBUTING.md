# Contributing

## Development principles

- Keep the application site-agnostic.
- Do not add DRM circumvention or access-control bypasses.
- Do not commit media files, credentials, cookies, signed URLs, or real captured
  traffic.
- Prefer small vertical changes with tests over speculative abstractions.
- Record architectural decisions that have meaningful alternatives and lasting
  consequences.

## Before opening a change

Use JDK 21 and Node.js 22. From the repository root on Windows:

```shell
.\backend\gradlew.bat -p backend clean build
node --test backend/src/test/js/ui-connection.test.cjs
```

`build` tests Java and verifies the pilot JAR's entry point, UI resources and test
exclusions. Use `./backend/gradlew -p backend ...` on Unix for the portable suite.
Browser/media tests skip when their prerequisites are absent. Follow the README
to install tools and set `STREAMBRIDGE_UI_BROWSER_CHECK=1` and
`STREAMBRIDGE_VISIBLE_BROWSER_CHECK=0` for full hidden Windows integration tests.
Never weaken the sandbox, URL policy or TLS validation to make a test pass.

Add regression tests for behavior changes, especially cleanup, cancellation,
untrusted input and concurrency. Prefer owned local fixtures; tests must not
depend on real video websites or captured credentials. Documentation-only changes
do not require artificial tests that merely repeat the document.

## Continuous integration

The workflow runs on pushes and pull requests with read-only repository permissions:

- Portable Java build/tests and Node UI tests on Ubuntu.
- Windows integration with freshly installed pinned Chromium and checksum-verified
  FFmpeg, no visible browser windows; skipped Java tests fail this job.
- Windows app-image creation and hidden self-check using its bundled Java.
- Gitleaks on the checked-out files, with redacted output and no blanket exclusions.

No release, binary artifact or deployment is published by CI. A hosted runner is
not a replacement for a clean supported end-user Windows acceptance test. Workflow
changes require review; actions are pinned to commits and should be updated
deliberately. Public dependency/security review remains separate from passing tests.

## Before committing or reporting

Inspect the actual staged diff, including new files. Do not rely only on .gitignore.
Scan the proposed source tree with Gitleaks and manually inspect matches. Test data
that resembles a credential needs an explanation, not a broad scanner suppression.
Do not attach raw startup logs, browser profiles, signed URLs or personal paths.
See SECURITY.md for private vulnerability reporting. Project creator/maintainer:
Vladyslav Ponomarenko ([Nanachi357](https://github.com/Nanachi357)).
