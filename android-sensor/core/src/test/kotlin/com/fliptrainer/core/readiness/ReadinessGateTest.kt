package com.fliptrainer.core.readiness

import com.fliptrainer.core.features.CameraView
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.synthetic.SyntheticPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val STEP_MS = 33L

class ReadinessGateTest {
    private val upperBody = ReadinessPresets.upperBody(CameraView.FRONT)
    private val lowerBody = ReadinessPresets.lowerBody(CameraView.FRONT_DIAGONAL)

    private fun standing(torso: Double = 0.25) = SyntheticPose(torso = torso)

    /** Feed [count] frames built by [build] and return every state the gate settled on. */
    private fun feed(
        gate: ReadinessGate,
        count: Int,
        startMs: Long = 0,
        build: (Int, Long) -> PoseFrame,
    ): List<ReadinessState> = (0 until count).map { i -> gate.accept(build(i, startMs + i * STEP_MS)).state }

    private fun settle(
        gate: ReadinessGate,
        pose: SyntheticPose,
        frames: Int = 40,
    ): ReadinessState = feed(gate, frames) { _, t -> pose.frame(t) }.last()

    @Test
    fun `a well-framed athlete becomes ready`() {
        val gate = ReadinessGate(upperBody)
        assertEquals(ReadinessStatus.READY, settle(gate, standing()).status)
    }

    @Test
    fun `readiness requires the hold to elapse and does not flip on the first good frame`() {
        val config = ReadinessConfig(enterReadyHoldMs = 600)
        val gate = ReadinessGate(upperBody, config)
        val pose = standing()

        val states = feed(gate, 30) { _, t -> pose.frame(t) }

        assertFalse(states[1].isReady, "must not be ready one frame in")
        assertFalse(states[10].isReady, "must not be ready at ~330ms with a 600ms hold")
        assertTrue(states.last().isReady, "must be ready once the hold elapses")

        val firstReadyIndex = states.indexOfFirst { it.isReady }
        assertTrue(firstReadyIndex * STEP_MS >= 600, "became ready too early, at frame $firstReadyIndex")
    }

    @Test
    fun `counting cannot begin without a person`() {
        val gate = ReadinessGate(upperBody)
        val states =
            feed(gate, 20) { _, t -> PoseFrame.empty(t, imageWidth = 720, imageHeight = 1280) }
        assertTrue(states.all { it.status == ReadinessStatus.NO_PERSON })
        assertEquals("Step into frame", states.last().instruction)
    }

    @Test
    fun `an athlete too far away is told to move closer`() {
        val gate = ReadinessGate(upperBody)
        val state = settle(gate, standing(torso = 0.12))
        assertEquals(ReadinessStatus.TOO_FAR, state.status)
        assertEquals("Move closer", state.instruction)
    }

    @Test
    fun `an athlete too close is told to move away`() {
        val gate = ReadinessGate(upperBody)
        val state = settle(gate, standing(torso = 0.75))
        assertEquals(ReadinessStatus.TOO_CLOSE, state.status)
        assertEquals("Move farther away", state.instruction)
    }

    @Test
    fun `clipped feet block a lower-body exercise and name the fix`() {
        val gate = ReadinessGate(lowerBody)
        val state = settle(gate, SyntheticPose(torso = 0.25, groundY = 0.995, openness = 0.8))
        assertEquals(ReadinessStatus.BODY_CLIPPED, state.status)
        assertEquals("Show your feet", state.instruction)
        assertTrue(state.details.any { it.contains("ANKLE") }, state.details.toString())
    }

    @Test
    fun `feet are not required for an upper-body exercise`() {
        // A curl is fully measurable without ankles. Demanding them would reject good framing.
        // Ground below the frame puts the feet out of shot while hips and up stay visible.
        val gate = ReadinessGate(upperBody)
        val pose = SyntheticPose(torso = 0.25, groundY = 1.15)
        assertTrue(pose.frame(0)[LandmarkType.LEFT_ANKLE]!!.y > 1f, "precondition: feet out of frame")
        assertTrue(pose.frame(0)[LandmarkType.LEFT_HIP]!!.inFrame, "precondition: hips in frame")

        val state = settle(gate, pose)
        assertEquals(ReadinessStatus.READY, state.status, state.details.toString())
    }

    @Test
    fun `a missing required landmark is distinguished from a low-confidence one`() {
        val missingGate = ReadinessGate(upperBody)
        val missing = settle(missingGate, SyntheticPose(torso = 0.25, absent = setOf(LandmarkType.LEFT_WRIST)))
        assertEquals(ReadinessStatus.REQUIRED_LANDMARKS_MISSING, missing.status)
        assertTrue(missing.details.contains("LEFT_WRIST"))

        val dimGate = ReadinessGate(upperBody)
        val dim =
            settle(
                dimGate,
                SyntheticPose(torso = 0.25, confidenceOverrides = mapOf(LandmarkType.LEFT_WRIST to 0.20f)),
            )
        assertEquals(ReadinessStatus.LOW_CONFIDENCE, dim.status)
        assertTrue(dim.details.any { it.startsWith("LEFT_WRIST") }, dim.details.toString())
    }

    @Test
    fun `a side-on athlete fails a front-view exercise`() {
        val gate = ReadinessGate(upperBody)
        val state = settle(gate, SyntheticPose(torso = 0.25, openness = 0.15))
        assertEquals(ReadinessStatus.WRONG_VIEW, state.status)
        assertEquals("Turn slightly sideways", state.instruction)
    }

    @Test
    fun `view enforcement can be switched off for view-agnostic exercises`() {
        val gate = ReadinessGate(upperBody.copy(enforceView = false))
        assertEquals(ReadinessStatus.READY, settle(gate, SyntheticPose(torso = 0.25, openness = 0.15)).status)
    }

    @Test
    fun `a landscape frame fails a portrait-only exercise`() {
        val gate = ReadinessGate(upperBody.copy(requiredOrientation = FrameOrientation.PORTRAIT))
        val pose = standing()
        val state =
            feed(gate, 30) { _, t -> pose.frame(t, imageWidth = 1280, imageHeight = 720) }.last()
        assertEquals(ReadinessStatus.WRONG_ORIENTATION, state.status)
        assertEquals("Rotate the phone", state.instruction)
    }

    @Test
    fun `a shaking camera is detected from coherent whole-body motion`() {
        val gate = ReadinessGate(upperBody)
        // Every landmark translates by the same vector each frame: rigid motion, which is the
        // camera moving, not the athlete.
        val states =
            feed(gate, 60) { i, t ->
                val shake = if (i % 2 == 0) 0.012 else -0.012
                standing().translated(dy = shake).frame(t)
            }
        assertEquals(ReadinessStatus.UNSTABLE_CAMERA, states.last().status)
        assertEquals("Hold the camera still", states.last().instruction)
    }

    @Test
    fun `an athlete moving their arms is not mistaken for a shaking camera`() {
        val gate = ReadinessGate(upperBody)
        val states =
            feed(gate, 60) { i, t ->
                // A curl: elbows and wrists move, everything else is still. Low coherence.
                val angle = 175.0 - 60.0 * kotlin.math.abs(kotlin.math.sin(i * 0.2))
                val limb = SyntheticPose.LimbState(elbowAngleDeg = angle)
                SyntheticPose(torso = 0.25, left = limb, right = limb).frame(t)
            }
        assertEquals(ReadinessStatus.READY, states.last().status, states.last().details.toString())
    }

    @Test
    fun `a short landmark dropout does not lose readiness`() {
        val gate = ReadinessGate(upperBody, ReadinessConfig(enterReadyHoldMs = 300, leaveReadyHoldMs = 400))
        val pose = standing()

        // Reach ready.
        feed(gate, 20) { _, t -> pose.frame(t) }
        assertTrue(gate.current.isReady, "precondition: gate should be ready")

        // Two bad frames — less than the 400ms leave hold at 33ms per frame.
        val dropout = SyntheticPose(torso = 0.25, absent = setOf(LandmarkType.LEFT_WRIST))
        val duringDropout = feed(gate, 2, startMs = 660) { _, t -> dropout.frame(t) }
        assertTrue(duringDropout.all { it.isReady }, "a two-frame dropout must not clear readiness")

        // Recovery keeps it ready throughout.
        val after = feed(gate, 5, startMs = 726) { _, t -> pose.frame(t) }
        assertTrue(after.all { it.isReady })
    }

    @Test
    fun `a sustained dropout does clear readiness`() {
        val gate = ReadinessGate(upperBody, ReadinessConfig(enterReadyHoldMs = 300, leaveReadyHoldMs = 400))
        val pose = standing()
        feed(gate, 20) { _, t -> pose.frame(t) }
        assertTrue(gate.current.isReady)

        val dropout = SyntheticPose(torso = 0.25, absent = setOf(LandmarkType.LEFT_WRIST))
        val states = feed(gate, 30, startMs = 660) { _, t -> dropout.frame(t) }
        assertEquals(ReadinessStatus.REQUIRED_LANDMARKS_MISSING, states.last().status)
    }

    @Test
    fun `transitions are reported exactly once`() {
        val gate = ReadinessGate(upperBody)
        val pose = standing()
        val changes = (0 until 40).count { i -> gate.accept(pose.frame(i * STEP_MS)).changed }
        assertEquals(1, changes, "NO_PERSON -> READY is a single transition")
    }

    @Test
    fun `replaying the same frames produces the same transition sequence`() {
        // Determinism is the property that makes trace replay a test rather than an anecdote.
        fun run(): List<Pair<ReadinessStatus, Boolean>> {
            val gate = ReadinessGate(upperBody)
            return (0 until 90).map { i ->
                val pose =
                    when {
                        i < 10 -> SyntheticPose(torso = 0.12)
                        i < 20 -> SyntheticPose(torso = 0.25, openness = 0.15)
                        i < 30 -> SyntheticPose(torso = 0.25, absent = setOf(LandmarkType.RIGHT_ELBOW))
                        else -> standing()
                    }
                val t = gate.accept(pose.frame(i * STEP_MS))
                t.state.status to t.changed
            }
        }
        assertEquals(run(), run())
    }

    @Test
    fun `an IMU reading overrides the landmark motion estimate`() {
        val gate = ReadinessGate(upperBody, ReadinessConfig(maxDeviceAccelerationMs2 = 1.0))
        val pose = standing()
        repeat(40) { i -> gate.accept(pose.frame(i * STEP_MS), deviceAccelerationMs2 = 0.1) }
        assertTrue(gate.current.isReady, "a still device must not be flagged")

        val shaken = ReadinessGate(upperBody, ReadinessConfig(maxDeviceAccelerationMs2 = 1.0))
        repeat(40) { i -> shaken.accept(pose.frame(i * STEP_MS), deviceAccelerationMs2 = 5.0) }
        assertEquals(ReadinessStatus.UNSTABLE_CAMERA, shaken.current.status)
    }

    @Test
    fun `confidence is reported alongside the status`() {
        val gate = ReadinessGate(upperBody)
        val state = settle(gate, SyntheticPose(torso = 0.25, confidence = 0.8f))
        assertEquals(0.8f, state.confidence, 1e-4f)
    }

    @Test
    fun `reset returns the gate to its initial state`() {
        val gate = ReadinessGate(upperBody)
        settle(gate, standing())
        assertTrue(gate.current.isReady)
        gate.reset()
        assertEquals(ReadinessStatus.NO_PERSON, gate.current.status)
    }

    @Test
    fun `requirements reject nonsense configuration`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            ReadinessRequirements(requiredLandmarks = emptySet())
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            ReadinessRequirements(
                requiredLandmarks = setOf(LandmarkType.NOSE),
                minSubjectHeightFraction = 0.9,
                maxSubjectHeightFraction = 0.1,
            )
        }
    }
}
