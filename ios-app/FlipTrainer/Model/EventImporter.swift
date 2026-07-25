import Foundation
import SwiftData

/// Decoded form of the JSON payload carried in `WorkoutEventMessage`.
///
/// Mirrors `com.fliptrainer.core.session.EventPayload`. The discriminator key is `type`, matching
/// the Kotlin `Json { classDiscriminator = "type" }` configuration.
enum WorkoutEventPayload: Decodable {
    case sessionAccepted(exerciseId: String, definitionVersion: String, targetReps: Int, setNumber: Int)
    case readinessChanged(status: String, details: [String], confidencePerMille: Int)
    case setStarted
    case phaseChanged(phase: String, previousPhase: String, label: String)
    case repCompleted(RepPayload)
    case repRejected(reason: String, peakPerMille: Int)
    case cueRaised(cueId: String, message: String)
    case cueCleared(cueId: String)
    case setPaused
    case setResumed
    case setCompleted(totalReps: Int)
    case setAborted(reason: String)
    case deviceWarning(code: String, message: String)
    /// A newer sensor build sent an event this app does not know. Not an error — ignore it and
    /// keep the rest of the set, rather than failing the whole import.
    case unknown(String)

    struct RepPayload: Decodable {
        let repNumber: Int
        let repDurationMs: Int
        let concentricDurationMs: Int
        let eccentricDurationMs: Int
        let rangeOfMotionPerMille: Int
        let confidencePerMille: Int
        let cues: [String]

        enum CodingKeys: String, CodingKey {
            case repNumber, repDurationMs, concentricDurationMs, eccentricDurationMs
            case rangeOfMotionPerMille, confidencePerMille, cues
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            repNumber = try c.decode(Int.self, forKey: .repNumber)
            repDurationMs = try c.decodeIfPresent(Int.self, forKey: .repDurationMs) ?? 0
            concentricDurationMs = try c.decodeIfPresent(Int.self, forKey: .concentricDurationMs) ?? 0
            eccentricDurationMs = try c.decodeIfPresent(Int.self, forKey: .eccentricDurationMs) ?? 0
            rangeOfMotionPerMille = try c.decodeIfPresent(Int.self, forKey: .rangeOfMotionPerMille) ?? 0
            confidencePerMille = try c.decodeIfPresent(Int.self, forKey: .confidencePerMille) ?? 0
            cues = try c.decodeIfPresent([String].self, forKey: .cues) ?? []
        }
    }

    private enum CodingKeys: String, CodingKey {
        case type, exerciseId, exerciseDefinitionVersion, targetReps, setNumber
        case status, details, confidencePerMille
        case phase, previousPhase, label
        case reason, peakRangeOfMotionPerMille
        case cueId, message, code
        case totalReps
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let type = try c.decode(String.self, forKey: .type)
        switch type {
        case "sessionAccepted":
            self = .sessionAccepted(
                exerciseId: try c.decode(String.self, forKey: .exerciseId),
                definitionVersion: try c.decode(String.self, forKey: .exerciseDefinitionVersion),
                targetReps: try c.decodeIfPresent(Int.self, forKey: .targetReps) ?? 0,
                setNumber: try c.decodeIfPresent(Int.self, forKey: .setNumber) ?? 1
            )
        case "readinessChanged":
            self = .readinessChanged(
                status: try c.decode(String.self, forKey: .status),
                details: try c.decodeIfPresent([String].self, forKey: .details) ?? [],
                confidencePerMille: try c.decodeIfPresent(Int.self, forKey: .confidencePerMille) ?? 0
            )
        case "setStarted": self = .setStarted
        case "phaseChanged":
            self = .phaseChanged(
                phase: try c.decode(String.self, forKey: .phase),
                previousPhase: try c.decode(String.self, forKey: .previousPhase),
                label: try c.decodeIfPresent(String.self, forKey: .label) ?? ""
            )
        case "repCompleted": self = .repCompleted(try RepPayload(from: decoder))
        case "repRejected":
            self = .repRejected(
                reason: try c.decode(String.self, forKey: .reason),
                peakPerMille: try c.decodeIfPresent(Int.self, forKey: .peakRangeOfMotionPerMille) ?? 0
            )
        case "cueRaised":
            self = .cueRaised(
                cueId: try c.decode(String.self, forKey: .cueId),
                message: try c.decodeIfPresent(String.self, forKey: .message) ?? ""
            )
        case "cueCleared": self = .cueCleared(cueId: try c.decode(String.self, forKey: .cueId))
        case "setPaused": self = .setPaused
        case "setResumed": self = .setResumed
        case "setCompleted": self = .setCompleted(totalReps: try c.decodeIfPresent(Int.self, forKey: .totalReps) ?? 0)
        case "setAborted": self = .setAborted(reason: try c.decodeIfPresent(String.self, forKey: .reason) ?? "")
        case "deviceWarning":
            self = .deviceWarning(
                code: try c.decodeIfPresent(String.self, forKey: .code) ?? "",
                message: try c.decodeIfPresent(String.self, forKey: .message) ?? ""
            )
        default: self = .unknown(type)
        }
    }
}

struct IncomingEvent {
    let sessionId: String
    let sequenceNumber: Int
    let monotonicTimestampMs: Int
    let payload: WorkoutEventPayload

    var key: String { "\(sessionId)#\(sequenceNumber)" }
}

/// Writes Galaxy events into SwiftData, idempotently.
///
/// Session 11 acceptance: **replayed events must not duplicate reps.** The mechanism is the unique
/// `eventKey` — an event that has already been stored is skipped without touching anything else.
/// It is checked explicitly rather than relying on a uniqueness constraint to throw, because a
/// thrown constraint violation mid-batch would abandon the rest of the replay.
@MainActor
struct EventImporter {
    let context: ModelContext

    struct Result {
        var importedReps = 0
        var duplicatesSkipped = 0
        var unknownEvents = 0
        var completed = false
        var aborted: String?
    }

    @discardableResult
    func importEvents(_ events: [IncomingEvent], into set: StoredSet) throws -> Result {
        var result = Result()
        let existingKeys = Set(set.reps.map(\.eventKey))

        for event in events.sorted(by: { $0.sequenceNumber < $1.sequenceNumber }) {
            switch event.payload {
            case let .repCompleted(rep):
                guard !existingKeys.contains(event.key) else {
                    result.duplicatesSkipped += 1
                    continue
                }
                let stored = StoredRep(
                    eventKey: event.key,
                    sequenceNumber: event.sequenceNumber,
                    repNumber: rep.repNumber,
                    durationMs: rep.repDurationMs,
                    concentricDurationMs: rep.concentricDurationMs,
                    eccentricDurationMs: rep.eccentricDurationMs,
                    rangeOfMotion: Double(rep.rangeOfMotionPerMille) / 1000.0,
                    confidence: Double(rep.confidencePerMille) / 1000.0,
                    cueIds: rep.cues,
                    monotonicTimestampMs: event.monotonicTimestampMs
                )
                stored.set = set
                set.reps.append(stored)
                context.insert(stored)
                result.importedReps += 1

            case let .setCompleted(totalReps):
                set.completedAt = Date()
                // Trust the device's own total over a count of the rep events we happen to hold:
                // if a rep event was lost, the total is still right and the discrepancy is
                // visible rather than silently absorbed.
                set.measuredRepCount = totalReps
                result.completed = true

            case let .setAborted(reason):
                set.aborted = true
                set.abortReason = reason
                set.completedAt = Date()
                result.aborted = reason

            case .unknown:
                result.unknownEvents += 1

            default:
                break
            }

            set.highestSequence = max(set.highestSequence, event.sequenceNumber)
        }

        // Only fall back to counting rep events when the device never told us a total.
        if !result.completed, set.measuredRepCount < set.reps.count {
            set.measuredRepCount = set.reps.count
        }

        try context.save()
        return result
    }

    /// Apply a user correction. Measured values are left untouched, always.
    func correct(
        set: StoredSet,
        repCount: Int? = nil,
        weight: Double? = nil,
        exerciseId: String? = nil
    ) throws {
        if let repCount { set.correctedRepCount = repCount }
        if let weight { set.correctedWeight = weight }
        if let exerciseId { set.correctedExerciseId = exerciseId }
        set.correctedAt = Date()
        try context.save()
    }

    /// Undo a correction, restoring the measured values as the effective ones.
    func clearCorrections(set: StoredSet) throws {
        set.correctedRepCount = nil
        set.correctedWeight = nil
        set.correctedExerciseId = nil
        set.correctedAt = nil
        try context.save()
    }
}

/// JSON and CSV export (plan §11 task 6). Local file only — no upload, ever.
///
/// Exports carry the app, protocol, pose-model and exercise-definition versions, because a rep
/// count without the versions that produced it cannot be compared against anything later.
enum WorkoutExport {
    static func json(sets: [StoredSet]) throws -> Data {
        let payload = sets.map { set -> [String: Any] in
            [
                "sessionId": set.sessionId,
                "exerciseId": set.effectiveExerciseId,
                "exerciseDefinitionVersion": set.exerciseDefinitionVersion,
                "appVersion": set.appVersion,
                "protocolVersion": set.protocolVersion,
                "poseModelVersion": set.poseModelVersion,
                "deviceId": set.deviceId,
                "setNumber": set.setNumber,
                "startedAt": ISO8601DateFormatter().string(from: set.startedAt),
                "measuredRepCount": set.measuredRepCount,
                "correctedRepCount": set.correctedRepCount as Any,
                "measuredWeight": set.measuredWeight,
                "correctedWeight": set.correctedWeight as Any,
                "weightUnit": set.weightUnit,
                "aborted": set.aborted,
                "reps": set.reps.sorted { $0.repNumber < $1.repNumber }.map { rep in
                    [
                        "repNumber": rep.repNumber,
                        "durationMs": rep.durationMs,
                        "concentricDurationMs": rep.concentricDurationMs,
                        "eccentricDurationMs": rep.eccentricDurationMs,
                        "rangeOfMotion": rep.rangeOfMotion,
                        "confidence": rep.confidence,
                        "cues": rep.cueIds,
                    ]
                },
            ]
        }
        return try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
    }

    static func csv(sets: [StoredSet]) -> String {
        var lines = [
            "sessionId,exerciseId,definitionVersion,appVersion,poseModelVersion,setNumber,startedAt,"
                + "measuredReps,correctedReps,measuredWeight,correctedWeight,unit,meanRom,meanRepMs,aborted",
        ]
        let formatter = ISO8601DateFormatter()
        for set in sets.sorted(by: { $0.startedAt < $1.startedAt }) {
            lines.append(
                [
                    set.sessionId,
                    set.effectiveExerciseId,
                    set.exerciseDefinitionVersion,
                    set.appVersion,
                    set.poseModelVersion,
                    String(set.setNumber),
                    formatter.string(from: set.startedAt),
                    String(set.measuredRepCount),
                    set.correctedRepCount.map(String.init) ?? "",
                    String(set.measuredWeight),
                    set.correctedWeight.map(String.init) ?? "",
                    set.weightUnit,
                    set.meanRangeOfMotion.map { String(format: "%.3f", $0) } ?? "",
                    set.meanRepDurationMs.map(String.init) ?? "",
                    String(set.aborted),
                ].joined(separator: ",")
            )
        }
        return lines.joined(separator: "\n") + "\n"
    }
}
