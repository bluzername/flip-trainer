package com.fliptrainer.core.exercise

import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.session.SessionManifest
import com.fliptrainer.core.session.WeightUnit
import com.fliptrainer.core.synthetic.SyntheticMotion
import com.fliptrainer.core.synthetic.SyntheticPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The four-exercise catalog.
 *
 * Rules that must hold for *every* exercise are asserted once here rather than copied into four
 * files — a new exercise added to `index.json` is automatically held to all of them.
 *
 * Synthetic input throughout. Determinism and threshold behaviour, not accuracy.
 */
class CatalogEvaluatorsTest {
    private fun manifestFor(definition: ExerciseDefinition) =
        SessionManifest(
            sessionId = "catalog-${definition.id}",
            exerciseId = definition.id,
            exerciseDefinitionVersion = definition.version,
            targetReps = 10,
            setNumber = 1,
            dumbbellWeight = 10.0,
            weightUnit = WeightUnit.KG,
            laterality = definition.laterality,
            preferredView = definition.preferredView,
            createdAtEpochMs = 0,
        )

    private fun replay(
        definition: ExerciseDefinition,
        frames: List<PoseFrame>,
    ): ReplayResult =
        TraceReplayRunner(definition)
            .run(frames, ExerciseCatalog.evaluatorFor(definition.id), manifestFor(definition))

    // --- pose builders, one per exercise -------------------------------------------------

    private fun curlPose(u: Double): SyntheticPose {
        val limb = SyntheticPose.LimbState(elbowAngleDeg = SyntheticMotion.lerp(172.0, 45.0, u))
        return SyntheticPose(torso = 0.25, left = limb, right = limb)
    }

    /** Knees bend and the torso leans forward together, as in a real squat. */
    private fun squatPose(
        u: Double,
        rightKneeBias: Double = 0.0,
    ): SyntheticPose {
        fun leg(bias: Double) =
            SyntheticPose.LimbState(
                kneeAngleDeg = SyntheticMotion.lerp(172.0, 85.0, u) + bias,
                shinAngleDeg = SyntheticMotion.lerp(0.0, 18.0, u),
            )
        return SyntheticPose(
            // Offset from centre: a squat both leans the torso forward and pushes the hips back,
            // so the figure travels sideways in a planar rig. Centred at 0.5 the leading shoulder
            // leaves the frame at depth and the readiness gate correctly reports BODY_CLIPPED.
            midlineX = 0.33,
            torso = 0.24,
            groundY = 0.95,
            openness = 0.62,
            torsoLeanDeg = SyntheticMotion.lerp(0.0, 28.0, u),
            left = leg(0.0),
            right = leg(rightKneeBias * u),
        )
    }

    /** Hips travel back, torso inclines, knees stay soft unless [kneeFlexionAtBottom] says otherwise. */
    private fun rdlPose(
        u: Double,
        kneeFlexionAtBottom: Double = 0.0,
    ): SyntheticPose {
        val leg =
            SyntheticPose.LimbState(
                kneeAngleDeg = SyntheticMotion.lerp(168.0, 168.0 - kneeFlexionAtBottom, u),
                shinAngleDeg = 0.0,
            )
        return SyntheticPose(
            midlineX = 0.34,
            torso = 0.24,
            groundY = 0.95,
            openness = 0.18,
            torsoLeanDeg = SyntheticMotion.lerp(6.0, 74.0, u),
            left = leg,
            right = leg,
        )
    }

    /** Racked at the shoulders, pressed to lockout. */
    private fun pressPose(
        u: Double,
        rightLagFraction: Double = 0.0,
    ): SyntheticPose {
        fun arm(fraction: Double) =
            SyntheticPose.LimbState(
                // Racked: upper arm down-and-out at 35°, forearm folded up. Wider than this and
                // the hands leave the frame, which the readiness gate correctly calls clipping.
                upperArmAngleDeg = SyntheticMotion.lerp(35.0, 180.0, fraction),
                elbowAngleDeg = SyntheticMotion.lerp(65.0, 175.0, fraction),
            )
        return SyntheticPose(
            // Small on purpose. At the mid-point of a press the hands are ~2.4 torso-lengths
            // apart, so on a 9:16 portrait frame anything larger puts a wrist outside the frame
            // and the readiness gate correctly reports clipping. See the definition's
            // knownLimitations — frame width, not height, is the binding constraint here.
            torso = 0.21,
            groundY = 1.05,
            left = arm(u),
            right = arm((u - rightLagFraction).coerceAtLeast(0.0)),
        )
    }

    private fun poseBuilderFor(id: String): (Double) -> SyntheticPose =
        when (id) {
            StandingCurlEvaluator.ID -> ::curlPose
            GobletSquatEvaluator.ID -> { u -> squatPose(u) }
            RomanianDeadliftEvaluator.ID -> { u -> rdlPose(u) }
            ShoulderPressEvaluator.ID -> { u -> pressPose(u) }
            else -> error("no pose builder for $id")
        }

    // --- catalog-wide invariants ----------------------------------------------------------

    @Test
    fun `the catalog holds exactly the four planned exercises`() {
        assertEquals(
            listOf(
                StandingCurlEvaluator.ID,
                GobletSquatEvaluator.ID,
                RomanianDeadliftEvaluator.ID,
                ShoulderPressEvaluator.ID,
            ),
            ExerciseCatalog.ids,
        )
    }

    @Test
    fun `every definition parses, declares limitations, and has an evaluator`() {
        for (definition in ExerciseCatalog.all()) {
            assertTrue(definition.cues.size <= ExerciseDefinition.MAX_CUES, "${definition.id} exceeds the cue budget")
            assertTrue(definition.knownLimitations.isNotEmpty(), "${definition.id} declares no limitations")
            assertTrue(
                definition.knownLimitations.any { it.contains("EXPERIMENTAL") },
                "${definition.id} must not imply validation that has not happened",
            )
            assertTrue(definition.unsupportedViews.isNotEmpty(), "${definition.id} documents no unsupported views")
            ExerciseCatalog.evaluatorFor(definition.id) // must not throw
        }
    }

    @Test
    fun `every exercise counts ten clean repetitions as ten`() {
        for (definition in ExerciseCatalog.all()) {
            val frames =
                SyntheticMotion.set(
                    reps = 10,
                    profile = SyntheticMotion.RepProfile(outboundMs = 1_100, returnMs = 1_300, restMs = 600),
                    poseAt = poseBuilderFor(definition.id),
                )
            val result = replay(definition, frames)
            assertEquals(10, result.repCount, "${definition.id}\n${result.summarize()}")
        }
    }

    @Test
    fun `no exercise produces a phantom rep during sixty seconds of idle`() {
        // Session 7 acceptance, applied uniformly.
        for (definition in ExerciseCatalog.all()) {
            val builder = poseBuilderFor(definition.id)
            val frames = SyntheticMotion.set(0, poseAt = { builder(0.0) }, leadInMs = 60_000, tailMs = 0)
            val result = replay(definition, frames)
            assertEquals(0, result.repCount, "${definition.id} invented a rep while idle\n${result.summarize()}")
        }
    }

    @Test
    fun `no exercise counts a half repetition`() {
        for (definition in ExerciseCatalog.all()) {
            val frames =
                SyntheticMotion.set(
                    reps = 6,
                    profile =
                        SyntheticMotion.RepProfile(
                            outboundMs = 1_100,
                            returnMs = 1_300,
                            restMs = 600,
                            peak = 0.5,
                        ),
                    poseAt = poseBuilderFor(definition.id),
                )
            val result = replay(definition, frames)
            assertEquals(0, result.repCount, "${definition.id} counted a half rep\n${result.summarize()}")
            assertEquals(6, result.rejectedRepCount, "${definition.id}\n${result.summarize()}")
        }
    }

    @Test
    fun `bad framing produces abstention rather than fabricated metrics`() {
        // Session 7 acceptance. Athlete far too small in frame for every exercise.
        for (definition in ExerciseCatalog.all()) {
            val builder = poseBuilderFor(definition.id)
            val frames =
                SyntheticMotion
                    .set(6, poseAt = builder)
                    .map { frame ->
                        // Shrink towards the frame centre: everything visible, nothing measurable.
                        frame.copy(
                            landmarks =
                                frame.landmarks.map {
                                    it.copy(x = 0.5f + (it.x - 0.5f) * 0.15f, y = 0.5f + (it.y - 0.5f) * 0.15f)
                                },
                        )
                    }
            val result = replay(definition, frames)
            assertEquals(0, result.readyFrameCount, "${definition.id} claimed ready on unusable framing")
            assertEquals(0, result.repCount, "${definition.id} produced reps from unusable framing")
        }
    }

    @Test
    fun `every exercise replays deterministically`() {
        for (definition in ExerciseCatalog.all()) {
            val frames = SyntheticMotion.set(5, poseAt = poseBuilderFor(definition.id))
            assertEquals(
                replay(definition, frames).renderEvents(),
                replay(definition, frames).renderEvents(),
                "${definition.id} is not deterministic",
            )
        }
    }

    @Test
    fun `phase labels name the concentric half correctly per exercise`() {
        val curl = ExerciseCatalog.load(StandingCurlEvaluator.ID).cycle
        assertEquals("concentric", curl.label(MovementPhase.OUTBOUND), "curling up is concentric")

        val squat = ExerciseCatalog.load(GobletSquatEvaluator.ID).cycle
        assertEquals("eccentric", squat.label(MovementPhase.OUTBOUND), "descending is eccentric")
        assertEquals("bottom", squat.label(MovementPhase.APEX))

        val rdl = ExerciseCatalog.load(RomanianDeadliftEvaluator.ID).cycle
        assertEquals("eccentric", rdl.label(MovementPhase.OUTBOUND), "hinging down is eccentric")

        val press = ExerciseCatalog.load(ShoulderPressEvaluator.ID).cycle
        assertEquals("concentric", press.label(MovementPhase.OUTBOUND), "pressing up is concentric")
    }

    // --- exercise-specific cues -----------------------------------------------------------

    @Test
    fun `squat depth cue fires short and stays quiet deep`() {
        val definition = ExerciseCatalog.load(GobletSquatEvaluator.ID)

        val shallow =
            replay(definition, SyntheticMotion.set(5, SyntheticMotion.RepProfile(peak = 0.84), { squatPose(it) }))
        assertEquals(5, shallow.repCount, shallow.summarize())
        assertTrue(GobletSquatEvaluator.CUE_INCOMPLETE_DEPTH in shallow.raisedCues, shallow.summarize())

        val deep = replay(definition, SyntheticMotion.set(5, poseAt = { squatPose(it) }))
        assertFalse(GobletSquatEvaluator.CUE_INCOMPLETE_DEPTH in deep.raisedCues, deep.summarize())
    }

    @Test
    fun `squat asymmetry cue fires on a lopsided squat and not on an even one`() {
        val definition = ExerciseCatalog.load(GobletSquatEvaluator.ID)

        val even = replay(definition, SyntheticMotion.set(5, poseAt = { squatPose(it) }))
        assertFalse(GobletSquatEvaluator.CUE_LATERAL_ASYMMETRY in even.raisedCues, even.summarize())

        val lopsided = replay(definition, SyntheticMotion.set(5, poseAt = { squatPose(it, rightKneeBias = 35.0) }))
        assertTrue(GobletSquatEvaluator.CUE_LATERAL_ASYMMETRY in lopsided.raisedCues, lopsided.summarize())
    }

    @Test
    fun `rdl knee travel cue fires when the hinge turns into a squat`() {
        val definition = ExerciseCatalog.load(RomanianDeadliftEvaluator.ID)

        val strict = replay(definition, SyntheticMotion.set(5, poseAt = { rdlPose(it) }))
        assertEquals(5, strict.repCount, strict.summarize())
        assertFalse(RomanianDeadliftEvaluator.CUE_EXCESSIVE_KNEE_TRAVEL in strict.raisedCues, strict.summarize())

        val squatty = replay(definition, SyntheticMotion.set(5, poseAt = { rdlPose(it, kneeFlexionAtBottom = 70.0) }))
        assertTrue(RomanianDeadliftEvaluator.CUE_EXCESSIVE_KNEE_TRAVEL in squatty.raisedCues, squatty.summarize())
    }

    @Test
    fun `press asymmetry cue fires when one arm lags`() {
        val definition = ExerciseCatalog.load(ShoulderPressEvaluator.ID)

        val even = replay(definition, SyntheticMotion.set(5, poseAt = { pressPose(it) }))
        assertFalse(ShoulderPressEvaluator.CUE_PRESS_TIMING_ASYMMETRY in even.raisedCues, even.summarize())

        val lagging = replay(definition, SyntheticMotion.set(5, poseAt = { pressPose(it, rightLagFraction = 0.45) }))
        assertTrue(ShoulderPressEvaluator.CUE_PRESS_TIMING_ASYMMETRY in lagging.raisedCues, lagging.summarize())
    }

    @Test
    fun `the rdl abstains in a front view where a hinge is invisible`() {
        val definition = ExerciseCatalog.load(RomanianDeadliftEvaluator.ID)
        // Same movement, but the athlete is square to the camera. The definition says this view is
        // unsupported; the gate must enforce it rather than quietly producing numbers.
        val frames =
            SyntheticMotion.set(6, poseAt = { u ->
                rdlPose(u).copy(openness = 1.0)
            })
        val result = replay(definition, frames)
        assertEquals(0, result.readyFrameCount, "a front view must never reach ready for an RDL")
        assertEquals(0, result.repCount)
    }
}
