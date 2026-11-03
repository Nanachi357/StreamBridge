# ADR 0002: Start with a modular monolith

- Status: Accepted direction; capability package boundaries not yet implemented
- Date: 2026-09-03

## Context

Browser control, stream inspection, download orchestration, and local file output
have distinct responsibilities, but they currently form one product with one
deployment lifecycle.

## Decision

Keep them in one application and separate capabilities through Java packages and
explicit interfaces. Do not create independent services or publish reusable
libraries yet.

## Rationale

- A single product and deployment lifecycle is easier to run and package locally.
- Package boundaries preserve opportunities for later extraction.
- Independent services would add deployment, communication, and failure modes
  without solving a current problem.

## Consequences

- Boundaries are enforced through code structure and later with architecture
  tests.
- A module is extracted only when it has an independent lifecycle or a proven
  second consumer.

## Implementation note (2026-11-03)

The pilot uses a launcher/UI JVM and a child JVM per operation to isolate failures.
These are internal processes, not independent services. Experiments still share
the `spike` package and test source directory; the old API skeleton was removed.
See [current architecture](../architecture.md). This ADR describes intended
boundaries, not an assertion that they are already enforced.
