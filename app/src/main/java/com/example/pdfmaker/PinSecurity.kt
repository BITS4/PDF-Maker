package com.example.pdfmaker

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A versioned, salted verifier. The PIN itself is never persisted. */
object PinCredential {
    private const val VERSION = "v1"
    private const val DEFAULT_ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256

    fun isValidPin(pin: String): Boolean = pin.length in 4..6 && pin.all(Char::isDigit)

    fun create(
        pin: String,
        random: SecureRandom = SecureRandom(),
        iterations: Int = DEFAULT_ITERATIONS,
    ): String {
        require(isValidPin(pin)) { "PIN must contain 4 to 6 digits" }
        require(iterations >= 1_000) { "PBKDF2 work factor is too low" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(pin, salt, iterations)
        return listOf(
            VERSION,
            pin.length.toString(),
            iterations.toString(),
            Base64.getEncoder().withoutPadding().encodeToString(salt),
            Base64.getEncoder().withoutPadding().encodeToString(hash),
        ).joinToString("$")
    }

    fun verify(pin: String, encoded: String): Boolean {
        if (!isValidPin(pin)) return false
        val parsed = parse(encoded) ?: return false
        if (pin.length != parsed.pinLength) return false
        return MessageDigest.isEqual(derive(pin, parsed.salt, parsed.iterations), parsed.hash)
    }

    fun pinLength(encoded: String): Int? = parse(encoded)?.pinLength

    fun isCredential(encoded: String): Boolean = parse(encoded) != null

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun parse(encoded: String): ParsedCredential? = try {
        val parts = encoded.split('$')
        if (parts.size != 5 || parts[0] != VERSION) return null
        val pinLength = parts[1].toInt()
        val iterations = parts[2].toInt()
        val salt = Base64.getDecoder().decode(parts[3])
        val hash = Base64.getDecoder().decode(parts[4])
        if (pinLength !in 4..6 || iterations < 1_000 || salt.size != SALT_BYTES || hash.size != HASH_BITS / 8) {
            return null
        }
        ParsedCredential(pinLength, iterations, salt, hash)
    } catch (_: IllegalArgumentException) {
        null
    }

    private data class ParsedCredential(
        val pinLength: Int,
        val iterations: Int,
        val salt: ByteArray,
        val hash: ByteArray,
    )
}

data class PinAttemptState(
    val failedAttempts: Int = 0,
    val lockedUntilEpochMillis: Long = 0,
)

object PinLockoutPolicy {
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_MILLIS = 30_000L

    fun recordFailure(state: PinAttemptState, nowEpochMillis: Long): PinAttemptState {
        val current = afterExpiry(state, nowEpochMillis)
        if (current.lockedUntilEpochMillis > nowEpochMillis) return current
        val failures = current.failedAttempts + 1
        return if (failures >= MAX_ATTEMPTS) {
            PinAttemptState(0, nowEpochMillis + LOCKOUT_MILLIS)
        } else {
            PinAttemptState(failures, 0)
        }
    }

    fun afterExpiry(state: PinAttemptState, nowEpochMillis: Long): PinAttemptState =
        if (state.lockedUntilEpochMillis in 1..nowEpochMillis) PinAttemptState() else state

    fun remainingMillis(state: PinAttemptState, nowEpochMillis: Long): Long =
        (state.lockedUntilEpochMillis - nowEpochMillis).coerceAtLeast(0)
}
