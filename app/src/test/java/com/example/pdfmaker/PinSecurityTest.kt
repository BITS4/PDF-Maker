package com.example.pdfmaker

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCredentialTest {
    @Test
    fun acceptsFourToSixDigitsOnly() {
        assertTrue(PinCredential.isValidPin("1234"))
        assertTrue(PinCredential.isValidPin("123456"))
        assertFalse(PinCredential.isValidPin("123"))
        assertFalse(PinCredential.isValidPin("1234567"))
        assertFalse(PinCredential.isValidPin("12a4"))
    }

    @Test
    fun verifiesWithoutPersistingPlaintext() {
        val credential = PinCredential.create("042951", iterations = 1_000)

        assertTrue(PinCredential.verify("042951", credential))
        assertFalse(PinCredential.verify("042950", credential))
        assertFalse(credential.contains("042951"))
        assertEquals(6, PinCredential.pinLength(credential))
    }

    @Test
    fun uniqueSaltsProduceDifferentCredentials() {
        val random = SecureRandom()
        val first = PinCredential.create("1234", random, iterations = 1_000)
        val second = PinCredential.create("1234", random, iterations = 1_000)

        assertNotEquals(first, second)
        assertTrue(PinCredential.verify("1234", first))
        assertTrue(PinCredential.verify("1234", second))
    }

    @Test
    fun malformedAndTamperedCredentialsFailClosed() {
        val credential = PinCredential.create("98765", iterations = 1_000)
        val corrupted = credential.dropLast(1) + if (credential.last() == 'A') "B" else "A"

        assertFalse(PinCredential.verify("98765", ""))
        assertFalse(PinCredential.verify("98765", "v1\$5\$1000\$broken\$broken"))
        assertFalse(PinCredential.verify("98765", corrupted))
        assertFalse(PinCredential.verify("9876", credential))
    }
}

class PinLockoutPolicyTest {
    @Test
    fun fifthFailureCreatesPersistentThirtySecondLockout() {
        val now = 1_000_000L
        var state = PinAttemptState()
        repeat(4) { state = PinLockoutPolicy.recordFailure(state, now) }
        assertEquals(4, state.failedAttempts)
        assertEquals(0, state.lockedUntilEpochMillis)

        state = PinLockoutPolicy.recordFailure(state, now)
        assertEquals(0, state.failedAttempts)
        assertEquals(now + 30_000L, state.lockedUntilEpochMillis)
        assertEquals(30_000L, PinLockoutPolicy.remainingMillis(state, now))
    }

    @Test
    fun failuresDuringLockoutCannotShortenIt() {
        val locked = PinAttemptState(0, 50_000L)
        assertEquals(locked, PinLockoutPolicy.recordFailure(locked, 25_000L))
    }

    @Test
    fun expiredLockoutResetsFailureState() {
        val locked = PinAttemptState(3, 10_000L)
        assertEquals(PinAttemptState(), PinLockoutPolicy.afterExpiry(locked, 10_000L))
        assertEquals(0, PinLockoutPolicy.remainingMillis(locked, 10_001L))
    }
}
