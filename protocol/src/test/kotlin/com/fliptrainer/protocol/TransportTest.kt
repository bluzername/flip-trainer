package com.fliptrainer.protocol

import com.fliptrainer.core.session.EventPayload
import com.fliptrainer.core.session.InMemoryWorkoutEventLog
import com.fliptrainer.core.session.WorkoutEvent
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SecureChannelTest {
    private val key = Crypto.randomBytes(Crypto.KEY_LENGTH)

    private fun pair(): Pair<SecureChannel, SecureChannel> =
        SecureChannel(key, Direction.PERIPHERAL_TO_CENTRAL) to SecureChannel(key, Direction.CENTRAL_TO_PERIPHERAL)

    @Test
    fun `a sealed message opens on the other side`() {
        val (galaxy, iphone) = pair()
        val message = "rep 7 complete".toByteArray()
        assertArrayEquals(message, iphone.open(galaxy.seal(message)))
    }

    @Test
    fun `counters increase strictly per direction`() {
        val (galaxy, iphone) = pair()
        val sealed = (0 until 5).map { galaxy.seal(byteArrayOf(it.toByte())) }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), sealed.map { it.counter })
        sealed.forEach { iphone.open(it) }
        assertEquals(4L, iphone.lastInboundCounter)
    }

    @Test
    fun `a replayed envelope is rejected`() {
        val (galaxy, iphone) = pair()
        val envelope = galaxy.seal("start set".toByteArray())
        iphone.open(envelope)

        val error = assertThrows<EnvelopeRejected> { iphone.open(envelope) }
        assertEquals(EnvelopeRejection.REPLAYED_COUNTER, error.rejection)
    }

    @Test
    fun `an out-of-order older envelope is rejected`() {
        val (galaxy, iphone) = pair()
        val first = galaxy.seal("a".toByteArray())
        val second = galaxy.seal("b".toByteArray())
        iphone.open(second)
        assertEquals(
            EnvelopeRejection.REPLAYED_COUNTER,
            assertThrows<EnvelopeRejected> { iphone.open(first) }.rejection,
        )
    }

    @Test
    fun `a tampered payload fails authentication`() {
        val (galaxy, iphone) = pair()
        val envelope = galaxy.seal("reps=10".toByteArray())
        val tampered = envelope.copy(payload = "reps=99".toByteArray())
        assertEquals(
            EnvelopeRejection.BAD_MAC,
            assertThrows<EnvelopeRejected> { iphone.open(tampered) }.rejection,
        )
    }

    @Test
    fun `a tampered counter fails authentication rather than being accepted as new`() {
        // The counter is inside the MAC, so bumping it to dodge the replay check invalidates it.
        val (galaxy, iphone) = pair()
        val envelope = galaxy.seal("x".toByteArray())
        iphone.open(envelope)
        assertEquals(
            EnvelopeRejection.BAD_MAC,
            assertThrows<EnvelopeRejected> { iphone.open(envelope.copy(counter = 500)) }.rejection,
        )
    }

    @Test
    fun `an envelope cannot be reflected back at its sender`() {
        val (galaxy, _) = pair()
        val envelope = galaxy.seal("hello".toByteArray())
        val otherGalaxy = SecureChannel(key, Direction.PERIPHERAL_TO_CENTRAL)
        assertEquals(
            EnvelopeRejection.WRONG_DIRECTION,
            assertThrows<EnvelopeRejected> { otherGalaxy.open(envelope) }.rejection,
        )
    }

    @Test
    fun `an envelope from an unknown key is rejected`() {
        val galaxy = SecureChannel(key, Direction.PERIPHERAL_TO_CENTRAL)
        val stranger = SecureChannel(Crypto.randomBytes(Crypto.KEY_LENGTH), Direction.CENTRAL_TO_PERIPHERAL)
        assertEquals(
            EnvelopeRejection.BAD_MAC,
            assertThrows<EnvelopeRejected> { stranger.open(galaxy.seal("arm".toByteArray())) }.rejection,
        )
    }

    @Test
    fun `a forged envelope does not poison the replay window`() {
        // Checking the counter only after the MAC verifies matters: otherwise an attacker could
        // send counter=Long.MAX_VALUE with a junk MAC and make us reject the peer's real traffic.
        val (galaxy, iphone) = pair()
        val forged =
            SealedEnvelope(
                protocolVersion = ProtocolVersion.CURRENT,
                direction = Direction.PERIPHERAL_TO_CENTRAL,
                counter = Long.MAX_VALUE,
                payload = "junk".toByteArray(),
                mac = ByteArray(Crypto.MAC_LENGTH),
            )
        assertThrows<EnvelopeRejected> { iphone.open(forged) }

        // The genuine next message still gets through.
        assertArrayEquals("real".toByteArray(), iphone.open(galaxy.seal("real".toByteArray())))
    }

    @Test
    fun `a protocol version mismatch is reported distinctly`() {
        val (galaxy, iphone) = pair()
        val envelope = galaxy.seal("x".toByteArray()).copy(protocolVersion = 99)
        assertEquals(
            EnvelopeRejection.INCOMPATIBLE_VERSION,
            assertThrows<EnvelopeRejected> { iphone.open(envelope) }.rejection,
        )
    }

    @Test
    fun `an oversized payload is refused on both send and receive`() {
        val small = SecureChannel(key, Direction.PERIPHERAL_TO_CENTRAL, maxPayloadBytes = 64)
        assertThrows<IllegalArgumentException> { small.seal(ByteArray(65)) }

        val galaxy = SecureChannel(key, Direction.PERIPHERAL_TO_CENTRAL)
        val strictReceiver = SecureChannel(key, Direction.CENTRAL_TO_PERIPHERAL, maxPayloadBytes = 64)
        assertEquals(
            EnvelopeRejection.TOO_LARGE,
            assertThrows<EnvelopeRejected> { strictReceiver.open(galaxy.seal(ByteArray(65))) }.rejection,
        )
    }

    @Test
    fun `reconnecting resets counters without changing the key`() {
        val (galaxy, iphone) = pair()
        repeat(5) { iphone.open(galaxy.seal(byteArrayOf(it.toByte()))) }

        galaxy.resetCounters()
        iphone.resetCounters()
        assertArrayEquals("after reconnect".toByteArray(), iphone.open(galaxy.seal("after reconnect".toByteArray())))
    }

    @Test
    fun `a malformed mac length is rejected before comparison`() {
        val (galaxy, iphone) = pair()
        assertEquals(
            EnvelopeRejection.MALFORMED,
            assertThrows<EnvelopeRejected> {
                iphone.open(
                    galaxy.seal("x".toByteArray()).copy(mac = ByteArray(4)),
                )
            }.rejection,
        )
    }

    @Test
    fun `a wrong-sized key is refused at construction`() {
        assertThrows<IllegalArgumentException> { SecureChannel(ByteArray(16), Direction.PERIPHERAL_TO_CENTRAL) }
    }
}

class FramingTest {
    @Test
    fun `a small message survives a round trip`() {
        val message = "hello".toByteArray()
        val reassembler = Framing.Reassembler()
        val out = Framing.chunk(message).flatMap { reassembler.accept(it) }
        assertEquals(1, out.size)
        assertArrayEquals(message, out.single())
        assertEquals(0, reassembler.pendingBytes)
    }

    @Test
    fun `a message larger than the MTU is split and rejoined`() {
        val message = ByteArray(1000) { (it % 251).toByte() }
        val chunks = Framing.chunk(message, chunkBytes = 60)
        assertTrue(chunks.size > 15, "expected many chunks, got ${chunks.size}")
        assertTrue(chunks.all { it.size <= 60 })

        val reassembler = Framing.Reassembler()
        val out = chunks.flatMap { reassembler.accept(it) }
        assertArrayEquals(message, out.single())
    }

    @Test
    fun `back-to-back messages in one chunk are both delivered`() {
        // A burst of rep events can coalesce into a single notification payload.
        val a = "first".toByteArray()
        val b = "second".toByteArray()
        val combined = Framing.chunk(a, 4096).single() + Framing.chunk(b, 4096).single()

        val out = Framing.Reassembler().accept(combined)
        assertEquals(2, out.size)
        assertArrayEquals(a, out[0])
        assertArrayEquals(b, out[1])
    }

    @Test
    fun `a message split across an arbitrary byte boundary still reassembles`() {
        val message = ByteArray(300) { it.toByte() }
        val framed = Framing.chunk(message, 4096).single()
        val reassembler = Framing.Reassembler()

        // One byte at a time — the worst case for a length-prefixed reader.
        val out = framed.flatMap { reassembler.accept(byteArrayOf(it)) }
        assertArrayEquals(message, out.single())
    }

    @Test
    fun `an empty message is representable`() {
        val out = Framing.Reassembler().accept(Framing.chunk(ByteArray(0)).single())
        assertEquals(0, out.single().size)
    }

    @Test
    fun `a bad magic byte is rejected rather than resynchronised`() {
        val error =
            assertThrows<EnvelopeRejected> {
                Framing.Reassembler().accept(byteArrayOf(0x00, 0x01, 0, 0, 0, 1, 0x41))
            }
        assertEquals(EnvelopeRejection.MALFORMED, error.rejection)
    }

    @Test
    fun `a frame declaring an absurd length is refused before allocating`() {
        // The whole point of validating the length separately: a hostile peer must not be able to
        // make the appliance allocate a gigabyte in the middle of a set.
        val hostile = byteArrayOf(Framing.MAGIC, 1, 0x7F, 0x7F, 0x7F, 0x7F)
        val error = assertThrows<EnvelopeRejected> { Framing.Reassembler().accept(hostile) }
        assertEquals(EnvelopeRejection.TOO_LARGE, error.rejection)
    }

    @Test
    fun `a frame from an incompatible protocol version is refused`() {
        val error =
            assertThrows<EnvelopeRejected> {
                Framing.Reassembler().accept(byteArrayOf(Framing.MAGIC, 99, 0, 0, 0, 0))
            }
        assertEquals(EnvelopeRejection.INCOMPATIBLE_VERSION, error.rejection)
    }

    @Test
    fun `the reassembler resets after a violation`() {
        val reassembler = Framing.Reassembler()
        assertThrows<EnvelopeRejected> { reassembler.accept(byteArrayOf(0x00, 0x01, 0, 0, 0, 1, 0x41)) }
        assertEquals(0, reassembler.pendingBytes)

        val message = "recovered".toByteArray()
        assertArrayEquals(message, Framing.chunk(message).flatMap { reassembler.accept(it) }.single())
    }

    @Test
    fun `chunking refuses a message beyond the cap`() {
        assertThrows<IllegalArgumentException> { Framing.chunk(ByteArray(100), maxMessageBytes = 50) }
        assertThrows<IllegalArgumentException> { Framing.chunk(ByteArray(10), chunkBytes = 4) }
    }
}

class EventReplayTest {
    private fun log(events: Int): InMemoryWorkoutEventLog =
        InMemoryWorkoutEventLog().apply {
            repeat(events) { i ->
                append(
                    WorkoutEvent(
                        sessionId = "s",
                        sequenceNumber = i.toLong(),
                        monotonicTimestampMs = i * 1000L,
                        payload = EventPayload.RepCompleted(i + 1, 1500, 700, 800, 980, 950),
                    ),
                )
            }
        }

    @Test
    fun `a reconnecting controller receives everything it missed`() {
        // Session 9 acceptance: disconnect for several reps, reconnect, replay every missing event.
        val server = EventReplayServer(log(12))

        // The controller had persisted events 0..3 before the radio dropped, and says so on
        // reconnect. Both sides resume from there rather than replaying the whole set.
        server.acknowledge("s", 3)
        val client = EventReplayClient(resumeAfter = 3)

        var batch = server.pending("s")
        client.acceptAll(batch.events)
        while (batch.hasMore) {
            batch = server.replay("s", client.acknowledgement())
            client.acceptAll(batch.events)
        }

        assertEquals(8, client.events.size)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), client.events.map { it.sequenceNumber })
        assertEquals(11L, client.acknowledgement())
    }

    @Test
    fun `duplicate delivery does not create duplicate logical events`() {
        val server = EventReplayServer(log(5))
        val client = EventReplayClient()

        val batch = server.pending("s")
        client.acceptAll(batch.events)
        client.acceptAll(batch.events) // the same batch again, as after a spurious reconnect

        assertEquals(5, client.events.size)
        assertEquals(5, client.duplicatesRejected)
    }

    @Test
    fun `a large backlog is delivered in batches rather than one burst`() {
        val server = EventReplayServer(log(200), maxEventsPerBatch = 32)
        val first = server.pending("s")
        assertEquals(32, first.events.size)
        assertTrue(first.hasMore)
        assertEquals(199L, first.highestAvailable)
    }

    @Test
    fun `the last batch reports no more`() {
        val server = EventReplayServer(log(10), maxEventsPerBatch = 32)
        assertFalse(server.pending("s").hasMore)
    }

    @Test
    fun `acknowledgement is monotonic and bounded by what exists`() {
        val log = log(10)
        val server = EventReplayServer(log)

        assertTrue(server.acknowledge("s", 5))
        assertFalse(server.acknowledge("s", 3), "a stale acknowledgement must be ignored")
        assertEquals(5L, server.acknowledgedThrough("s"))

        // A controller claiming to have persisted events that do not exist is confused or
        // hostile; honouring it would discard the real events when they arrive.
        assertFalse(server.acknowledge("s", 999))
        assertEquals(5L, server.acknowledgedThrough("s"))
    }

    @Test
    fun `the client acknowledges the highest contiguous sequence not the highest seen`() {
        // If 5 and 7 arrive but 6 does not, acknowledging 7 would let the Galaxy forget 6 forever.
        val client = EventReplayClient()
        listOf(0L, 1L, 2L, 4L, 5L).forEach { seq ->
            client.accept(WorkoutEvent("s", seq, seq * 100, EventPayload.SetStarted))
        }
        assertEquals(2L, client.acknowledgement())
        assertEquals(listOf(3L), client.gaps())

        client.accept(WorkoutEvent("s", 3, 300, EventPayload.SetStarted))
        assertEquals(5L, client.acknowledgement(), "filling the gap must advance the acknowledgement")
        assertTrue(client.gaps().isEmpty())
    }

    @Test
    fun `out-of-order arrival is absorbed`() {
        val client = EventReplayClient()
        listOf(3L, 1L, 0L, 2L).forEach { seq ->
            client.accept(WorkoutEvent("s", seq, seq * 100, EventPayload.SetStarted))
        }
        assertEquals(listOf(0L, 1L, 2L, 3L), client.events.map { it.sequenceNumber })
        assertEquals(3L, client.acknowledgement())
    }

    @Test
    fun `an unknown session replays nothing rather than failing`() {
        val server = EventReplayServer(log(3))
        val batch = server.replay("no-such-session", -1)
        assertTrue(batch.isEmpty)
        assertFalse(batch.hasMore)
    }

    @Test
    fun `the set continues and is fully recoverable while no controller is attached`() {
        // Plan §9 acceptance: the Galaxy completes the set with no BLE client present, and the
        // whole history is available when one returns.
        val log = log(0)
        repeat(10) { i ->
            log.append(
                WorkoutEvent("s", i.toLong(), i * 1000L, EventPayload.RepCompleted(i + 1, 1500, 700, 800, 990, 950)),
            )
        }
        log.append(WorkoutEvent("s", 10, 11_000, EventPayload.SetCompleted(10)))

        val client = EventReplayClient()
        val server = EventReplayServer(log)
        var batch = server.pending("s")
        client.acceptAll(batch.events)
        while (batch.hasMore) {
            batch = server.replay("s", client.acknowledgement())
            client.acceptAll(batch.events)
        }

        assertEquals(11, client.events.size)
        assertEquals(10, client.events.count { it.payload is EventPayload.RepCompleted })
        assertTrue(client.events.last().payload is EventPayload.SetCompleted)
    }
}
