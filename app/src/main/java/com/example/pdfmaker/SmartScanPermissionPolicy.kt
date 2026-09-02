package com.example.pdfmaker

/** User-visible states for the Smart Scan camera permission flow. */
internal enum class SmartScanPermissionStatus {
    REQUESTING,
    GRANTED,
    RATIONALE_REQUIRED,
    SETTINGS_REQUIRED,
}

/** Pure transition policy for the runtime camera permission flow. */
internal object SmartScanPermissionPolicy {
    fun initialStatus(
        isGranted: Boolean,
        wasRequestedBefore: Boolean,
        shouldShowRationale: Boolean,
    ): SmartScanPermissionStatus =
        when {
            isGranted -> SmartScanPermissionStatus.GRANTED
            !wasRequestedBefore -> SmartScanPermissionStatus.REQUESTING
            shouldShowRationale -> SmartScanPermissionStatus.RATIONALE_REQUIRED
            else -> SmartScanPermissionStatus.SETTINGS_REQUIRED
        }

    fun afterRequestResult(
        isGranted: Boolean,
        shouldShowRationale: Boolean,
    ): SmartScanPermissionStatus =
        when {
            isGranted -> SmartScanPermissionStatus.GRANTED
            shouldShowRationale -> SmartScanPermissionStatus.RATIONALE_REQUIRED
            else -> SmartScanPermissionStatus.SETTINGS_REQUIRED
        }

    fun afterResume(
        currentStatus: SmartScanPermissionStatus,
        isGranted: Boolean,
        shouldShowRationale: Boolean,
    ): SmartScanPermissionStatus =
        when {
            isGranted -> SmartScanPermissionStatus.GRANTED
            currentStatus == SmartScanPermissionStatus.REQUESTING -> currentStatus
            shouldShowRationale -> SmartScanPermissionStatus.RATIONALE_REQUIRED
            else -> SmartScanPermissionStatus.SETTINGS_REQUIRED
        }

    fun shouldLaunchInitialRequest(
        status: SmartScanPermissionStatus,
        wasLaunched: Boolean,
    ): Boolean = status == SmartScanPermissionStatus.REQUESTING && !wasLaunched

    fun canRequestPermission(status: SmartScanPermissionStatus): Boolean = status == SmartScanPermissionStatus.RATIONALE_REQUIRED

    fun canOpenSettings(status: SmartScanPermissionStatus): Boolean = status == SmartScanPermissionStatus.SETTINGS_REQUIRED

    fun canBindCamera(status: SmartScanPermissionStatus): Boolean = status == SmartScanPermissionStatus.GRANTED
}
