import Foundation

/// Mirrors `com.fliptrainer.protocol.QrPairingPayload`.
///
/// Strict on purpose: a malformed, foreign or expired code must be rejected **before** BLE
/// discovery starts (plan §10 task 4), so the user gets "this isn't a Flip Trainer code" rather
/// than a scan that quietly finds nothing.
struct QrPairingPayload: Equatable {
    static let prefix = "FT1"
    static let separator = ":"
    static let safeId = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{1,64}$")

    let protocolVersion: Int
    let deviceId: String
    let pairingId: String
    let pairingSecret: Data
    let expiresAtEpochMs: Int64

    func isExpired(at nowEpochMs: Int64) -> Bool { nowEpochMs >= expiresAtEpochMs }

    var advertisingDiscriminator: Data { PairingCrypto.discriminator(pairingId: pairingId) }

    /// Never prints the secret.
    var debugDescription: String {
        "QrPairingPayload(v=\(protocolVersion), device=\(deviceId), pairing=\(pairingId), "
            + "expiresAt=\(expiresAtEpochMs), secret=<redacted>)"
    }

    static func decode(_ text: String) throws -> QrPairingPayload {
        let parts = text.trimmingCharacters(in: .whitespacesAndNewlines).components(separatedBy: separator)
        guard parts.count == 6 else {
            throw PairingError.malformedCode("expected 6 fields, found \(parts.count)")
        }
        guard parts[0] == prefix else {
            throw PairingError.malformedCode("not a Flip Trainer pairing code")
        }
        guard let version = Int(parts[1]) else {
            throw PairingError.malformedCode("bad protocol version '\(parts[1])'")
        }
        guard let expiresAt = Int64(parts[5]) else {
            throw PairingError.malformedCode("bad expiry '\(parts[5])'")
        }
        guard isSafeId(parts[2]), isSafeId(parts[3]) else {
            throw PairingError.malformedCode("device or pairing id contains unexpected characters")
        }
        guard let secret = Data(base64UrlEncoded: parts[4]), secret.count == PairingCrypto.secretLength else {
            throw PairingError.malformedCode("secret is not a \(PairingCrypto.secretLength)-byte base64url value")
        }
        return QrPairingPayload(
            protocolVersion: version,
            deviceId: parts[2],
            pairingId: parts[3],
            pairingSecret: secret,
            expiresAtEpochMs: expiresAt
        )
    }

    private static func isSafeId(_ value: String) -> Bool {
        let range = NSRange(value.startIndex ..< value.endIndex, in: value)
        return safeId.firstMatch(in: value, range: range) != nil
    }
}

enum PairingError: LocalizedError, Equatable {
    case malformedCode(String)
    case expired
    case incompatibleVersion(Int)
    case wrongDevice
    case badProof
    case notPaired

    var errorDescription: String? {
        switch self {
        case let .malformedCode(detail):
            return "That doesn't look like a Flip Trainer pairing code. (\(detail))"
        case .expired:
            return "This pairing code has expired. Refresh it on the Galaxy and scan again."
        case let .incompatibleVersion(version):
            return "That Galaxy is running protocol version \(version); this app speaks "
                + "\(ProtocolConstants.version). Update both apps to the same version."
        case .wrongDevice:
            return "That's a different Galaxy from the one whose code you scanned."
        case .badProof:
            return "The Galaxy couldn't prove it generated that code. Pairing was cancelled."
        case .notPaired:
            return "No Galaxy is paired yet."
        }
    }
}

enum ProtocolConstants {
    static let version = 1

    /// Strict equality. Best-effort parsing of an unknown version is how silent corruption starts.
    static func isCompatible(_ other: Int) -> Bool { other == version }
}

extension Data {
    init?(base64UrlEncoded: String) {
        var text = base64UrlEncoded.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while text.count % 4 != 0 { text.append("=") }
        guard let data = Data(base64Encoded: text) else { return nil }
        self = data
    }
}

/// The controller's half of the ceremony. Mirrors `CentralPairingSession`.
final class CentralPairingSession {
    private let scanned: QrPairingPayload
    private var clientNonce: Data?

    init(scanned: QrPairingPayload) {
        self.scanned = scanned
    }

    var expectedDiscriminator: Data { scanned.advertisingDiscriminator }

    /// Reject before touching the radio.
    func validate(now: Int64) throws {
        guard ProtocolConstants.isCompatible(scanned.protocolVersion) else {
            throw PairingError.incompatibleVersion(scanned.protocolVersion)
        }
        guard !scanned.isExpired(at: now) else { throw PairingError.expired }
    }

    func respond(to challenge: PairingChallenge) throws -> PairingProofMessage {
        guard challenge.pairingId == scanned.pairingId, challenge.deviceId == scanned.deviceId else {
            throw PairingError.wrongDevice
        }
        guard ProtocolConstants.isCompatible(challenge.protocolVersion) else {
            throw PairingError.incompatibleVersion(challenge.protocolVersion)
        }

        let nonce = PairingCrypto.randomBytes(PairingCrypto.nonceLength)
        clientNonce = nonce
        return PairingProofMessage(
            protocolVersion: ProtocolConstants.version,
            clientNonce: nonce,
            clientProof: PairingCrypto.clientProof(
                secret: scanned.pairingSecret,
                deviceId: scanned.deviceId,
                pairingId: scanned.pairingId,
                clientNonce: nonce,
                serverNonce: challenge.serverNonce
            )
        )
    }

    /// Mutual authentication. Skipping this would pair with anything that answered.
    func confirm(challenge: PairingChallenge, serverProof: Data) throws -> PairedDevice {
        guard let nonce = clientNonce else { throw PairingError.badProof }

        let expected = PairingCrypto.serverProof(
            secret: scanned.pairingSecret,
            deviceId: scanned.deviceId,
            pairingId: scanned.pairingId,
            clientNonce: nonce,
            serverNonce: challenge.serverNonce
        )
        guard PairingCrypto.constantTimeEquals(expected, serverProof) else { throw PairingError.badProof }

        return PairedDevice(
            deviceId: scanned.deviceId,
            pairingId: scanned.pairingId,
            pairedKey: PairingCrypto.derivePairedKey(
                secret: scanned.pairingSecret,
                deviceId: scanned.deviceId,
                pairingId: scanned.pairingId,
                clientNonce: nonce,
                serverNonce: challenge.serverNonce
            )
        )
    }
}

struct PairingChallenge: Equatable {
    let protocolVersion: Int
    let deviceId: String
    let pairingId: String
    let serverNonce: Data
}

struct PairingProofMessage: Equatable {
    let protocolVersion: Int
    let clientNonce: Data
    let clientProof: Data
}

struct PairedDevice: Equatable {
    let deviceId: String
    let pairingId: String
    let pairedKey: Data
}
