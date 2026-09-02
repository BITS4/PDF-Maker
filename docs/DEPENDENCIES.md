# Dependency policy

## Sources of truth

- Direct library and plugin versions: `gradle/libs.versions.toml`
- Version-catalog import lock state: `settings-gradle.lockfile`
- Resolved application graph: `app/gradle.lockfile`
- Artifact integrity: `gradle/verification-metadata.xml`
- Gradle runtime: `gradle/wrapper/gradle-wrapper.properties`

Literal dependency coordinates should not be added to module build files. Dynamic versions and snapshots are not
accepted.

## Reviewed platform baseline

The current Android build deliberately aligns Android Gradle Plugin 8.13.2 with Gradle 8.13, JDK 17, and compile/target
SDK 36. Direct AndroidX, Compose, CameraX, ML Kit, Coil, and coroutines versions remain individually visible in the
catalog so automated scanners can enumerate them.

Some libraries may be behind their newest major version. They should be upgraded in isolated pull requests after
checking Kotlin metadata compatibility, Android API requirements, behavior changes, and release notes. A newer number
alone is not sufficient evidence for a safe document-processing release.

## Updating a dependency

1. Review upstream release and security notes.
2. Update only the related catalog entry or dependency family.
3. Refresh Gradle lock state for tasks that resolve the changed configuration.
4. Refresh SHA-256 verification metadata and review every new repository coordinate/checksum.
5. Run formatting, Detekt, Android lint, unit tests, Kover verification, emulator tests, and both Android builds.
6. Commit the catalog, lock, verification, behavior, and test changes together.

Never hand-edit a lock entry or copy an unreviewed verification file from CI.

## Automation

Dependabot checks Gradle, GitHub Actions, and the tagged Temurin base image weekly. Both container stages remain pinned
by digest; the Android SDK image digest is reviewed manually because it is the second `FROM` instruction. Pull requests
block newly introduced high-severity vulnerable dependencies, the resolved graph is submitted to GitHub, and Google's OSV Scanner
blocks known vulnerabilities in the locked release runtime graph on every main-branch change and weekly. The
`writeRuntimeOsvManifest` task derives its input from `app/gradle.lockfile`; build/test tooling stays covered by
Dependabot and dependency review without being misrepresented as shipped Android code. The OSV workflow is pinned to
an immutable revision and needs no third-party API key.

## Deferred upgrades

Record an intentionally deferred major update here with the package, reviewed version/date, reason, risk, and follow-up
issue. This makes dependency age a visible engineering decision instead of an invisible omission.

| Family                     | Retained baseline                                             | Reviewed   | Reason and follow-up                                                                                                                                                                                  |
| -------------------------- | ------------------------------------------------------------- | ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android platform toolchain | AGP 8.13.2, Gradle 8.13, compile/target SDK 36, Kotlin 2.3.21 | 2026-09-02 | Compose 1.12, Core 1.19, and Kotlin 2.4 require the API 37/AGP 9 generation. Adopt that toolchain as one tested platform migration rather than bypassing published AAR/R8 compatibility requirements. |
| Jetpack Compose            | BOM 2026.06.01 / Compose 1.11.4                               | 2026-09-02 | This is the newest stable Compose line compatible with the current toolchain. Move to the 2026.08 BOM only with the API 37/AGP 9 migration.                                                           |
| AndroidX Core              | 1.18.0                                                        | 2026-09-02 | Core 1.19 declares `minCompileSdk=37` and `minAndroidGradlePluginVersion=9.1.0`; upgrade it with the platform migration.                                                                              |
| AndroidX Lifecycle         | 2.10.0                                                        | 2026-09-02 | Lifecycle 2.11's Compose runtime requires the API 37/AGP 9.1 generation; upgrade the lifecycle family with that platform migration.                                                                   |
| Coil                       | 2.7.0                                                         | 2026-09-02 | 2.7.0 is the latest 2.x release. Treat Coil 3 as an isolated API/package migration with image-loading regression tests.                                                                               |
