import org.gradle.api.GradleException
import org.gradle.api.artifacts.dsl.LockMode

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.owasp.dependency.check) apply false
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

val productionKotlin = fileTree("app/src/main") {
    include("**/*.kt")
}

tasks.register("checkSourceFileSize") {
    group = "verification"
    description = "Fails when a production Kotlin source file exceeds 500 physical lines."
    inputs.files(productionKotlin)

    doLast {
        val oversized = productionKotlin.files
            .map { file -> file.relativeTo(rootDir) to file.readLines(Charsets.UTF_8).size }
            .filter { (_, lineCount) -> lineCount > 500 }
            .sortedByDescending { (_, lineCount) -> lineCount }

        if (oversized.isNotEmpty()) {
            val details = oversized.joinToString(separator = System.lineSeparator()) { (file, lineCount) ->
                "  - ${file.invariantSeparatorsPath}: $lineCount lines"
            }
            throw GradleException(
                "Production Kotlin files must stay at or below 500 lines:${System.lineSeparator()}$details",
            )
        }
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Runs the reproducible local quality, test, coverage, and debug-build gates."
    dependsOn(
        "checkSourceFileSize",
        ":app:ktlintCheck",
        ":app:detekt",
        ":app:lintDebug",
        ":app:testDebugUnitTest",
        ":app:koverXmlReportDebug",
        ":app:koverVerifyDebug",
        ":app:assembleDebug",
    )
}
