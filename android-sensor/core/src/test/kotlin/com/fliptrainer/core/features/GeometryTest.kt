package com.fliptrainer.core.features

import com.fliptrainer.core.pose.Landmark
import com.fliptrainer.core.pose.LandmarkType
import com.fliptrainer.core.pose.PoseFrame
import com.fliptrainer.core.pose.Side
import com.fliptrainer.core.synthetic.SyntheticPose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Feature primitives, including the coordinate and left/right edge cases Session 5 calls out.
 */
class GeometryTest {
    private fun frameOf(
        vararg points: Triple<LandmarkType, Double, Double>,
        confidence: Float = 1f,
        width: Int = 720,
        height: Int = 1280,
    ) = PoseFrame(
        monotonicTimestampMs = 0,
        imageWidth = width,
        imageHeight = height,
        inferenceDurationMs = 0,
        landmarks =
            points.map { (type, x, y) ->
                Landmark(type, x.toFloat(), y.toFloat(), 0f, confidence, confidence)
            },
    )

    private fun assertNear(
        expected: Double,
        actual: Double?,
        tolerance: Double = 1e-6,
        what: String = "value",
    ) {
        assertNotNull(actual, "$what was null")
        assertTrue(abs(expected - actual!!) <= tolerance, "$what: expected ~$expected, was $actual")
    }

    @Test
    fun `a right angle measures ninety degrees in aspect-corrected space`() {
        // In a 720x1280 frame one unit of x is 0.5625 units of y. Points chosen so the corrected
        // triangle is a true right angle; measuring in raw normalized space would give ~60°.
        val vertex = Point2(0.0, 0.0)
        val a = Point2(0.0, -0.1)
        val c = Point2(0.1, 0.0)
        assertNear(90.0, Geometry.jointAngle(a, vertex, c), 1e-9, "right angle")
    }

    @Test
    fun `a straight limb measures one hundred and eighty degrees`() {
        assertNear(180.0, Geometry.jointAngle(Point2(0.0, -1.0), Point2(0.0, 0.0), Point2(0.0, 1.0)), 1e-9)
    }

    @Test
    fun `coincident points produce no angle rather than a fabricated one`() {
        assertNull(Geometry.jointAngle(Point2(0.0, 0.0), Point2(0.0, 0.0), Point2(1.0, 0.0)))
    }

    @Test
    fun `the same physical pose measures the same on portrait and landscape frames`() {
        // The single most important property of the aspect correction.
        val portrait =
            SyntheticPose(right = SyntheticPose.LimbState(elbowAngleDeg = 90.0))
                .frame(0, imageWidth = 720, imageHeight = 1280)
        val landscape =
            SyntheticPose(right = SyntheticPose.LimbState(elbowAngleDeg = 90.0))
                .frame(0, imageWidth = 1280, imageHeight = 720)

        val p =
            Geometry.jointAngle(
                portrait,
                LandmarkType.RIGHT_SHOULDER,
                LandmarkType.RIGHT_ELBOW,
                LandmarkType.RIGHT_WRIST,
            )
        val l =
            Geometry.jointAngle(
                landscape,
                LandmarkType.RIGHT_SHOULDER,
                LandmarkType.RIGHT_ELBOW,
                LandmarkType.RIGHT_WRIST,
            )
        assertNear(p!!, l, 0.01, "elbow angle across aspect ratios")
    }

    @Test
    fun `normalized distance is invariant to how far away the athlete stands`() {
        val near = SyntheticPose(torso = 0.35).frame(0)
        val far = SyntheticPose(torso = 0.15).frame(0)
        val a = Geometry.normalizedDistance(near, LandmarkType.LEFT_SHOULDER, LandmarkType.LEFT_WRIST)
        val b = Geometry.normalizedDistance(far, LandmarkType.LEFT_SHOULDER, LandmarkType.LEFT_WRIST)
        assertNear(a!!, b, 1e-4, "normalized shoulder-to-wrist distance")
    }

    @Test
    fun `signed angle from vertical uses screen-up as zero`() {
        assertNear(0.0, Geometry.signedAngleFromVertical(Point2(0.0, 1.0), Point2(0.0, 0.0)), 1e-9, "up")
        assertNear(90.0, Geometry.signedAngleFromVertical(Point2(0.0, 0.0), Point2(1.0, 0.0)), 1e-9, "right")
        assertNear(-90.0, Geometry.signedAngleFromVertical(Point2(0.0, 0.0), Point2(-1.0, 0.0)), 1e-9, "left")
        assertNear(180.0, Geometry.signedAngleFromVertical(Point2(0.0, 0.0), Point2(0.0, 1.0)), 1e-9, "down")
    }

    @Test
    fun `line inclination is a magnitude between zero and ninety`() {
        assertNear(0.0, Geometry.lineInclinationFromHorizontal(Point2(0.0, 0.0), Point2(1.0, 0.0)), 1e-9)
        assertNear(45.0, Geometry.lineInclinationFromHorizontal(Point2(0.0, 0.0), Point2(1.0, 1.0)), 1e-9)
        assertNear(45.0, Geometry.lineInclinationFromHorizontal(Point2(0.0, 0.0), Point2(-1.0, 1.0)), 1e-9)
        assertNear(90.0, Geometry.lineInclinationFromHorizontal(Point2(0.0, 0.0), Point2(0.0, 1.0)), 1e-9)
    }

    @Test
    fun `vertical offset is positive when the first landmark is higher`() {
        val frame = SyntheticPose(torso = 0.25).frame(0)
        val shoulderAboveHip =
            Geometry.normalizedVerticalOffset(frame, LandmarkType.LEFT_SHOULDER, LandmarkType.LEFT_HIP)
        assertNotNull(shoulderAboveHip)
        assertTrue(shoulderAboveHip!! > 0, "shoulders are above hips, so the offset must be positive")
    }

    @Test
    fun `horizontal offset is signed rightwards`() {
        val frame = SyntheticPose(torso = 0.25).frame(0)
        val rightOfLeft =
            Geometry.normalizedHorizontalOffset(frame, LandmarkType.RIGHT_SHOULDER, LandmarkType.LEFT_SHOULDER)
        assertNotNull(rightOfLeft)
        assertTrue(rightOfLeft!! > 0, "the right shoulder sits at greater x")
    }

    @Test
    fun `body scale prefers torso length and falls back when the torso is unavailable`() {
        val full = SyntheticPose(torso = 0.30).frame(0)
        assertNear(0.30, Geometry.bodyScale(full), 1e-6, "torso-based scale")

        val noHips =
            SyntheticPose(torso = 0.30, absent = setOf(LandmarkType.LEFT_HIP, LandmarkType.RIGHT_HIP))
                .frame(0)
        val fallback = Geometry.bodyScale(noHips)
        assertNotNull(fallback, "must fall back to shoulder width rather than abstaining entirely")
        assertTrue(fallback!! > 0)
    }

    @Test
    fun `every primitive abstains when confidence is below the floor`() {
        val dim = SyntheticPose(torso = 0.25, confidence = 0.2f).frame(0)
        val floor = 0.6f
        assertNull(Geometry.point(dim, LandmarkType.LEFT_ELBOW, floor))
        assertNull(
            Geometry.jointAngle(
                dim,
                LandmarkType.LEFT_SHOULDER,
                LandmarkType.LEFT_ELBOW,
                LandmarkType.LEFT_WRIST,
                floor,
            ),
        )
        assertNull(Geometry.bodyScale(dim, floor))
        assertNull(Geometry.torsoInclination(dim, floor))
        assertNull(Geometry.normalizedDistance(dim, LandmarkType.LEFT_SHOULDER, LandmarkType.LEFT_WRIST, floor))
        assertNull(Geometry.estimateView(dim, floor))
    }

    @Test
    fun `a missing landmark abstains rather than defaulting to the origin`() {
        val frame = frameOf(LandmarkType.LEFT_SHOULDER to0 0.4 to0 0.3)
        assertNull(
            Geometry.jointAngle(frame, LandmarkType.LEFT_SHOULDER, LandmarkType.LEFT_ELBOW, LandmarkType.LEFT_WRIST),
        )
    }

    @Test
    fun `symmetry needs both sides`() {
        assertNull(Geometry.symmetry(1.0, null))
        assertNull(Geometry.symmetry(null, 1.0))
        assertEquals(0.5, Geometry.symmetry(1.5, 1.0))
    }

    @Test
    fun `visibility aggregate abstains when a landmark is absent entirely`() {
        val frame = SyntheticPose(confidence = 0.8f).frame(0)
        assertNear(
            0.8,
            Geometry.visibilityAggregate(frame, listOf(LandmarkType.LEFT_WRIST, LandmarkType.RIGHT_WRIST)),
            1e-6,
        )

        val partial = SyntheticPose(absent = setOf(LandmarkType.LEFT_WRIST)).frame(0)
        assertNull(Geometry.visibilityAggregate(partial, listOf(LandmarkType.LEFT_WRIST, LandmarkType.RIGHT_WRIST)))
    }

    @Test
    fun `the depth tiebreaker refuses to answer when the gap is small`() {
        val ambiguous =
            frameOf(
                LandmarkType.LEFT_SHOULDER to0 0.4 to0 0.3,
                LandmarkType.RIGHT_SHOULDER to0 0.6 to0 0.3,
            )
        assertNull(Geometry.nearerSide(ambiguous), "modelZ is not calibrated depth; a small gap means nothing")
    }

    @Test
    fun `the depth tiebreaker answers only for a decisive gap`() {
        val frame =
            PoseFrame(
                monotonicTimestampMs = 0,
                imageWidth = 720,
                imageHeight = 1280,
                inferenceDurationMs = 0,
                landmarks =
                    listOf(
                        Landmark(LandmarkType.LEFT_SHOULDER, 0.4f, 0.3f, z = -0.5f, visibility = 1f, presence = 1f),
                        Landmark(LandmarkType.RIGHT_SHOULDER, 0.6f, 0.3f, z = 0.5f, visibility = 1f, presence = 1f),
                    ),
            )
        assertEquals(Side.LEFT, Geometry.nearerSide(frame))
    }

    @Test
    fun `bounding box spans the requested landmarks only`() {
        val frame = SyntheticPose(torso = 0.25).frame(0)
        val shouldersOnly =
            Geometry.boundingBox(frame, listOf(LandmarkType.LEFT_SHOULDER, LandmarkType.RIGHT_SHOULDER))!!
        val withAnkles =
            Geometry.boundingBox(
                frame,
                listOf(LandmarkType.LEFT_SHOULDER, LandmarkType.RIGHT_SHOULDER, LandmarkType.LEFT_ANKLE),
            )!!
        assertTrue(shouldersOnly.height < 1e-6, "both shoulders sit at the same height")
        assertTrue(withAnkles.height > shouldersOnly.height)
    }

    @Test
    fun `bounding box abstains when any requested landmark is missing`() {
        val frame = SyntheticPose(absent = setOf(LandmarkType.LEFT_ANKLE)).frame(0)
        assertNull(Geometry.boundingBox(frame, listOf(LandmarkType.LEFT_SHOULDER, LandmarkType.LEFT_ANKLE)))
    }

    @Test
    fun `a front-diagonal requirement is satisfied by a straight-on view`() {
        assertTrue(CameraView.FRONT_DIAGONAL.isSatisfiedBy(CameraView.FRONT))
        assertTrue(CameraView.FRONT.isSatisfiedBy(CameraView.FRONT_DIAGONAL))
        assertTrue(!CameraView.SIDE.isSatisfiedBy(CameraView.FRONT))
        assertTrue(!CameraView.FRONT.isSatisfiedBy(CameraView.SIDE))
    }
}

/** Tiny helper so triples read left-to-right in the fixtures above. */
private infix fun LandmarkType.to0(x: Double): Pair<LandmarkType, Double> = this to x

private infix fun Pair<LandmarkType, Double>.to0(y: Double): Triple<LandmarkType, Double, Double> =
    Triple(first, second, y)
