package com.example.pdfmaker

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.security.NetworkSecurityPolicy
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SecurityConfigurationInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manifestDoesNotRequestBroadStorageAccess() {
        @Suppress("DEPRECATION")
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()
        val prohibited = setOf(
            Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )

        assertTrue("Broad storage permissions must not be packaged", permissions.intersect(prohibited).isEmpty())
    }

    @Test
    fun manifestIncludesCameraAndDisablesApplicationBackup() {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.CAMERA in permissions)
        val applicationInfo = requireNotNull(packageInfo.applicationInfo)
        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun cameraHardwareRemainsOptionalForNonScanningDevices() {
        @Suppress("DEPRECATION")
        val requestedFeatures =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS)
                .reqFeatures
                .orEmpty()
                .associateBy { feature -> feature.name }

        listOf(
            PackageManager.FEATURE_CAMERA,
            PackageManager.FEATURE_CAMERA_AUTOFOCUS,
        ).forEach { featureName ->
            val feature = requireNotNull(requestedFeatures[featureName])
            assertEquals(0, feature.flags and FeatureInfo.FLAG_REQUIRED)
        }
    }

    @Test
    fun cleartextNetworkTrafficIsBlockedByThePackagedPolicy() {
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
    }

    @Test
    fun onlyTheIntentHandlingActivityIsExported() {
        @Suppress("DEPRECATION")
        val packageInfo =
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS,
            )
        val exportedActivities = packageInfo.activities.orEmpty().filter { activity -> activity.exported }
        val fileProvider =
            requireNotNull(
                packageInfo.providers.orEmpty().singleOrNull { provider ->
                    provider.authority == "${context.packageName}.provider"
                },
            )

        assertEquals(listOf(MainActivity::class.java.name), exportedActivities.map { activity -> activity.name })
        assertFalse(fileProvider.exported)
        assertTrue(fileProvider.grantUriPermissions)
    }

    @Test
    fun sentryCannotInitializeBeforeThePrivacyPolicy() {
        @Suppress("DEPRECATION")
        val applicationInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )

        assertEquals(PdfMakerApplication::class.java.name, applicationInfo.className)
        assertFalse(applicationInfo.metaData.getBoolean("io.sentry.auto-init", true))
    }

    @Test
    fun fileProviderExposesGeneratedDocuments() {
        val output = OutputStore.writeUnique(getPdfMakerDir(context), "provider-contract", "pdf") {
            it.write("%PDF-1.7".toByteArray())
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", output)

            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.provider", uri.authority)
        } finally {
            output.delete()
        }
    }

    @Test
    fun fileProviderRejectsFilesOutsideApprovedRoots() {
        val outside = File(context.cacheDir, "outside-provider-root.txt").apply { writeText("private") }
        try {
            val exposed = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", outside)
            }.isSuccess

            assertFalse("The cache root must not be broadly exposed", exposed)
        } finally {
            outside.delete()
        }
    }

    @Test
    fun jpgSharingUsesAContentUriWithReadOnlyTemporaryAccess() {
        val image = File(getPdfMakerDir(context), "share-contract.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val chooser =
                requireNotNull(
                    DocumentShareAdapter.buildShareChooser(
                        context = context,
                        files = listOf(image),
                        chooserTitle = "Share JPG",
                        requestedMimeType = "image/jpeg",
                    ),
                )
            val shareIntent =
                requireNotNull(
                    IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java),
                )
            val stream =
                requireNotNull(
                    IntentCompat.getParcelableExtra(shareIntent, Intent.EXTRA_STREAM, Uri::class.java),
                )

            assertEquals(Intent.ACTION_SEND, shareIntent.action)
            assertEquals("image/jpeg", shareIntent.type)
            assertEquals("content", stream.scheme)
            assertEquals("${context.packageName}.provider", stream.authority)
            assertTrue(shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, shareIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(1, shareIntent.clipData?.itemCount)
            assertEquals(stream, shareIntent.clipData?.getItemAt(0)?.uri)
        } finally {
            image.delete()
        }
    }

    @Test
    fun multipleJpgsUseReadOnlyContentUrisWithoutCreatingAZip() {
        val images =
            listOf(
                File(getPdfMakerDir(context), "share-contract-1.jpg"),
                File(getPdfMakerDir(context), "share-contract-2.jpg"),
            ).onEach { image -> image.writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val chooser =
                requireNotNull(
                    DocumentShareAdapter.buildShareChooser(
                        context = context,
                        files = images,
                        chooserTitle = "Share JPG images",
                        requestedMimeType = "image/jpeg",
                    ),
                )
            val shareIntent =
                requireNotNull(
                    IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java),
                )
            val streams =
                requireNotNull(
                    IntentCompat.getParcelableArrayListExtra(
                        shareIntent,
                        Intent.EXTRA_STREAM,
                        Uri::class.java,
                    ),
                )
            val clipData = requireNotNull(shareIntent.clipData)

            assertEquals(Intent.ACTION_SEND_MULTIPLE, shareIntent.action)
            assertEquals("image/jpeg", shareIntent.type)
            assertEquals(images.size, streams.size)
            assertEquals(images.size, clipData.itemCount)
            assertTrue(shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, shareIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            streams.forEachIndexed { index, stream ->
                assertEquals("content", stream.scheme)
                assertEquals("${context.packageName}.provider", stream.authority)
                assertEquals(stream, clipData.getItemAt(index).uri)
                assertFalse(stream.lastPathSegment.orEmpty().endsWith(".zip", ignoreCase = true))
            }
        } finally {
            images.forEach(File::delete)
        }
    }
}
