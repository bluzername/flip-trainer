package com.fliptrainer.core.session

import com.fliptrainer.core.features.CameraView
import com.fliptrainer.core.pose.Laterality
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

enum class WeightUnit {
    KG,
    LB,
}

/**
 * Everything the Galaxy needs to run a set on its own (plan §6.3).
 *
 * The iPhone sends this before arming and then becomes optional. That is the whole point of
 * §4.3: once the manifest is accepted, a locked phone, a dropped connection or a walk out of BLE
 * range cannot stop the set. Any field the evaluator needs mid-set must be in here.
 */
@Serializable
data class SessionManifest(
    val sessionId: String,
    val exerciseId: String,
    val exerciseDefinitionVersion: String,
    val targetReps: Int,
    val setNumber: Int,
    val dumbbellWeight: Double,
    val weightUnit: WeightUnit,
    val laterality: Laterality,
    val preferredView: CameraView,
    val createdAtEpochMs: Long,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(exerciseId.isNotBlank()) { "exerciseId must not be blank" }
        require(targetReps >= 0) { "targetReps must not be negative" }
        require(setNumber >= 1) { "setNumber starts at 1" }
        require(dumbbellWeight >= 0) { "dumbbellWeight must not be negative" }
    }
}

/**
 * Metrics that appear in golden files are integers, deliberately (ADR-0005).
 *
 * A `Double` renders differently across platforms and optimization levels, which would make a
 * byte-equivalence test flaky for reasons that have nothing to do with the evaluator.
 */
object Quantize {
    /** `0.0..1.0` → `0..1000`. Values outside the range are clamped, not wrapped. */
    fun perMille(value: Double): Int = (value.coerceIn(0.0, 1.0) * 1000).roundToInt()

    fun perMille(value: Float): Int = perMille(value.toDouble())

    /** Degrees → tenths of a degree, so a 0.1° change is representable and stable. */
    fun deciDegrees(value: Double): Int = (value * 10).roundToInt()
}

/** Event kinds from plan §6.4. The wire name is the serial name — do not rename casually. */
@Serializable
sealed interface EventPayload {
    @Serializable
    @SerialName("sessionAccepted")
    data class SessionAccepted(
        val exerciseId: String,
        val exerciseDefinitionVersion: String,
        val targetReps: Int,
        val setNumber: Int,
    ) : EventPayload

    @Serializable
    @SerialName("readinessChanged")
    data class ReadinessChanged(
        val status: String,
        val details: List<String> = emptyList(),
        val confidencePerMille: Int = 0,
    ) : EventPayload

    @Serializable
    @SerialName("setStarted")
    data object SetStarted : EventPayload

    @Serializable
    @SerialName("phaseChanged")
    data class PhaseChanged(
        val phase: String,
        val previousPhase: String,
        /** Exercise-specific label, e.g. "concentric" for a curl's outbound phase. */
        val label: String,
    ) : EventPayload

    @Serializable
    @SerialName("repCompleted")
    data class RepCompleted(
        val repNumber: Int,
        val repDurationMs: Int,
        val concentricDurationMs: Int,
        val eccentricDurationMs: Int,
        val rangeOfMotionPerMille: Int,
        val confidencePerMille: Int,
        val cues: List<String> = emptyList(),
    ) : EventPayload

    @Serializable
    @SerialName("repRejected")
    data class RepRejected(
        val reason: String,
        val peakRangeOfMotionPerMille: Int,
    ) : EventPayload

    @Serializable
    @SerialName("cueRaised")
    data class CueRaised(
        val cueId: String,
        val message: String,
        /** The measured values that triggered it, kept so a false cue can be debugged (plan §7.4). */
        val evidence: Map<String, Int> = emptyMap(),
    ) : EventPayload

    @Serializable
    @SerialName("cueCleared")
    data class CueCleared(
        val cueId: String,
    ) : EventPayload

    @Serializable
    @SerialName("setPaused")
    data object SetPaused : EventPayload

    @Serializable
    @SerialName("setResumed")
    data object SetResumed : EventPayload

    @Serializable
    @SerialName("setCompleted")
    data class SetCompleted(
        val totalReps: Int,
    ) : EventPayload

    @Serializable
    @SerialName("setAborted")
    data class SetAborted(
        val reason: String,
    ) : EventPayload

    @Serializable
    @SerialName("deviceWarning")
    data class DeviceWarning(
        val code: String,
        val message: String,
    ) : EventPayload
}

/**
 * One durable entry in the append-only log.
 *
 * `sessionId + sequenceNumber` is the unique key (plan §6.5). Replay across a BLE reconnect is
 * idempotent because of it, so the iPhone can be handed the same event twice without
 * double-counting a rep.
 */
@Serializable
data class WorkoutEvent(
    val sessionId: String,
    val sequenceNumber: Long,
    @SerialName("t") val monotonicTimestampMs: Long,
    val payload: EventPayload,
) {
    val key: String get() = "$sessionId#$sequenceNumber"

    companion object {
        /** Compact, stable JSON. Used for golden files and for diagnostics export. */
        val json: Json =
            Json {
                encodeDefaults = false
                classDiscriminator = "type"
                prettyPrint = false
            }

        fun render(events: List<WorkoutEvent>): String =
            events.joinToString("\n", postfix = if (events.isEmpty()) "" else "\n") {
                json.encodeToString(serializer(), it)
            }

        fun parse(text: String): List<WorkoutEvent> =
            text
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { json.decodeFromString(serializer(), it) }
                .toList()
    }
}

/** Assigns monotonically increasing sequence numbers within a session. */
class SequenceSource(
    start: Long = 0,
) {
    private var next = start

    fun next(): Long = next++

    val peek: Long get() = next
}

/** Current evaluator state, for the live UI and the debug overlay. */
data class ExerciseSnapshot(
    val phase: String,
    val phaseLabel: String,
    val repCount: Int,
    val activeCues: Set<String>,
    val confidence: Float,
    /**
     * Raw and filtered values of the exercise's driving feature.
     *
     * Both are kept because a rep-counting bug is almost always a filtering question — was the
     * signal noisy, or was the filter eating the movement? (plan §5 task 4)
     */
    val rawProgress: Double?,
    val filteredProgress: Double?,
    val rawPrimaryFeature: Double?,
    val filteredPrimaryFeature: Double?,
) {
    companion object {
        val IDLE =
            ExerciseSnapshot(
                phase = "IDLE",
                phaseLabel = "idle",
                repCount = 0,
                activeCues = emptySet(),
                confidence = 0f,
                rawProgress = null,
                filteredProgress = null,
                rawPrimaryFeature = null,
                filteredPrimaryFeature = null,
            )
    }
}
