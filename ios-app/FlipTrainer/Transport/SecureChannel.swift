import Foundation

enum Direction: UInt8 {
    case peripheralToCentral = 0
    case centralToPeripheral = 1

    var opposite: Direction { self == .peripheralToCentral ? .centralToPeripheral : .peripheralToCentral }
}

enum EnvelopeError: LocalizedError, Equatable {
    case incompatibleVersion(Int)
    case badMac
    case replayedCounter(UInt64, UInt64)
    case wrongDirection
    case tooLarge(Int)
    case malformed(String)

    var errorDescription: String? {
        switch self {
        case let .incompatibleVersion(v): return "Message uses protocol \(v); this app speaks \(ProtocolConstants.version)."
        case .badMac: return "A message failed authentication and was discarded."
        case let .replayedCounter(got, highest): return "Replayed message (counter \(got), highest seen \(highest))."
        case .wrongDirection: return "A message arrived addressed the wrong way."
        case let .tooLarge(size): return "A message of \(size) bytes exceeded the limit."
        case let .malformed(detail): return "Malformed message: \(detail)."
        }
    }
}

struct SealedEnvelope: Equatable {
    let protocolVersion: Int
    let direction: Direction
    let counter: UInt64
    let payload: Data
    let mac: Data

    /// Field order matches `AuthenticatedEnvelope` in flip_trainer_v1.proto and the Kotlin
    /// `PeripheralServer.encode`.
    func encoded() -> Data {
        var out = Data()
        out.append(UInt8(protocolVersion & 0xFF))
        out.append(direction.rawValue)
        for shift in stride(from: 56, through: 0, by: -8) {
            out.append(UInt8((counter >> UInt64(shift)) & 0xFF))
        }
        out.append(UInt8(mac.count))
        out.append(mac)
        out.append(payload)
        return out
    }

    static func decode(_ bytes: Data) throws -> SealedEnvelope {
        guard bytes.count > 11 else { throw EnvelopeError.malformed("envelope too short") }
        var index = bytes.startIndex

        let version = Int(bytes[index]); index += 1
        guard let direction = Direction(rawValue: bytes[index]) else {
            throw EnvelopeError.malformed("bad direction byte")
        }
        index += 1

        var counter: UInt64 = 0
        for _ in 0 ..< 8 {
            counter = (counter << 8) | UInt64(bytes[index])
            index += 1
        }

        let macLength = Int(bytes[index]); index += 1
        guard index + macLength <= bytes.endIndex else { throw EnvelopeError.malformed("truncated envelope") }
        let mac = bytes[index ..< index + macLength]
        index += macLength

        return SealedEnvelope(
            protocolVersion: version,
            direction: direction,
            counter: counter,
            payload: Data(bytes[index...]),
            mac: Data(mac)
        )
    }
}

/// Mirrors `com.fliptrainer.protocol.SecureChannel`.
///
/// Authenticates; does not encrypt (ADR-0007). The counter is verified only **after** the MAC, so
/// a forged envelope cannot advance the replay window and lock out the real peer.
final class SecureChannel {
    static let defaultMaxPayloadBytes = 64 * 1024

    private let pairedKey: Data
    private let outboundDirection: Direction
    private let maxPayloadBytes: Int

    private var outboundCounter: UInt64 = 0
    private var highestInboundCounter: Int64 = -1

    init(pairedKey: Data, outboundDirection: Direction, maxPayloadBytes: Int = defaultMaxPayloadBytes) {
        precondition(pairedKey.count == PairingCrypto.keyLength, "paired key must be 32 bytes")
        self.pairedKey = pairedKey
        self.outboundDirection = outboundDirection
        self.maxPayloadBytes = maxPayloadBytes
    }

    func seal(_ payload: Data) throws -> SealedEnvelope {
        guard payload.count <= maxPayloadBytes else { throw EnvelopeError.tooLarge(payload.count) }
        let counter = outboundCounter
        outboundCounter += 1
        return SealedEnvelope(
            protocolVersion: ProtocolConstants.version,
            direction: outboundDirection,
            counter: counter,
            payload: payload,
            mac: PairingCrypto.envelopeMac(
                key: pairedKey,
                protocolVersion: ProtocolConstants.version,
                direction: outboundDirection.rawValue,
                counter: counter,
                payload: payload
            )
        )
    }

    func open(_ envelope: SealedEnvelope) throws -> Data {
        guard ProtocolConstants.isCompatible(envelope.protocolVersion) else {
            throw EnvelopeError.incompatibleVersion(envelope.protocolVersion)
        }
        guard envelope.payload.count <= maxPayloadBytes else {
            throw EnvelopeError.tooLarge(envelope.payload.count)
        }
        guard envelope.direction == outboundDirection.opposite else { throw EnvelopeError.wrongDirection }
        guard envelope.mac.count == PairingCrypto.macLength else {
            throw EnvelopeError.malformed("mac must be \(PairingCrypto.macLength) bytes")
        }

        let expected = PairingCrypto.envelopeMac(
            key: pairedKey,
            protocolVersion: envelope.protocolVersion,
            direction: envelope.direction.rawValue,
            counter: envelope.counter,
            payload: envelope.payload
        )
        guard PairingCrypto.constantTimeEquals(expected, envelope.mac) else { throw EnvelopeError.badMac }

        guard Int64(envelope.counter) > highestInboundCounter else {
            throw EnvelopeError.replayedCounter(envelope.counter, UInt64(max(highestInboundCounter, 0)))
        }
        highestInboundCounter = Int64(envelope.counter)
        return envelope.payload
    }

    /// A BLE reconnect starts both sides from zero. Safe because the counter is inside the MAC.
    func resetCounters() {
        outboundCounter = 0
        highestInboundCounter = -1
    }
}

/// Mirrors `com.fliptrainer.protocol.Framing`.
enum Framing {
    static let magic: UInt8 = 0x46
    static let headerBytes = 6
    static let defaultChunkBytes = 180
    static let defaultMaxMessageBytes = 64 * 1024

    static func chunk(_ message: Data, chunkBytes: Int = defaultChunkBytes) -> [Data] {
        let length = UInt32(message.count)
        var framed = Data([
            magic,
            UInt8(ProtocolConstants.version),
            UInt8((length >> 24) & 0xFF),
            UInt8((length >> 16) & 0xFF),
            UInt8((length >> 8) & 0xFF),
            UInt8(length & 0xFF),
        ])
        framed.append(message)

        var chunks: [Data] = []
        var offset = 0
        while offset < framed.count {
            let end = min(offset + chunkBytes, framed.count)
            chunks.append(framed.subdata(in: offset ..< end))
            offset = end
        }
        return chunks
    }

    /// Stateful, single-peer. Validates a declared length before allocating anything.
    final class Reassembler {
        private var buffer = Data()
        private let maxMessageBytes: Int

        init(maxMessageBytes: Int = defaultMaxMessageBytes) {
            self.maxMessageBytes = maxMessageBytes
        }

        var pendingBytes: Int { buffer.count }

        func reset() { buffer.removeAll(keepingCapacity: true) }

        func accept(_ chunk: Data) throws -> [Data] {
            buffer.append(chunk)
            var messages: [Data] = []

            while true {
                guard buffer.count >= Framing.headerBytes else { break }

                guard buffer[buffer.startIndex] == Framing.magic else {
                    reset()
                    throw EnvelopeError.malformed("framing lost: bad magic byte")
                }
                let version = Int(buffer[buffer.startIndex + 1])
                guard ProtocolConstants.isCompatible(version) else {
                    reset()
                    throw EnvelopeError.incompatibleVersion(version)
                }

                let length = (Int(buffer[buffer.startIndex + 2]) << 24)
                    | (Int(buffer[buffer.startIndex + 3]) << 16)
                    | (Int(buffer[buffer.startIndex + 4]) << 8)
                    | Int(buffer[buffer.startIndex + 5])

                guard length >= 0, length <= maxMessageBytes else {
                    reset()
                    throw EnvelopeError.tooLarge(length)
                }

                let total = Framing.headerBytes + length
                guard buffer.count >= total else { break }

                let start = buffer.startIndex + Framing.headerBytes
                messages.append(Data(buffer[start ..< buffer.startIndex + total]))
                buffer = Data(buffer[(buffer.startIndex + total)...])
            }

            return messages
        }
    }
}
