# Architecture

## System context

PDF Maker is a local Android application. Android supplies documents, images, camera frames, printing, and sharing; the
application transforms those inputs and writes user-owned output. There is no application server or remote database.

```text
Android intents / picker / camera
              |
              v
    Compose screen and state
              |
              v
   Tested document policies/helpers
              |
              v
Renderer / OCR / filesystem / print adapters
              |
              v
App-specific Documents/PDFMaker
```

## Dependency direction

1. Compose code may call pure policies and narrow Android adapters.
2. Pure policy code must not import Compose, Activity, Context, mutable UI state, or concrete filesystem services.
3. Adapters translate Android or document-library outcomes into explicit success/failure values.
4. Navigation passes identifiers or content URIs; it should not become a second document-processing layer.

This direction allows page selection, ranges, names, geometry, validation, ordering, and conversion decisions to run in
ordinary JVM tests.

## Primary capabilities

- **Acquisition:** Android picker, share/open intents, image selection, and CameraX scan capture.
- **Document policy:** supported formats, page/range rules, output naming, transformations, and failure mapping.
- **Rendering and conversion:** Android PDF rendering, bitmap processing, OCR, export, and office conversion.
- **Persistence:** app-specific generated documents plus bounded temporary cache files.
- **Presentation:** Compose screens, navigation, accessibility semantics, and user confirmation.

## Data ownership

Generated documents live below the app-specific external `Documents/PDFMaker` directory, with an internal
`documents/PDFMaker` fallback. Android removes both app-specific locations on uninstall. Temporary artifacts use the
app's `cache/pdfmaker` directory. Imports arrive through the Storage Access Framework; exports use `FileProvider`
content URIs with temporary grants. These boundaries keep canonical paths, filename validation, size limits, and
cleanup behavior explicit without broad storage permissions.

## Failure model

Expected input errors should return a typed or sealed result that the UI can explain. Unexpected parser or platform
errors may be logged without document content and mapped to a safe generic message. Code must not silently report
success after partial output.

## Observability boundary

Timber provides structured local logging without document contents or filesystem paths. Sentry is an optional release
crash sink behind an explicit build-time flag and nonblank DSN; automatic SDK startup is disabled, debug/default builds
remain off, and event enrichment strips PII, breadcrumbs, screenshots, view hierarchy, and local path details. Feature
code logs through the local abstraction and does not initialize or call a remote SDK directly.

## Test strategy

- JVM unit tests cover pure policy and transformation decisions.
- Android instrumentation tests cover intents, providers, permissions, resources, and essential Compose journeys.
- Small synthetic documents exercise parser boundaries without checking private samples into Git.
- Kover gates JVM-tested code; Android lint, Detekt, and CodeQL provide complementary static evidence.

## Growth rules

- Production Kotlin files are limited to 500 physical lines.
- A new capability should arrive as a focused policy/adapter/UI slice with its tests.
- Shared code moves into a named package instead of being copied between screens.
- New permissions require a documented use case and a security review.
