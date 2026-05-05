package com.example.pdfmaker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.pdfmaker.ui.theme.PDFMakerTheme

class MainActivity : ComponentActivity() {

    var incomingUri: Uri? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) FileCache.invalidate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        requestStoragePermissionsIfNeeded()
        setContent {
            PDFMakerTheme {
                AppNavigation(activity = this@MainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after the user returns from the All-Files-Access settings screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            FileCache.invalidate()
        }
    }

    /** Opens the system screen where the user grants All Files Access (Android 11+). */
    fun openManageAllFilesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun requestStoragePermissionsIfNeeded() {
        val perms = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            else ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            FileCache.invalidate()  // already granted — make sure first load runs
        } else {
            permissionLauncher.launch(perms)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_VIEW -> incomingUri = intent.data
            Intent.ACTION_SEND -> incomingUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}
