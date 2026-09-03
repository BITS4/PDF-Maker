package com.example.pdfmaker

/**
 * Central validation boundary for documents entering the authenticated encryption codec.
 *
 * Callers can reject invalid sizes, passwords, and wire-format markers before allocating large buffers or beginning
 * expensive key derivation. The byte-array and streaming paths intentionally share these limits.
 */
object DocumentInputValidator {
    const val MAX_PLAINTEXT_BYTES = 100L * 1024L * 1024L
    const val AUTHENTICATED_OVERHEAD_BYTES = 8L + Int.SIZE_BYTES + 16L + 12L + 16L
    const val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + AUTHENTICATED_OVERHEAD_BYTES
    const val MIN_ENCRYPTED_BYTES = AUTHENTICATED_OVERHEAD_BYTES + 1L

    private const val MIN_PASSWORD_LENGTH = 4
    private const val MAX_PASSWORD_LENGTH = 128
    private val authenticatedMagic = AUTHENTICATED_DOCUMENT_MAGIC.toByteArray(Charsets.US_ASCII)
    private val legacyMagic = LEGACY_DOCUMENT_MAGIC.toByteArray(Charsets.US_ASCII)

    /** Requires a non-empty plaintext length within the 100 MB document-processing budget. */
    fun requirePlaintextLength(length: Long): Long {
        require(length in 1..MAX_PLAINTEXT_BYTES) {
            "File is empty or exceeds the 100 MB safety limit"
        }
        return length
    }

    /** Requires a locked-document length that can contain a complete envelope without exceeding its safety budget. */
    fun requireEncryptedLength(length: Long): Long {
        require(isEncryptedLengthAccepted(length)) {
            "Locked file is empty or exceeds its safety limit"
        }
        return length
    }

    /** Returns whether a locked-document length falls within the supported authenticated-envelope limits. */
    fun isEncryptedLengthAccepted(length: Long): Boolean = length in MIN_ENCRYPTED_BYTES..MAX_ENCRYPTED_BYTES

    /** Calculates the exact authenticated wire length after first validating the plaintext length. */
    fun authenticatedLength(plaintextLength: Long): Long = Math.addExact(requirePlaintextLength(plaintextLength), AUTHENTICATED_OVERHEAD_BYTES)

    /** Requires a password length accepted by both encryption and decryption paths. */
    fun requirePassword(password: String): String {
        require(isPasswordAccepted(password)) {
            "Password must contain $MIN_PASSWORD_LENGTH to $MAX_PASSWORD_LENGTH characters"
        }
        return password
    }

    /** Returns whether a password is within the bounded key-derivation input range. */
    fun isPasswordAccepted(password: String): Boolean = password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH

    /** Returns whether bytes begin with a supported, versioned locked-document marker. */
    fun hasSupportedMagic(bytes: ByteArray): Boolean = bytes.startsWith(authenticatedMagic) || bytes.startsWith(legacyMagic)

    /** Returns whether an in-memory locked document has both a safe size and a recognized envelope marker. */
    fun isSupportedEncryptedDocument(bytes: ByteArray): Boolean = isEncryptedLengthAccepted(bytes.size.toLong()) && hasSupportedMagic(bytes)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
}
