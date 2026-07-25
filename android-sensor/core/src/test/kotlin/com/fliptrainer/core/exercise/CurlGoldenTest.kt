package com.fliptrainer.core.exercise

import com.fliptrainer.core.features.CameraView
import com.fliptrainer.core.pose.Laterality
import com.fliptrainer.core.session.SessionManifest
import com.fliptrainer.core.session.WeightUnit
import com.fliptrainer.core.synthetic.SyntheticMotion
import com.fliptrainer.core.synthetic.SyntheticPose
import com.fliptrainer.core.trace.TraceHeader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end golden replay for the curl: synthetic figure → trace file on disk → parse → readiness
 * gate → evaluator → event stream, compared byte-for-byte against a stored expectation.
 *
 * The traces are synthetic and say so in their header. They pin *behaviour*, not accuracy. When
 * real recordings exist they will sit alongside these, and only those can close Session 6's
 * human-validation gate.
 */
class CurlGoldenTest {
    private val definition = ExerciseCatalog.load(StandingCurlEvaluator.ID)

    private fun manifest() =
        SessionManifest(
            sessionId = "golden",
            exerciseId = StandingCurlEvaluator.ID,
            exerciseDefinitionVersion = definition.version,
            targetReps = 10,
            setNumber = 1,
            dumbbellWeight = 12.5,
            weightUnit = WeightUnit.KG,
            laterality = Laterality.BILATERAL,
            preferredView = CameraView.FRONT,
            createdAtEpochMs = 0,
        )

    private fun pose(
        u: Double,
        driftDegAtTop: Double = 0.0,
    ): SyntheticPose {
        val elbow = SyntheticMotion.lerp(definition.range.restValue, definition.range.apexValue, u)
        val limb = SyntheticPose.LimbState(elbowAngleDeg = elbow, upperArmAngleDeg = driftDegAtTop * u)
        return SyntheticPose(torso = 0.25, left = limb, right = limb)
    }

    private fun header(notes: String) =
        TraceHeader
            .synthetic(exerciseId = StandingCurlEvaluator.ID, notes = notes)
            .copy(exerciseDefinitionVersion = definition.version, laterality = "BILATERAL", cameraView = "FRONT")

    private fun runGolden(
        name: String,
        notes: String,
        frames: List<com.fliptrainer.core.pose.PoseFrame>,
    ): ReplayResult {
        val trace = Goldens.materializeTrace("$name.jsonl", header(notes), frames)
        val result = TraceReplayRunner(definition).run(trace, StandingCurlEvaluator(definition), manifest())
        Goldens.assertMatches("$name.events.jsonl", result.renderEvents()) { result.summarize() }
        return result
    }

    @Test
    fun `ten clean repetitions`() {
        val result =
            runGolden(
                name = "curl_synthetic_10reps_clean",
                notes = "SYNTHETIC. 10 full curls, 900ms up / 1100ms down. Not a recording of a person.",
                frames = SyntheticMotion.set(10, poseAt = { pose(it) }),
            )
        assertEquals(10, result.repCount, result.summarize())
    }

    @Test
    fun `mixed full and partial repetitions`() {
        val profiles =
            listOf(1.0, 0.55, 1.0, 0.40, 1.0, 0.84).map { SyntheticMotion.RepProfile(peak = it) }
        val result =
            runGolden(
                name = "curl_synthetic_mixed_partials",
                notes = "SYNTHETIC. Peaks 1.0/0.55/1.0/0.40/1.0/0.84 — four countable, two partial.",
                frames = SyntheticMotion.set(profiles, { pose(it) }),
            )
        assertEquals(4, result.repCount, result.summarize())
        assertEquals(2, result.rejectedRepCount, result.summarize())
    }

    @Test
    fun `swinging upper arms raise the drift cue`() {
        val result =
            runGolden(
                name = "curl_synthetic_upper_arm_drift",
                notes = "SYNTHETIC. 5 curls with the upper arm swinging to 55 degrees at the top.",
                frames = SyntheticMotion.set(5, poseAt = { pose(it, driftDegAtTop = 55.0) }),
            )
        assertTrue(StandingCurlEvaluator.CUE_UPPER_ARM_DRIFT in result.raisedCues, result.summarize())
    }

    @Test
    fun `a confidence dropout mid-set`() {
        val frames =
            SyntheticMotion.withLowConfidence(
                SyntheticMotion.set(6, poseAt = { pose(it) }),
                fromMs = 7_000,
                toMs = 11_000,
                types = setOf(com.fliptrainer.core.pose.LandmarkType.LEFT_ELBOW),
                confidence = 0.15f,
            )
        val result =
            runGolden(
                name = "curl_synthetic_confidence_dropout",
                notes = "SYNTHETIC. 6 curls with the left elbow unreliable from 7s to 11s.",
                frames = frames,
            )
        assertTrue(result.repCount in 2..5, "expected some but not all reps: ${result.repCount}")
    }

    @Test
    fun `idle standing produces an empty event stream`() {
        val result =
            runGolden(
                name = "curl_synthetic_idle",
                notes = "SYNTHETIC. 60 seconds standing still with arms down.",
                frames = SyntheticMotion.set(0, poseAt = { pose(0.0) }, leadInMs = 60_000, tailMs = 0),
            )
        assertEquals(0, result.repCount)
        assertEquals(0, result.rejectedRepCount)
    }
}
