package com.fliptrainer.protocol

import java.security.SecureRandom

/** Why a pairing attempt was refused. Mirrors `ProtocolError.Code` on the wire. */
enum class PairingRejection {
    INCOMPATIBLE_VERSION,
    PAIRING_EXPIRED,
    PAIRING_CONSUMED,
    UNKNOWN_PAIRING_ID,
    BAD_PROOF,
    ALREADY_PAIRED,
    MALFORMED,
}

class PairingRejected(
    val rejection: PairingRejection,
    message: String,
) : IllegalStateException(message)

/** A completed pairing: what both sides persist. */
data class PairedController(
    val deviceId: String,
    val pairingId: String,
    val pairedKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairedController) return false
        return deviceId == other.deviceId && pairingId == other.pairingId && pairedKey.contentEquals(other.pairedKey)
    }

    override fun hashCode(): Int = 31 * (31 * deviceId.hashCode() + pairingId.hashCode()) + pairedKey.contentHashCode()

    /** Never print the key. */
    override fun toString(): String = "PairedController(device=$deviceId, pairing=$pairingId, key=<redacted>)"
}

/**
 * The Galaxy's side of the pairing ceremony.
 *
 * Owns the one-time secret and enforces every lifecycle rule from plan §6.6: expiry, single use,
 * fresh nonces, and — the important one — **the secret is never transmitted.** It only ever
 * appears as an input to an HMAC.
 *
 * One instance per displayed QR code. When the QR is refreshed, a new instance with a new secret
 * replaces it; the old one can no longer pair anything.
 */
class PeripheralPairingSession(
    payload: QrPairingPayload,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Own copy of the credential.
     *
     * `QrPairingPayload` is a data class over a mutable `ByteArray`, so two payloads produced by
     * `copy()` alias the same secret. Without this defensive copy, wiping after a successful
     * pairing would blank the secret out from under whoever else still held the payload.
     */
    private val payload = payload.copy(pairingSecret = payload.pairingSecret.copyOf())

    private var consumed = false
    private var issuedServerNonce: ByteArray? = null

    val pairingId: String get() = payload.pairingId
    val deviceId: String get() = payload.deviceId
    val isConsumed: Boolean get() = consumed

    /** What the Galaxy shows on screen. */
    fun qrText(): String = payload.encode()

    fun advertisingDiscriminator(): ByteArray = payload.advertisingDiscriminator()

    fun isExpiredAt(nowEpochMs: Long): Boolean = payload.isExpiredAt(nowEpochMs)

    /**
     * Begin an attempt: a fresh nonce per connection.
     *
     * Reusing a nonce across attempts would let a proof captured from one connection be replayed
     * on the next, which is exactly the attack the nonce exists to prevent.
     */
    fun challenge(nowEpochMs: Long): PairingChallengeData {
        if (consumed) throw PairingRejected(PairingRejection.PAIRING_CONSUMED, "this QR has already been used")
        if (payload.isExpiredAt(nowEpochMs)) {
            throw PairingRejected(PairingRejection.PAIRING_EXPIRED, "this QR expired at ${payload.expiresAtEpochMs}")
        }
        val nonce = Crypto.randomBytes(Crypto.NONCE_LENGTH, random)
        issuedServerNonce = nonce
        return PairingChallengeData(
            protocolVersion = payload.protocolVersion,
            deviceId = payload.deviceId,
            pairingId = payload.pairingId,
            serverNonce = nonce,
        )
    }

    /**
     * Verify the controller's proof and, on success, consume the QR and derive the paired key.
     *
     * Consumption happens only on success. A wrong proof must not burn the credential, or an
     * attacker in radio range could deny pairing by connecting once with garbage.
     */
    fun verify(
        proof: PairingProofData,
        nowEpochMs: Long,
    ): PairingAccepted {
        if (consumed) throw PairingRejected(PairingRejection.PAIRING_CONSUMED, "this QR has already been used")
        if (payload.isExpiredAt(nowEpochMs)) {
            throw PairingRejected(PairingRejection.PAIRING_EXPIRED, "this QR expired at ${payload.expiresAtEpochMs}")
        }
        if (!ProtocolVersion.isCompatible(proof.protocolVersion)) {
            throw PairingRejected(
                PairingRejection.INCOMPATIBLE_VERSION,
                "controller speaks protocol ${proof.protocolVersion}, this device speaks ${ProtocolVersion.CURRENT}",
            )
        }
        val serverNonce =
            issuedServerNonce
                ?: throw PairingRejected(PairingRejection.MALFORMED, "proof received before a challenge was issued")
        if (proof.clientNonce.size != Crypto.NONCE_LENGTH) {
            throw PairingRejected(PairingRejection.MALFORMED, "client nonce must be ${Crypto.NONCE_LENGTH} bytes")
        }

        val expected =
            PairingProofs.clientProof(
                secret = payload.pairingSecret,
                deviceId = payload.deviceId,
                pairingId = payload.pairingId,
                clientNonce = proof.clientNonce,
                serverNonce = serverNonce,
            )
        if (!Crypto.constantTimeEquals(expected, proof.clientProof)) {
            throw PairingRejected(PairingRejection.BAD_PROOF, "client proof did not verify")
        }

        val serverProof =
            PairingProofs.serverProof(
                secret = payload.pairingSecret,
                deviceId = payload.deviceId,
                pairingId = payload.pairingId,
                clientNonce = proof.clientNonce,
                serverNonce = serverNonce,
            )
        val pairedKey =
            PairingProofs.derivePairedKey(
                secret = payload.pairingSecret,
                deviceId = payload.deviceId,
                pairingId = payload.pairingId,
                clientNonce = proof.clientNonce,
                serverNonce = serverNonce,
            )

        consumed = true
        // The one-time secret has served its only purpose. Wiping is best-effort on a JVM, but
        // leaving it live in a long-running appliance process is worse.
        Crypto.wipe(payload.pairingSecret)

        return PairingAccepted(
            serverProof = serverProof,
            paired = PairedController(payload.deviceId, payload.pairingId, pairedKey),
        )
    }
}

/**
 * The controller's side. Used by the iPhone, and by the development client that lets the
 * handshake be tested end to end without an iPhone in the room.
 */
class CentralPairingSession(
    scanned: QrPairingPayload,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Own copy, for the same aliasing reason as [PeripheralPairingSession]. */
    private val scanned = scanned.copy(pairingSecret = scanned.pairingSecret.copyOf())

    private var clientNonce: ByteArray? = null

    /** Reject a QR that is expired or foreign before ever touching the radio. */
    fun validate(nowEpochMs: Long) {
        if (!ProtocolVersion.isCompatible(scanned.protocolVersion)) {
            throw PairingRejected(
                PairingRejection.INCOMPATIBLE_VERSION,
                "this code is for protocol ${scanned.protocolVersion}; this app speaks ${ProtocolVersion.CURRENT}",
            )
        }
        if (scanned.isExpiredAt(nowEpochMs)) {
            throw PairingRejected(PairingRejection.PAIRING_EXPIRED, "this code has expired — refresh it on the Galaxy")
        }
    }

    fun expectedDiscriminator(): ByteArray = scanned.advertisingDiscriminator()

    fun respond(challenge: PairingChallengeData): PairingProofData {
        if (challenge.pairingId != scanned.pairingId) {
            throw PairingRejected(
                PairingRejection.UNKNOWN_PAIRING_ID,
                "connected to a device advertising pairing '${challenge.pairingId}', expected '${scanned.pairingId}'",
            )
        }
        if (challenge.deviceId != scanned.deviceId) {
            throw PairingRejected(PairingRejection.UNKNOWN_PAIRING_ID, "device id does not match the scanned code")
        }
        if (!ProtocolVersion.isCompatible(challenge.protocolVersion)) {
            throw PairingRejected(PairingRejection.INCOMPATIBLE_VERSION, "incompatible protocol version")
        }

        val nonce = Crypto.randomBytes(Crypto.NONCE_LENGTH, random)
        clientNonce = nonce
        return PairingProofData(
            protocolVersion = ProtocolVersion.CURRENT,
            clientNonce = nonce,
            clientProof =
                PairingProofs.clientProof(
                    secret = scanned.pairingSecret,
                    deviceId = scanned.deviceId,
                    pairingId = scanned.pairingId,
                    clientNonce = nonce,
                    serverNonce = challenge.serverNonce,
                ),
        )
    }

    /**
     * Check the peripheral's proof before trusting it.
     *
     * Mutual, not one-way: without this the iPhone would pair with anything that answered, and a
     * nearby device could impersonate the Galaxy by accepting any proof it was offered.
     */
    fun confirm(
        challenge: PairingChallengeData,
        serverProof: ByteArray,
    ): PairedController {
        val nonce = clientNonce ?: throw PairingRejected(PairingRejection.MALFORMED, "confirm before respond")

        val expected =
            PairingProofs.serverProof(
                secret = scanned.pairingSecret,
                deviceId = scanned.deviceId,
                pairingId = scanned.pairingId,
                clientNonce = nonce,
                serverNonce = challenge.serverNonce,
            )
        if (!Crypto.constantTimeEquals(expected, serverProof)) {
            throw PairingRejected(PairingRejection.BAD_PROOF, "the device could not prove it generated this code")
        }

        val pairedKey =
            PairingProofs.derivePairedKey(
                secret = scanned.pairingSecret,
                deviceId = scanned.deviceId,
                pairingId = scanned.pairingId,
                clientNonce = nonce,
                serverNonce = challenge.serverNonce,
            )
        return PairedController(scanned.deviceId, scanned.pairingId, pairedKey)
    }
}

data class PairingChallengeData(
    val protocolVersion: Int,
    val deviceId: String,
    val pairingId: String,
    val serverNonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingChallengeData) return false
        return protocolVersion == other.protocolVersion &&
            deviceId == other.deviceId &&
            pairingId == other.pairingId &&
            serverNonce.contentEquals(other.serverNonce)
    }

    override fun hashCode(): Int =
        31 * (31 * (31 * protocolVersion + deviceId.hashCode()) + pairingId.hashCode()) + serverNonce.contentHashCode()
}

data class PairingProofData(
    val protocolVersion: Int,
    val clientNonce: ByteArray,
    val clientProof: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingProofData) return false
        return protocolVersion == other.protocolVersion &&
            clientNonce.contentEquals(other.clientNonce) &&
            clientProof.contentEquals(other.clientProof)
    }

    override fun hashCode(): Int =
        31 * (31 * protocolVersion + clientNonce.contentHashCode()) + clientProof.contentHashCode()
}

data class PairingAccepted(
    val serverProof: ByteArray,
    val paired: PairedController,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingAccepted) return false
        return serverProof.contentEquals(other.serverProof) && paired == other.paired
    }

    override fun hashCode(): Int = 31 * serverProof.contentHashCode() + paired.hashCode()
}
