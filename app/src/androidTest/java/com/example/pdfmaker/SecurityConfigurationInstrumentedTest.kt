package com.example.pdfmaker

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
    fun manifestDeclaresNotificationsWithoutAllowingApplicationBackup() {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertEquals(0, packageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
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
}
