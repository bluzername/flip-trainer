package com.fliptrainer.protocol

/**
 * Message framing over a BLE characteristic.
 *
 * GATT writes and notifications are capped at the negotiated MTU — typically 20 to 244 usable
 * bytes — and arrive as discrete packets with no message boundaries of their own. A rep event with
 * cues can exceed that, and a replay burst certainly does, so messages are length-prefixed and
 * split into chunks that the receiver reassembles.
 *
 * The reassembler is written to be hostile-input-safe: a declared length is validated against the
 * cap *before* any buffer is allocated, so a peer cannot ask the appliance to allocate a gigabyte
 * mid-workout.
 */
object Framing {
    /** Magic + version, so a stray write from an unrelated app is rejected rather than parsed. */
    const val MAGIC: Byte = 0x46 // 'F'

    const val HEADER_BYTES: Int = 6 // magic(1) + version(1) + length(4)

    /** Conservative default; the real value comes from the negotiated MTU minus ATT overhead. */
    const val DEFAULT_CHUNK_BYTES: Int = 180

    const val DEFAULT_MAX_MESSAGE_BYTES: Int = 64 * 1024

    /** Split one message into transport-sized chunks. */
    fun chunk(
        message: ByteArray,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES,
        maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    ): List<ByteArray> {
        require(chunkBytes > HEADER_BYTES) { "chunk size must exceed the ${HEADER_BYTES}-byte header" }
        require(message.size <= maxMessageBytes) {
            "message of ${message.size} bytes exceeds the $maxMessageBytes byte limit"
        }

        val framed = header(message.size) + message
        return framed.asSequence().chunked(chunkBytes) { it.toByteArray() }.toList()
    }

    private fun header(length: Int): ByteArray =
        byteArrayOf(
            MAGIC,
            ProtocolVersion.CURRENT.toByte(),
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        )

    /**
     * Reassembles chunks back into whole messages.
     *
     * Stateful and single-peer. On any protocol violation it throws and resets, because a stream
     * whose framing is out of sync cannot be recovered by guessing — the connection has to be
     * torn down and re-established.
     */
    class Reassembler(
        private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    ) {
        private var buffer = ByteArray(0)

        /** @return whole messages completed by this chunk; usually empty or one. */
        fun accept(chunk: ByteArray): List<ByteArray> {
            buffer += chunk
            val messages = mutableListOf<ByteArray>()

            while (true) {
                if (buffer.size < HEADER_BYTES) break

                if (buffer[0] != MAGIC) {
                    reset()
                    throw EnvelopeRejected(EnvelopeRejection.MALFORMED, "framing lost: bad magic byte")
                }
                val version = buffer[1].toInt() and 0xFF
                if (!ProtocolVersion.isCompatible(version)) {
                    reset()
                    throw EnvelopeRejected(
                        EnvelopeRejection.INCOMPATIBLE_VERSION,
                        "frame declares protocol $version, this build speaks ${ProtocolVersion.CURRENT}",
                    )
                }

                val length =
                    ((buffer[2].toInt() and 0xFF) shl 24) or
                        ((buffer[3].toInt() and 0xFF) shl 16) or
                        ((buffer[4].toInt() and 0xFF) shl 8) or
                        (buffer[5].toInt() and 0xFF)

                // Validate before allocating. This check is the whole reason the length is
                // inspected separately rather than trusted into a buffer allocation.
                if (length < 0 || length > maxMessageBytes) {
                    reset()
                    throw EnvelopeRejected(
                        EnvelopeRejection.TOO_LARGE,
                        "frame declares $length bytes, limit is $maxMessageBytes",
                    )
                }

                val total = HEADER_BYTES + length
                if (buffer.size < total) break // wait for more chunks

                messages += buffer.copyOfRange(HEADER_BYTES, total)
                buffer = buffer.copyOfRange(total, buffer.size)
            }

            return messages
        }

        /** Bytes held pending more chunks. Non-zero mid-message; should be zero when idle. */
        val pendingBytes: Int get() = buffer.size

        fun reset() {
            buffer = ByteArray(0)
        }
    }
}
