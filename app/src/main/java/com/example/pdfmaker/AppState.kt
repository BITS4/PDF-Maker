package com.example.pdfmaker

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ── App-wide colour tokens ────────────────────────────────────────────────────

val AccentBlue      = Color(0xFF4F8EF7)
val BadgeRed        = Color(0xFFEF5350)
val TextSecond      = Color(0xFF9999BB)
val BgToolIcon      = Color(0xFF252535)

// Fixed dark-theme colours (the app uses a single dark theme)
val currentBg          = Color(0xFF0D0D16)
val currentCard        = Color(0xFF1A1A2A)
val currentText        = Color.White
val currentTextSecond  = Color(0xFF9999BB)
val currentDivider     = Color(0xFF2A2A3A)
val currentThumbnail   = Color(0xFF252535)
val currentToolIcon    = Color(0xFF252535)

// ── Scan mode ─────────────────────────────────────────────────────────────────

enum class ScanMode { DOCS, ID_CARD }

// ── Global image-to-PDF state ─────────────────────────────────────────────────

object ImageToPdfState {

    val editStates = mutableStateListOf<ImageEditState>()
    var currentEditIndex by mutableStateOf(0)
    var isIdCardScan     by mutableStateOf(false)

    fun addUris(uris: List<Uri>) {
        val existing = editStates.map { it.uri }.toSet()
        uris.filter { it !in existing }.forEach { editStates.add(ImageEditState(it)) }
    }

    fun clear() {
        editStates.clear()
        currentEditIndex = 0
        isIdCardScan     = false
    }

    fun removeAt(index: Int) {
        if (index in editStates.indices) {
            editStates.removeAt(index)
            currentEditIndex = currentEditIndex.coerceIn(0, (editStates.size - 1).coerceAtLeast(0))
        }
    }
}

// ── Global smart-scan state ───────────────────────────────────────────────────

object SmartScanState {
    fun clear() { /* no persistent state needed — camera screen owns its state */ }
}

// ── Settings manager ──────────────────────────────────────────────────────────

object SettingsManager {

    private const val PREFS = "pdfmaker_prefs"

    private const val PIN_CREDENTIAL = "pin_credential"
    private const val LEGACY_PIN = "pin"
    private const val PIN_FAILURES = "pin_failures"
    private const val PIN_LOCKED_UNTIL = "pin_locked_until"

    fun hasPin(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, 0)
        val credential = preferences.getString(PIN_CREDENTIAL, "").orEmpty()
        val legacy = preferences.getString(LEGACY_PIN, "").orEmpty()
        return PinCredential.isCredential(credential) || PinCredential.isValidPin(legacy)
    }

    fun getPinLength(context: Context): Int {
        val preferences = context.getSharedPreferences(PREFS, 0)
        val credential = preferences.getString(PIN_CREDENTIAL, "").orEmpty()
        return PinCredential.pinLength(credential)
            ?: preferences.getString(LEGACY_PIN, "").orEmpty().length.takeIf { it in 4..6 }
            ?: 4
    }

    fun savePin(context: Context, pin: String) {
        require(PinCredential.isValidPin(pin)) { "PIN must contain 4 to 6 digits" }
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(PIN_CREDENTIAL, PinCredential.create(pin))
            .remove(LEGACY_PIN)
            .apply()
        clearPinFailures(context)
    }

    fun verifyPin(context: Context, candidate: String): Boolean {
        val preferences = context.getSharedPreferences(PREFS, 0)
        val credential = preferences.getString(PIN_CREDENTIAL, "").orEmpty()
        if (PinCredential.isCredential(credential)) return PinCredential.verify(candidate, credential)

        // One-time migration from releases that stored the PIN in plaintext.
        val legacy = preferences.getString(LEGACY_PIN, "").orEmpty()
        val matches = PinCredential.isValidPin(legacy) &&
            java.security.MessageDigest.isEqual(candidate.toByteArray(), legacy.toByteArray())
        if (matches) savePin(context, candidate)
        return matches
    }

    fun failedPinAttempts(context: Context): Int = currentAttemptState(context).failedAttempts

    fun remainingPinLockoutSeconds(context: Context, nowEpochMillis: Long = System.currentTimeMillis()): Int {
        val current = normalizedAttemptState(context, nowEpochMillis)
        val millis = PinLockoutPolicy.remainingMillis(current, nowEpochMillis)
        return ((millis + 999L) / 1_000L).toInt()
    }

    fun recordFailedPinAttempt(context: Context, nowEpochMillis: Long = System.currentTimeMillis()): Int {
        val next = PinLockoutPolicy.recordFailure(normalizedAttemptState(context, nowEpochMillis), nowEpochMillis)
        saveAttemptState(context, next)
        return ((PinLockoutPolicy.remainingMillis(next, nowEpochMillis) + 999L) / 1_000L).toInt()
    }

    fun clearPinFailures(context: Context) {
        saveAttemptState(context, PinAttemptState())
    }

    fun getSecurityEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean("security_enabled", false)

    fun setSecurityEnabled(context: Context, enabled: Boolean) {
        val safeValue = enabled && hasPin(context)
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("security_enabled", safeValue).apply()
    }

    private fun currentAttemptState(context: Context): PinAttemptState {
        val preferences = context.getSharedPreferences(PREFS, 0)
        return PinAttemptState(
            failedAttempts = preferences.getInt(PIN_FAILURES, 0).coerceIn(0, PinLockoutPolicy.MAX_ATTEMPTS - 1),
            lockedUntilEpochMillis = preferences.getLong(PIN_LOCKED_UNTIL, 0).coerceAtLeast(0),
        )
    }

    private fun normalizedAttemptState(context: Context, nowEpochMillis: Long): PinAttemptState {
        val current = currentAttemptState(context)
        val normalized = PinLockoutPolicy.afterExpiry(current, nowEpochMillis)
        if (normalized != current) saveAttemptState(context, normalized)
        return normalized
    }

    private fun saveAttemptState(context: Context, state: PinAttemptState) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putInt(PIN_FAILURES, state.failedAttempts)
            .putLong(PIN_LOCKED_UNTIL, state.lockedUntilEpochMillis)
            .apply()
    }
}

// ── Navigation transition helpers ─────────────────────────────────────────────

enum class NavDirection { LEFT, RIGHT, NONE }

fun navDirectionFor(from: Screen, to: Screen): NavDirection {
    val order = listOf(
        Screen.HOME, Screen.FILES, Screen.SETTINGS,
        Screen.IMAGE_SELECTION, Screen.IMAGE_EDIT, Screen.IMAGE_CROP, Screen.IMAGE_REVIEW, Screen.CONVERT_RESULT,
        Screen.SMART_SCAN, Screen.COMPRESS, Screen.PDF_TO_JPG,
        Screen.MERGE_PDF, Screen.MORE_TOOLS, Screen.DOCX_TO_PDF,
        Screen.IMPORT_PDF, Screen.IMPORTED_PDF_VIEWER, Screen.SIGNATURE_PAD,
        Screen.SPLIT_PDF, Screen.PAGE_MANAGER, Screen.LOCK_PDF, Screen.UNLOCK_PDF,
        Screen.OCR, Screen.PRINT_PDF, Screen.CAMERA_DENIED,
        Screen.ONBOARDING, Screen.ID_CARD_RESULT
    )
    val fromIdx = order.indexOf(from)
    val toIdx   = order.indexOf(to)
    return when {
        fromIdx == -1 || toIdx == -1 || fromIdx == toIdx -> NavDirection.NONE
        toIdx > fromIdx -> NavDirection.LEFT
        else            -> NavDirection.RIGHT
    }
}

@Composable
fun ScreenTransition(
    targetState: Screen,
    direction  : NavDirection,
    content    : @Composable (Screen) -> Unit
) {
    val slideDistance = 300
    val transform: ContentTransform = when (direction) {
        NavDirection.LEFT  -> slideInHorizontally(tween(260)) { slideDistance } + fadeIn(tween(200)) togetherWith
                             slideOutHorizontally(tween(260)) { -slideDistance } + fadeOut(tween(200))
        NavDirection.RIGHT -> slideInHorizontally(tween(260)) { -slideDistance } + fadeIn(tween(200)) togetherWith
                             slideOutHorizontally(tween(260)) { slideDistance } + fadeOut(tween(200))
        NavDirection.NONE  -> fadeIn(tween(160)) togetherWith fadeOut(tween(160))
    }

    AnimatedContent(
        targetState   = targetState,
        transitionSpec = { transform },
        label         = "screenTransition"
    ) { screen ->
        content(screen)
    }
}

// ── Skeleton loading composables ──────────────────────────────────────────────

@Composable
fun SectionHeaderSkeleton() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(currentCard, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(100.dp).height(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(currentThumbnail)
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .width(60.dp).height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(currentThumbnail)
            )
        }
    }
}

@Composable
fun FileItemSkeleton() {
    Box(Modifier.fillMaxWidth().background(currentCard)) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(width = 72.dp, height = 80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(currentThumbnail)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f).height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(currentThumbnail)
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.4f).height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(currentThumbnail)
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(currentDivider)
            )
        }
    }
}
