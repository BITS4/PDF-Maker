package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartScanPermissionPolicyTest {
    @Test
    fun initialStatusCoversEveryPlatformSignalCombination() {
        data class Case(
            val granted: Boolean,
            val requestedBefore: Boolean,
            val rationale: Boolean,
            val expected: SmartScanPermissionStatus,
        )

        val cases =
            listOf(
                Case(true, false, false, SmartScanPermissionStatus.GRANTED),
                Case(true, false, true, SmartScanPermissionStatus.GRANTED),
                Case(true, true, false, SmartScanPermissionStatus.GRANTED),
                Case(true, true, true, SmartScanPermissionStatus.GRANTED),
                Case(false, false, false, SmartScanPermissionStatus.REQUESTING),
                Case(false, false, true, SmartScanPermissionStatus.REQUESTING),
                Case(false, true, true, SmartScanPermissionStatus.RATIONALE_REQUIRED),
                Case(false, true, false, SmartScanPermissionStatus.SETTINGS_REQUIRED),
            )

        cases.forEach { case ->
            assertEquals(
                case.expected,
                SmartScanPermissionPolicy.initialStatus(
                    isGranted = case.granted,
                    wasRequestedBefore = case.requestedBefore,
                    shouldShowRationale = case.rationale,
                ),
            )
        }
    }

    @Test
    fun requestResultPrioritizesGrantThenRationaleThenSettings() {
        assertEquals(
            SmartScanPermissionStatus.GRANTED,
            SmartScanPermissionPolicy.afterRequestResult(
                isGranted = true,
                shouldShowRationale = false,
            ),
        )
        assertEquals(
            SmartScanPermissionStatus.GRANTED,
            SmartScanPermissionPolicy.afterRequestResult(
                isGranted = true,
                shouldShowRationale = true,
            ),
        )
        assertEquals(
            SmartScanPermissionStatus.RATIONALE_REQUIRED,
            SmartScanPermissionPolicy.afterRequestResult(
                isGranted = false,
                shouldShowRationale = true,
            ),
        )
        assertEquals(
            SmartScanPermissionStatus.SETTINGS_REQUIRED,
            SmartScanPermissionPolicy.afterRequestResult(
                isGranted = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun resumeRefreshesEverySettledStateWithoutInterruptingAnActiveRequest() {
        SmartScanPermissionStatus.entries.forEach { current ->
            assertEquals(
                SmartScanPermissionStatus.GRANTED,
                SmartScanPermissionPolicy.afterResume(
                    currentStatus = current,
                    isGranted = true,
                    shouldShowRationale = false,
                ),
            )

            val deniedExpected =
                if (current == SmartScanPermissionStatus.REQUESTING) {
                    SmartScanPermissionStatus.REQUESTING
                } else {
                    SmartScanPermissionStatus.RATIONALE_REQUIRED
                }
            assertEquals(
                deniedExpected,
                SmartScanPermissionPolicy.afterResume(
                    currentStatus = current,
                    isGranted = false,
                    shouldShowRationale = true,
                ),
            )

            val blockedExpected =
                if (current == SmartScanPermissionStatus.REQUESTING) {
                    SmartScanPermissionStatus.REQUESTING
                } else {
                    SmartScanPermissionStatus.SETTINGS_REQUIRED
                }
            assertEquals(
                blockedExpected,
                SmartScanPermissionPolicy.afterResume(
                    currentStatus = current,
                    isGranted = false,
                    shouldShowRationale = false,
                ),
            )
        }
    }

    @Test
    fun initialRequestCanLaunchOnlyOncePerComposition() {
        SmartScanPermissionStatus.entries.forEach { status ->
            val expected = status == SmartScanPermissionStatus.REQUESTING
            assertEquals(
                expected,
                SmartScanPermissionPolicy.shouldLaunchInitialRequest(status, wasLaunched = false),
            )
            assertFalse(SmartScanPermissionPolicy.shouldLaunchInitialRequest(status, wasLaunched = true))
        }
    }

    @Test
    fun actionsAreRestrictedToTheirMatchingStates() {
        SmartScanPermissionStatus.entries.forEach { status ->
            assertEquals(
                status == SmartScanPermissionStatus.RATIONALE_REQUIRED,
                SmartScanPermissionPolicy.canRequestPermission(status),
            )
            assertEquals(
                status == SmartScanPermissionStatus.SETTINGS_REQUIRED,
                SmartScanPermissionPolicy.canOpenSettings(status),
            )
            assertEquals(
                status == SmartScanPermissionStatus.GRANTED,
                SmartScanPermissionPolicy.canBindCamera(status),
            )
        }
        assertTrue(SmartScanPermissionPolicy.canBindCamera(SmartScanPermissionStatus.GRANTED))
    }
}
