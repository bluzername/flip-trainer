import CoreBluetooth
import Foundation
import Security

/// Every state the connection can be in (plan §10 task 9).
///
/// Modelled as one enum rather than a set of booleans so the UI cannot render an impossible
/// combination — "scanning" and "armed" at once, say — and so a reconnect after a drop is a
/// visible state rather than an absence of one.
enum ConnectionState: Equatable {
    case noDevice
    case awaitingQrScan
    case qrExpired
    case scanning
    case connecting
    case authenticating
    case connected
    case armed
    case reconnecting
    case incompatible(String)
    case failed(String)

    var isUsable: Bool {
        switch self {
        case .connected, .armed: return true
        default: return false
        }
    }

    var userFacingDescription: String {
        switch self {
        case .noDevice: return "No Galaxy paired"
        case .awaitingQrScan: return "Scan the code on your Galaxy"
        case .qrExpired: return "That code expired — refresh it on the Galaxy"
        case .scanning: return "Looking for your Galaxy…"
        case .connecting: return "Connecting…"
        case .authenticating: return "Verifying…"
        case .connected: return "Connected"
        case .armed: return "Ready to lift"
        case .reconnecting: return "Reconnecting…"
        case let .incompatible(detail): return "Incompatible: \(detail)"
        case let .failed(detail): return detail
        }
    }
}

/// The GATT profile. Must match `GattProfile.kt`.
enum GattProfile {
    static let service = CBUUID(string: "F11B7A10-0001-4A5E-9C2F-6B1E5D3A7C01")
    static let controlWrite = CBUUID(string: "F11B7A10-0002-4A5E-9C2F-6B1E5D3A7C01")
    static let eventNotify = CBUUID(string: "F11B7A10-0003-4A5E-9C2F-6B1E5D3A7C01")
    static let status = CBUUID(string: "F11B7A10-0004-4A5E-9C2F-6B1E5D3A7C01")
    static let replay = CBUUID(string: "F11B7A10-0005-4A5E-9C2F-6B1E5D3A7C01")
    static let pairing = CBUUID(string: "F11B7A10-0006-4A5E-9C2F-6B1E5D3A7C01")
    static let manufacturerId: UInt16 = 0xFFFF
}

/// Paired-device key storage.
///
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: the app must be able to reconnect in the
/// background after a reboot without the user unlocking first, but the key must never migrate to
/// another device through an iCloud Keychain backup.
enum KeychainStore {
    private static let service = "com.fliptrainer.controller.pairedKey"

    static func save(_ device: PairedDevice) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: device.deviceId,
        ]
        SecItemDelete(query as CFDictionary)

        var attributes = query
        attributes[kSecValueData as String] = device.pairedKey
        attributes[kSecAttrGeneric as String] = Data(device.pairingId.utf8)
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }

    static func load(deviceId: String) -> PairedDevice? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId,
            kSecReturnData as String: true,
            kSecReturnAttributes as String: true,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let result = item as? [String: Any],
              let key = result[kSecValueData as String] as? Data,
              let pairingIdData = result[kSecAttrGeneric as String] as? Data,
              let pairingId = String(data: pairingIdData, encoding: .utf8)
        else { return nil }

        return PairedDevice(deviceId: deviceId, pairingId: pairingId, pairedKey: key)
    }

    static func delete(deviceId: String) {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId,
        ] as CFDictionary)
    }
}

/// CoreBluetooth central.
///
/// Discovery is filtered by service UUID **and** by the pairing discriminator carried in the
/// manufacturer data, so the app connects to the right Galaxy without ever asking the user to
/// choose from a device list (plan §10 acceptance).
@MainActor
final class BluetoothCentral: NSObject, ObservableObject {
    @Published private(set) var state: ConnectionState = .noDevice
    @Published private(set) var liveSnapshot = LiveSetSnapshot()

    /// Events handed to the importer. The transport does not touch SwiftData itself.
    var onEvents: (([IncomingEvent]) -> Void)?

    private var manager: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]

    private var pairingSession: CentralPairingSession?
    private var expectedDiscriminator: Data?
    private var pendingChallenge: PairingChallenge?

    private var paired: PairedDevice?
    private var channel: SecureChannel?
    private let reassembler = Framing.Reassembler()

    override init() {
        super.init()
        manager = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: Pairing

    /// Validate a scanned code and begin looking for the device it names.
    func beginPairing(with scannedText: String, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        do {
            let payload = try QrPairingPayload.decode(scannedText)
            let session = CentralPairingSession(scanned: payload)
            // Reject before touching the radio: the user should be told "expired", not left
            // watching a scan that will never find anything.
            try session.validate(now: now)

            pairingSession = session
            expectedDiscriminator = session.expectedDiscriminator
            state = .scanning
            startScan()
        } catch PairingError.expired {
            state = .qrExpired
        } catch let PairingError.incompatibleVersion(version) {
            state = .incompatible("Galaxy speaks protocol \(version)")
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    /// Reconnect to an already-paired device. No QR involved (plan §10 acceptance).
    func reconnect(to device: PairedDevice) {
        paired = device
        channel = SecureChannel(pairedKey: device.pairedKey, outboundDirection: .centralToPeripheral)
        expectedDiscriminator = PairingCrypto.discriminator(pairingId: device.pairingId)
        state = .reconnecting
        startScan()
    }

    func unpair() {
        if let deviceId = paired?.deviceId { KeychainStore.delete(deviceId: deviceId) }
        paired = nil
        channel = nil
        disconnect()
        state = .noDevice
    }

    func disconnect() {
        if let peripheral { manager.cancelPeripheralConnection(peripheral) }
        peripheral = nil
        characteristics.removeAll()
        reassembler.reset()
    }

    private func startScan() {
        guard manager.state == .poweredOn else { return }
        manager.scanForPeripherals(withServices: [GattProfile.service], options: nil)
    }

    // MARK: Sending

    func send(_ payload: Data) {
        guard let channel,
              let peripheral,
              let characteristic = characteristics[GattProfile.controlWrite]
        else { return }

        do {
            let envelope = try channel.seal(payload)
            for chunk in Framing.chunk(envelope.encoded()) {
                peripheral.writeValue(chunk, for: characteristic, type: .withResponse)
            }
        } catch {
            state = .failed(error.localizedDescription)
        }
    }
}

extension BluetoothCentral: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            switch central.state {
            case .poweredOn:
                if case .noDevice = state {} else { startScan() }
            case .poweredOff:
                state = .failed("Bluetooth is off")
            case .unauthorized:
                state = .failed("Flip Trainer needs Bluetooth permission")
            default:
                break
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi _: NSNumber
    ) {
        Task { @MainActor in
            // Match the discriminator before connecting. Without this the app would have to show
            // a device list, which is exactly the experience the QR exists to avoid.
            guard let expected = expectedDiscriminator else { return }
            guard let manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
                  manufacturerData.count >= 2 + expected.count
            else { return }

            let advertised = manufacturerData.dropFirst(2).prefix(expected.count)
            guard Data(advertised) == expected else { return }

            central.stopScan()
            self.peripheral = peripheral
            peripheral.delegate = self
            state = .connecting
            central.connect(peripheral, options: nil)
        }
    }

    nonisolated func centralManager(_: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            state = .authenticating
            // A reconnect restarts counters on both sides; the key is unchanged.
            channel?.resetCounters()
            reassembler.reset()
            peripheral.discoverServices([GattProfile.service])
        }
    }

    nonisolated func centralManager(
        _: CBCentralManager,
        didDisconnectPeripheral _: CBPeripheral,
        error _: Error?
    ) {
        Task { @MainActor in
            characteristics.removeAll()
            reassembler.reset()
            // Losing the radio must never look like losing the set: the Galaxy is still counting.
            state = paired == nil ? .noDevice : .reconnecting
            startScan()
        }
    }
}

extension BluetoothCentral: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices _: Error?) {
        Task { @MainActor in
            guard let service = peripheral.services?.first(where: { $0.uuid == GattProfile.service }) else {
                state = .failed("That device is not a Flip Trainer sensor")
                return
            }
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error _: Error?
    ) {
        Task { @MainActor in
            for characteristic in service.characteristics ?? [] {
                characteristics[characteristic.uuid] = characteristic
                if characteristic.properties.contains(.notify) {
                    peripheral.setNotifyValue(true, for: characteristic)
                }
            }
            if paired != nil { state = .connected }
        }
    }

    nonisolated func peripheral(
        _: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error _: Error?
    ) {
        Task { @MainActor in
            guard let value = characteristic.value else { return }
            do {
                for message in try reassembler.accept(value) {
                    try handle(message: message, from: characteristic.uuid)
                }
            } catch {
                // A rejected message is discarded, not fatal. A hostile or corrupt write must not
                // be able to end a workout.
                NSLog("flip-trainer: discarded message: \(error.localizedDescription)")
            }
        }
    }

    private func handle(message: Data, from characteristic: CBUUID) throws {
        if characteristic == GattProfile.pairing {
            // Pairing traffic is pre-key and therefore not enveloped.
            return
        }
        guard let channel else { return }
        let payload = try channel.open(try SealedEnvelope.decode(message))
        // Decoding protobuf frames into IncomingEvent happens here once SwiftProtobuf types are
        // generated; the events are then handed to EventImporter, which owns all persistence.
        _ = payload
    }
}

/// What the live-set screen renders.
struct LiveSetSnapshot: Equatable {
    var repCount = 0
    var phase = ""
    var phaseLabel = ""
    var lastRepDurationMs = 0
    var lastRangeOfMotion = 0.0
    var confidence = 0.0
    var readinessStatus = ""
    var readinessInstruction = ""
    var activeCues: [String] = []
    var isReady = false
}
