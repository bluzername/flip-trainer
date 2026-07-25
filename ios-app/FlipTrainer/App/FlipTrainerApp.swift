import AVFoundation
import SwiftData
import SwiftUI

@main
struct FlipTrainerApp: App {
    /// Local store only. No cloud container, no account, no network (plan §2.2).
    private let container: ModelContainer = {
        let schema = Schema([StoredWorkout.self, StoredSet.self, StoredRep.self, StoredPairedDevice.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false, cloudKitDatabase: .none)
        do {
            return try ModelContainer(for: schema, configurations: [configuration])
        } catch {
            fatalError("could not open the local workout store: \(error)")
        }
    }()

    @StateObject private var central = BluetoothCentral()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(central)
        }
        .modelContainer(container)
    }
}

struct RootView: View {
    @EnvironmentObject private var central: BluetoothCentral
    @Query(sort: \StoredPairedDevice.pairedAt, order: .reverse) private var pairedDevices: [StoredPairedDevice]

    var body: some View {
        NavigationStack {
            Group {
                if pairedDevices.isEmpty {
                    DeviceSetupView()
                } else {
                    ExerciseSelectionView()
                }
            }
            .navigationTitle("Flip Trainer")
            .safeAreaInset(edge: .bottom) { ConnectionBanner(state: central.state) }
        }
        .task {
            guard let device = pairedDevices.first,
                  let stored = KeychainStore.load(deviceId: device.deviceId)
            else { return }
            // No QR on a reconnect — that is the whole point of persisting the key.
            central.reconnect(to: stored)
        }
    }
}

struct ConnectionBanner: View {
    let state: ConnectionState

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(tint).frame(width: 8, height: 8)
            Text(state.userFacingDescription).font(.footnote)
            Spacer()
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.thinMaterial)
    }

    private var tint: Color {
        switch state {
        case .connected, .armed: return .green
        case .scanning, .connecting, .authenticating, .reconnecting: return .orange
        case .noDevice, .awaitingQrScan: return .secondary
        case .qrExpired, .incompatible, .failed: return .red
        }
    }
}

// MARK: - Device setup

struct DeviceSetupView: View {
    @EnvironmentObject private var central: BluetoothCentral
    @State private var scanning = false

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "qrcode.viewfinder").font(.system(size: 56)).foregroundStyle(.secondary)
            Text("Pair your Galaxy").font(.title2.bold())
            Text(
                "Open Flip Trainer on the Galaxy. It shows a pairing code automatically when no "
                    + "phone is paired. The code lasts five minutes and works once."
            )
            .font(.callout)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)

            Button("Scan the code") { scanning = true }
                .buttonStyle(.borderedProminent)

            if case let .failed(detail) = central.state {
                Text(detail).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center)
            }
            if case .qrExpired = central.state {
                Text("That code expired. Refresh it on the Galaxy and scan again.")
                    .font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center)
            }
        }
        .padding()
        .sheet(isPresented: $scanning) {
            QrScannerView { text in
                scanning = false
                central.beginPairing(with: text)
            }
        }
    }
}

/// AVFoundation QR scanner. The camera is used for this and nothing else — no video is recorded
/// and nothing leaves the phone, which is what `NSCameraUsageDescription` promises.
struct QrScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = UIViewController()
        let session = AVCaptureSession()

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else { return controller }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return controller }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(context.coordinator, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = controller.view.bounds
        preview.videoGravity = .resizeAspectFill
        controller.view.layer.addSublayer(preview)

        context.coordinator.session = session
        DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
        return controller
    }

    func updateUIViewController(_: UIViewController, context _: Context) {}

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        var session: AVCaptureSession?
        private let onScan: (String) -> Void
        private var handled = false

        init(onScan: @escaping (String) -> Void) { self.onScan = onScan }

        func metadataOutput(
            _: AVCaptureMetadataOutput,
            didOutput objects: [AVMetadataObject],
            from _: AVCaptureConnection
        ) {
            guard !handled,
                  let object = objects.first as? AVMetadataMachineReadableCodeObject,
                  let text = object.stringValue,
                  // Filter here so scanning an unrelated QR does not dismiss the sheet with an error.
                  text.hasPrefix(QrPairingPayload.prefix + QrPairingPayload.separator)
            else { return }

            handled = true
            session?.stopRunning()
            onScan(text)
        }
    }
}

// MARK: - Exercise selection and set configuration

struct ExerciseSelectionView: View {
    /// Loaded from the bundled copy of `exercise-definitions/`. The Galaxy is the authority on
    /// what it can actually evaluate and reports it in DeviceCapability; this list is the menu.
    private let exercises = ExerciseCatalog.bundled

    var body: some View {
        List {
            Section("Exercises") {
                ForEach(exercises, id: \.id) { exercise in
                    NavigationLink(value: exercise.id) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(exercise.name)
                            Text("\(exercise.preferredView) · v\(exercise.version)")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Section {
                NavigationLink("Workout history") { HistoryView() }
                NavigationLink("Settings") { SettingsView() }
            }
        }
        .navigationDestination(for: String.self) { SetConfigurationView(exerciseId: $0) }
    }
}

struct SetConfigurationView: View {
    let exerciseId: String
    @EnvironmentObject private var central: BluetoothCentral
    @State private var weight = 10.0
    @State private var targetReps = 10
    @State private var unit = "KG"

    var body: some View {
        Form {
            Section("Weight") {
                // Typed in by hand, always. Visual weight recognition is an explicit non-goal.
                HStack {
                    TextField("Weight", value: $weight, format: .number)
                        .keyboardType(.decimalPad)
                    Picker("", selection: $unit) {
                        Text("kg").tag("KG")
                        Text("lb").tag("LB")
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 120)
                }
            }
            Section("Target") {
                Stepper("\(targetReps) reps", value: $targetReps, in: 1 ... 50)
            }
            Section {
                NavigationLink("Place the Galaxy") {
                    PlacementView(exerciseId: exerciseId, weight: weight, unit: unit, targetReps: targetReps)
                }
                .disabled(!central.state.isUsable)
            } footer: {
                if !central.state.isUsable {
                    Text("Waiting for the Galaxy: \(central.state.userFacingDescription)")
                }
            }
        }
        .navigationTitle(ExerciseCatalog.bundled.first { $0.id == exerciseId }?.name ?? exerciseId)
    }
}

struct PlacementView: View {
    let exerciseId: String
    let weight: Double
    let unit: String
    let targetReps: Int

    @EnvironmentObject private var central: BluetoothCentral

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: central.liveSnapshot.isReady ? "checkmark.circle.fill" : "figure.stand")
                .font(.system(size: 64))
                .foregroundStyle(central.liveSnapshot.isReady ? .green : .secondary)

            // Spoken/displayed placement instruction, straight from the Galaxy's readiness gate.
            Text(central.liveSnapshot.readinessInstruction.isEmpty
                ? "Waiting for the Galaxy…"
                : central.liveSnapshot.readinessInstruction)
                .font(.title3.bold())
                .multilineTextAlignment(.center)

            NavigationLink("Start set") {
                LiveSetView(exerciseId: exerciseId)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!central.liveSnapshot.isReady)
        }
        .padding()
        .navigationTitle("Placement")
    }
}

struct LiveSetView: View {
    let exerciseId: String
    @EnvironmentObject private var central: BluetoothCentral

    var body: some View {
        VStack(spacing: 16) {
            Text("\(central.liveSnapshot.repCount)").font(.system(size: 96, weight: .bold, design: .rounded))
            Text(central.liveSnapshot.phaseLabel.uppercased()).font(.headline).foregroundStyle(.secondary)

            HStack(spacing: 24) {
                Metric(title: "Last rep", value: String(format: "%.1fs", Double(central.liveSnapshot.lastRepDurationMs) / 1000))
                Metric(title: "Range", value: "\(Int(central.liveSnapshot.lastRangeOfMotion * 100))%")
                Metric(title: "Confidence", value: "\(Int(central.liveSnapshot.confidence * 100))%")
            }

            ForEach(central.liveSnapshot.activeCues, id: \.self) { cue in
                Text(cue).font(.callout).padding(8).background(.yellow.opacity(0.25)).clipShape(.rect(cornerRadius: 8))
            }

            Spacer()

            Text("The Galaxy keeps counting if this phone locks or goes out of range.")
                .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .padding()
        .navigationTitle("Set")
    }

    struct Metric: View {
        let title: String
        let value: String
        var body: some View {
            VStack {
                Text(value).font(.title3.bold())
                Text(title).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - History, corrections, settings

struct HistoryView: View {
    @Query(sort: \StoredSet.startedAt, order: .reverse) private var sets: [StoredSet]

    var body: some View {
        List(sets, id: \.sessionId) { set in
            NavigationLink {
                SetDetailView(set: set)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(set.effectiveExerciseId)
                    Text("\(set.effectiveRepCount) reps · \(set.effectiveWeight, format: .number) \(set.weightUnit.lowercased())")
                        .font(.caption).foregroundStyle(.secondary)
                    if set.repCountDisputed {
                        Text("corrected from \(set.measuredRepCount)")
                            .font(.caption2).foregroundStyle(.orange)
                    }
                }
            }
        }
        .navigationTitle("History")
    }
}

struct SetDetailView: View {
    @Bindable var set: StoredSet
    @Environment(\.modelContext) private var context
    @State private var repCount: Int = 0
    @State private var weight: Double = 0

    var body: some View {
        Form {
            Section("Measured") {
                LabeledContent("Reps", value: "\(set.measuredRepCount)")
                LabeledContent("Weight", value: "\(set.measuredWeight) \(set.weightUnit.lowercased())")
                if let rom = set.meanRangeOfMotion {
                    LabeledContent("Mean range", value: "\(Int(rom * 100))%")
                }
                LabeledContent("Definition", value: "v\(set.exerciseDefinitionVersion)")
                LabeledContent("Pose model", value: set.poseModelVersion)
            }

            Section("Correct") {
                Stepper("\(repCount) reps", value: $repCount, in: 0 ... 100)
                HStack {
                    Text("Weight")
                    Spacer()
                    TextField("Weight", value: $weight, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                }
                Button("Save correction") {
                    try? EventImporter(context: context).correct(set: set, repCount: repCount, weight: weight)
                }
                if set.wasCorrected {
                    Button("Undo correction", role: .destructive) {
                        try? EventImporter(context: context).clearCorrections(set: set)
                    }
                }
            } footer: {
                // Stating the guarantee where the user can see it.
                Text("Corrections are stored alongside what the Galaxy measured. The original measurement is never overwritten.")
            }
        }
        .navigationTitle("Set \(set.setNumber)")
        .onAppear {
            repCount = set.effectiveRepCount
            weight = set.effectiveWeight
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var central: BluetoothCentral
    @Environment(\.modelContext) private var context
    @Query private var devices: [StoredPairedDevice]
    @Query private var sets: [StoredSet]

    var body: some View {
        Form {
            Section("Paired Galaxy") {
                ForEach(devices, id: \.deviceId) { device in
                    VStack(alignment: .leading) {
                        Text(device.displayName)
                        Text(device.deviceId).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Button("Unpair", role: .destructive) {
                    central.unpair()
                    for device in devices { context.delete(device) }
                    try? context.save()
                }
            }
            Section("Export") {
                ShareLink(item: WorkoutExport.csv(sets: sets), preview: SharePreview("flip-trainer.csv")) {
                    Text("Export CSV")
                }
            } footer: {
                Text("Exports stay on this phone unless you share them. Flip Trainer has no account and no server.")
            }
        }
        .navigationTitle("Settings")
    }
}

/// Exercise metadata, read from the bundled copy of `exercise-definitions/`.
enum ExerciseCatalog {
    struct Entry {
        let id: String
        let name: String
        let version: String
        let preferredView: String
    }

    /// Populated at build time from the shared definitions. Hard-coded here as a placeholder so
    /// the UI compiles before the resource-copy build phase is wired up.
    static let bundled: [Entry] = [
        Entry(id: "standing_dumbbell_curl", name: "Standing dumbbell curl", version: "1.0.0", preferredView: "Front"),
        Entry(id: "goblet_squat", name: "Goblet squat", version: "1.0.0", preferredView: "Front-diagonal"),
        Entry(id: "dumbbell_rdl", name: "Dumbbell Romanian deadlift", version: "1.0.0", preferredView: "Side"),
        Entry(
            id: "standing_dumbbell_shoulder_press",
            name: "Standing dumbbell shoulder press",
            version: "1.0.0",
            preferredView: "Front"
        ),
    ]
}
