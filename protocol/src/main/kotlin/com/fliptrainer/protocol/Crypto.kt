package com.fliptrainer.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The primitives the pairing handshake is built from.
 *
 * Deliberately small and boring: HMAC-SHA256 and HKDF, both from the JDK's `javax.crypto`, with no
 * third-party crypto dependency. Everything here is pure JVM so the whole handshake — including
 * every rejection path — is unit-testable without BLE, a phone, or a radio.
 */
object Crypto {
    const val HMAC_ALGORITHM: String = "HmacSHA256"
    const val HASH_LENGTH: Int = 32

    /** Paired-device key length. */
    const val KEY_LENGTH: Int = 32

    /** One-time QR secret length. */
    const val SECRET_LENGTH: Int = 32

    /** Nonce length for each side of the handshake. */
    const val NONCE_LENGTH: Int = 16

    /**
     * Envelope MAC length.
     *
     * Truncated from 32 bytes to keep per-message overhead low on a transport where the practical
     * MTU is around 180 bytes. 128 bits of authentication tag is standard practice and is not the
     * weak link here.
     */
    const val MAC_LENGTH: Int = 16

    fun hmac(
        key: ByteArray,
        vararg parts: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    /**
     * Constant-time comparison.
     *
     * `contentEquals` short-circuits on the first differing byte, which leaks how much of a forged
     * MAC was correct. [MessageDigest.isEqual] does not.
     */
    fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)

    /** RFC 5869 HKDF-Extract with HMAC-SHA256. */
    fun hkdfExtract(
        salt: ByteArray,
        inputKeyMaterial: ByteArray,
    ): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt
        return hmac(actualSalt, inputKeyMaterial)
    }

    /** RFC 5869 HKDF-Expand with HMAC-SHA256. */
    fun hkdfExpand(
        pseudoRandomKey: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length > 0) { "length must be positive" }
        require(length <= 255 * HASH_LENGTH) { "HKDF cannot expand beyond 255 hash lengths" }

        val output = ByteArray(length)
        var previousBlock = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previousBlock = hmac(pseudoRandomKey, previousBlock, info, byteArrayOf(counter.toByte()))
            val take = minOf(previousBlock.size, length - written)
            previousBlock.copyInto(output, written, 0, take)
            written += take
            counter++
        }
        return output
    }

    /** Full RFC 5869 HKDF. */
    fun hkdf(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = KEY_LENGTH,
    ): ByteArray = hkdfExpand(hkdfExtract(salt, inputKeyMaterial), info, length)

    fun randomBytes(
        length: Int,
        random: SecureRandom = secureRandom,
    ): ByteArray = ByteArray(length).also { random.nextBytes(it) }

    fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    /** Best-effort wipe. Not a guarantee on a JVM, but it shortens the window. */
    fun wipe(bytes: ByteArray) {
        bytes.fill(0)
    }

    private val secureRandom: SecureRandom by lazy { SecureRandom() }
}

/**
 * Domain-separation labels.
 *
 * Every HMAC in the protocol is over a distinct labelled transcript. Without this, a value
 * computed for one purpose could be presented as proof of another — the client proof replayed as
 * a server proof being the obvious one.
 */
internal object Labels {
    val CLIENT_PROOF = "FT1-client-proof".toByteArray()
    val SERVER_PROOF = "FT1-server-proof".toByteArray()
    val PAIRED_KEY_INFO = "FT1-paired-key".toByteArray()
    val ENVELOPE = "FT1-envelope".toByteArray()
    val DISCRIMINATOR = "FT1-discriminator".toByteArray()
}
