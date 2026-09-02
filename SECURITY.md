# Security policy

## Supported versions

| Version           | Security fixes |
| ----------------- | -------------- |
| 1.0.x             | Supported      |
| Earlier snapshots | Not supported  |

## Reporting a vulnerability

Use the repository's private GitHub security-advisory form:

https://github.com/BITS4/PDF-Maker/security/advisories/new

Do not open a public issue and do not attach a private document. Include the affected version, Android version, a
minimal reproduction using synthetic data, impact, and any suggested mitigation. The maintainer should acknowledge a
complete report within seven days and coordinate disclosure after a fix is available.

## Security boundaries

- PDF, image, and office inputs are untrusted, even when selected by the device owner.
- File operations must remain inside an explicitly selected or application-owned location.
- Imported content URIs are copied only after the 100 MiB bound and format-specific signature checks pass.
- DOCX archives are checked for traversal and decompression-bomb patterns; images are bounded by decoded pixel count.
- Shared files use content URIs and temporary read grants rather than exposed filesystem paths.
- `FileProvider` exposes only generated app-specific document directories and `cache/pdfmaker`.
- Parser loops and bitmap/document allocations require explicit size/page/count bounds.
- Signing keys and credentials are supplied outside source control.
- Logs, crashes, tests, and issue reports must not contain document contents or sensitive paths.

## Optional crash reporting

Telemetry is disabled by default and in every debug build. A repository owner may opt a non-debug build into Sentry
only by supplying both `SENTRY_ENABLED=true` and a nonblank `SENTRY_DSN` at build time. The initializer disables
automatic SDK startup and excludes personally identifiable information, breadcrumbs, screenshots, view hierarchy,
document contents, and local paths. Leaving either value unset keeps the client off; `.env.example` is not loaded
automatically.

## Automated controls

Pull requests run Android lint, Detekt, tests, dependency review, and CodeQL. Gradle dependency graphs are submitted to
GitHub, Dependabot proposes updates, a blocking OSV Scanner job audits the locked release runtime graph on every
main-branch change and weekly, and release artifacts receive SHA-256 checksums.

Automated analysis does not replace review of permissions, intent handling, archive traversal, memory limits, or
document-parser failure paths.
