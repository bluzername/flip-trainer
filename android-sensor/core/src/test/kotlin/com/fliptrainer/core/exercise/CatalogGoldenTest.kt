package com.fliptrainer.core.exercise

import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.session.SessionManifest
import com.fliptrainer.core.session.WeightUnit
import com.fliptrainer.core.synthetic.SyntheticMotion
import com.fliptrainer.core.synthetic.SyntheticPose
import com.fliptrainer.core.trace.TraceHeader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * One committed golden per exercise: a clean ten-repetition set, end to end.
 *
 * These files are the tripwire for an unintended behaviour change anywhere in the stack — a
 * geometry fix, a filter tweak, a threshold edit. If a diff appears here and you did not mean it,
 * something moved that you did not think you were touching.
 *
 * Synthetic input. Behaviour, not accuracy.
 */
class CatalogGoldenTest {
    private val poses: Map<String, (Double) -> SyntheticPose> =
        mapOf(
            StandingCurlEvaluator.ID to { u ->
                val limb = SyntheticPose.LimbState(elbowAngleDeg = SyntheticMotion.lerp(172.0, 45.0, u))
                SyntheticPose(torso = 0.25, left = limb, right = limb)
            },
            GobletSquatEvaluator.ID to { u ->
                val leg =
                    SyntheticPose.LimbState(
                        kneeAngleDeg = SyntheticMotion.lerp(172.0, 85.0, u),
                        shinAngleDeg = SyntheticMotion.lerp(0.0, 18.0, u),
                    )
                SyntheticPose(
                    midlineX = 0.33,
                    torso = 0.24,
                    openness = 0.62,
                    torsoLeanDeg = SyntheticMotion.lerp(0.0, 28.0, u),
                    left = leg,
                    right = leg,
                )
            },
            RomanianDeadliftEvaluator.ID to { u ->
                val leg = SyntheticPose.LimbState(kneeAngleDeg = 168.0, shinAngleDeg = 0.0)
                SyntheticPose(
                    midlineX = 0.34,
                    torso = 0.24,
                    openness = 0.18,
                    torsoLeanDeg = SyntheticMotion.lerp(6.0, 74.0, u),
                    left = leg,
                    right = leg,
                )
            },
            ShoulderPressEvaluator.ID to { u ->
                val arm =
                    SyntheticPose.LimbState(
                        upperArmAngleDeg = SyntheticMotion.lerp(35.0, 180.0, u),
                        elbowAngleDeg = SyntheticMotion.lerp(65.0, 175.0, u),
                    )
                SyntheticPose(torso = 0.21, groundY = 1.05, left = arm, right = arm)
            },
        )

    private fun manifestFor(definition: ExerciseDefinition) =
        SessionManifest(
            sessionId = "golden-${definition.id}",
            exerciseId = definition.id,
            exerciseDefinitionVersion = definition.version,
            targetReps = 10,
            setNumber = 1,
            dumbbellWeight = 15.0,
            weightUnit = WeightUnit.KG,
            laterality = definition.laterality,
            preferredView = definition.preferredView,
            createdAtEpochMs = 0,
        )

    private fun goldenFor(
        definition: ExerciseDefinition,
        frames: List<PoseFrame>,
    ): ReplayResult {
        val header =
            TraceHeader
                .synthetic(
                    exerciseId = definition.id,
                    notes = "SYNTHETIC. 10 clean repetitions. Not a recording of a person.",
                ).copy(
                    exerciseDefinitionVersion = definition.version,
                    laterality = definition.laterality.name,
                    cameraView = definition.preferredView.name,
                )
        val trace = Goldens.materializeTrace("${definition.id}_synthetic_10reps.jsonl", header, frames)
        val result =
            TraceReplayRunner(definition)
                .run(trace, ExerciseCatalog.evaluatorFor(definition.id), manifestFor(definition))
        Goldens.assertMatches("${definition.id}_synthetic_10reps.events.jsonl", result.renderEvents()) {
            result.summarize()
        }
        return result
    }

    @Test
    fun `every exercise has a committed golden for a clean ten-rep set`() {
        for (definition in ExerciseCatalog.all()) {
            val poseAt = poses.getValue(definition.id)
            val frames =
                SyntheticMotion.set(
                    reps = 10,
                    profile = SyntheticMotion.RepProfile(outboundMs = 1_100, returnMs = 1_300, restMs = 600),
                    poseAt = poseAt,
                )
            val result = goldenFor(definition, frames)
            assertEquals(10, result.repCount, "${definition.id}\n${result.summarize()}")
        }
    }
}
