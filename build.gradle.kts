import org.gradle.api.GradleException
import org.gradle.api.artifacts.dsl.LockMode
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set(libs.versions.ktlintEngine)
    ignoreFailures.set(false)
    outputToConsole.set(true)
    reporters {
        reporter(ReporterType.CHECKSTYLE)
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.SARIF)
    }
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
    }
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

fun lockedReleaseRuntimeCoordinates(lockFile: File): List<Triple<String, String, String>> {
    val safePart = Regex("[A-Za-z0-9_.+\\-]+")
    return lockFile
        .readLines(Charsets.UTF_8)
        .asSequence()
        .filterNot { line -> line.isBlank() || line.startsWith('#') || line.startsWith("empty=") }
        .mapNotNull { line ->
            val (coordinate, configurations) =
                line.split('=', limit = 2).takeIf { it.size == 2 }
                    ?: throw GradleException("Unexpected Gradle lock entry: $line")
            if ("releaseRuntimeClasspath" !in configurations.split(',')) return@mapNotNull null

            val parts = coordinate.split(':')
            if (parts.size != 3 || parts.any { part -> !safePart.matches(part) }) {
                throw GradleException("Cannot safely export Maven coordinate: $coordinate")
            }
            Triple(parts[0], parts[1], parts[2])
        }.distinct()
        .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        .toList()
        .ifEmpty { throw GradleException("No locked releaseRuntimeClasspath dependencies were found") }
}

fun cycloneDxInventory(
    lockFile: File,
    applicationVersion: String,
): String {
    val components =
        lockedReleaseRuntimeCoordinates(lockFile).joinToString(",\n") { (group, name, version) ->
            val purl = "pkg:maven/$group/$name@$version"
            """    {"type":"library","bom-ref":"$purl","group":"$group","name":"$name","version":"$version","scope":"required","purl":"$purl"}"""
        }
    return """{
  "bomFormat": "CycloneDX",
  "specVersion": "1.6",
  "version": 1,
  "metadata": {
    "component": {
      "type": "application",
      "name": "PDF Maker",
      "version": "$applicationVersion"
    },
    "properties": [
      {"name": "pdf-maker:source-configuration", "value": "releaseRuntimeClasspath"},
      {"name": "pdf-maker:lockfile", "value": "app/gradle.lockfile"}
    ]
  },
  "components": [
$components
  ]
}
"""
}

val productionKotlin =
    fileTree("app/src/main") {
        include("**/*.kt")
    }

tasks.register("checkSourceFileSize") {
    group = "verification"
    description = "Fails when a production Kotlin source file exceeds 500 physical lines."
    inputs.files(productionKotlin)

    doLast {
        val oversized =
            productionKotlin.files
                .map { file -> file.relativeTo(rootDir) to file.readLines(Charsets.UTF_8).size }
                .filter { (_, lineCount) -> lineCount > 500 }
                .sortedByDescending { (_, lineCount) -> lineCount }

        if (oversized.isNotEmpty()) {
            val details =
                oversized.joinToString(separator = System.lineSeparator()) { (file, lineCount) ->
                    "  - ${file.invariantSeparatorsPath}: $lineCount lines"
                }
            throw GradleException(
                "Production Kotlin files must stay at or below 500 lines:${System.lineSeparator()}$details",
            )
        }
    }
}

tasks.register("checkPrivacySafeLogging") {
    group = "verification"
    description = "Fails when production code bypasses the privacy-safe Timber logging boundary."
    inputs.files(productionKotlin)

    doLast {
        val approvedSink = "app/src/main/java/com/example/pdfmaker/PrivacySafeTree.kt"
        val prohibitedMarkers =
            listOf(
                "import android.util.Log",
                "import android.util.*",
                "android.util.Log.",
            )
        val unqualifiedLogCall = Regex("""\bLog\.""")
        val violations =
            productionKotlin.files
                .filter { file -> file.relativeTo(rootDir).invariantSeparatorsPath != approvedSink }
                .filter { file ->
                    val source = file.readText(Charsets.UTF_8)
                    prohibitedMarkers.any(source::contains) || unqualifiedLogCall.containsMatchIn(source)
                }.map { file -> file.relativeTo(rootDir).invariantSeparatorsPath }
                .sorted()

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Production code must log through Timber and the privacy-safe sink; direct Android Log usage found in: " +
                    violations.joinToString(),
            )
        }
    }
}

tasks.register("lint") {
    group = "verification"
    description = "Runs Kotlin formatting, Detekt, Android lint, and the production file-size gate."
    dependsOn(
        "checkDependencyInventory",
        "checkPrivacySafeLogging",
        "checkSourceFileSize",
        "ktlintCheck",
        ":app:ktlintCheck",
        ":app:detekt",
        ":app:lintDebug",
    )
}

tasks.register("typecheck") {
    group = "verification"
    description = "Compiles debug Kotlin sources as the project's explicit type-check gate."
    dependsOn(":app:compileDebugKotlin")
}

tasks.register("test") {
    group = "verification"
    description = "Runs the deterministic JVM unit-test suite."
    dependsOn(":app:testDebugUnitTest")
}

tasks.register("coverage") {
    group = "verification"
    description = "Reports whole-app coverage and enforces the tested critical-domain thresholds."
    dependsOn(
        ":app:testDebugUnitTest",
        ":app:koverHtmlReportCritical",
        ":app:koverHtmlReportDebug",
        ":app:koverVerifyCritical",
        ":app:koverXmlReportCritical",
        ":app:koverXmlReportDebug",
    )
}

tasks.register("build") {
    group = "build"
    description = "Builds the installable debug APK and the minified unsigned release bundle."
    dependsOn(
        ":app:assembleDebug",
        ":app:bundleRelease",
    )
}

tasks.register("writeRuntimeOsvManifest") {
    group = "verification"
    description = "Exports the locked release runtime graph in OSV Scanner's documented interchange format."

    val lockFile = layout.projectDirectory.file("app/gradle.lockfile")
    val outputFile = layout.buildDirectory.file("reports/dependency-audit/osv-scanner.json")
    inputs.file(lockFile)
    outputs.file(outputFile)

    doLast {
        val runtimeCoordinates = lockedReleaseRuntimeCoordinates(lockFile.asFile)

        val packages =
            runtimeCoordinates.joinToString(",\n") { (group, name, version) ->
                """        {"package":{"name":"$group:$name","version":"$version","ecosystem":"Maven"}}"""
            }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """{
  "results": [
    {
      "source": {"path": "app/gradle.lockfile", "type": "lockfile"},
      "packages": [
$packages
      ]
    }
  ]
}
""",
                Charsets.UTF_8,
            )
        }
    }
}

val dependencyLockFile = layout.projectDirectory.file("app/gradle.lockfile")
val dependencyInventoryFile = layout.projectDirectory.file("bom.cdx.json")
val applicationBuildFile = layout.projectDirectory.file("app/build.gradle.kts")

fun currentApplicationVersion(buildFile: File): String =
    Regex("""(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$""")
        .find(buildFile.readText(Charsets.UTF_8))
        ?.groupValues
        ?.get(1)
        ?: throw GradleException("Could not read versionName from app/build.gradle.kts")

tasks.register("writeDependencyInventory") {
    group = "verification"
    description = "Writes the deterministic CycloneDX inventory for the locked release runtime graph."
    inputs.files(dependencyLockFile, applicationBuildFile)
    outputs.file(dependencyInventoryFile)

    doLast {
        dependencyInventoryFile.asFile.writeText(
            cycloneDxInventory(
                dependencyLockFile.asFile,
                currentApplicationVersion(applicationBuildFile.asFile),
            ),
            Charsets.UTF_8,
        )
    }
}

tasks.register("checkDependencyInventory") {
    group = "verification"
    description = "Fails when the committed CycloneDX inventory differs from the locked release runtime graph."
    inputs.files(dependencyLockFile, applicationBuildFile, dependencyInventoryFile)

    doLast {
        val expected =
            cycloneDxInventory(
                dependencyLockFile.asFile,
                currentApplicationVersion(applicationBuildFile.asFile),
            )
        val committed = dependencyInventoryFile.asFile.readText(Charsets.UTF_8)
        if (committed != expected) {
            throw GradleException("bom.cdx.json is stale; regenerate it with writeDependencyInventory")
        }
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Runs the reproducible local quality, test, coverage, and distributable-build gates."
    dependsOn(
        "build",
        "coverage",
        "lint",
        "test",
        "typecheck",
    )
}
