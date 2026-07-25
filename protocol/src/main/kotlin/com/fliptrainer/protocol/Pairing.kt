package com.fliptrainer.protocol

import java.security.SecureRandom

/**
 * The payload encoded in the pairing QR code (plan §6.6).
 *
 * **This is a temporary bearer credential.** Anyone who photographs the screen while it is
 * displayed can pair. That is acceptable only because it is short-lived, single-use, and grants
 * nothing but the right to become the controller of one workout sensor.
 *
 * What must never go in here: cloud credentials, IP addresses, permanent bearer tokens, or any
 * workout history.
 *
 * **[pairingSecret] is a mutable array and `copy()` shares it.** Anything that holds a payload for
 * longer than a single expression should take its own copy — the pairing sessions do, so that one
 * side wiping the credential after use cannot blank it out from under the other.
 */
data class QrPairingPayload(
    val protocolVersion: Int,
    val deviceId: String,
    val pairingId: String,
    val pairingSecret: ByteArray,
    val expiresAtEpochMs: Long,
) {
    init {
        require(deviceId.matches(SAFE_ID)) { "deviceId must match $SAFE_ID" }
        require(pairingId.matches(SAFE_ID)) { "pairingId must match $SAFE_ID" }
        require(pairingSecret.size == Crypto.SECRET_LENGTH) {
            "pairing secret must be ${Crypto.SECRET_LENGTH} bytes, was ${pairingSecret.size}"
        }
    }

    fun isExpiredAt(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs

    /**
     * Short public value derived from the pairing id, advertised alongside the service UUID.
     *
     * Lets the iPhone pick the right Galaxy out of a BLE scan without presenting a device list
     * (plan §10 acceptance). Derived from the pairing id, never from the secret, so a passive
     * observer of the advertisement learns nothing that helps them pair.
     */
    fun advertisingDiscriminator(): ByteArray = discriminatorFor(pairingId)

    /**
     * Compact, versioned, single-line encoding.
     *
     * Field-delimited rather than JSON: it keeps the QR small enough to scan reliably off a phone
     * screen at arm's length, and it is readable in a log line when something goes wrong.
     */
    fun encode(): String =
        listOf(
            PREFIX,
            protocolVersion.toString(),
            deviceId,
            pairingId,
            Crypto.base64UrlEncode(pairingSecret),
            expiresAtEpochMs.toString(),
        ).joinToString(SEPARATOR)

    /** Excludes the secret. Safe to log. */
    override fun toString(): String =
        "QrPairingPayload(v=$protocolVersion, device=$deviceId, pairing=$pairingId, " +
            "expiresAt=$expiresAtEpochMs, secret=<redacted>)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrPairingPayload) return false
        return protocolVersion == other.protocolVersion &&
            deviceId == other.deviceId &&
            pairingId == other.pairingId &&
            pairingSecret.contentEquals(other.pairingSecret) &&
            expiresAtEpochMs == other.expiresAtEpochMs
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + pairingId.hashCode()
        result = 31 * result + pairingSecret.contentHashCode()
        result = 31 * result + expiresAtEpochMs.hashCode()
        return result
    }

    companion object {
        const val PREFIX: String = "FT1"
        const val SEPARATOR: String = ":"

        /** Ids are constrained so the delimited encoding cannot be ambiguous. */
        val SAFE_ID: Regex = Regex("[A-Za-z0-9_-]{1,64}")

        /** Plan §6.6: expire after a short interval, initially five minutes. */
        const val DEFAULT_TTL_MS: Long = 5 * 60 * 1000

        const val DISCRIMINATOR_LENGTH: Int = 4

        fun discriminatorFor(pairingId: String): ByteArray =
            Crypto
                .hmac(Labels.DISCRIMINATOR, pairingId.toByteArray())
                .copyOf(DISCRIMINATOR_LENGTH)

        /** Mint a fresh credential. A new secret every time — never reuse across QR refreshes. */
        fun generate(
            deviceId: String,
            nowEpochMs: Long,
            ttlMs: Long = DEFAULT_TTL_MS,
            protocolVersion: Int = ProtocolVersion.CURRENT,
            random: SecureRandom = SecureRandom(),
        ): QrPairingPayload =
            QrPairingPayload(
                protocolVersion = protocolVersion,
                deviceId = deviceId,
                pairingId = Crypto.base64UrlEncode(Crypto.randomBytes(9, random)),
                pairingSecret = Crypto.randomBytes(Crypto.SECRET_LENGTH, random),
                expiresAtEpochMs = nowEpochMs + ttlMs,
            )

        /**
         * Parse a scanned string.
         *
         * @throws PairingFormatException on anything not exactly right. The iPhone must reject a
         *   malformed or foreign QR *before* starting BLE discovery (plan §10 task 4), so this is
         *   strict on purpose — no lenient parsing, no partial acceptance.
         */
        fun decode(text: String): QrPairingPayload {
            val trimmed = text.trim()
            val parts = trimmed.split(SEPARATOR)
            if (parts.size != 6) {
                throw PairingFormatException("expected 6 fields, found ${parts.size}")
            }
            if (parts[0] != PREFIX) {
                throw PairingFormatException("not a Flip Trainer pairing code (prefix '${parts[0]}')")
            }

            val version = parts[1].toIntOrNull() ?: throw PairingFormatException("bad protocol version '${parts[1]}'")
            val expiresAt = parts[5].toLongOrNull() ?: throw PairingFormatException("bad expiry '${parts[5]}'")
            val secret =
                try {
                    Crypto.base64UrlDecode(parts[4])
                } catch (e: IllegalArgumentException) {
                    throw PairingFormatException("secret is not valid base64url", e)
                }

            return try {
                QrPairingPayload(version, parts[2], parts[3], secret, expiresAt)
            } catch (e: IllegalArgumentException) {
                throw PairingFormatException(e.message ?: "invalid pairing payload", e)
            }
        }
    }
}

class PairingFormatException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object ProtocolVersion {
    const val CURRENT: Int = 1

    /** Strict equality. Best-effort parsing of an unknown version is how silent corruption starts. */
    fun isCompatible(other: Int): Boolean = other == CURRENT
}

/**
 * Proof and key computation, shared by both sides so they cannot drift apart.
 *
 * The transcript binds the device id, the pairing id and both nonces. Binding the ids stops a
 * proof captured from one device being presented to another; binding both nonces stops a proof
 * captured from one connection being replayed on the next.
 */
object PairingProofs {
    fun clientProof(
        secret: ByteArray,
        deviceId: String,
        pairingId: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray = Crypto.hmac(secret, Labels.CLIENT_PROOF, *transcript(deviceId, pairingId, clientNonce, serverNonce))

    fun serverProof(
        secret: ByteArray,
        deviceId: String,
        pairingId: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray = Crypto.hmac(secret, Labels.SERVER_PROOF, *transcript(deviceId, pairingId, clientNonce, serverNonce))

    /**
     * The persistent paired-device key, stored in Android Keystore and iOS Keychain.
     *
     * Derived from the one-time secret plus both nonces, so it is unique per pairing ceremony even
     * if a QR secret were somehow reused.
     */
    fun derivePairedKey(
        secret: ByteArray,
        deviceId: String,
        pairingId: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray =
        Crypto.hkdf(
            inputKeyMaterial = secret,
            salt = clientNonce + serverNonce,
            info = Labels.PAIRED_KEY_INFO + deviceId.toByteArray() + pairingId.toByteArray(),
            length = Crypto.KEY_LENGTH,
        )

    private fun transcript(
        deviceId: String,
        pairingId: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): Array<ByteArray> =
        arrayOf(
            lengthPrefixed(deviceId.toByteArray()),
            lengthPrefixed(pairingId.toByteArray()),
            lengthPrefixed(clientNonce),
            lengthPrefixed(serverNonce),
        )

    /**
     * Length-prefix each field before hashing.
     *
     * Without it, `("ab", "c")` and `("a", "bc")` produce the same transcript, and a device id can
     * be traded against a pairing id to forge a proof for a different pairing.
     */
    private fun lengthPrefixed(bytes: ByteArray): ByteArray {
        val length = bytes.size
        return byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        ) + bytes
    }
}
