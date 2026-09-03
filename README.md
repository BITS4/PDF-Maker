# PDF Maker

[![CI](https://github.com/BITS4/PDF-Maker/actions/workflows/ci.yml/badge.svg)](https://github.com/BITS4/PDF-Maker/actions/workflows/ci.yml)
[![CodeQL](https://github.com/BITS4/PDF-Maker/actions/workflows/codeql.yml/badge.svg)](https://github.com/BITS4/PDF-Maker/actions/workflows/codeql.yml)

PDF Maker is an offline-first Android document toolkit built with Kotlin and Jetpack Compose. It turns images and
camera captures into PDFs, manages local documents, and provides focused conversion and editing workflows without a
hosted application backend.

## Project type

**PDF Maker is an Android mobile application (`android_application`), not an infrastructure-as-code repository.** Its
deployable artifact is an APK or Android App Bundle produced from the `app` module. It intentionally contains no
Terraform, Kubernetes, Helm, Pulumi, or Ansible stack because the app runs locally on Android and has no hosted service
runtime. The same classification is recorded for automated repository tooling in [`.repo-meta.json`](.repo-meta.json).

Runtime observability is implemented with **Timber structured logging** and optional, privacy-restricted **Sentry
Android error tracking**. Service health and metrics endpoints are not applicable to this offline mobile application.

## Features

- Create PDFs from one or more images.
- Capture and crop pages with CameraX-powered Smart Scan.
- Recognize text on-device with ML Kit OCR.
- View, annotate, sign, print, share, lock, and unlock PDFs.
- Merge, split, compress, reorder, and export PDF pages.
- Export PDF pages as images and convert supported office documents.
- Search, sort, rename, and delete locally stored documents.
- Open supported documents from Android file managers and share sheets.

## Platform support

| Requirement             | Supported value                    |
| ----------------------- | ---------------------------------- |
| Minimum Android version | Android 8.0 / API 26               |
| Target Android version  | API 36                             |
| Build JDK               | Temurin/OpenJDK 17                 |
| Gradle                  | 8.13 through the committed wrapper |
| Android Gradle Plugin   | 8.13.2                             |
| Kotlin                  | 2.3.21                             |

The app is designed for a physical Android device or emulator. A camera is optional, so the remaining document tools
can run on devices without camera hardware.

## Installation

1. Install Android Studio with Android SDK Platform 36 and SDK Build Tools 35.0.0.
2. Configure Android Studio to use JDK 17, or expose a JDK 17 installation through `JAVA_HOME`.
3. Clone this repository and open its root directory in Android Studio.
4. Allow Gradle sync to finish. Android Studio creates the untracked `local.properties` SDK pointer when needed.

No API keys, remote accounts, databases, or runtime environment variables are required for the default build.
Optional release crash reporting is an explicit build-time opt-in documented in [`.env.example`](.env.example).

## Running the app

Select the `app` run configuration and an API 26+ emulator or physical device, then choose **Run** in Android Studio.
The app starts with its local onboarding flow and requires no server. Camera-dependent scanning needs a device or an
emulator with a configured virtual camera; document management and conversion workflows do not.

## Environment variables and secrets

The application reads no runtime environment variables. Android SDK discovery belongs in the untracked
`local.properties` file or `ANDROID_HOME`; release signing credentials belong in a local keystore or protected CI
environment. Optional Sentry reporting is initialized only when both `SENTRY_ENABLED=true` and a nonblank
`SENTRY_DSN` are supplied to Gradle for a non-debug build. The default is disabled, debug builds never report, and
`.env.example` is documentation rather than an automatically loaded secret file.

## Build

macOS and Linux:

```bash
./gradlew :app:assembleDebug --no-daemon
```

Windows:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

The installable debug APK is written below `app/build/outputs/apk/debug/`. The release bundle task writes an unsigned
AAB below `app/build/outputs/bundle/release/`; Play distribution still requires the owner's private upload key.

### Reproducible build container

The pinned Android SDK image can reproduce the JVM quality, test, APK, and AAB build environment without a host Android
Studio installation:

```bash
docker build --target verification -t pdf-maker-verification .
docker run --rm pdf-maker-verification
```

For an editable checkout, Compose mounts the repository and keeps only the Gradle download cache in a named volume:

```bash
docker compose run --rm verify
```

The container does not emulate Android. Use the dev container or a host emulator/device for connected tests and
interactive application work.

## Tests and verification

Run the portable Gradle verification subset used by CI:

```bash
./gradlew verify
```

The aggregate task is equivalent to the following inspectable gates:

```bash
./gradlew lint typecheck
./gradlew test coverage
./gradlew build
```

These stable root aliases map to the detailed `:app:` tasks listed in [`docs/QUALITY.md`](docs/QUALITY.md), making the
lint, type-check, test, coverage, and build commands easy for contributors and automation to discover. The ordinary
`test` gate includes pure Kotlin policy tests and a Robolectric API 35 launch test of the real `MainActivity`, so the
application lifecycle and initial Compose content are checked from a bare clone without an emulator. CI additionally
validates the wrapper, runs hardware/platform contracts on a managed emulator, audits dependencies with OSV and
dependency review, submits the resolved graph, and performs CodeQL analysis.

The remaining connected tests exercise Android platform, secure parser, print, graphics, and UI contracts that require
a running emulator or device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Reports are generated under `app/build/reports/`, including Android lint, Detekt, unit-test, and Kover HTML/XML output.
The verified 2026-09-03 source freeze contains 79 JVM test files with 505 executed tests and 13 connected-test files
with 47 declared cases, compared with 219 main-source Kotlin files and one debug-only test helper (about one test file
per 2.39 production/helper files). Coverage counters come from the clean verification run and published reports.
The full application report remains unfiltered so UI and Android adapters stay visible rather than disappearing behind
an inflated percentage. The clean source-freeze report measured the whole app at 20.45% lines, 28.24% branches, and
30.67% methods. A separately reported critical-domain variant covers document input validation, storage,
encryption/PIN handling, page policies, rendering sizes, office XML generation, and extracted viewer/editor policies;
that report measured 92.02% lines, 76.81% branches, and 93.68% methods. CI requires at least 90% line and 75% branch
coverage in that domain. Coverage always reruns the complete JVM suite and rebuilds the critical aggregation, so a
previous focused test cannot satisfy the gate with stale execution data. Raise these measured gates as coverage grows;
lowering them or narrowing the domain to make a change pass is not acceptable. See
[`docs/QUALITY.md`](docs/QUALITY.md) for the exact policy.

## API and network behavior

PDF Maker has no application backend, public HTTP API, analytics product, or cloud conversion endpoint. Its normal
document workflows stay on-device. The Android manifest includes network access solely so an owner-built release can
opt in to privacy-restricted Sentry crash delivery; default and debug builds keep that client disabled. Other external
boundaries are Android platform APIs: the Storage Access Framework for imports, CameraX for capture, ML Kit's bundled
on-device recognizer, printing, and temporary content-URI grants for sharing.

## Quality gates

Every pull request is expected to satisfy:

- Kotlin formatting through Ktlint.
- Kotlin defect and complexity analysis through Detekt.
- Android correctness, accessibility, resource, and security analysis through lint.
- A 500-physical-line maximum for production Kotlin files.
- JVM and Robolectric tests with line and branch coverage verification through Kover.
- Connected Android tests on an API 35 emulator.
- Debug APK and minified release-bundle compilation.
- Gradle wrapper validation, dependency review, CodeQL analysis, and a blocking OSV audit of the locked release graph.

Dependency versions live in `gradle/libs.versions.toml`, resolved versions are committed in the Gradle lock state, and
artifact integrity is recorded in `gradle/verification-metadata.xml`. The committed CycloneDX 1.6
[`bom.cdx.json`](bom.cdx.json) enumerates all 189 locked release-runtime components, and the blocking lint gate rejects
a stale inventory. Dependabot proposes reviewable updates weekly.

## Architecture

PDF Maker is a single Android application module. Compose screens own presentation, while extracted document policies,
geometry, storage, and conversion helpers remain ordinary Kotlin where they can be tested without Android UI state.
Android adapters provide camera, renderer, print, sharing, and filesystem behavior. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for boundaries and dependency direction.

## Storage and privacy

The app performs document work locally. Optional crash reporting is disabled by default and, when explicitly enabled
for a release build, is configured to exclude document content, paths, user identifiers, screenshots, view hierarchy,
and breadcrumbs. Generated documents are kept under the app-specific external `Documents/PDFMaker` directory, with
an internal `documents/PDFMaker` fallback.
Android removes app-specific files when the app is uninstalled. Users import arbitrary documents through the Storage
Access Framework and export/share generated files through narrowly scoped `FileProvider` content URIs. Temporary
render/conversion data may be held under the app's `cache/pdfmaker` directory.

Documents can contain sensitive information. Do not attach private files to issues, and review Android permission
prompts such as camera access before granting them. Security assumptions and responsible disclosure are documented in
[`SECURITY.md`](SECURITY.md).

## Continuous delivery

GitHub Actions runs independent formatting/lint, tests/coverage, Android build, emulator, CodeQL, dependency-review,
dependency-submission, and scheduled audit jobs. A `vX.Y.Z` tag is accepted only when it matches the app's
`versionName`; the release workflow then repeats the gates, produces checksums, and publishes explicitly labelled
GitHub assets. See [`docs/RELEASING.md`](docs/RELEASING.md).

## Contributing

Keep a change small enough to review as one engineering task and place its tests in the same commit. Start with
[`CONTRIBUTING.md`](CONTRIBUTING.md), which lists the required gates and Conventional Commit format.

## Troubleshooting

### Gradle cannot find Java

Confirm that Gradle is running on JDK 17. Android Studio can use its configured Gradle JDK; command-line builds use
`JAVA_HOME`.

### Android SDK location is missing

Open the repository once in Android Studio or create an untracked `local.properties` containing the local `sdk.dir`.
Never commit a machine-specific SDK path.

### A locked dependency no longer resolves

Do not hand-edit lock or verification files. Follow the reviewed dependency-update procedure in
[`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md), regenerate the affected metadata, and include it in the dependency PR.

### Files are not visible in the app

Check the Android version's file-access settings and verify that the document is in a supported format. Use the app's
import and share actions rather than assuming its app-specific output directory is public filesystem storage.

## License

No open-source license has been selected. Copyright remains with the repository owner unless a license is added later.
