import Foundation
import SwiftData

/// Local workout history. **No cloud SDK, no login, no network** (plan §2.2).
///
/// Two invariants drive the shape of these models:
///
/// 1. **Import is idempotent.** `sessionId + sequenceNumber` is unique, so a replayed event after
///    a BLE reconnect cannot create a second repetition.
/// 2. **Corrections never overwrite measurements.** Measured and corrected values live in separate
///    fields. What the device saw is evidence; what the user says is also evidence; overwriting
///    one with the other destroys the ability to tell whether the system was wrong.

@Model
final class StoredWorkout {
    @Attribute(.unique) var id: UUID
    var startedAt: Date
    var endedAt: Date?
    var notes: String?

    @Relationship(deleteRule: .cascade, inverse: \StoredSet.workout)
    var sets: [StoredSet]

    init(id: UUID = UUID(), startedAt: Date = Date(), endedAt: Date? = nil, notes: String? = nil) {
        self.id = id
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.notes = notes
        sets = []
    }

    var totalVolume: Double {
        sets.reduce(0) { $0 + $1.volume }
    }
}

@Model
final class StoredSet {
    /// The Galaxy's session id. Unique, so re-importing the same set cannot duplicate it.
    @Attribute(.unique) var sessionId: String

    var exerciseId: String
    var exerciseDefinitionVersion: String
    var setNumber: Int
    var startedAt: Date
    var completedAt: Date?

    // --- provenance (plan §5.3): every recorded set says what produced it ------------------
    var appVersion: String
    var protocolVersion: Int
    var poseModelVersion: String
    var deviceId: String

    // --- measured, never mutated by a correction ------------------------------------------
    var measuredRepCount: Int
    var measuredWeight: Double
    var weightUnit: String

    // --- user corrections, nil until the user actually corrects something ------------------
    var correctedRepCount: Int?
    var correctedWeight: Double?
    var correctedExerciseId: String?
    var correctedAt: Date?

    var aborted: Bool
    var abortReason: String?

    /// Highest contiguous sequence imported. Where a reconnect resumes from.
    var highestSequence: Int

    var workout: StoredWorkout?

    @Relationship(deleteRule: .cascade, inverse: \StoredRep.set)
    var reps: [StoredRep]

    init(
        sessionId: String,
        exerciseId: String,
        exerciseDefinitionVersion: String,
        setNumber: Int,
        startedAt: Date = Date(),
        appVersion: String,
        protocolVersion: Int,
        poseModelVersion: String,
        deviceId: String,
        measuredWeight: Double,
        weightUnit: String
    ) {
        self.sessionId = sessionId
        self.exerciseId = exerciseId
        self.exerciseDefinitionVersion = exerciseDefinitionVersion
        self.setNumber = setNumber
        self.startedAt = startedAt
        self.appVersion = appVersion
        self.protocolVersion = protocolVersion
        self.poseModelVersion = poseModelVersion
        self.deviceId = deviceId
        measuredRepCount = 0
        self.measuredWeight = measuredWeight
        self.weightUnit = weightUnit
        aborted = false
        highestSequence = -1
        reps = []
    }

    /// What the user sees: the correction when there is one, the measurement otherwise.
    var effectiveRepCount: Int { correctedRepCount ?? measuredRepCount }
    var effectiveWeight: Double { correctedWeight ?? measuredWeight }
    var effectiveExerciseId: String { correctedExerciseId ?? exerciseId }

    var wasCorrected: Bool { correctedAt != nil }

    /// True when the user disagreed with the count. Worth surfacing — a set the athlete had to
    /// correct is exactly the input the exercise definitions need.
    var repCountDisputed: Bool {
        guard let corrected = correctedRepCount else { return false }
        return corrected != measuredRepCount
    }

    var volume: Double { Double(effectiveRepCount) * effectiveWeight }

    var meanRangeOfMotion: Double? {
        guard !reps.isEmpty else { return nil }
        return reps.reduce(0.0) { $0 + $1.rangeOfMotion } / Double(reps.count)
    }

    var meanRepDurationMs: Int? {
        guard !reps.isEmpty else { return nil }
        return reps.reduce(0) { $0 + $1.durationMs } / reps.count
    }
}

@Model
final class StoredRep {
    /// `sessionId#sequenceNumber`. Unique, so duplicate delivery is a no-op.
    @Attribute(.unique) var eventKey: String

    var sequenceNumber: Int
    var repNumber: Int
    var durationMs: Int
    var concentricDurationMs: Int
    var eccentricDurationMs: Int
    /// 0…1.
    var rangeOfMotion: Double
    var confidence: Double
    var cueIds: [String]
    var monotonicTimestampMs: Int

    var set: StoredSet?

    init(
        eventKey: String,
        sequenceNumber: Int,
        repNumber: Int,
        durationMs: Int,
        concentricDurationMs: Int,
        eccentricDurationMs: Int,
        rangeOfMotion: Double,
        confidence: Double,
        cueIds: [String],
        monotonicTimestampMs: Int
    ) {
        self.eventKey = eventKey
        self.sequenceNumber = sequenceNumber
        self.repNumber = repNumber
        self.durationMs = durationMs
        self.concentricDurationMs = concentricDurationMs
        self.eccentricDurationMs = eccentricDurationMs
        self.rangeOfMotion = rangeOfMotion
        self.confidence = confidence
        self.cueIds = cueIds
        self.monotonicTimestampMs = monotonicTimestampMs
    }
}

@Model
final class StoredPairedDevice {
    @Attribute(.unique) var deviceId: String
    var pairingId: String
    var pairedAt: Date
    var lastSeenAt: Date?
    var displayName: String

    /// The key itself lives in the Keychain, never here — SwiftData's store is an ordinary file.
    init(deviceId: String, pairingId: String, displayName: String, pairedAt: Date = Date()) {
        self.deviceId = deviceId
        self.pairingId = pairingId
        self.displayName = displayName
        self.pairedAt = pairedAt
    }
}
