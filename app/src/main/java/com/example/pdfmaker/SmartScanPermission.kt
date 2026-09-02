package com.example.pdfmaker

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class SmartScanPermission(
    val status: SmartScanPermissionStatus,
    val requestPermission: () -> Unit,
    val openSettings: () -> Unit,
)

@Composable
internal fun rememberSmartScanPermissionState(): SmartScanPermission {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val requestHistory = remember(context) { SmartScanPermissionHistory(context) }

    fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun shouldShowRationale(): Boolean = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true

    var status by rememberSaveable {
        mutableStateOf(
            SmartScanPermissionPolicy.initialStatus(
                isGranted = isGranted(),
                wasRequestedBefore = requestHistory.wasRequestedBefore(),
                shouldShowRationale = shouldShowRationale(),
            ),
        )
    }
    var requestLaunchedInComposition by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status =
                SmartScanPermissionPolicy.afterRequestResult(
                    isGranted = granted,
                    shouldShowRationale = shouldShowRationale(),
                )
        }

    fun launchPermissionRequest() {
        requestLaunchedInComposition = true
        requestHistory.markRequested()
        status = SmartScanPermissionStatus.REQUESTING
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(status, requestLaunchedInComposition) {
        if (SmartScanPermissionPolicy.shouldLaunchInitialRequest(status, requestLaunchedInComposition)) {
            launchPermissionRequest()
        }
    }
    DisposableEffect(lifecycleOwner, activity) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    status =
                        SmartScanPermissionPolicy.afterResume(
                            currentStatus = status,
                            isGranted = isGranted(),
                            shouldShowRationale = shouldShowRationale(),
                        )
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return SmartScanPermission(
        status = status,
        requestPermission = {
            if (SmartScanPermissionPolicy.canRequestPermission(status)) {
                launchPermissionRequest()
            }
        },
        openSettings = {
            if (SmartScanPermissionPolicy.canOpenSettings(status)) {
                openApplicationDetailsSettings(context, activity)
            }
        },
    )
}

private class SmartScanPermissionHistory(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences("smart_scan_permissions", Context.MODE_PRIVATE)

    fun wasRequestedBefore(): Boolean = preferences.getBoolean("camera_requested", false)

    fun markRequested() {
        preferences.edit().putBoolean("camera_requested", true).apply()
    }
}

internal fun openApplicationDetailsSettings(
    context: Context,
    activity: Activity?,
) {
    val flags = if (activity == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0
    val detailsIntent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(flags)
    if (context.startActivitySafely(detailsIntent)) return

    val fallbackIntent = Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(flags)
    if (!context.startActivitySafely(fallbackIntent)) {
        Toast.makeText(context, "Unable to open Settings on this device.", Toast.LENGTH_LONG).show()
    }
}

private fun Context.startActivitySafely(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
