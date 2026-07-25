package com.fliptrainer.core.exercise

import com.fliptrainer.core.features.CameraView
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.Laterality
import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.session.EventPayload
import com.fliptrainer.core.session.SessionManifest
import com.fliptrainer.core.session.WeightUnit
import com.fliptrainer.core.synthetic.SyntheticMotion
import com.fliptrainer.core.synthetic.SyntheticPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The curl vertical slice.
 *
 * Everything here runs on synthetic input, which proves determinism and threshold behaviour and
 * proves **nothing at all** about real-world accuracy. Session 6's human-validation gate is not
 * satisfied by this file; see `docs/protocols/curl-physical-test.md`.
 */
class StandingCurlEvaluatorTest {
    private val definition = ExerciseCatalog.load(StandingCurlEvaluator.ID)

    private fun manifest(reps: Int = 10) =
        SessionManifest(
            sessionId = "test-session",
            exerciseId = StandingCurlEvaluator.ID,
            exerciseDefinitionVersion = definition.version,
            targetReps = reps,
            setNumber = 1,
            dumbbellWeight = 10.0,
            weightUnit = WeightUnit.KG,
            laterality = Laterality.BILATERAL,
            preferredView = CameraView.FRONT,
            createdAtEpochMs = 0,
        )

    /** A standing figure whose elbows close as `u` goes 0 → 1. */
    private fun curlPose(
        u: Double,
        driftDegAtTop: Double = 0.0,
        torso: Double = 0.25,
        confidence: Float = 0.95f,
    ): SyntheticPose {
        val elbow = SyntheticMotion.lerp(definition.range.restValue, definition.range.apexValue, u)
        val drift = driftDegAtTop * u
        val limb = SyntheticPose.LimbState(elbowAngleDeg = elbow, upperArmAngleDeg = drift)
        return SyntheticPose(torso = torso, left = limb, right = limb, confidence = confidence)
    }

    private fun replay(
        frames: List<PoseFrame>,
        reps: Int = 10,
    ): ReplayResult = TraceReplayRunner(definition).run(frames, StandingCurlEvaluator(definition), manifest(reps))

    private fun cleanSet(
        reps: Int,
        profile: SyntheticMotion.RepProfile = SyntheticMotion.RepProfile(),
    ): List<PoseFrame> = SyntheticMotion.set(reps, profile, { curlPose(it) })

    @Test
    fun `the definition loads from the catalog`() {
        assertEquals(StandingCurlEvaluator.ID, definition.id)
        assertEquals(2, definition.cues.size)
        assertTrue(definition.knownLimitations.isNotEmpty(), "an unvalidated exercise must say so")
        assertTrue(
            definition.knownLimitations.any { it.contains("EXPERIMENTAL") },
            "the definition must not imply validation that has not happened",
        )
    }

    @Test
    fun `ten clean repetitions count as ten`() {
        val result = replay(cleanSet(10))
        assertEquals(10, result.repCount, result.summarize())
        assertEquals(0, result.rejectedRepCount)
    }

    @Test
    fun `rep numbers are contiguous from one`() {
        val result = replay(cleanSet(5))
        assertEquals(listOf(1, 2, 3, 4, 5), result.reps.map { it.repNumber })
    }

    @Test
    fun `sixty seconds of standing still produces no reps`() {
        // Session 6 acceptance: no phantom reps during a minute of idle.
        val frames =
            SyntheticMotion.set(
                reps = 0,
                poseAt = { curlPose(0.0) },
                leadInMs = 60_000,
                tailMs = 0,
            )
        val result = replay(frames)
        assertEquals(0, result.repCount, result.summarize())
        assertEquals(0, result.rejectedRepCount)
    }

    @Test
    fun `sixty seconds of small idle arm movement produces no reps`() {
        // Fidgeting, scratching, adjusting grip: real movement, but nothing near a curl.
        val frames =
            SyntheticMotion.set(
                reps = 30,
                profile = SyntheticMotion.RepProfile(outboundMs = 500, returnMs = 500, restMs = 500, peak = 0.18),
                poseAt = { curlPose(it) },
                leadInMs = 2_000,
                tailMs = 0,
            )
        val result = replay(frames)
        assertEquals(0, result.repCount, "small arm movement is not a repetition\n${result.summarize()}")
    }

    @Test
    fun `half repetitions are not counted as complete`() {
        val frames = SyntheticMotion.set(8, SyntheticMotion.RepProfile(peak = 0.5), { curlPose(it) })
        val result = replay(frames)
        assertEquals(0, result.repCount, result.summarize())
        assertEquals(8, result.rejectedRepCount)
        assertTrue(
            result.events.filterIsInstance<EventPayload.RepRejected>().isEmpty() ||
                result.events.any { (it.payload as? EventPayload.RepRejected)?.reason == "incomplete range" },
        )
    }

    @Test
    fun `a set mixing full and partial reps counts only the full ones`() {
        val profiles =
            listOf(
                SyntheticMotion.RepProfile(peak = 1.0),
                SyntheticMotion.RepProfile(peak = 0.55),
                SyntheticMotion.RepProfile(peak = 1.0),
                SyntheticMotion.RepProfile(peak = 0.40),
                SyntheticMotion.RepProfile(peak = 1.0),
            )
        val result = replay(SyntheticMotion.set(profiles, { curlPose(it) }))
        assertEquals(3, result.repCount, result.summarize())
        assertEquals(2, result.rejectedRepCount)
    }

    @Test
    fun `slow and fast repetitions both count`() {
        val slow = replay(cleanSet(5, SyntheticMotion.RepProfile(outboundMs = 2_200, returnMs = 2_600, restMs = 700)))
        assertEquals(5, slow.repCount, "slow reps\n${slow.summarize()}")

        val fast = replay(cleanSet(5, SyntheticMotion.RepProfile(outboundMs = 420, returnMs = 480, restMs = 260)))
        assertEquals(5, fast.repCount, "fast reps\n${fast.summarize()}")
    }

    @Test
    fun `a long pause at the top does not break the rep`() {
        val result = replay(cleanSet(3, SyntheticMotion.RepProfile(apexHoldMs = 2_500)))
        assertEquals(3, result.repCount, result.summarize())
    }

    @Test
    fun `tempo is reported and reflects the movement`() {
        val result = replay(cleanSet(3, SyntheticMotion.RepProfile(outboundMs = 800, returnMs = 1_600)))
        val rep = result.reps.first()
        assertTrue(
            rep.eccentricDurationMs > rep.concentricDurationMs,
            "a 2:1 eccentric must read as slower than the concentric: $rep",
        )
        // Durations are threshold-to-threshold (see RepMeasurement), so they are shorter than the
        // nominal 800+120+1600ms movement by the time spent inside the hysteresis band at each
        // end. Roughly 1.6-2.6s is the expected window for this profile.
        assertTrue(rep.repDurationMs in 1_600..2_600, "total duration was ${rep.repDurationMs}ms")
        assertTrue(
            rep.repDurationMs < 800 + 120 + 1_600,
            "a threshold-to-threshold duration must be shorter than the full excursion time",
        )
    }

    @Test
    fun `range of motion is near full for a complete rep and lower for a shallow one`() {
        val full = replay(cleanSet(3)).reps.first()
        assertTrue(full.rangeOfMotionPerMille >= 950, "full rep ROM was ${full.rangeOfMotionPerMille}")

        val shallow = replay(SyntheticMotion.set(3, SyntheticMotion.RepProfile(peak = 0.85), { curlPose(it) }))
        assertEquals(3, shallow.repCount, "0.85 clears minPeakProgress and must still count")
        assertTrue(
            shallow.reps.first().rangeOfMotionPerMille < full.rangeOfMotionPerMille,
            "a shallower rep must score lower",
        )
    }

    @Test
    fun `the incomplete range cue fires on shallow but countable reps`() {
        val result = replay(SyntheticMotion.set(4, SyntheticMotion.RepProfile(peak = 0.84), { curlPose(it) }))
        assertEquals(4, result.repCount, "0.84 is above minPeakProgress\n${result.summarize()}")
        assertTrue(
            StandingCurlEvaluator.CUE_INCOMPLETE_RANGE in result.raisedCues,
            "expected the shallow-range cue\n${result.summarize()}",
        )
    }

    @Test
    fun `the incomplete range cue stays silent on full reps`() {
        val result = replay(cleanSet(6))
        assertFalse(
            StandingCurlEvaluator.CUE_INCOMPLETE_RANGE in result.raisedCues,
            "full reps must not be told they are short\n${result.summarize()}",
        )
    }

    @Test
    fun `the upper arm drift cue fires only when the arm actually drifts`() {
        val strict = replay(SyntheticMotion.set(5, poseAt = { curlPose(it, driftDegAtTop = 0.0) }))
        assertFalse(
            StandingCurlEvaluator.CUE_UPPER_ARM_DRIFT in strict.raisedCues,
            "a strict curl must not raise a drift cue\n${strict.summarize()}",
        )

        val swinging = replay(SyntheticMotion.set(5, poseAt = { curlPose(it, driftDegAtTop = 55.0) }))
        assertTrue(
            StandingCurlEvaluator.CUE_UPPER_ARM_DRIFT in swinging.raisedCues,
            "a 55° swing must raise the drift cue\n${swinging.summarize()}",
        )
    }

    @Test
    fun `cues abstain when the relevant landmarks are not confident enough`() {
        // Session 6 acceptance: cues abstain when shoulder, elbow or wrist confidence is inadequate.
        val frames = SyntheticMotion.set(5, poseAt = { curlPose(it, driftDegAtTop = 55.0) })
        val blinded =
            SyntheticMotion.withLowConfidence(
                frames,
                fromMs = 0,
                toMs = Long.MAX_VALUE,
                types = setOf(LandmarkType.LEFT_ELBOW, LandmarkType.RIGHT_ELBOW),
                confidence = 0.25f,
            )
        val result = replay(blinded)
        assertFalse(
            StandingCurlEvaluator.CUE_UPPER_ARM_DRIFT in result.raisedCues,
            "with unreliable elbows the cue must abstain, not guess\n${result.summarize()}",
        )
    }

    @Test
    fun `an unreliable elbow suspends counting rather than inventing reps`() {
        val frames = cleanSet(6)
        val blinded =
            SyntheticMotion.withLowConfidence(
                frames,
                fromMs = 6_000,
                toMs = 12_000,
                types = setOf(LandmarkType.LEFT_ELBOW),
                confidence = 0.15f,
            )
        val result = replay(blinded)
        assertTrue(result.repCount < 6, "reps during the blind window must not be fabricated")
        assertTrue(result.repCount >= 2, "reps outside the blind window must still count: ${result.repCount}")
    }

    @Test
    fun `landmark jitter does not create phantom reps`() {
        val jittered = SyntheticMotion.withJitter(cleanSet(6), amplitude = 0.004, seed = 7)
        val result = replay(jittered)
        assertEquals(6, result.repCount, "jitter must not change the count\n${result.summarize()}")
    }

    @Test
    fun `the athlete standing too far away never starts the set`() {
        val frames = SyntheticMotion.set(6, poseAt = { curlPose(it, torso = 0.10) })
        val result = replay(frames)
        assertEquals(0, result.repCount)
        assertEquals(0, result.readyFrameCount, "the gate must never report ready")
    }

    @Test
    fun `replaying the same frames twice produces byte-identical events`() {
        // Session 5 acceptance, applied to a whole exercise: byte-equivalent ordered events.
        val frames = cleanSet(8)
        val a = replay(frames).renderEvents()
        val b = replay(frames).renderEvents()
        assertEquals(a, b)
    }

    @Test
    fun `the evaluator refuses a manifest for a different exercise`() {
        val wrong = manifest().copy(exerciseId = "goblet_squat")
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            StandingCurlEvaluator(definition).reset(wrong)
        }
    }

    @Test
    fun `the set is closed with a completion event carrying the final count`() {
        val result = replay(cleanSet(4))
        val completed = result.events.mapNotNull { it.payload as? EventPayload.SetCompleted }.single()
        assertEquals(4, completed.totalReps)
    }

    @Test
    fun `event sequence numbers are contiguous and ordered`() {
        val result = replay(cleanSet(4))
        assertEquals(result.events.indices.map { it.toLong() }, result.events.map { it.sequenceNumber })
        assertTrue(
            result.events.zipWithNext().all { (a, b) -> a.monotonicTimestampMs <= b.monotonicTimestampMs },
            "events must never go backwards in time",
        )
    }
}
