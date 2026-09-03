# Dependency policy

## Sources of truth

- Direct library and plugin versions: `gradle/libs.versions.toml`
- Version-catalog import lock state: `settings-gradle.lockfile`
- Resolved application graph: `app/gradle.lockfile`
- Artifact integrity: `gradle/verification-metadata.xml`
- Gradle runtime: `gradle/wrapper/gradle-wrapper.properties`
- Machine-readable release inventory: `bom.cdx.json`

Literal dependency coordinates should not be added to module build files. Dynamic versions and snapshots are not
accepted.

## Auditable dependency snapshot

The 2026-09-03 reviewed graph contains 21 direct application-runtime declarations, eight direct test/debug
declarations, and six build-plugin declarations. Resolving the locked `releaseRuntimeClasspath` produces 189 unique
Maven components; all 189 are recorded with package URLs in the committed CycloneDX 1.6 `bom.cdx.json`. The larger
458-entry application lockfile also covers compile, unit-test, connected-test, debug, and release configurations and
must not be presented as the shipped runtime footprint.

`writeDependencyInventory` deterministically regenerates the SBOM from the release entries in
`app/gradle.lockfile`. `checkDependencyInventory` compares that content with the committed file after portable newline
normalization and is a dependency of the blocking root `lint` task, so a version or lock change cannot merge with stale
inventory evidence. The separate `writeRuntimeOsvManifest` task exports the same 189-component release graph to the
OSV audit job.

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

Dependabot checks Gradle, GitHub Actions, and the tagged Temurin base image weekly. Both external container base images
remain pinned by digest; the Android SDK digest in the second external `FROM` instruction is reviewed manually. Pull
requests block newly introduced high-severity vulnerable dependencies, the resolved graph is submitted to GitHub, and
Google's OSV Scanner blocks known vulnerabilities in the locked release runtime graph on every main-branch change and
weekly. The `writeRuntimeOsvManifest` task derives its input from `app/gradle.lockfile`; build/test tooling stays covered
by Dependabot and dependency review without being misrepresented as shipped Android code. The OSV workflow is pinned
to an immutable revision and needs no third-party API key.

## Deferred upgrades

Record an intentionally deferred major update here with the package, reviewed version/date, reason, risk, and follow-up
issue. This makes dependency age a visible engineering decision instead of an invisible omission.

| Family                     | Retained baseline                                             | Reviewed   | Reason and follow-up                                                                                                                                                                                                                  |
| -------------------------- | ------------------------------------------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android platform toolchain | AGP 8.13.2, Gradle 8.13, compile/target SDK 36, Kotlin 2.3.21 | 2026-09-02 | Published AAR metadata for Core 1.19, Compose UI 1.12, and Lifecycle Runtime Compose 2.11 requires compile SDK 37 and AGP 9.1. Kotlin 2.4 also needs a newer R8 than AGP 8.13.2 bundles. Move these as one tested platform migration. |
| Jetpack Compose            | BOM 2026.06.01 / Compose 1.11.4                               | 2026-09-02 | The published 2026.08 BOM selects Compose UI 1.12, whose AAR metadata requires compile SDK 37 and AGP 9.1. Retain the 2026.06.01 line until that platform migration.                                                                  |
| AndroidX Core              | 1.18.0                                                        | 2026-09-02 | Core 1.19 declares `minCompileSdk=37` and `minAndroidGradlePluginVersion=9.1.0`; upgrade it with the platform migration.                                                                                                              |
| AndroidX Lifecycle         | 2.10.0                                                        | 2026-09-02 | The published `lifecycle-runtime-compose-android` 2.11 AAR declares `minCompileSdk=37` and `minAndroidGradlePluginVersion=9.1.0`; upgrade the lifecycle family with that platform migration.                                          |
| Coil                       | 2.7.0                                                         | 2026-09-02 | 2.7.0 is the latest 2.x release. Treat Coil 3 as an isolated API/package migration with image-loading regression tests.                                                                                                               |
