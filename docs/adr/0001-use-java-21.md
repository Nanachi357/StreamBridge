# ADR 0001: Use Java 21 as the baseline

- Status: Accepted
- Date: 2026-09-03

## Context

StreamBridge is both a practical local application and a JVM backend portfolio
project. Its future workload will wait on browser traffic, network responses, and
external FFmpeg processes.

## Decision

Use Eclipse Temurin Java 21 as the compilation, test, and runtime baseline.

## Rationale

- Java 21 is an LTS release.
- Stable virtual threads fit blocking browser, network, and subprocess work.
- It is a conservative baseline for JVM backend roles while still demonstrating
  modern Java.
- The selected Spring Boot and Gradle versions support Java 21.

## Consequences

- Production code may use stable Java 21 features but not preview features.
- CI must test on Java 21.
- Moving to a newer LTS will require a deliberate ADR rather than an incidental
  local JDK change.
