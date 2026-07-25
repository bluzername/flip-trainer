package com.fliptrainer.core.exercise

import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.Laterality
import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.pose.Side
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The supported exercises, loaded from the versioned JSON in `exercise-definitions/`.
 *
 * The index is an explicit file rather than a classpath scan: an exercise that exists as a file
 * but is not listed is not shipped, which makes "is this exercise live?" a one-line diff instead
 * of a question about resource discovery.
 */
object ExerciseCatalog {
    private const val ROOT = "/exercise-definitions"
    private const val INDEX = "$ROOT/index.json"

    private val cache = HashMap<String, ExerciseDefinition>()

    /** Ids in catalog order. */
    val ids: List<String> by lazy {
        val text =
            requireNotNull(readResource(INDEX)) {
                "exercise catalog index missing at $INDEX — is exercise-definitions/ on the classpath?"
            }
        ExerciseDefinition.json.decodeFromString(ListSerializer(String.serializer()), text)
    }

    fun load(id: String): ExerciseDefinition =
        cache.getOrPut(id) {
            val path = "$ROOT/$id.json"
            val text = requireNotNull(readResource(path)) { "no exercise definition at $path" }
            val definition = ExerciseDefinition.parse(text)
            require(definition.id == id) {
                "definition in $path declares id '${definition.id}', expected '$id'"
            }
            definition
        }

    fun all(): List<ExerciseDefinition> = ids.map(::load)

    /** Build the evaluator for an exercise id. New exercises are registered here. */
    fun evaluatorFor(id: String): BaseExerciseEvaluator {
        val definition = load(id)
        return when (id) {
            "standing_dumbbell_curl" -> StandingCurlEvaluator(definition)
            else -> error("no evaluator registered for exercise '$id'")
        }
    }

    private fun readResource(path: String): String? =
        ExerciseCatalog::class.java
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
}

/**
 * Resolves which side(s) an evaluator should measure.
 *
 * For [Laterality.EITHER] the choice is made once and then held: re-picking every frame would let
 * a momentary confidence dip switch the measured arm mid-repetition, which reads downstream as an
 * impossible jump in the driving feature.
 */
class SideSelector(
    private val laterality: Laterality,
    private val landmarksFor: (Side) -> Set<LandmarkType>,
) {
    private var resolved: Side? = null

    fun reset() {
        resolved = null
    }

    /** @return the sides to measure, or null if the requirement cannot currently be met. */
    fun sides(
        frame: PoseFrame,
        minConfidence: Float,
    ): List<Side>? =
        when (laterality) {
            Laterality.BILATERAL -> {
                val ok =
                    listOf(Side.LEFT, Side.RIGHT).all { side ->
                        landmarksFor(side).all { frame.confidenceOf(it) >= minConfidence }
                    }
                if (ok) listOf(Side.LEFT, Side.RIGHT) else null
            }

            Laterality.LEFT -> singleSide(frame, Side.LEFT, minConfidence)
            Laterality.RIGHT -> singleSide(frame, Side.RIGHT, minConfidence)

            Laterality.EITHER -> {
                val sticky = resolved
                if (sticky != null) {
                    singleSide(frame, sticky, minConfidence)
                } else {
                    val best =
                        listOf(Side.LEFT, Side.RIGHT)
                            .map { it to frame.minConfidenceOf(landmarksFor(it)) }
                            .filter { it.second >= minConfidence }
                            .maxByOrNull { it.second }
                            ?.first
                    if (best == null) {
                        null
                    } else {
                        resolved = best
                        listOf(best)
                    }
                }
            }
        }

    private fun singleSide(
        frame: PoseFrame,
        side: Side,
        minConfidence: Float,
    ): List<Side>? = if (landmarksFor(side).all { frame.confidenceOf(it) >= minConfidence }) listOf(side) else null
}

/** Landmark for [side], where the enum is named `LEFT_X` / `RIGHT_X`. */
fun landmark(
    side: Side,
    bare: String,
): LandmarkType =
    when (side) {
        Side.LEFT -> LandmarkType.valueOf("LEFT_$bare")
        Side.RIGHT -> LandmarkType.valueOf("RIGHT_$bare")
        Side.CENTER -> error("$bare has no centre variant")
    }
