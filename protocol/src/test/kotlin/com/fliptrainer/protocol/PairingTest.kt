package com.fliptrainer.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom

/**
 * The pairing ceremony, including every way it is supposed to fail.
 *
 * These are the Session 9 acceptance criteria expressed as tests. The security-relevant ones —
 * expired, consumed, wrong, and secret-never-transmitted — matter more than the happy path,
 * because the happy path is the one anyone would notice being broken.
 */
class PairingTest {
    private val deviceId = "flip5-test-01"
    private val now = 1_700_000_000_000L

    private fun fixedRandom(seed: Long = 42) = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }

    private fun payload(
        ttlMs: Long = QrPairingPayload.DEFAULT_TTL_MS,
        nowMs: Long = now,
    ) = QrPairingPayload.generate(deviceId, nowMs, ttlMs, random = fixedRandom())

    private fun pair(
        peripheral: PeripheralPairingSession,
        central: CentralPairingSession,
        nowMs: Long = now,
    ): Pair<PairedController, PairedController> {
        central.validate(nowMs)
        val challenge = peripheral.challenge(nowMs)
        val proof = central.respond(challenge)
        val accepted = peripheral.verify(proof, nowMs)
        val centralSide = central.confirm(challenge, accepted.serverProof)
        return accepted.paired to centralSide
    }

    // --- QR payload ------------------------------------------------------------------

    @Test
    fun `a generated payload round-trips through its encoding`() {
        val original = payload()
        assertEquals(original, QrPairingPayload.decode(original.encode()))
    }

    @Test
    fun `the encoded payload is small enough to scan comfortably`() {
        // A QR of this length is trivially scannable off a phone screen; a JSON encoding of the
        // same fields would be roughly double.
        assertTrue(payload().encode().length < 140, "encoded length was ${payload().encode().length}")
    }

    @Test
    fun `the secret never appears in a log line`() {
        val p = payload()
        val rendered = p.toString()
        assertTrue(rendered.contains("redacted"))
        assertFalse(rendered.contains(Crypto.base64UrlEncode(p.pairingSecret)))
    }

    @Test
    fun `every QR carries a fresh secret and a fresh pairing id`() {
        val a = QrPairingPayload.generate(deviceId, now, random = fixedRandom(1))
        val b = QrPairingPayload.generate(deviceId, now, random = fixedRandom(2))
        assertFalse(a.pairingSecret.contentEquals(b.pairingSecret), "a refreshed QR must not reuse the secret")
        assertFalse(a.pairingId == b.pairingId)
    }

    @Test
    fun `malformed codes are rejected before any radio work`() {
        for (
        bad in
        listOf(
            "",
            "nonsense",
            "FT1:1:device:pairing", // too few fields
            "XX1:1:device:pairing:AAAA:123", // wrong prefix
            "FT1:notanumber:device:pairing:AAAA:123",
            "FT1:1:device:pairing:AAAA:notanumber",
            "FT1:1:device:pairing:tooshort:123", // secret wrong length
            "FT1:1:bad device:pairing:AAAA:123", // space in id
        )
        ) {
            assertThrows<PairingFormatException>("should have rejected '$bad'") { QrPairingPayload.decode(bad) }
        }
    }

    @Test
    fun `the advertising discriminator is derived from the pairing id and not the secret`() {
        val p = payload()
        assertArrayEquals(QrPairingPayload.discriminatorFor(p.pairingId), p.advertisingDiscriminator())
        assertEquals(QrPairingPayload.DISCRIMINATOR_LENGTH, p.advertisingDiscriminator().size)

        // Same pairing id, different secret: the advertisement must be identical, proving it
        // leaks nothing about the secret to a passive scanner.
        val sameIdDifferentSecret = p.copy(pairingSecret = Crypto.randomBytes(Crypto.SECRET_LENGTH))
        assertArrayEquals(p.advertisingDiscriminator(), sameIdDifferentSecret.advertisingDiscriminator())
    }

    @Test
    fun `expiry is five minutes by default`() {
        val p = payload()
        assertFalse(p.isExpiredAt(now + 4 * 60 * 1000))
        assertTrue(p.isExpiredAt(now + 5 * 60 * 1000))
    }

    // --- the ceremony ----------------------------------------------------------------

    @Test
    fun `a correct handshake produces the same key on both sides`() {
        val p = payload()
        val (galaxy, iphone) =
            pair(
                PeripheralPairingSession(p, fixedRandom(7)),
                CentralPairingSession(p.copy(), fixedRandom(8)),
            )

        assertArrayEquals(galaxy.pairedKey, iphone.pairedKey, "both sides must derive the same key")
        assertEquals(Crypto.KEY_LENGTH, galaxy.pairedKey.size)
        assertEquals(deviceId, galaxy.deviceId)
    }

    @Test
    fun `the derived key is not the QR secret`() {
        val p = payload()
        val secretCopy = p.pairingSecret.copyOf()
        val (galaxy, _) =
            pair(
                PeripheralPairingSession(p, fixedRandom()),
                CentralPairingSession(p.copy(), fixedRandom()),
            )
        assertFalse(galaxy.pairedKey.contentEquals(secretCopy), "the paired key must be derived, not the secret itself")
    }

    @Test
    fun `two pairings with the same secret still derive different keys`() {
        // Nonces are mixed into the derivation, so even a reused credential cannot produce a
        // reused long-term key.
        val secret = Crypto.randomBytes(Crypto.SECRET_LENGTH)
        val base = payload().copy(pairingSecret = secret)

        val first =
            pair(
                PeripheralPairingSession(base.copy(pairingSecret = secret.copyOf()), fixedRandom(1)),
                CentralPairingSession(base.copy(pairingSecret = secret.copyOf()), fixedRandom(2)),
            )
        val second =
            pair(
                PeripheralPairingSession(base.copy(pairingSecret = secret.copyOf()), fixedRandom(3)),
                CentralPairingSession(base.copy(pairingSecret = secret.copyOf()), fixedRandom(4)),
            )

        assertFalse(first.first.pairedKey.contentEquals(second.first.pairedKey))
    }

    @Test
    fun `the QR secret is never present in anything sent over the wire`() {
        // Session 9 acceptance, stated directly.
        val p = payload()
        val secret = p.pairingSecret.copyOf()
        val peripheral = PeripheralPairingSession(p, fixedRandom())
        val central = CentralPairingSession(p.copy(pairingSecret = secret.copyOf()), fixedRandom())

        val challenge = peripheral.challenge(now)
        val proof = central.respond(challenge)
        val accepted = peripheral.verify(proof, now)

        val onTheWire =
            challenge.serverNonce + challenge.deviceId.toByteArray() + challenge.pairingId.toByteArray() +
                proof.clientNonce + proof.clientProof + accepted.serverProof
        assertFalse(containsSubsequence(onTheWire, secret), "the one-time secret must never be transmitted")
    }

    @Test
    fun `a wrong secret is rejected`() {
        val real = payload()
        val attacker = real.copy(pairingSecret = Crypto.randomBytes(Crypto.SECRET_LENGTH))

        val peripheral = PeripheralPairingSession(real, fixedRandom())
        val central = CentralPairingSession(attacker, fixedRandom())

        val challenge = peripheral.challenge(now)
        val proof = central.respond(challenge)
        val error = assertThrows<PairingRejected> { peripheral.verify(proof, now) }
        assertEquals(PairingRejection.BAD_PROOF, error.rejection)
    }

    @Test
    fun `an expired QR is rejected by both sides`() {
        val p = payload(ttlMs = 1_000)
        val late = now + 60_000

        val peripheral = PeripheralPairingSession(p, fixedRandom())
        assertEquals(
            PairingRejection.PAIRING_EXPIRED,
            assertThrows<PairingRejected> { peripheral.challenge(late) }.rejection,
        )

        val central = CentralPairingSession(p.copy(), fixedRandom())
        assertEquals(
            PairingRejection.PAIRING_EXPIRED,
            assertThrows<PairingRejected> { central.validate(late) }.rejection,
        )
    }

    @Test
    fun `a QR can only be used once`() {
        val p = payload()
        val peripheral = PeripheralPairingSession(p, fixedRandom())
        val central = CentralPairingSession(p.copy(), fixedRandom())
        pair(peripheral, central)

        assertTrue(peripheral.isConsumed)
        assertEquals(
            PairingRejection.PAIRING_CONSUMED,
            assertThrows<PairingRejected> { peripheral.challenge(now) }.rejection,
        )
    }

    @Test
    fun `a failed attempt does not burn the credential`() {
        // Otherwise anyone in radio range could deny pairing by connecting once with garbage.
        val p = payload()
        val peripheral = PeripheralPairingSession(p, fixedRandom())
        val secretCopy = p.pairingSecret.copyOf()

        val challenge = peripheral.challenge(now)
        val bogus = PairingProofData(ProtocolVersion.CURRENT, Crypto.randomBytes(Crypto.NONCE_LENGTH), ByteArray(32))
        assertThrows<PairingRejected> { peripheral.verify(bogus, now) }
        assertFalse(peripheral.isConsumed, "a wrong proof must not consume the QR")

        // The real controller can still pair, using the same challenge.
        val central = CentralPairingSession(p.copy(pairingSecret = secretCopy), fixedRandom())
        val proof = central.respond(challenge)
        assertTrue(
            peripheral
                .verify(proof, now)
                .paired.pairedKey
                .isNotEmpty(),
        )
    }

    @Test
    fun `a proof from one connection cannot be replayed on the next`() {
        val p = payload()
        val secret = p.pairingSecret.copyOf()

        val first = PeripheralPairingSession(p.copy(pairingSecret = secret.copyOf()), fixedRandom(1))
        val central = CentralPairingSession(p.copy(pairingSecret = secret.copyOf()), fixedRandom(2))
        val capturedProof = central.respond(first.challenge(now))

        // Same QR, new connection, new server nonce.
        val second = PeripheralPairingSession(p.copy(pairingSecret = secret.copyOf()), fixedRandom(3))
        second.challenge(now)
        assertEquals(
            PairingRejection.BAD_PROOF,
            assertThrows<PairingRejected> { second.verify(capturedProof, now) }.rejection,
        )
    }

    @Test
    fun `a proof cannot be presented before a challenge`() {
        val p = payload()
        val peripheral = PeripheralPairingSession(p, fixedRandom())
        val proof = PairingProofData(ProtocolVersion.CURRENT, Crypto.randomBytes(Crypto.NONCE_LENGTH), ByteArray(32))
        assertEquals(
            PairingRejection.MALFORMED,
            assertThrows<PairingRejected> { peripheral.verify(proof, now) }.rejection,
        )
    }

    @Test
    fun `a version mismatch is a clean rejection`() {
        val p = payload()
        val peripheral = PeripheralPairingSession(p, fixedRandom())
        peripheral.challenge(now)
        val proof = PairingProofData(99, Crypto.randomBytes(Crypto.NONCE_LENGTH), ByteArray(32))
        assertEquals(
            PairingRejection.INCOMPATIBLE_VERSION,
            assertThrows<PairingRejected> { peripheral.verify(proof, now) }.rejection,
        )

        val central = CentralPairingSession(p.copy(protocolVersion = 99), fixedRandom())
        assertEquals(
            PairingRejection.INCOMPATIBLE_VERSION,
            assertThrows<PairingRejected> { central.validate(now) }.rejection,
        )
    }

    @Test
    fun `the controller refuses a device that cannot prove it generated the code`() {
        // Mutual authentication: without confirming the server proof, the iPhone would pair with
        // anything that answered.
        val p = payload()
        val peripheral = PeripheralPairingSession(p, fixedRandom())
        val central = CentralPairingSession(p.copy(), fixedRandom())

        val challenge = peripheral.challenge(now)
        central.respond(challenge)
        assertEquals(
            PairingRejection.BAD_PROOF,
            assertThrows<PairingRejected> { central.confirm(challenge, ByteArray(32)) }.rejection,
        )
    }

    @Test
    fun `the controller refuses a device advertising a different pairing id`() {
        val scanned = payload()
        val central = CentralPairingSession(scanned, fixedRandom())
        val impostor =
            PairingChallengeData(
                protocolVersion = ProtocolVersion.CURRENT,
                deviceId = scanned.deviceId,
                pairingId = "someone-else",
                serverNonce = Crypto.randomBytes(Crypto.NONCE_LENGTH),
            )
        assertEquals(
            PairingRejection.UNKNOWN_PAIRING_ID,
            assertThrows<PairingRejected> { central.respond(impostor) }.rejection,
        )
    }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return true
        }
        return false
    }
}

class CryptoTest {
    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        // The canonical vector. If this drifts, the paired keys on two devices stop matching and
        // the failure looks like "pairing mysteriously broke".
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }

        val prk = Crypto.hkdfExtract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            prk.toHex(),
        )

        val okm = Crypto.hkdfExpand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun `hkdf matches RFC 5869 test case 3 with empty salt and info`() {
        val ikm = ByteArray(22) { 0x0b }
        val okm = Crypto.hkdf(ikm, ByteArray(0), ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.toHex(),
        )
    }

    @Test
    fun `hkdf output length is honoured and varies with info`() {
        val ikm = Crypto.randomBytes(32)
        val salt = Crypto.randomBytes(16)
        assertEquals(64, Crypto.hkdf(ikm, salt, "a".toByteArray(), 64).size)
        assertFalse(
            Crypto.hkdf(ikm, salt, "a".toByteArray()).contentEquals(Crypto.hkdf(ikm, salt, "b".toByteArray())),
            "different info must produce different keys",
        )
    }

    @Test
    fun `constant time comparison still compares correctly`() {
        val a = Crypto.randomBytes(32)
        assertTrue(Crypto.constantTimeEquals(a, a.copyOf()))
        assertFalse(Crypto.constantTimeEquals(a, Crypto.randomBytes(32)))
        assertFalse(Crypto.constantTimeEquals(a, a.copyOf(16)))
    }

    @Test
    fun `hkdf refuses to expand beyond its limit`() {
        assertThrows<IllegalArgumentException> { Crypto.hkdfExpand(Crypto.randomBytes(32), ByteArray(0), 0) }
        assertThrows<IllegalArgumentException> { Crypto.hkdfExpand(Crypto.randomBytes(32), ByteArray(0), 255 * 32 + 1) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
