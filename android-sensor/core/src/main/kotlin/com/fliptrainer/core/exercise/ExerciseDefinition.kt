package com.fliptrainer.core.exercise

import com.fliptrainer.core.BuildIdentity
import com.fliptrainer.core.features.CameraView
import com.fliptrainer.core.features.FilterSpec
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.Laterality
import com.fliptrainer.core.readiness.FrameOrientation
import com.fliptrainer.core.readiness.ReadinessRequirements
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An exercise's tunable data — thresholds, required landmarks, metadata.
 *
 * The split enforced by plan §7.1: **behaviour is typed Kotlin, numbers are JSON.** Phase
 * transitions and cue logic live in an evaluator class where they can be read and tested; every
 * value someone might want to tune after a validation session lives here, versioned, so a
 * threshold change is a data diff and shows up in the test matrix as a new definition version.
 *
 * Explicitly *not* a general exercise language. That comes only after four evaluators have proven
 * which patterns actually repeat (§13.1).
 */
@Serializable
data class ExerciseDefinition(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    val name: String,
    val instructions: String,
    val preferredView: CameraView,
    val laterality: Laterality,
    val requiredLandmarks: List<LandmarkType>,
    val readiness: ReadinessSpec,
    val cycle: RepCycleConfig,
    val filter: FilterSpec = FilterSpec.MedianThenEma(),
    val range: RangeSpec,
    val cues: List<CueSpec> = emptyList(),
    val knownLimitations: List<String> = emptyList(),
    val unsupportedViews: List<String> = emptyList(),
) {
    init {
        require(schemaVersion == BuildIdentity.EXERCISE_DEFINITION_SCHEMA_VERSION) {
            "exercise definition '$id' uses schema version $schemaVersion, this build reads " +
                "${BuildIdentity.EXERCISE_DEFINITION_SCHEMA_VERSION}"
        }
        require(id.isNotBlank()) { "exercise id must not be blank" }
        require(version.isNotBlank()) { "exercise version must not be blank" }
        require(requiredLandmarks.isNotEmpty()) { "exercise '$id' must require at least one landmark" }
        require(cues.size <= MAX_CUES) {
            "exercise '$id' declares ${cues.size} cues; the cap is $MAX_CUES until validation " +
                "shows each one is precise (plan §7.4)"
        }
        require(cues.map { it.id }.toSet().size == cues.size) { "duplicate cue id in '$id'" }
    }

    fun readinessRequirements(): ReadinessRequirements =
        ReadinessRequirements(
            requiredLandmarks = requiredLandmarks.toSet(),
            preferredView = preferredView,
            minLandmarkConfidence = readiness.minLandmarkConfidence,
            minSubjectHeightFraction = readiness.minSubjectHeightFraction,
            maxSubjectHeightFraction = readiness.maxSubjectHeightFraction,
            frameMarginFraction = readiness.frameMarginFraction,
            requiredOrientation = readiness.requiredOrientation,
            enforceView = readiness.enforceView,
        )

    fun cue(id: String): CueSpec? = cues.firstOrNull { it.id == id }

    companion object {
        /**
         * Plan §7.4: begin with one or two cues per exercise and prefer precision over recall.
         * The cap is enforced rather than suggested — a third cue means deleting one first.
         */
        const val MAX_CUES: Int = 2

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val json: Json =
            Json {
                ignoreUnknownKeys = false // a typo in a threshold name must fail loudly, not silently default
                prettyPrint = true
                prettyPrintIndent = "  "
                encodeDefaults = true
            }

        fun parse(text: String): ExerciseDefinition = json.decodeFromString(serializer(), text)

        fun render(definition: ExerciseDefinition): String = json.encodeToString(serializer(), definition)
    }
}

@Serializable
data class ReadinessSpec(
    val minLandmarkConfidence: Float = 0.5f,
    val minSubjectHeightFraction: Double = 0.30,
    val maxSubjectHeightFraction: Double = 0.92,
    val frameMarginFraction: Double = 0.02,
    val enforceView: Boolean = true,
    val requiredOrientation: FrameOrientation = FrameOrientation.ANY,
)

/**
 * Maps the exercise's driving feature onto the `0..1` progress scale the cycle detector consumes.
 *
 * [apexValue] may be below [restValue] — a curl's elbow angle *decreases* as the rep progresses —
 * so the mapping handles either direction rather than forcing evaluators to pre-negate.
 *
 * @property restValue feature value at the resting position (progress 0).
 * @property apexValue feature value at the far end of a full repetition (progress 1).
 */
@Serializable
data class RangeSpec(
    val restValue: Double,
    val apexValue: Double,
) {
    init {
        require(kotlin.math.abs(apexValue - restValue) > 1e-6) {
            "restValue and apexValue must differ, otherwise progress is undefined"
        }
    }

    /** Feature value → progress, clamped to `0..1`. */
    fun progressOf(featureValue: Double): Double =
        ((featureValue - restValue) / (apexValue - restValue)).coerceIn(0.0, 1.0)

    /** Progress → feature value. Used to report thresholds in the units an athlete understands. */
    fun featureAt(progress: Double): Double = restValue + progress * (apexValue - restValue)
}

/**
 * One conservative movement cue.
 *
 * @property threshold the exercise-specific trigger value; its meaning is defined by the
 *   evaluator that reads it, and documented on that evaluator.
 * @property minConfidence every landmark the cue depends on must clear this for the whole
 *   evaluation window, or the cue abstains.
 * @property debounceMs how long a violation must persist before the cue is raised, and how long
 *   it must be absent before it is cleared. Stops flicker (plan §7.4).
 * @property enabled set false to ship an exercise with a cue disabled rather than deleting the
 *   threshold history. A disabled cue is never evaluated.
 */
@Serializable
data class CueSpec(
    val id: String,
    val message: String,
    val threshold: Double,
    val minConfidence: Float = 0.6f,
    val debounceMs: Long = 500,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "cue id must not be blank" }
        require(message.isNotBlank()) { "cue '$id' needs a message" }
        require(debounceMs >= 0) { "cue '$id' debounce must not be negative" }
    }
}
