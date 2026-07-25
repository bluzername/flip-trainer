package com.fliptrainer.core.synthetic

import com.fliptrainer.core.features.CameraView
import com.fliptrainer.core.features.Geometry
import com.fliptrainer.core.pose.LandmarkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The rig is test infrastructure, so it needs tests of its own.
 *
 * Every evaluator assertion downstream is only as trustworthy as the claim that asking for a 90°
 * elbow actually produces a 90° elbow. These tests close that loop by measuring the generated
 * figure with the same [Geometry] the production code uses.
 */
class SyntheticPoseTest {
    private fun assertNear(
        expected: Double,
        actual: Double?,
        tolerance: Double,
        what: String,
    ) {
        assertNotNull(actual, "$what was null")
        assertTrue(abs(expected - actual!!) <= tolerance, "$what: expected ~$expected, was $actual")
    }

    @Test
    fun `requested elbow angle is the angle actually produced`() {
        for (requested in listOf(40.0, 60.0, 90.0, 120.0, 175.0)) {
            val frame =
                SyntheticPose(right = SyntheticPose.LimbState(elbowAngleDeg = requested))
                    .frame(0)
            val measured =
                Geometry.jointAngle(
                    frame,
                    LandmarkType.RIGHT_SHOULDER,
                    LandmarkType.RIGHT_ELBOW,
                    LandmarkType.RIGHT_WRIST,
                )
            assertNear(requested, measured, 0.5, "elbow angle $requested")
        }
    }

    @Test
    fun `requested knee angle is the angle actually produced`() {
        for (requested in listOf(80.0, 100.0, 140.0, 180.0)) {
            val limb = SyntheticPose.LimbState(kneeAngleDeg = requested, shinAngleDeg = 10.0)
            val frame = SyntheticPose(left = limb, right = limb).frame(0)
            val measured =
                Geometry.jointAngle(
                    frame,
                    LandmarkType.RIGHT_HIP,
                    LandmarkType.RIGHT_KNEE,
                    LandmarkType.RIGHT_ANKLE,
                )
            assertNear(requested, measured, 0.5, "knee angle $requested")
        }
    }

    @Test
    fun `bending the knees lowers the hips`() {
        val standing = SyntheticPose().frame(0)
        val squatLimb = SyntheticPose.LimbState(kneeAngleDeg = 90.0, shinAngleDeg = 20.0)
        val squatting = SyntheticPose(left = squatLimb, right = squatLimb, torsoLeanDeg = 25.0).frame(0)

        val standingHip = Geometry.midpoint(standing, LandmarkType.LEFT_HIP, LandmarkType.RIGHT_HIP)!!
        val squatHip = Geometry.midpoint(squatting, LandmarkType.LEFT_HIP, LandmarkType.RIGHT_HIP)!!

        assertTrue(squatHip.y > standingHip.y, "squatting must move the hips down the screen")
    }

    @Test
    fun `ankles stay on the ground when the knees bend`() {
        val squatLimb = SyntheticPose.LimbState(kneeAngleDeg = 85.0, shinAngleDeg = 20.0)
        val frame = SyntheticPose(groundY = 0.9, left = squatLimb, right = squatLimb).frame(0)
        assertEquals(0.9f, frame[LandmarkType.LEFT_ANKLE]!!.y, 1e-4f)
        assertEquals(0.9f, frame[LandmarkType.RIGHT_ANKLE]!!.y, 1e-4f)
    }

    @Test
    fun `a square-on figure reads as a front view`() {
        val frame = SyntheticPose(openness = 1.0).frame(0)
        val estimate = Geometry.estimateView(frame)
        assertNotNull(estimate)
        assertEquals(CameraView.FRONT, estimate!!.view, "openness was ${estimate.openness}")
    }

    @Test
    fun `a turned figure reads as a side view`() {
        val frame = SyntheticPose(openness = 0.2).frame(0)
        assertEquals(CameraView.SIDE, Geometry.estimateView(frame)!!.view)
    }

    @Test
    fun `a partly turned figure reads as front-diagonal`() {
        val frame = SyntheticPose(openness = 0.6).frame(0)
        assertEquals(CameraView.FRONT_DIAGONAL, Geometry.estimateView(frame)!!.view)
    }

    @Test
    fun `view classification is independent of frame aspect ratio`() {
        // The whole point of isotropic authoring: the same body must not change apparent
        // orientation when the camera rotates.
        val portrait = SyntheticPose(openness = 1.0).frame(0, imageWidth = 720, imageHeight = 1280)
        val landscape = SyntheticPose(openness = 1.0).frame(0, imageWidth = 1280, imageHeight = 720)
        assertEquals(CameraView.FRONT, Geometry.estimateView(portrait)!!.view)
        assertEquals(CameraView.FRONT, Geometry.estimateView(landscape)!!.view)
    }

    @Test
    fun `torso length in body-scale units is the torso parameter`() {
        val frame = SyntheticPose(torso = 0.25).frame(0)
        assertNear(0.25, Geometry.bodyScale(frame), 1e-6, "bodyScale")
    }

    @Test
    fun `torso lean is measurable and signed`() {
        val upright = SyntheticPose(torsoLeanDeg = 0.0).frame(0)
        assertNear(0.0, Geometry.torsoInclination(upright), 0.5, "upright lean")

        val leaning = SyntheticPose(torsoLeanDeg = 30.0).frame(0)
        val measured = Geometry.torsoInclination(leaning)
        assertNotNull(measured)
        assertTrue(measured!! > 20.0, "a forward lean must show up as a large inclination, was $measured")
    }

    @Test
    fun `absent landmarks are omitted entirely`() {
        val frame = SyntheticPose(absent = setOf(LandmarkType.LEFT_WRIST)).frame(0)
        assertNull(frame[LandmarkType.LEFT_WRIST])
        assertNotNull(frame[LandmarkType.RIGHT_WRIST])
    }

    @Test
    fun `confidence overrides apply to one landmark only`() {
        val frame =
            SyntheticPose(confidence = 0.9f, confidenceOverrides = mapOf(LandmarkType.LEFT_ELBOW to 0.1f))
                .frame(0)
        assertEquals(0.1f, frame[LandmarkType.LEFT_ELBOW]!!.confidence)
        assertEquals(0.9f, frame[LandmarkType.RIGHT_ELBOW]!!.confidence)
    }

    @Test
    fun `the figure produces all 33 landmarks`() {
        val frame = SyntheticPose().frame(0)
        assertEquals(LandmarkType.entries.size, frame.landmarks.size)
        assertEquals(LandmarkType.entries.toSet(), frame.landmarks.map { it.type }.toSet())
    }

    @Test
    fun `generation is deterministic`() {
        val a = SyntheticPose(torso = 0.23, openness = 0.8).frame(1234)
        val b = SyntheticPose(torso = 0.23, openness = 0.8).frame(1234)
        assertEquals(a, b)
    }

    @Test
    fun `left and right sides are mirror images when configured identically`() {
        val frame =
            SyntheticPose(
                right = SyntheticPose.LimbState(elbowAngleDeg = 70.0),
                left = SyntheticPose.LimbState(elbowAngleDeg = 70.0),
            ).frame(0)
        val leftAngle =
            Geometry.jointAngle(frame, LandmarkType.LEFT_SHOULDER, LandmarkType.LEFT_ELBOW, LandmarkType.LEFT_WRIST)
        val rightAngle =
            Geometry.jointAngle(frame, LandmarkType.RIGHT_SHOULDER, LandmarkType.RIGHT_ELBOW, LandmarkType.RIGHT_WRIST)
        assertNear(leftAngle!!, rightAngle, 0.01, "mirrored elbow angles")

        val midline = 0.5f
        val lw = frame[LandmarkType.LEFT_WRIST]!!
        val rw = frame[LandmarkType.RIGHT_WRIST]!!
        assertEquals((midline - lw.x).toDouble(), (rw.x - midline).toDouble(), 1e-5)
    }
}
