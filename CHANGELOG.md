# Changelog

All notable changes to PDF Maker are documented here. The format follows Keep a Changelog, and releases use Semantic
Versioning.

## [Unreleased]

No unreleased changes.

## [1.1.0] - 2026-09-03

### Added

- Machine-readable Android application classification and explicit Timber/Sentry observability evidence.
- A named document input validation boundary with focused size, password, envelope, and malformed-input tests.
- Emulator-independent API 35 launcher coverage through Robolectric while retaining the managed emulator suite.
- A committed CycloneDX 1.6 inventory for all 189 locked release-runtime components, enforced against stale changes.

### Changed

- Raised critical-domain coverage enforcement to 91% lines and 76% branches after a clean 512-test run.
- Renamed CI job identifiers to explicit lint, typecheck, and test gates for unambiguous automation discovery.

### Fixed

- Read thumbnail metadata from the canonical source path so equivalent paths share the same cache identity on every OS.
- Made committed dependency-inventory verification invariant to platform newline conversion.

## [1.0.0] - 2026-09-03

### Added

- Blocking CI for formatting, static analysis, Android lint, tests, coverage, APK/AAB builds, and emulator tests.
- CodeQL, dependency review, Gradle dependency submission, blocking OSV auditing, and Dependabot updates.
- Whole-application Kover reports plus a 90% line/75% branch gate for critical document and security policies.
- Reproducible Gradle wrapper and dependency-integrity controls.
- Contributor, security, architecture, dependency, and release documentation.
- Privacy-restricted structured logging and optional, build-time Sentry crash reporting; telemetry remains disabled
  by default and in debug builds.
- A blocking OSV scan of the exact locked release-runtime graph.

### Changed

- Enabled release code/resource shrinking and aligned compilation on JDK/JVM 17.
- Centralized direct dependency declarations in the Gradle version catalog.
- Extracted oversized Compose and document-processing files into focused, testable units.
- Raised critical-domain coverage enforcement to 90% lines and 75% branches.
- Refreshed Kotlin, AndroidX, Compose, CameraX, Coil, and coroutine dependencies within the API 36/AGP 8
  compatibility line and regenerated strict lock/checksum state.

[Unreleased]: https://github.com/BITS4/PDF-Maker/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/BITS4/PDF-Maker/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/BITS4/PDF-Maker/releases/tag/v1.0.0
