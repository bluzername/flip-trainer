package com.fliptrainer.protocol

/** Which way a message travels. Bound into the MAC so a message cannot be reflected. */
enum class Direction(
    val wire: Int,
) {
    PERIPHERAL_TO_CENTRAL(0),
    CENTRAL_TO_PERIPHERAL(1),
    ;

    val opposite: Direction
        get() = if (this == PERIPHERAL_TO_CENTRAL) CENTRAL_TO_PERIPHERAL else PERIPHERAL_TO_CENTRAL

    companion object {
        fun fromWire(value: Int): Direction? = entries.firstOrNull { it.wire == value }
    }
}

/** Why an inbound envelope was refused. Mirrors `ProtocolError.Code`. */
enum class EnvelopeRejection {
    INCOMPATIBLE_VERSION,
    BAD_MAC,
    REPLAYED_COUNTER,
    WRONG_DIRECTION,
    TOO_LARGE,
    MALFORMED,
}

class EnvelopeRejected(
    val rejection: EnvelopeRejection,
    message: String,
) : IllegalArgumentException(message)

/** An authenticated envelope, before or after serialization. */
data class SealedEnvelope(
    val protocolVersion: Int,
    val direction: Direction,
    val counter: Long,
    val payload: ByteArray,
    val mac: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SealedEnvelope) return false
        return protocolVersion == other.protocolVersion &&
            direction == other.direction &&
            counter == other.counter &&
            payload.contentEquals(other.payload) &&
            mac.contentEquals(other.mac)
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + direction.hashCode()
        result = 31 * result + counter.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + mac.contentHashCode()
        return result
    }
}

/**
 * Authenticates every post-pairing message and rejects replays.
 *
 * One instance per peer, per connection lifetime of the paired key. Outbound counters are
 * strictly increasing; inbound counters must exceed the highest seen.
 *
 * **This authenticates, it does not encrypt.** Contents are visible to a passive listener in
 * radio range. That is a deliberate, documented position for v1: the payloads are rep counts and
 * phase names, BLE link-layer encryption applies underneath where the platform supports it, and
 * adding an AEAD layer is listed in Session 14's hardening decisions rather than guessed at now.
 * Anything genuinely sensitive must not be put on this channel in the first place.
 */
class SecureChannel(
    private val pairedKey: ByteArray,
    private val outboundDirection: Direction,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
) {
    init {
        require(pairedKey.size == Crypto.KEY_LENGTH) { "paired key must be ${Crypto.KEY_LENGTH} bytes" }
    }

    private var outboundCounter = 0L
    private var highestInboundCounter = -1L

    val nextOutboundCounter: Long get() = outboundCounter
    val lastInboundCounter: Long get() = highestInboundCounter

    fun seal(payload: ByteArray): SealedEnvelope {
        require(payload.size <= maxPayloadBytes) {
            "payload of ${payload.size} bytes exceeds the $maxPayloadBytes byte limit"
        }
        val counter = outboundCounter++
        return SealedEnvelope(
            protocolVersion = ProtocolVersion.CURRENT,
            direction = outboundDirection,
            counter = counter,
            payload = payload,
            mac = mac(ProtocolVersion.CURRENT, outboundDirection, counter, payload),
        )
    }

    /**
     * Verify and unwrap.
     *
     * Order matters: version, then size, then direction, then MAC, then counter. The counter is
     * checked **last and only after the MAC verifies**, so an attacker cannot advance our replay
     * window with forged envelopes and cause us to reject the peer's genuine traffic.
     */
    fun open(envelope: SealedEnvelope): ByteArray {
        if (!ProtocolVersion.isCompatible(envelope.protocolVersion)) {
            throw EnvelopeRejected(
                EnvelopeRejection.INCOMPATIBLE_VERSION,
                "envelope declares protocol ${envelope.protocolVersion}, this build speaks ${ProtocolVersion.CURRENT}",
            )
        }
        if (envelope.payload.size > maxPayloadBytes) {
            throw EnvelopeRejected(
                EnvelopeRejection.TOO_LARGE,
                "payload of ${envelope.payload.size} bytes exceeds the $maxPayloadBytes byte limit",
            )
        }
        if (envelope.direction != outboundDirection.opposite) {
            throw EnvelopeRejected(
                EnvelopeRejection.WRONG_DIRECTION,
                "received an envelope addressed ${envelope.direction}; this endpoint expects ${outboundDirection.opposite}",
            )
        }
        if (envelope.mac.size != Crypto.MAC_LENGTH) {
            throw EnvelopeRejected(EnvelopeRejection.MALFORMED, "mac must be ${Crypto.MAC_LENGTH} bytes")
        }

        val expected = mac(envelope.protocolVersion, envelope.direction, envelope.counter, envelope.payload)
        if (!Crypto.constantTimeEquals(expected, envelope.mac)) {
            throw EnvelopeRejected(EnvelopeRejection.BAD_MAC, "envelope failed authentication")
        }

        if (envelope.counter <= highestInboundCounter) {
            throw EnvelopeRejected(
                EnvelopeRejection.REPLAYED_COUNTER,
                "counter ${envelope.counter} is not above the highest seen ($highestInboundCounter)",
            )
        }
        highestInboundCounter = envelope.counter
        return envelope.payload
    }

    /**
     * Reset counters for a new connection while keeping the paired key.
     *
     * Safe because the MAC covers the counter and the key is per-pairing, and necessary because a
     * BLE reconnect starts both sides from zero. If counters ever needed to survive across
     * connections, the key would have to be rotated instead.
     */
    fun resetCounters() {
        outboundCounter = 0
        highestInboundCounter = -1
    }

    private fun mac(
        version: Int,
        direction: Direction,
        counter: Long,
        payload: ByteArray,
    ): ByteArray {
        val header =
            byteArrayOf(
                (version ushr 8).toByte(),
                version.toByte(),
                direction.wire.toByte(),
                (counter ushr 56).toByte(),
                (counter ushr 48).toByte(),
                (counter ushr 40).toByte(),
                (counter ushr 32).toByte(),
                (counter ushr 24).toByte(),
                (counter ushr 16).toByte(),
                (counter ushr 8).toByte(),
                counter.toByte(),
            )
        return Crypto.hmac(pairedKey, Labels.ENVELOPE, header, payload).copyOf(Crypto.MAC_LENGTH)
    }

    companion object {
        /**
         * Generous relative to a BLE MTU because [Framing] chunks anything larger. The cap exists
         * to bound memory against a hostile or broken peer, not to match the radio.
         */
        const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 64 * 1024
    }
}
