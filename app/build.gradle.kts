import io.gitlab.arturbosch.detekt.Detekt
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.example.pdfmaker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.pdfmaker"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        val sentryDsn =
            providers
                .gradleProperty("SENTRY_DSN")
                .orElse(providers.environmentVariable("SENTRY_DSN"))
                .orElse("")
                .get()
        val sentryEnabled =
            providers
                .gradleProperty("SENTRY_ENABLED")
                .orElse(providers.environmentVariable("SENTRY_ENABLED"))
                .orElse("false")
                .get()
                .trim()
                .lowercase()
                .let { value ->
                    require(value == "true" || value == "false") {
                        "SENTRY_ENABLED must be either true or false"
                    }
                    value
                }
        val escapedSentryDsn =
            sentryDsn
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
        buildConfigField("String", "SENTRY_DSN", "\"$escapedSentryDsn\"")
        buildConfigField("boolean", "SENTRY_ENABLED", sentryEnabled)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Android 15+ requires 16 KB page-aligned native libraries.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                )
        }
    }

    lint {
        abortOnError = true
        absolutePaths = false
        checkDependencies = true
        checkReleaseBuilds = true
        explainIssues = true
        htmlReport = true
        lintConfig = rootProject.file("config/lint/lint.xml")
        sarifReport = true
        textReport = true
        warningsAsErrors = true
        xmlReport = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

detekt {
    allRules = false
    autoCorrect = false
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    parallel = true
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
        exclude("**/generated/**")
    }
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        md.required.set(true)
        sarif.required.set(true)
        txt.required.set(false)
        xml.required.set(true)
    }
}

kover {
    currentProject {
        // Keep the critical report on exactly the same production classes and complete JVM test task as debug.
        copyVariant("critical", "debug")
    }

    reports {
        filters {
            excludes {
                classes(
                    "*.BuildConfig",
                    "*.R",
                    "*.R$*",
                    "*.ComposableSingletons*",
                )
            }
        }
        total {
            html {
                onCheck = false
            }
            xml {
                onCheck = false
            }
        }
        variant("critical") {
            filters {
                includes {
                    classes(
                        "com.example.pdfmaker.AppNavigationPolicy",
                        "com.example.pdfmaker.AppNavigationState",
                        "com.example.pdfmaker.BoundedIo",
                        "com.example.pdfmaker.CompressUiState",
                        "com.example.pdfmaker.CompressionPolicy",
                        "com.example.pdfmaker.ConversionDocumentHandler",
                        "com.example.pdfmaker.ConversionRelationshipHandler",
                        "com.example.pdfmaker.DocxConversionArchive",
                        "com.example.pdfmaker.DocxConversionArchiveKt",
                        "com.example.pdfmaker.DocxConversionPolicy",
                        "com.example.pdfmaker.DocxConversionXmlKt",
                        "com.example.pdfmaker.DocxToPdfActions",
                        "com.example.pdfmaker.DocxToPdfPolicy",
                        "com.example.pdfmaker.DocxToPdfUiState",
                        "com.example.pdfmaker.DocumentInputValidator",
                        "com.example.pdfmaker.DocumentSharePolicy",
                        "com.example.pdfmaker.EditorGeometryKt",
                        "com.example.pdfmaker.FileCatalog",
                        "com.example.pdfmaker.FilesScreenPolicy",
                        "com.example.pdfmaker.FilesScreenState",
                        "com.example.pdfmaker.GallerySavePolicy",
                        "com.example.pdfmaker.ImageInputPolicy",
                        "com.example.pdfmaker.ImagePdfExport",
                        "com.example.pdfmaker.ImagePixelAlgorithms",
                        "com.example.pdfmaker.ImageTransformPolicy",
                        "com.example.pdfmaker.ImportedDocumentArtifact",
                        "com.example.pdfmaker.ImportedDocumentInspector",
                        "com.example.pdfmaker.ImportedImageValidator",
                        "com.example.pdfmaker.ImportedPdfViewerPolicy",
                        "com.example.pdfmaker.IncomingImportStoragePolicy",
                        "com.example.pdfmaker.IncomingIntentLifecycleState",
                        "com.example.pdfmaker.LockPdfSelectionPolicy",
                        "com.example.pdfmaker.MergePdfPolicy",
                        "com.example.pdfmaker.MergePdfUiState",
                        "com.example.pdfmaker.MergeOperationState",
                        "com.example.pdfmaker.MergeScreenPolicy",
                        "com.example.pdfmaker.OcrResourcePolicy",
                        "com.example.pdfmaker.OcrTextFormatter",
                        "com.example.pdfmaker.ObservabilityPolicy",
                        "com.example.pdfmaker.OwnedFilePolicy",
                        "com.example.pdfmaker.OwnedImportCleanup",
                        "com.example.pdfmaker.OutputStore",
                        "com.example.pdfmaker.PageBitmapCachePolicy",
                        "com.example.pdfmaker.PageEditPolicy",
                        "com.example.pdfmaker.PageManagerOperationState",
                        "com.example.pdfmaker.PageManagerPolicy",
                        "com.example.pdfmaker.PageSelectionPolicy",
                        "com.example.pdfmaker.PdfEditorRenderPolicy",
                        "com.example.pdfmaker.PdfEditorOfficeXmlKt",
                        "com.example.pdfmaker.PdfToJpgPolicy",
                        "com.example.pdfmaker.PinCredential",
                        "com.example.pdfmaker.PinLockoutPolicy",
                        "com.example.pdfmaker.PrintPdfPolicy",
                        "com.example.pdfmaker.RenderSizing",
                        "com.example.pdfmaker.SafeDocxInput",
                        "com.example.pdfmaker.SafeFileName",
                        "com.example.pdfmaker.SafePdfInput",
                        "com.example.pdfmaker.SecureDocumentCodec",
                        "com.example.pdfmaker.SecureDocumentStore",
                        "com.example.pdfmaker.SecureDocumentTypePolicy",
                        "com.example.pdfmaker.SecureSaxLimits",
                        "com.example.pdfmaker.SecureSaxParser",
                        "com.example.pdfmaker.SmartScanCameraPolicy",
                        "com.example.pdfmaker.SmartScanPermissionPolicy",
                        "com.example.pdfmaker.SmartScanPolicy",
                        "com.example.pdfmaker.SmartScanWorkspace",
                        "com.example.pdfmaker.SplitPreviewPolicy",
                        "com.example.pdfmaker.ThumbnailCachePolicy",
                        "com.example.pdfmaker.ThumbnailGenerationPolicy",
                        "com.example.pdfmaker.ThumbnailInput",
                        "com.example.pdfmaker.ThumbnailXmlParser",
                        "com.example.pdfmaker.TemporaryImportLease",
                        "com.example.pdfmaker.UserVisibleFailurePolicy",
                        "com.example.pdfmaker.ViewerArchiveBudget",
                        "com.example.pdfmaker.ViewerArchiveIOKt",
                        "com.example.pdfmaker.ViewerCacheFilesKt",
                        "com.example.pdfmaker.ViewerDelimitedTextKt",
                        "com.example.pdfmaker.ViewerDocumentXmlKt",
                        "com.example.pdfmaker.ViewerFilePolicyKt",
                        "com.example.pdfmaker.ViewerOoxmlPolicyKt",
                        "com.example.pdfmaker.ViewerPageArtifactPolicy",
                        "com.example.pdfmaker.ViewerSpreadsheetXmlKt",
                        "com.example.pdfmaker.WeightedLruCache",
                    )
                }
            }
            html {
                onCheck = false
            }
            xml {
                onCheck = false
            }
            verify {
                rule("critical-domain line coverage") {
                    minBound(91, CoverageUnit.LINE)
                }
                rule("critical-domain branch coverage") {
                    minBound(76, CoverageUnit.BRANCH)
                }
            }
        }
    }
}

// A focused `--tests` run replaces Kover's execution data. Always rerun the complete suite and the critical
// aggregation tasks so a later coverage gate cannot accept a partial or stale local artifact.
tasks.matching { task -> task.name == "testDebugUnitTest" }.configureEach {
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Coverage gates require execution data from the complete current test suite") { true }
}

val alwaysFreshCriticalCoverageTasks =
    setOf(
        "koverGenerateArtifactCritical",
        "koverHtmlReportCritical",
        "koverVerifyCritical",
        "koverXmlReportCritical",
    )

tasks.matching { task -> task.name in alwaysFreshCriticalCoverageTasks }.configureEach {
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Critical coverage must be recalculated from the current complete test run") { true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.google.mlkit.text.recognition)
    implementation(libs.io.sentry.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
